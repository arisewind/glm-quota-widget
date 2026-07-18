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
import com.example.myapplication.domain.CodingPlanUsage
import com.example.myapplication.domain.UsageStatus
import com.example.myapplication.services.PrefsCacheStorage
import com.example.myapplication.services.UsageCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * GLM 用量桌面小部件。
 * - onUpdate：立即渲染占位（点击打开 App），再异步从缓存填充真实数据。
 * - 后台刷新由 [QuotaRefreshWorker] 每 30 分钟拉取并回调 [WidgetRenderer.refreshFromCache]。
 */
class QuotaWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        appWidgetIds.forEach { WidgetRenderer.render(context, appWidgetManager, it, null) }
        WidgetRenderer.refreshFromCache(context)
        QuotaRefreshWorker.ensureScheduled(context)
    }
}

object WidgetRenderer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 异步读取缓存并刷新所有已添加的 widget 实例。 */
    fun refreshFromCache(context: Context) {
        scope.launch {
            val usage = UsageCache.load(PrefsCacheStorage(context))
            val manager = AppWidgetManager.getInstance(context)
            val provider = ComponentName(context, QuotaWidgetProvider::class.java)
            val ids = manager.getAppWidgetIds(provider)
            ids.forEach { render(context, manager, it, usage) }
        }
    }

    fun render(
        context: Context,
        manager: AppWidgetManager,
        appWidgetId: Int,
        usage: CodingPlanUsage?
    ) {
        val views = RemoteViews(context.packageName, R.layout.widget_quota)

        // 点击任意区域 → 打开主 App
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            context, appWidgetId, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        views.setOnClickPendingIntent(R.id.widget_root, pi)

        when {
            usage == null || usage.status == UsageStatus.UNCONFIGURED -> {
                views.setTextViewText(R.id.widget_title, "智谱 GLM")
                views.setTextViewText(R.id.widget_session_value, "未配置")
                views.setTextViewText(R.id.widget_weekly_value, "—")
                views.setProgressBar(R.id.widget_session_bar, 100, 0, false)
                views.setProgressBar(R.id.widget_weekly_bar, 100, 0, false)
                views.setTextViewText(R.id.widget_updated, "点击打开 App 配置 Key")
            }
            usage.status == UsageStatus.ERROR -> {
                views.setTextViewText(R.id.widget_title, usage.providerLabel ?: "智谱 GLM Coding Plan")
                views.setTextViewText(R.id.widget_session_value, "—")
                views.setTextViewText(R.id.widget_weekly_value, "—")
                views.setProgressBar(R.id.widget_session_bar, 100, 0, false)
                views.setProgressBar(R.id.widget_weekly_bar, 100, 0, false)
                views.setTextViewText(R.id.widget_updated, usage.errorMessage ?: "无法获取用量")
            }
            else -> {
                views.setTextViewText(R.id.widget_title, usage.providerLabel ?: "智谱 GLM Coding Plan")
                views.setTextViewText(
                    R.id.widget_session_value, "${100 - usage.session.usedPercent}% 剩余"
                )
                views.setTextViewText(
                    R.id.widget_weekly_value, "${100 - usage.weekly.usedPercent}% 剩余"
                )
                views.setProgressBar(
                    R.id.widget_session_bar, 100, usage.session.usedPercent, false
                )
                views.setProgressBar(
                    R.id.widget_weekly_bar, 100, usage.weekly.usedPercent, false
                )
                views.setTextViewText(R.id.widget_updated, "更新于 ${formatTime(usage.updatedAt)}")
            }
        }
        manager.updateAppWidget(appWidgetId, views)
    }

    private fun formatTime(ts: Long): String {
        if (ts <= 0) return "—"
        return SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ts))
    }
}
