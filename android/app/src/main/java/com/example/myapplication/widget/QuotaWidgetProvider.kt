package com.example.myapplication.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.example.myapplication.MainActivity
import com.example.myapplication.R
import com.example.myapplication.domain.UsageSnapshot
import com.example.myapplication.domain.UsageStatus
import com.example.myapplication.domain.WindowKind
import com.example.myapplication.services.AccountRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 用量桌面小部件（v2.0：显示当前活跃账户的缓存快照）。
 * - onUpdate：立即渲染占位（点击打开 App），再异步从缓存填充。
 * - 后台刷新由 [QuotaRefreshWorker] 每 30 分钟遍历所有账户拉取，再回调 [WidgetRenderer.refreshFromCache]。
 */
class QuotaWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        appWidgetIds.forEach { WidgetRenderer.render(context, appWidgetManager, it, null, null) }
        WidgetRenderer.refreshFromCache(context)
        QuotaRefreshWorker.ensureScheduled(context)
    }
}

object WidgetRenderer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 读活跃账户缓存并刷新所有 widget 实例。 */
    fun refreshFromCache(context: Context) {
        scope.launch {
            val active = AccountRepository(context).activeSnapshot()
            val manager = AppWidgetManager.getInstance(context)
            val provider = ComponentName(context, QuotaWidgetProvider::class.java)
            manager.getAppWidgetIds(provider).forEach {
                render(context, manager, it, active?.snapshot, active?.account?.label)
            }
        }
    }

    fun render(
        context: Context,
        manager: AppWidgetManager,
        appWidgetId: Int,
        snapshot: UsageSnapshot?,
        label: String?
    ) {
        val views = RemoteViews(context.packageName, R.layout.widget_quota)

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            context, appWidgetId, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        views.setOnClickPendingIntent(R.id.widget_root, pi)

        val title = label ?: snapshot?.providerLabel ?: "智谱额度"
        when {
            snapshot == null || snapshot.status == UsageStatus.UNCONFIGURED -> {
                views.setTextViewText(R.id.widget_title, title)
                views.setTextViewText(R.id.widget_session_value, "未配置")
                views.setTextViewText(R.id.widget_weekly_value, "—")
                views.setProgressBar(R.id.widget_session_bar, 100, 0, false)
                views.setProgressBar(R.id.widget_weekly_bar, 100, 0, false)
                views.setTextViewText(R.id.widget_updated, "点击打开 App 配置")
            }
            snapshot.status == UsageStatus.ERROR -> {
                views.setTextViewText(R.id.widget_title, title)
                views.setTextViewText(R.id.widget_session_value, "—")
                views.setTextViewText(R.id.widget_weekly_value, "—")
                views.setProgressBar(R.id.widget_session_bar, 100, 0, false)
                views.setProgressBar(R.id.widget_weekly_bar, 100, 0, false)
                views.setTextViewText(R.id.widget_updated, snapshot.errorMessage ?: "无法获取用量")
            }
            else -> {
                views.setTextViewText(R.id.widget_title, title)
                val session = snapshot.window(WindowKind.FIVE_HOUR)
                val weekly = snapshot.window(WindowKind.WEEKLY)
                views.setTextViewText(
                    R.id.widget_session_value,
                    session?.let { "${100 - it.usedPercent}% 剩余" } ?: "—"
                )
                views.setTextViewText(
                    R.id.widget_weekly_value,
                    weekly?.let { "${100 - it.usedPercent}% 剩余" } ?: "—"
                )
                // 数字按各自窗口用量变色（与列表版 widget 共用 usageColorInt，阈值一致）
                session?.let { views.setTextColor(R.id.widget_session_value, usageColorInt(it.usedPercent)) }
                weekly?.let { views.setTextColor(R.id.widget_weekly_value, usageColorInt(it.usedPercent)) }
                views.setProgressBar(R.id.widget_session_bar, 100, session?.usedPercent ?: 0, false)
                views.setProgressBar(R.id.widget_weekly_bar, 100, weekly?.usedPercent ?: 0, false)
                views.setTextViewText(R.id.widget_updated, "更新于 ${formatTime(snapshot.updatedAt)}")
            }
        }
        manager.updateAppWidget(appWidgetId, views)
    }

    private fun formatTime(ts: Long): String {
        if (ts <= 0) return "—"
        return SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ts))
    }
}
