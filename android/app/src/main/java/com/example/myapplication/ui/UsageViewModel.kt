package com.example.myapplication.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.domain.CodingPlanUsage
import com.example.myapplication.services.CacheStorage
import com.example.myapplication.services.CredentialStore
import com.example.myapplication.services.DirectKeyUsageProvider
import com.example.myapplication.services.OkHttpExecutor
import com.example.myapplication.services.PrefsCacheStorage
import com.example.myapplication.services.Region
import com.example.myapplication.services.UsageProviderException
import com.example.myapplication.services.UsageRefreshService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface UsageUiState {
    data object Loading : UsageUiState
    data object Unconfigured : UsageUiState
    data class Data(val usage: CodingPlanUsage) : UsageUiState
}

class UsageViewModel(app: Application) : AndroidViewModel(app) {
    private val creds = CredentialStore(app)
    private val http = OkHttpExecutor()
    private val cache: CacheStorage = PrefsCacheStorage(app)

    private val _state = MutableStateFlow<UsageUiState>(UsageUiState.Loading)
    val state: StateFlow<UsageUiState> = _state.asStateFlow()

    /** 脱敏后的 Key（如 ****...ABCD），供设置页展示。未配置时为空串。 */
    private val _maskedKey = MutableStateFlow("")
    val maskedKey: StateFlow<String> = _maskedKey.asStateFlow()

    private val provider = DirectKeyUsageProvider(
        getRegion = { creds.getRegion() },
        getKey = { creds.getKey() },
        http = http
    )
    private val refreshService = UsageRefreshService(
        provider = provider,
        cacheStorage = cache,
        onRefresh = { usage -> _state.value = UsageUiState.Data(usage) }
    )

    init {
        viewModelScope.launch {
            val cached = refreshService.hydrateFromCache()
            val key = creds.getKey()
            _maskedKey.value = creds.maskKey(key)
            when {
                key == null -> _state.value = UsageUiState.Unconfigured
                cached != null -> {
                    _state.value = UsageUiState.Data(cached)
                    refresh()
                }
                else -> refresh()
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            if (_state.value !is UsageUiState.Data) _state.value = UsageUiState.Loading
            val usage = refreshService.refresh(UsageRefreshService.Reason.MANUAL)
            _state.value = UsageUiState.Data(usage)
        }
    }

    /**
     * App 回前台时静默刷新：走 FOREGROUND reason（自带 15min 节流 + 认证/解析失败停止策略）。
     * 仅当已配置（Data 态）才触发，避免打扰未配置/加载中状态。
     */
    fun refreshOnForeground() {
        viewModelScope.launch {
            if (_state.value !is UsageUiState.Data) return@launch
            val usage = refreshService.refresh(UsageRefreshService.Reason.FOREGROUND)
            _state.value = UsageUiState.Data(usage)
        }
    }

    /** 测试连接并保存。onResult(ok, errorMsg) 在主线程回调。 */
    fun setup(key: String, region: Region, onResult: (Boolean, String?) -> Unit) {
        viewModelScope.launch {
            creds.save(key, region)
            try {
                provider.testConnection()
                _maskedKey.value = creds.maskKey(key)
                onResult(true, null)
                refresh()
            } catch (e: UsageProviderException) {
                creds.clear()
                _maskedKey.value = ""
                onResult(false, e.mapped.message)
            }
        }
    }

    fun clearConfig() {
        viewModelScope.launch {
            creds.clear()
            cache.clear()
            _maskedKey.value = ""
            _state.value = UsageUiState.Unconfigured
        }
    }
}
