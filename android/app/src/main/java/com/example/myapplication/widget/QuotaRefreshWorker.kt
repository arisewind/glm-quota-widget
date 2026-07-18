package com.example.myapplication.widget

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.myapplication.services.AccountRepository
import com.example.myapplication.services.OkHttpExecutor
import com.example.myapplication.services.PrefsCacheStorage
import com.example.myapplication.services.ServiceProviders
import com.example.myapplication.services.SettingsStore
import com.example.myapplication.services.UsageRefreshService
import java.util.concurrent.TimeUnit

/**
 * 周期性后台刷新缓存并更新 widget。
 * v2.1：默认只刷新 active 账户（省电 + 降低风控）；开启「后台刷新全部」设置后遍历所有账户。
 * 失败由 RefreshService 内部捕获并写 stale/error 缓存，不会抛出。
 */
class QuotaRefreshWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val repo = AccountRepository(applicationContext)
        val settings = SettingsStore(applicationContext)
        val cache = PrefsCacheStorage(applicationContext)
        val http = OkHttpExecutor()

        // 候选2：active 选择收敛到 repository；候选3：跳过 VM 已停止的账户(AUTH/NO_PLAN/UPSTREAM_CHANGED)
        val accounts = if (settings.backgroundRefreshAll()) {
            repo.listAccounts()
        } else {
            listOfNotNull(repo.explicitActiveAccountId()?.let { repo.getAccount(it) })
        }
        if (accounts.isEmpty()) return Result.success()

        accounts.forEach { account ->
            // 候选3：缓存已是 stop-code 错误的账户后台不再重试（避免无意义请求 + 风控）
            if (UsageRefreshService.isStopCode(repo.snapshotFor(account.accountId)?.errorCode)) return@forEach
            val refreshService = UsageRefreshService(
                account = account,
                provider = ServiceProviders.byId(account.providerId),
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
