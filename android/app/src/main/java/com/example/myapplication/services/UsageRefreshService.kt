package com.example.myapplication.services

import com.example.myapplication.domain.Account
import com.example.myapplication.domain.UsageErrorCode
import com.example.myapplication.domain.UsageSnapshot
import com.example.myapplication.domain.UsageSource
import com.example.myapplication.domain.UsageStatus
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 单账户刷新编排（架构 §4.4/§5）：节流、并发锁、连续失败退避、认证/解析失败停止自动刷新。
 * 状态 per-instance；多账户下每个 [Account] 各持一个本实例。
 */
class UsageRefreshService(
    private val account: Account,
    private val provider: ServiceProvider,
    private val http: HttpExecutor,
    private val cacheStorage: CacheStorage,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val onRefresh: ((UsageSnapshot) -> Unit)? = null,
    private val getSettings: () -> RefreshSettings = { RefreshSettings(false) }
) {
    data class RefreshSettings(val autoRefreshEnabled: Boolean)
    enum class Reason { MANUAL, FOREGROUND, BACKGROUND }

    private val mutex = Mutex()
    private var lastFetchStartedAt = 0L
    private var lastSuccessAt = 0L
    private var consecutiveFailures = 0
    private var autoRefreshPausedUntil = 0L
    private var stoppedFor: UsageErrorCode? = null
    private var lastUsage: UsageSnapshot? = null

    fun canRefresh(reason: Reason): Boolean {
        val n = now()
        if (reason == Reason.MANUAL) {
            return lastFetchStartedAt == 0L || n - lastFetchStartedAt >= THROTTLE_MS
        }
        if (stoppedFor != null) return false
        if (n < autoRefreshPausedUntil) return false
        if (reason == Reason.BACKGROUND && !getSettings().autoRefreshEnabled) return false
        val interval = if (reason == Reason.FOREGROUND) FOREGROUND_MS else BACKGROUND_MS
        return lastSuccessAt == 0L || n - lastSuccessAt >= interval
    }

    /** 成功返回 ok 快照；失败保留缓存标 stale，或无缓存返回 error 态。并发由 Mutex 串行化。 */
    suspend fun refresh(reason: Reason): UsageSnapshot = mutex.withLock {
        if (!canRefresh(reason)) return@withLock currentSnapshot()
        lastFetchStartedAt = now()
        try {
            val snap = provider.fetchUsage(account.credential, account.region, http)
            lastUsage = snap
            lastSuccessAt = now()
            consecutiveFailures = 0
            autoRefreshPausedUntil = 0L
            stoppedFor = null
            UsageCache.save(cacheStorage, account.accountId, snap)
            onRefresh?.invoke(snap)
            snap
        } catch (e: UsageProviderException) {
            val failed = applyFailure(e.mapped)
            onRefresh?.invoke(failed)
            failed
        }
    }

    fun currentSnapshot(): UsageSnapshot = lastUsage ?: UsageSnapshot(
        providerId = account.providerId,
        providerLabel = account.label,
        windows = emptyList(),
        updatedAt = 0L,
        source = UsageSource.DIRECT,
        status = UsageStatus.UNCONFIGURED
    )

    suspend fun hydrateFromCache(): UsageSnapshot? {
        val cached = UsageCache.load(cacheStorage, account.accountId)
        if (cached != null) {
            lastUsage = cached
            lastSuccessAt = cached.updatedAt
        }
        return cached
    }

    private fun applyFailure(mapped: MappedError): UsageSnapshot {
        val code = mapped.code
        if (code in STOP_CODES) {
            stoppedFor = code
        } else {
            consecutiveFailures++
            if (consecutiveFailures >= FAILURE_THRESHOLD) {
                autoRefreshPausedUntil = now() + AUTO_PAUSE_MS
            }
        }
        val status = if (lastUsage != null) UsageStatus.STALE else UsageStatus.ERROR
        return if (lastUsage != null) {
            lastUsage!!.copy(status = status, errorCode = code, errorMessage = mapped.message)
        } else {
            UsageSnapshot(
                providerId = account.providerId,
                providerLabel = account.label,
                windows = emptyList(),
                updatedAt = now(),
                source = UsageSource.DIRECT,
                status = status,
                errorCode = code,
                errorMessage = mapped.message
            )
        }
    }

    companion object {
        private const val THROTTLE_MS = 10_000L
        private const val FOREGROUND_MS = 15 * 60_000L
        private const val BACKGROUND_MS = 60 * 60_000L
        private const val FAILURE_THRESHOLD = 3
        private const val AUTO_PAUSE_MS = 6 * 60 * 60_000L
        private val STOP_CODES =
            setOf(UsageErrorCode.AUTH, UsageErrorCode.NO_PLAN, UsageErrorCode.UPSTREAM_CHANGED)
    }
}
