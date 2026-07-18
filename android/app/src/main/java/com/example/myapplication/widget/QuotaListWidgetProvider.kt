package com.example.myapplication.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import com.example.myapplication.MainActivity
import com.example.myapplication.R

/**
 * 多账户列表 widget（阶段 D）。配合 [QuotaListRemoteViewsService] 渲染竖向列表。
 * 系统管理滚动，账户数任意不崩。
 */
class QuotaListWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { id ->
            val views = RemoteViews(context.packageName, R.layout.widget_quota_list)

            // ListView 数据由 RemoteViewsService 提供
            val svcIntent = Intent(context, QuotaListRemoteViewsService::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
            }
            views.setRemoteAdapter(R.id.widget_list, svcIntent)

            // 模板点击 intent：每行 fillInIntent 带 account_id → MainActivity 切换账户
            val openIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val templatePi = PendingIntent.getActivity(
                context, 0, openIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            views.setPendingIntentTemplate(R.id.widget_list, templatePi)

            manager.updateAppWidget(id, views)
        }
        QuotaRefreshWorker.ensureScheduled(context)
    }

    companion object {
        /** 缓存变更后通知列表 widget 重新拉数据（Factory.onDataSetChanged）。 */
        fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val provider = ComponentName(context, QuotaListWidgetProvider::class.java)
            manager.getAppWidgetIds(provider).forEach { id ->
                manager.notifyAppWidgetViewDataChanged(id, R.id.widget_list)
            }
        }
    }
}
