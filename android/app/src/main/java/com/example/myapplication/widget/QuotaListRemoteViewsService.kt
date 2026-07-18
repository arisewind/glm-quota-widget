package com.example.myapplication.widget

import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.example.myapplication.R
import com.example.myapplication.domain.ServiceProviderInfo
import com.example.myapplication.domain.UsageSnapshot
import com.example.myapplication.domain.WindowKind
import com.example.myapplication.services.AccountStore
import com.example.myapplication.services.PrefsCacheStorage
import com.example.myapplication.services.UsageCache
import kotlinx.coroutines.runBlocking

/**
 * 多账户列表 widget 的 RemoteViewsService（阶段 D）。
 * 系统管理 ListView 滚动，账户数任意也不崩 —— Factory 按账户数返回条目。
 */
class QuotaListRemoteViewsService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        QuotaListFactory(applicationContext)
}

class QuotaListFactory(private val context: Context) : RemoteViewsService.RemoteViewsFactory {

    private data class Row(
        val accountId: String,
        val label: String,
        val sub: String,
        val usedPercent: Int
    )

    private var rows: List<Row> = emptyList()

    override fun onCreate() {}

    /** 系统在后台线程调用；runBlocking 拉 AccountStore + 缓存构建行。 */
    override fun onDataSetChanged() {
        val store = AccountStore(context)
        val cache = PrefsCacheStorage(context)
        rows = runBlocking {
            store.listAccounts().map { acc ->
                val snap = UsageCache.load(cache, acc.accountId)
                val used = snap?.window(WindowKind.FIVE_HOUR)?.usedPercent
                    ?: snap?.windows?.maxOfOrNull { it.usedPercent }
                    ?: 0
                Row(acc.accountId, acc.label, buildSub(acc.providerId, snap), used)
            }
        }
    }

    private fun buildSub(providerId: String, snap: UsageSnapshot?): String {
        val providerLabel = when (providerId) {
            ServiceProviderInfo.GLM_ID -> ServiceProviderInfo.GLM_LABEL
            ServiceProviderInfo.KIMI_ID -> ServiceProviderInfo.KIMI_LABEL
            ServiceProviderInfo.MINIMAX_ID -> ServiceProviderInfo.MINIMAX_LABEL
            else -> providerId
        }
        val plan = snap?.planName
        return if (plan.isNullOrEmpty()) providerLabel else "$providerLabel · $plan"
    }

    override fun getViewAt(position: Int): RemoteViews {
        if (position >= rows.size) return RemoteViews(context.packageName, R.layout.widget_quota_list_item)
        val row = rows[position]
        val views = RemoteViews(context.packageName, R.layout.widget_quota_list_item)
        views.setTextViewText(R.id.list_item_name, row.label)
        views.setTextViewText(R.id.list_item_plan, row.sub)
        val remaining = (100 - row.usedPercent).coerceIn(0, 100)
        views.setTextViewText(R.id.list_item_percent, "${remaining}%")
        views.setTextColor(R.id.list_item_percent, usageColor(row.usedPercent))
        views.setProgressBar(R.id.list_item_bar, 100, row.usedPercent, false)
        // 点击该行 → fillInIntent 带账户 id（配合 Provider 的 setPendingIntentTemplate）
        val fillIn = Intent().putExtra(EXTRA_ACCOUNT_ID, row.accountId)
        views.setOnClickFillInIntent(R.id.list_item_root, fillIn)
        return views
    }

    override fun getItemId(position: Int) = position.toLong()
    override fun hasStableIds() = true
    override fun getLoadingView(): RemoteViews? = null
    override fun getViewTypeCount() = 1
    override fun getCount() = rows.size
    override fun onDestroy() {}

    /** 用量色：<60% 安全青、60–85% 琥珀、>85% 珊瑚红。 */
    private fun usageColor(usedPercent: Int): Int = when {
        usedPercent > 85 -> 0xFFFF6B6B.toInt()
        usedPercent >= 60 -> 0xFFFFB84D.toInt()
        else -> 0xFF00C2B8.toInt()
    }

    companion object {
        const val EXTRA_ACCOUNT_ID = "account_id"
    }
}
