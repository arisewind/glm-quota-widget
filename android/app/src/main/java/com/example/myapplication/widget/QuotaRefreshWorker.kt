package com.example.myapplication.widget

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.myapplication.services.AccountStore
import com.example.myapplication.services.OkHttpExecutor
import com.example.myapplication.services.PrefsCacheStorage
import com.example.myapplication.services.Providers
import com.example.myapplication.services.UsageRefreshService
import java.util.concurrent.TimeUnit

/**
 * 周期性后台刷新缓存并更新 widget。
 * v2.0：遍历所有账户，逐个刷新其缓存（per-account RefreshService）；
 * 失败由 RefreshService 内部捕获并写 stale/error 缓存，不会抛出。
 */
class QuotaRefreshWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val store = AccountStore(applicationContext)
        val accounts = store.listAccounts()
        if (accounts.isEmpty()) return Result.success()

        val cache = PrefsCacheStorage(applicationContext)
        val http = OkHttpExecutor()
        accounts.forEach { account ->
            val provider = Providers.create(account.providerId)
            val refreshService = UsageRefreshService(
                account = account,
                provider = provider,
                http = http,
                cacheStorage = cache
            )
            runCatching { refreshService.refresh(UsageRefreshService.Reason.BACKGROUND) }
        }
        WidgetRenderer.refreshFromCache(applicationContext)
        QuotaListWidgetProvider.refreshAll(applicationContext)
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
