package com.example.myapplication.widget

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.myapplication.services.CredentialStore
import com.example.myapplication.services.DirectKeyUsageProvider
import com.example.myapplication.services.OkHttpExecutor
import com.example.myapplication.services.PrefsCacheStorage
import com.example.myapplication.services.UsageRefreshService
import java.util.concurrent.TimeUnit

/**
 * 周期性后台刷新缓存并更新 widget。
 * 复用核心层（CredentialStore / DirectKeyUsageProvider / UsageRefreshService），
 * 失败由 refreshService 内部捕获并写 stale/error 缓存，不会抛出。
 */
class QuotaRefreshWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val creds = CredentialStore(applicationContext)
        if (creds.getKey() == null) return Result.success()

        val cache = PrefsCacheStorage(applicationContext)
        val provider = DirectKeyUsageProvider(
            getRegion = { creds.getRegion() },
            getKey = { creds.getKey() },
            http = OkHttpExecutor()
        )
        val refreshService = UsageRefreshService(
            provider = provider,
            cacheStorage = cache
        )
        runCatching { refreshService.refresh(UsageRefreshService.Reason.BACKGROUND) }
        WidgetRenderer.refreshFromCache(applicationContext)
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "glm_quota_widget_refresh"

        fun ensureScheduled(context: Context) {
            val request = PeriodicWorkRequestBuilder<QuotaRefreshWorker>(30, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request
            )
        }
    }
}
