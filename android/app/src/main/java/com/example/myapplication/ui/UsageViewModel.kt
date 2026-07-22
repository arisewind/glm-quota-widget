package com.example.myapplication.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.domain.Account
import com.example.myapplication.domain.Credential
import com.example.myapplication.domain.UsageSnapshot
import com.example.myapplication.domain.UsageStatus
import com.example.myapplication.domain.WindowKind
import com.example.myapplication.domain.primaryPercent
import com.example.myapplication.domain.primaryWindow
import com.example.myapplication.services.AccountRepository
import com.example.myapplication.services.AccountStore
import com.example.myapplication.services.CacheStorage
import com.example.myapplication.services.OkHttpExecutor
import com.example.myapplication.services.PrefsCacheStorage
import com.example.myapplication.services.ServiceProviders
import com.example.myapplication.services.ServiceProviderConfig
import com.example.myapplication.services.SettingsStore
import com.example.myapplication.services.UsageAlerter
import com.example.myapplication.services.NotificationLogEntry
import com.example.myapplication.services.NotificationLogStore
import com.example.myapplication.services.UsageHistoryStore
import com.example.myapplication.services.UsageProviderException
import com.example.myapplication.services.UsageRefreshService
import com.example.myapplication.widget.QuotaListWidgetProvider
import com.example.myapplication.widget.WidgetRenderer
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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
        val snapshot: UsageSnapshot,
        val primaryPercent: Int,
        val primaryWindowKind: WindowKind?
    ) : UsageUiState
}

/** v3.8 任务1 Toast：分级 + 可选 action（重试）。底部 Snackbar 由 AppScaffold 收集展示。 */
enum class SnackbarLevel { SUCCESS, ERROR, INFO }
data class SnackbarMsg(val level: SnackbarLevel, val message: String, val actionLabel: String? = null)

/** UI provider 选择器项。 */
data class ProviderOption(
    val providerId: String,
    val label: String,
    val supportsRegion: Boolean,
    val requiresTeamCreds: Boolean = false   // true=GLM 团队版三件套，UI 出 3 输入框
)

class UsageViewModel(app: Application) : AndroidViewModel(app) {
    private val accountStore = AccountStore(app)
    private val http = OkHttpExecutor()
    private val cache: CacheStorage = PrefsCacheStorage(app)
    private val repository = AccountRepository(app)
    private val settings = SettingsStore(app)
    private val alerter = UsageAlerter(app)
    private val historyStore = UsageHistoryStore(app)
    private val notificationLogStore = NotificationLogStore(app)

    /** 后台刷新是否遍历全部账户（默认 false = 仅 active，省电 + 降低风控）。 */
    private val _backgroundRefreshAll = MutableStateFlow(settings.backgroundRefreshAll())
    val backgroundRefreshAll: StateFlow<Boolean> = _backgroundRefreshAll

    /** 低额度告警（≥85%）开关：默认开。 */
    private val _alertLowEnabled = MutableStateFlow(settings.alertLowEnabled())
    val alertLowEnabled: StateFlow<Boolean> = _alertLowEnabled

    /** 额度耗尽（100%）告警开关：默认开。 */
    private val _alertExhaustedEnabled = MutableStateFlow(settings.alertExhaustedEnabled())
    val alertExhaustedEnabled: StateFlow<Boolean> = _alertExhaustedEnabled

    /** 主题模式（浅色/深色/跟随系统）。 */
    private val _themeMode = MutableStateFlow(settings.themeMode())
    val themeMode: StateFlow<String> = _themeMode

    /** v3.5：续航页主卡窗口偏好（null=默认 5h 优先回退）。 */
    private val _primaryWindowKind = MutableStateFlow(settings.primaryWindowKind())
    val primaryWindowKind: StateFlow<WindowKind?> = _primaryWindowKind

    /** v3.5：通知已读时间戳 + 铃铛未读 badge。v3.8.1：未读数（badge 显数字，非仅红点）。 */
    private val _lastSeenNotificationAt = MutableStateFlow(settings.lastSeenNotificationAt())
    private val _unreadCount = MutableStateFlow(0)
    val unreadCount: StateFlow<Int> = _unreadCount
    private val _hasUnreadNotifications = MutableStateFlow(false)
    val hasUnreadNotifications: StateFlow<Boolean> = _hasUnreadNotifications

    /** 通知记录（v3.2，通知记录页用）。 */
    private val _notificationLog = MutableStateFlow<List<NotificationLogEntry>>(emptyList())
    val notificationLog: StateFlow<List<NotificationLogEntry>> = _notificationLog

    /** 当前活跃账户的周窗用量历史（v3.1 趋势折线用）。 */
    private val _weeklyHistory = MutableStateFlow<List<UsageHistoryStore.Point>>(emptyList())
    val weeklyHistory: StateFlow<List<UsageHistoryStore.Point>> = _weeklyHistory

    /** v3.8 任务3①：刷新中（驱动续航页顶栏刷新 icon 匀速旋转）。 */
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing

    /** v3.8 任务1：Toast 事件流（刷新成功/失败反馈；extraBufferCapacity 防背压丢消息）。 */
    private val _snackbar = MutableSharedFlow<SnackbarMsg>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val snackbar = _snackbar.asSharedFlow()

    private val _accounts = MutableStateFlow<List<Account>>(emptyList())
    val accounts: StateFlow<List<Account>> = _accounts

    private val _activeAccountId = MutableStateFlow<String?>(null)
    val activeAccountId: StateFlow<String?> = _activeAccountId

    private val _activeSnapshot = MutableStateFlow<UsageSnapshot?>(null)
    val activeSnapshot: StateFlow<UsageSnapshot?> = _activeSnapshot

    val state: StateFlow<UsageUiState> = combine(_accounts, _activeAccountId, _activeSnapshot, _primaryWindowKind) { accounts, activeId, snap, preferredKind ->
        when {
            accounts.isEmpty() -> UsageUiState.Unconfigured
            activeId == null || snap == null -> UsageUiState.Loading
            else -> {
                // v3.5：偏好优先；偏好窗口在该账户不存在（如 Kimi 无 TOOLS）时回退 primaryWindow()
                val actualKind = preferredKind?.let { snap.window(it)?.kind } ?: snap.primaryWindow()?.kind
                UsageUiState.Content(accounts, activeId, snap, snap.primaryPercent(), actualKind)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UsageUiState.Loading)

    /** 可添加的服务商列表（从 [ServiceProviders] 注册表派生，加服务商这里自动出现）。 */
    val providerOptions: List<ProviderOption> =
        ServiceProviders.all().map {
            ProviderOption(
                providerId = it.providerId,
                label = it.label,
                supportsRegion = it.supportsRegion,
                requiresTeamCreds = it.credentialType == ServiceProviderConfig.CredentialType.ZHIPU_TEAM
            )
        }

    private val refreshServices = mutableMapOf<String, UsageRefreshService>()

    init {
        recomputeUnread()  // v3.5：启动即算一次铃铛 badge
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
        _weeklyHistory.value = historyStore.read(accountId)
        val rs = refreshServiceFor(account)
        val cached = rs.hydrateFromCache()
        if (cached != null) _activeSnapshot.value = cached
        refresh()
    }

    private var refreshJob: Job? = null

    /** 手动刷新。single-flight（并发点击跳过）+ 仅真刷新弹成功 Toast（节流命中/缓存不变静默）。 */
    fun refresh() {
        if (refreshJob?.isActive == true) return  // 任务8：单飞守卫，防并发竞写 _isRefreshing/_snackbar
        refreshJob = viewModelScope.launch {
            val activeId = _activeAccountId.value ?: return@launch
            val account = _accounts.value.firstOrNull { it.accountId == activeId } ?: return@launch
            val rs = refreshServiceFor(account)
            _isRefreshing.value = true
            try {
                val before = _activeSnapshot.value?.updatedAt
                val snap = rs.refresh(UsageRefreshService.Reason.MANUAL)
                _activeSnapshot.value = snap
                alerter.check(snap, account)
                appendWeekly(account.accountId, snap)
                notifyWidgets()
                // 任务8：仅真刷新（updatedAt 变化）弹成功，节流命中返回缓存静默；ERROR/异常弹失败带重试
                // tryEmit + DROP_OLDEST：不挂起，防 buffer 满阻塞 finally 导致 _isRefreshing 卡 true
                when {
                    snap.status == UsageStatus.ERROR ->
                        _snackbar.tryEmit(SnackbarMsg(SnackbarLevel.ERROR, snap.errorMessage ?: "刷新失败", "重试"))
                    snap.updatedAt != before ->
                        _snackbar.tryEmit(SnackbarMsg(SnackbarLevel.SUCCESS, "已刷新最新用量"))
                }
            } catch (e: Exception) {
                _snackbar.tryEmit(SnackbarMsg(SnackbarLevel.ERROR, "网络异常，请重试", "重试"))
            } finally {
                _isRefreshing.value = false
            }
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
            appendWeekly(account.accountId, snap)
            notifyWidgets()
            recomputeUnread()  // v3.5：后台 Worker 可能 append 新通知，回前台即重算 badge
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

    /** 添加账户（单 Key 服务商）：测试连接成功后保存并切为活跃。onResult(ok, errorMsg)。 */
    fun addAccount(
        providerId: String,
        key: String,
        region: String?,
        label: String?,
        onResult: (Boolean, String?) -> Unit
    ) {
        viewModelScope.launch {
            val credential = credentialFor(providerId, key)
            doAddAccount(providerId, credential, region, label, onResult)
        }
    }

    /** 添加 GLM 团队版账户（三件套凭据）：测试连接成功后保存。onResult(ok, errorMsg)。 */
    fun addTeamAccount(
        providerId: String,
        apiKey: String,
        orgId: String,
        projectId: String,
        label: String?,
        onResult: (Boolean, String?) -> Unit
    ) {
        viewModelScope.launch {
            if (apiKey.isBlank() || orgId.isBlank() || projectId.isBlank()) {
                onResult(false, "API Key、组织 ID、项目 ID 均不能为空")
                return@launch
            }
            // Team 固定国内站（supportsRegion=false），region 传 null
            val credential = Credential.ZhipuTeam(apiKey.trim(), orgId.trim(), projectId.trim())
            doAddAccount(providerId, credential, null, label, onResult)
        }
    }

    /** addAccount/addTeamAccount 共用：重名校验 + 测试连接 + 落盘 + 切活跃 + 刷新。 */
    private suspend fun doAddAccount(
        providerId: String,
        credential: Credential,
        region: String?,
        label: String?,
        onResult: (Boolean, String?) -> Unit
    ) {
        val provider = ServiceProviders.byId(providerId)
        val accountLabel = label?.takeIf { it.isNotBlank() }
            ?: providerOptions.firstOrNull { it.providerId == providerId }?.label
            ?: providerId
        // 重名校验（账户名全局唯一，避免同服务商多账户混淆）
        if (accountStore.listAccounts().any { it.label == accountLabel }) {
            onResult(false, "账户名「$accountLabel」已存在，请改名后重试")
            return
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

    fun setAlertLowEnabled(enabled: Boolean) {
        settings.setAlertLowEnabled(enabled)
        _alertLowEnabled.value = enabled
        if (!enabled && !settings.alertExhaustedEnabled()) {
            // 两档都关 → 清全部 armed + cancel 已显示告警通知
            alerter.onAllArmedClear(_accounts.value.map { it.accountId })
        }
    }

    fun setAlertExhaustedEnabled(enabled: Boolean) {
        settings.setAlertExhaustedEnabled(enabled)
        _alertExhaustedEnabled.value = enabled
        if (!enabled && !settings.alertLowEnabled()) {
            alerter.onAllArmedClear(_accounts.value.map { it.accountId })
        }
    }

    fun setThemeMode(mode: String) {
        settings.setThemeMode(mode)
        _themeMode.value = mode
        notifyWidgets()  // v3.6：主题变更 → widget 深浅重绘
    }

    /** v3.5：把指定窗口设为主卡（点 mini 升主），持久化偏好。 */
    fun setPrimaryWindow(kind: WindowKind) {
        settings.setPrimaryWindowKind(kind)
        _primaryWindowKind.value = kind
    }

    /** v3.5：进入通知记录页标记已读，铃铛 badge 清零。 */
    fun markNotificationsSeen() {
        val now = System.currentTimeMillis()
        settings.setLastSeenNotificationAt(now)
        _lastSeenNotificationAt.value = now
        recomputeUnread()
    }

    /** v3.8.1：重算铃铛未读数（timestamp > 已读时间的条数，badge 显数字）。 */
    private fun recomputeUnread() {
        val seen = settings.lastSeenNotificationAt()
        val unread = notificationLogStore.readAll().count { it.timestamp > seen }
        _unreadCount.value = unread
        _hasUnreadNotifications.value = unread > 0
    }

    /** 进入通知记录页时读最新（alerter append 已落盘）。 */
    fun refreshNotificationLog() {
        _notificationLog.value = notificationLogStore.readAll()
        recomputeUnread()
    }

    fun clearNotificationLog() {
        notificationLogStore.clearAll()
        _notificationLog.value = emptyList()
    }

    fun removeAccount(accountId: String) {
        viewModelScope.launch {
            accountStore.removeAccount(accountId)
            cache.clear(accountId)
            refreshServices.remove(accountId)
            alerter.onAccountRemoved(accountId)
            historyStore.clearAccount(accountId)
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
                alerter.onAccountRemoved(it.accountId)  // 清 armed + cancel 通知防孤儿（与 removeAccount 一致）
                historyStore.clearAccount(it.accountId) // 清周窗历史（与 removeAccount 一致）
            }
            refreshServices.clear()
            notificationLogStore.clearAll()
            _notificationLog.value = emptyList()
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

    /** 追加周窗历史采样点 + 刷新趋势 state（供 UI 折线）。无周窗快照则跳过。 */
    private fun appendWeekly(accountId: String, snap: UsageSnapshot) {
        val weekly = snap.window(WindowKind.WEEKLY)?.usedPercent ?: return
        historyStore.append(accountId, weekly, snap.updatedAt)
        _weeklyHistory.value = historyStore.read(accountId)
    }
}
