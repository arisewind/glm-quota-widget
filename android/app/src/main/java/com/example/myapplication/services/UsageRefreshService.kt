package com.example.myapplication.services

import com.example.myapplication.domain.CodingPlanUsage
import com.example.myapplication.domain.ServiceProviderInfo
import com.example.myapplication.domain.UsageErrorCode
import com.example.myapplication.domain.UsageSource
import com.example.myapplication.domain.UsageStatus
import com.example.myapplication.domain.UsageWindow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 刷新编排（架构 §4.4/§5）：节流、并发锁、连续失败退避、认证/解析失败停止自动刷新。 */
class UsageRefreshService(
    private val provider: UsageProvider,
    private val cacheStorage: CacheStorage,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val onRefresh: ((CodingPlanUsage) -> Unit)? = null,
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
    private var lastUsage: CodingPlanUsage? = null

    fun canRefresh(reason: Reason): Boolean {
        val now = now()
        if (reason == Reason.MANUAL) {
            return lastFetchStartedAt == 0L || now - lastFetchStartedAt >= THROTTLE_MS
        }
        if (stoppedFor != null) return false
        if (now < autoRefreshPausedUntil) return false
        if (reason == Reason.BACKGROUND && !getSettings().autoRefreshEnabled) return false
        val interval = if (reason == Reason.FOREGROUND) FOREGROUND_MS else BACKGROUND_MS
        return lastSuccessAt == 0L || now - lastSuccessAt >= interval
    }

    /** 成功返回 ok 用量；失败保留缓存标 stale，或无缓存返回 error 态。并发由 Mutex 串行化。 */
    suspend fun refresh(reason: Reason): CodingPlanUsage = mutex.withLock {
        if (!canRefresh(reason)) return@withLock currentSnapshot()
        lastFetchStartedAt = now()
        try {
            val usage = provider.fetchUsage()
            lastUsage = usage
            lastSuccessAt = now()
            consecutiveFailures = 0
            autoRefreshPausedUntil = 0L
            stoppedFor = null
            UsageCache.save(cacheStorage, usage)
            onRefresh?.invoke(usage)
            usage
        } catch (e: UsageProviderException) {
            val snap = applyFailure(e.mapped)
            onRefresh?.invoke(snap)
            snap
        }
    }

    fun currentSnapshot(): CodingPlanUsage = lastUsage ?: CodingPlanUsage(
        session = UsageWindow(0, 100),
        weekly = UsageWindow(0, 100),
        updatedAt = 0L,
        source = UsageSource.DIRECT,
        providerLabel = ServiceProviderInfo.GLM_LABEL,
        status = UsageStatus.UNCONFIGURED
    )

    suspend fun hydrateFromCache(): CodingPlanUsage? {
        val cached = UsageCache.load(cacheStorage)
        if (cached != null) {
            lastUsage = cached
            lastSuccessAt = cached.updatedAt
        }
        return cached
    }

    private fun applyFailure(mapped: MappedError): CodingPlanUsage {
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
            CodingPlanUsage(
                session = UsageWindow(0, 0),
                weekly = UsageWindow(0, 0),
                updatedAt = now(),
                source = UsageSource.DIRECT,
                providerLabel = ServiceProviderInfo.GLM_LABEL,
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
