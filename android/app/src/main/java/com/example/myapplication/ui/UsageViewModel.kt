package com.example.myapplication.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.domain.Account
import com.example.myapplication.domain.Credential
import com.example.myapplication.domain.UsageSnapshot
import com.example.myapplication.services.AccountRepository
import com.example.myapplication.services.AccountStore
import com.example.myapplication.services.CacheStorage
import com.example.myapplication.services.OkHttpExecutor
import com.example.myapplication.services.PrefsCacheStorage
import com.example.myapplication.services.ServiceProviders
import com.example.myapplication.services.SettingsStore
import com.example.myapplication.services.UsageAlerter
import com.example.myapplication.services.UsageProviderException
import com.example.myapplication.services.UsageRefreshService
import com.example.myapplication.widget.QuotaListWidgetProvider
import com.example.myapplication.widget.WidgetRenderer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

sealed interface UsageUiState {
    data object Loading : UsageUiState
    data object Unconfigured : UsageUiState
    data class Content(
        val accounts: List<Account>,
        val activeAccountId: String,
        val snapshot: UsageSnapshot
    ) : UsageUiState
}

/** UI provider 选择器项。 */
data class ProviderOption(
    val providerId: String,
    val label: String,
    val supportsRegion: Boolean
)

class UsageViewModel(app: Application) : AndroidViewModel(app) {
    private val accountStore = AccountStore(app)
    private val http = OkHttpExecutor()
    private val cache: CacheStorage = PrefsCacheStorage(app)
    private val repository = AccountRepository(app)
    private val settings = SettingsStore(app)
    private val alerter = UsageAlerter(app)

    /** 后台刷新是否遍历全部账户（默认 false = 仅 active，省电 + 降低风控）。 */
    private val _backgroundRefreshAll = MutableStateFlow(settings.backgroundRefreshAll())
    val backgroundRefreshAll: StateFlow<Boolean> = _backgroundRefreshAll

    /** 额度告警开关（默认开）：关则刷新后不发通知。 */
    private val _alertEnabled = MutableStateFlow(settings.alertEnabled())
    val alertEnabled: StateFlow<Boolean> = _alertEnabled

    private val _accounts = MutableStateFlow<List<Account>>(emptyList())
    val accounts: StateFlow<List<Account>> = _accounts

    private val _activeAccountId = MutableStateFlow<String?>(null)
    val activeAccountId: StateFlow<String?> = _activeAccountId

    private val _activeSnapshot = MutableStateFlow<UsageSnapshot?>(null)
    val activeSnapshot: StateFlow<UsageSnapshot?> = _activeSnapshot

    val state: StateFlow<UsageUiState> = combine(_accounts, _activeAccountId, _activeSnapshot) { accounts, activeId, snap ->
        when {
            accounts.isEmpty() -> UsageUiState.Unconfigured
            activeId == null || snap == null -> UsageUiState.Loading
            else -> UsageUiState.Content(accounts, activeId, snap)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UsageUiState.Loading)

    /** 可添加的服务商列表（从 [ServiceProviders] 注册表派生，加服务商这里自动出现）。 */
    val providerOptions: List<ProviderOption> =
        ServiceProviders.all().map { ProviderOption(it.providerId, it.label, it.supportsRegion) }

    private val refreshServices = mutableMapOf<String, UsageRefreshService>()

    init {
        viewModelScope.launch {
            val list = accountStore.listAccounts()
            _accounts.value = list
            val activeId = repository.explicitActiveAccountId() ?: list.firstOrNull()?.accountId
            if (activeId != null) {
                _activeAccountId.value = activeId
                repository.setActive(activeId)
                hydrateAndRefresh(activeId)
            }
        }
    }

    private fun refreshServiceFor(account: Account): UsageRefreshService =
        refreshServices.getOrPut(account.accountId) {
            UsageRefreshService(
                account = account,
                provider = ServiceProviders.byId(account.providerId),
                http = http,
                cacheStorage = cache
            )
        }

    private suspend fun hydrateAndRefresh(accountId: String) {
        val account = _accounts.value.firstOrNull { it.accountId == accountId } ?: return
        val rs = refreshServiceFor(account)
        val cached = rs.hydrateFromCache()
        if (cached != null) _activeSnapshot.value = cached
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val activeId = _activeAccountId.value ?: return@launch
            val account = _accounts.value.firstOrNull { it.accountId == activeId } ?: return@launch
            val rs = refreshServiceFor(account)
            val snap = rs.refresh(UsageRefreshService.Reason.MANUAL)
            _activeSnapshot.value = snap
            alerter.check(snap, account)
            notifyWidgets()
        }
    }

    /** App 回前台时静默刷新（FOREGROUND reason，自带 15min 节流 + 停止策略）。 */
    fun refreshOnForeground() {
        viewModelScope.launch {
            val activeId = _activeAccountId.value ?: return@launch
            val account = _accounts.value.firstOrNull { it.accountId == activeId } ?: return@launch
            val rs = refreshServiceFor(account)
            val snap = rs.refresh(UsageRefreshService.Reason.FOREGROUND)
            _activeSnapshot.value = snap
            alerter.check(snap, account)
            notifyWidgets()
        }
    }

    fun switchAccount(accountId: String) {
        viewModelScope.launch {
            _activeAccountId.value = accountId
            repository.setActive(accountId)
            _activeSnapshot.value = null
            notifyWidgets()
            hydrateAndRefresh(accountId)
        }
    }

    /** 添加账户：测试连接成功后保存并切为活跃。onResult(ok, errorMsg)。 */
    fun addAccount(
        providerId: String,
        key: String,
        region: String?,
        label: String?,
        onResult: (Boolean, String?) -> Unit
    ) {
        viewModelScope.launch {
            val credential = credentialFor(providerId, key)
            val provider = ServiceProviders.byId(providerId)
            val accountLabel = label?.takeIf { it.isNotBlank() }
                ?: providerOptions.firstOrNull { it.providerId == providerId }?.label
                ?: providerId
            // 重名校验（账户名全局唯一，避免同服务商多账户混淆）
            if (accountStore.listAccounts().any { it.label == accountLabel }) {
                onResult(false, "账户名「$accountLabel」已存在，请改名后重试")
                return@launch
            }
            try {
                provider.fetchUsage(credential, region, http) // 测试连接
                val account = Account(
                    accountId = "$providerId-${UUID.randomUUID()}",
                    providerId = providerId,
                    label = accountLabel,
                    credential = credential,
                    region = region,
                    isActive = true
                )
                accountStore.saveAccount(account)
                refreshServices.clear()
                _accounts.value = accountStore.listAccounts()
                _activeAccountId.value = account.accountId
                repository.setActive(account.accountId)
                onResult(true, null)
                notifyWidgets()
                hydrateAndRefresh(account.accountId)
            } catch (e: UsageProviderException) {
                onResult(false, e.mapped.message)
            }
        }
    }

    /** 重命名账户（只改 label，不动凭据；空名忽略）。 */
    fun renameAccount(accountId: String, newLabel: String) {
        viewModelScope.launch {
            val acc = accountStore.getAccount(accountId) ?: return@launch
            val trimmed = newLabel.trim().takeIf { it.isNotEmpty() } ?: return@launch
            accountStore.saveAccount(acc.copy(label = trimmed))
            refreshServices.clear()
            _accounts.value = accountStore.listAccounts()
            notifyWidgets()
        }
    }

    fun setBackgroundRefreshAll(all: Boolean) {
        settings.setBackgroundRefreshAll(all)
        _backgroundRefreshAll.value = all
    }

    fun setAlertEnabled(enabled: Boolean) {
        settings.setAlertEnabled(enabled)
        _alertEnabled.value = enabled
    }

    fun removeAccount(accountId: String) {
        viewModelScope.launch {
            accountStore.removeAccount(accountId)
            cache.clear(accountId)
            refreshServices.remove(accountId)
            alerter.onAccountRemoved(accountId)
            val list = accountStore.listAccounts()
            _accounts.value = list
            if (_activeAccountId.value == accountId) {
                val newActive = list.firstOrNull()?.accountId
                _activeAccountId.value = newActive
                repository.setActive(newActive)
                _activeSnapshot.value = null
                if (newActive != null) hydrateAndRefresh(newActive)
            }
            notifyWidgets()
        }
    }

    /** 清除全部账户（v1.x 「清除配置」兼容）。 */
    fun clearConfig() {
        viewModelScope.launch {
            _accounts.value.forEach {
                cache.clear(it.accountId)
                accountStore.removeAccount(it.accountId)
            }
            refreshServices.clear()
            _accounts.value = emptyList()
            _activeAccountId.value = null
            repository.setActive(null)
            _activeSnapshot.value = null
            notifyWidgets()
        }
    }

    fun maskKeyFor(account: Account): String = accountStore.maskKey(account.credential)

    private fun credentialFor(providerId: String, key: String): Credential =
        ServiceProviders.byId(providerId).credentialFor(key)

    /** 通知桌面 widget 重读缓存重渲染（App 内数据/账户变更后调用，让卡片立即跟上，不增网络请求）。 */
    private fun notifyWidgets() {
        val ctx = getApplication<Application>()
        WidgetRenderer.refreshFromCache(ctx)
        QuotaListWidgetProvider.refreshAll(ctx)
    }
}
