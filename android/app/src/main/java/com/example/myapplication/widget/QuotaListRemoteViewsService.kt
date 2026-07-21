package com.example.myapplication.widget

import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.example.myapplication.R
import com.example.myapplication.domain.Account
import com.example.myapplication.domain.UsageSnapshot
import com.example.myapplication.services.AccountRepository
import com.example.myapplication.services.ServiceProviders
import kotlinx.coroutines.runBlocking

/**
 * 多账户列表 widget 的 RemoteViewsService（阶段 D，卡片式）。
 * 系统管理 ListView 滚动，账户数任意也不崩。卡片左侧品牌色条区分服务商，%和进度条用量色表余量。
 */
class QuotaListRemoteViewsService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        QuotaListFactory(applicationContext)
}

class QuotaListFactory(private val context: Context) : RemoteViewsService.RemoteViewsFactory {

    private data class Row(
        val accountId: String,
        val name: String,         // 账户名（主，account.label）
        val sub: String,          // 副：服务商·套餐（重命名后）或 套餐（未重命名）
        val usedPercent: Int,
        val brandColor: Int       // 服务商品牌色（色条）
    )

    private var rows: List<Row> = emptyList()
    private var palette = WidgetPalette.forContext(context)  // v3.6 主题色，onDataSetChanged 刷新

    override fun onCreate() {}

    /** 系统在后台线程调用；经 [AccountRepository] 拉账户+缓存构建行（window fallback 已下沉到 primaryPercent）。 */
    override fun onDataSetChanged() {
        palette = WidgetPalette.forContext(context)  // v3.6：themeMode 变 → refreshAll → 此处刷新主题
        val repo = AccountRepository(context)
        rows = runBlocking {
            repo.allSnapshots().map { item ->
                Row(
                    accountId = item.account.accountId,
                    name = item.account.label,
                    sub = buildSub(item.account, item.snapshot),
                    usedPercent = item.primaryPercent,
                    brandColor = ServiceProviders.findById(item.account.providerId)?.brandColor
                        ?: 0xFF8A93A6.toInt()
                )
            }
        }
    }

    /** 副标识：账户重命名过(label≠服务商名) → 显示「服务商·套餐」；否则只显示「套餐」（可空）。 */
    private fun buildSub(acc: Account, snap: UsageSnapshot?): String {
        val providerLabel = providerLabelOf(acc.providerId)
        val plan = snap?.planName
        return if (acc.label != providerLabel) {
            providerLabel + (plan?.takeIf { it.isNotEmpty() }?.let { " · $it" } ?: "")
        } else {
            plan?.takeIf { it.isNotEmpty() } ?: ""
        }
    }

    private fun providerLabelOf(providerId: String) = ServiceProviders.labelOf(providerId)

    override fun getViewAt(position: Int): RemoteViews {
        if (position >= rows.size) return RemoteViews(context.packageName, R.layout.widget_quota_list_item)
        val row = rows[position]
        val views = RemoteViews(context.packageName, R.layout.widget_quota_list_item)
        val p = palette
        val usageColor = usageColorInt(row.usedPercent)
        views.setInt(R.id.list_item_root, "setBackgroundResource", p.itemDrawable)  // v3.6 item 背景（深/浅）
        views.setInt(R.id.list_item_brand, "setBackgroundColor", row.brandColor)
        views.setTextViewText(R.id.list_item_name, row.name)
        views.setTextColor(R.id.list_item_name, p.textPrimary)
        if (row.sub.isEmpty()) {
            views.setViewVisibility(R.id.list_item_plan, View.GONE)
        } else {
            views.setViewVisibility(R.id.list_item_plan, View.VISIBLE)
            views.setTextViewText(R.id.list_item_plan, row.sub)
            views.setTextColor(R.id.list_item_plan, p.textSecondary)
        }
        val remaining = (100 - row.usedPercent).coerceIn(0, 100)
        views.setTextViewText(R.id.list_item_percent, "${remaining}%")
        views.setTextColor(R.id.list_item_percent, usageColor)
        // 注：华为 ROM ProgressBar 未实现 setProgressTint/setProgressBackgroundTint（调了 inflate 失败），进度条全用 XML 静态色
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

    companion object {
        const val EXTRA_ACCOUNT_ID = "account_id"
    }
}
