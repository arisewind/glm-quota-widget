package com.example.myapplication

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.ViewModelProvider
import com.example.myapplication.ui.AccountsScreen
import com.example.myapplication.ui.AddAccountScreen
import com.example.myapplication.ui.PushedScreen
import com.example.myapplication.ui.Tab
import com.example.myapplication.ui.UsageScreen
import com.example.myapplication.ui.UsageUiState
import com.example.myapplication.ui.UsageViewModel
import com.example.myapplication.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Android 13+ 需运行时申请通知权限（额度告警 v3.0 用）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001
            )
        }
        val vm = ViewModelProvider(this)[UsageViewModel::class.java]
        // App 回前台时静默刷新（FOREGROUND reason，自带 15min 节流）。
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) {
                vm.refreshOnForeground()
            }
        })
        handleAccountIntent(intent, vm)
        setContent {
            val themeMode by vm.themeMode.collectAsState()
            MyApplicationTheme(themeMode) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val state by vm.state.collectAsState()
                    when (val s = state) {
                        UsageUiState.Loading -> LoadingView()
                        UsageUiState.Unconfigured -> AddAccountScreen(vm, isFirst = true, onDone = {})
                        is UsageUiState.Content -> AppScaffold(s, vm)
                    }
                }
            }
        }
    }

    /** 列表 widget 点击行 → 切到对应账户。 */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleAccountIntent(intent, ViewModelProvider(this)[UsageViewModel::class.java])
    }

    private fun handleAccountIntent(intent: Intent, vm: UsageViewModel) {
        intent.getStringExtra("account_id")?.let { vm.switchAccount(it) }
    }
}

/**
 * 主导航骨架（v3.4）：底栏 3-tab + pushed 子页。
 * - Loading/Unconfigured 由顶层渲染（无底栏）；Content 进本骨架。
 * - pushed（添加账户/通知记录）覆盖 tab，系统返回回 tab 而非退出 app。
 */
@Composable
private fun AppScaffold(content: UsageUiState.Content, vm: UsageViewModel) {
    var tab by rememberSaveable { mutableStateOf(Tab.RANGE) }
    var pushed by rememberSaveable { mutableStateOf<PushedScreen?>(null) }
    BackHandler(enabled = pushed != null) { pushed = null }

    val p = pushed
    if (p != null) {
        when (p) {
            PushedScreen.ADD_ACCOUNT -> AddAccountScreen(vm, isFirst = false, onDone = { pushed = null })
            PushedScreen.NOTIFICATIONS -> NotificationLogScreen(vm, onBack = { pushed = null })
        }
    } else {
        Scaffold(bottomBar = { AppBottomBar(selected = tab, onChange = { tab = it }) }) { padding ->
            Box(Modifier.padding(padding)) {
                when (tab) {
                    Tab.RANGE -> UsageScreen(
                        content, vm,
                        onOpenAccounts = { tab = Tab.ACCOUNTS },
                        onOpenNotifications = { pushed = PushedScreen.NOTIFICATIONS }
                    )
                    Tab.ACCOUNTS -> AccountsScreen(
                        vm, onAdd = { pushed = PushedScreen.ADD_ACCOUNT }
                    )
                    Tab.SETTINGS -> SettingsScreen(vm)
                }
            }
        }
    }
}

@Composable
private fun AppBottomBar(selected: Tab, onChange: (Tab) -> Unit) {
    NavigationBar {
        Tab.entries.forEach { t ->
            NavigationBarItem(
                selected = selected == t,
                onClick = { onChange(t) },
                icon = { Icon(imageVector = t.navIcon(), contentDescription = null) },
                label = { Text(t.label) }
            )
        }
    }
}

private fun Tab.navIcon(): ImageVector = when (this) {
    Tab.RANGE -> Icons.Filled.Home
    Tab.ACCOUNTS -> Icons.Filled.AccountCircle
    Tab.SETTINGS -> Icons.Filled.Settings
}

@Composable
private fun LoadingView() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
    }
}
