package com.example.myapplication.services

import com.example.myapplication.domain.Credential
import com.example.myapplication.domain.ServiceProviderInfo
import com.example.myapplication.domain.UsageSnapshot

/** 服务商用量查询抽象（ADR-0002）。实现无状态，凭据 / region / http 由调用方注入。 */
interface ServiceProvider {
    val providerId: String
    val label: String
    /** 是否需要选区域（GLM/MiniMax 双站 true；Kimi 单区域 false）。 */
    val supportsRegion: Boolean

    suspend fun fetchUsage(credential: Credential, region: String?, http: HttpExecutor): UsageSnapshot
}

/** Provider 工厂。 */
object Providers {
    fun create(providerId: String): ServiceProvider = when (providerId) {
        ServiceProviderInfo.GLM_ID -> GlmUsageProvider()
        ServiceProviderInfo.KIMI_ID -> KimiUsageProvider()
        ServiceProviderInfo.MINIMAX_ID -> MiniMaxUsageProvider()
        else -> throw IllegalArgumentException("未知 provider: $providerId")
    }
}
