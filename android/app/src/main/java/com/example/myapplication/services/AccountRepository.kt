package com.example.myapplication.services

import android.content.Context
import com.example.myapplication.domain.Account
import com.example.myapplication.domain.UsageSnapshot
import com.example.myapplication.domain.WindowKind
import com.example.myapplication.domain.primaryPercent

/**
 * 账户读 facade（ADR-0002 多账户）。聚合「账户列表 + 活跃选择 + 缓存读取」三件事，
 * 消除 [com.example.myapplication.widget.WidgetRenderer] / [com.example.myapplication.widget.QuotaListFactory]
 * / [com.example.myapplication.widget.QuotaRefreshWorker] 各自 new AccountStore + PrefsCacheStorage
 * 并重抄 `"active_account_id"` 字符串契约的重复（原三份副本 + window fallback 业务规则下沉在 Factory）。
 *
 * 写路径（增删账户）仍走 [AccountStore]；本类只负责"读哪个账户 + 它的缓存快照"。
 */
class AccountRepository(context: Context) {
    private val store = AccountStore(context)
    private val cache: CacheStorage = PrefsCacheStorage(context)
    private val uiPrefs = context.applicationContext
        .getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE)

    suspend fun listAccounts(): List<Account> = store.listAccounts()
    suspend fun getAccount(accountId: String): Account? = store.getAccount(accountId)

    /** 活跃账户 id（带 fallback：显式选择优先，否则首个账户）。widget 详情用。 */
    suspend fun activeAccountId(): String? =
        explicitActiveAccountId() ?: store.listAccounts().firstOrNull()?.accountId

    /** 同步、无 fallback 的显式活跃 id。VM init / Worker 只认用户显式选择时用。 */
    fun explicitActiveAccountId(): String? = uiPrefs.getString(KEY_ACTIVE, null)

    /** 活跃账户及其缓存快照；无活跃账户返回 null。 */
    suspend fun activeSnapshot(): AccountSnapshot? {
        val id = activeAccountId() ?: return null
        val account = store.getAccount(id) ?: return null
        return AccountSnapshot(account, UsageCache.load(cache, id))
    }

    /** 所有账户及其缓存快照（列表 widget / Worker 遍历用）。 */
    suspend fun allSnapshots(): List<AccountSnapshot> =
        store.listAccounts().map { AccountSnapshot(it, UsageCache.load(cache, it.accountId)) }

    suspend fun snapshotFor(accountId: String): UsageSnapshot? = UsageCache.load(cache, accountId)

    /** 写活跃账户 id（null = 清除选择）。 */
    fun setActive(accountId: String?) {
        val edit = uiPrefs.edit()
        if (accountId == null) edit.remove(KEY_ACTIVE) else edit.putString(KEY_ACTIVE, accountId)
        edit.apply()
    }

    /**
     * 账户 + 其缓存快照。[primaryPercent] 把原下沉在 Factory 的 window fallback 业务规则
     * （5h 窗优先，缺失取所有窗最大值）收敛到此处，渲染层只取值。
     */
    data class AccountSnapshot(
        val account: Account,
        val snapshot: UsageSnapshot?
    ) {
        /** 列表/详情主用量百分比（5h 优先 → 周 → 非工具窗最大值；逻辑见 [com.example.myapplication.domain.primaryPercent]，排除工具调用额度）。 */
        val primaryPercent: Int
            get() = snapshot?.primaryPercent() ?: 0
    }

    companion object {
        private const val UI_PREFS = "glm_quota_ui"
        private const val KEY_ACTIVE = "active_account_id"
    }
}
