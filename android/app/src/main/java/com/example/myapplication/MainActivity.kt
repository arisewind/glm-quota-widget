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
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.ViewModelProvider
import com.example.myapplication.ui.AccountsScreen
import com.example.myapplication.ui.AddAccountScreen
import com.example.myapplication.ui.GlintSnackbar
import com.example.myapplication.ui.Motion
import com.example.myapplication.ui.PushedScreen
import com.example.myapplication.ui.rememberReduceMotion
import com.example.myapplication.ui.SnackbarLevel
import com.example.myapplication.ui.SnackbarMsg
import com.example.myapplication.ui.Tab
import com.example.myapplication.ui.UsageScreen
import com.example.myapplication.ui.UsageUiState
import com.example.myapplication.ui.UsageViewModel
import com.example.myapplication.ui.theme.MyApplicationTheme
import kotlinx.coroutines.delay

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
            // glintapi 品牌 splash（~1.1s），结束后进入主界面；rememberSaveable 避免旋转/恢复重显
            var showSplash by rememberSaveable { mutableStateOf(true) }
            LaunchedEffect(Unit) {
                delay(1100L)
                showSplash = false
            }
            MyApplicationTheme(themeMode) {
                if (showSplash) {
                    GlintSplash()
                } else {
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
    var snackbarMsg by remember { mutableStateOf<SnackbarMsg?>(null) }
    BackHandler(enabled = pushed != null) { pushed = null }

    // v3.8 任务1：收集 VM toast 事件，分级 Snackbar 底部展示（错误 3.5s / 其余 2s）
    LaunchedEffect(Unit) {
        vm.snackbar.collect { msg ->
            snackbarMsg = msg
            delay(if (msg.level == SnackbarLevel.ERROR) 3500L else 2000L)
            snackbarMsg = null
        }
    }

    val reduceMotion = rememberReduceMotion()

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            bottomBar = { AppBottomBar(selected = tab, onChange = { tab = it }) }
        ) { padding ->
            Box(Modifier.padding(padding)) {
                // 任务3②：tab 水平 Slide（按 ordinal 定方向）；reduced-motion 退化为 fade
                AnimatedContent(
                    targetState = tab,
                    transitionSpec = {
                        if (reduceMotion) {
                            fadeIn() togetherWith fadeOut()
                        } else {
                            val dir = if (targetState.ordinal > initialState.ordinal) 1 else -1
                            (slideInHorizontally { w -> w / 3 * dir } + fadeIn()) togetherWith
                                (slideOutHorizontally { w -> -w / 3 * dir } + fadeOut())
                        }
                    },
                    label = "tab"
                ) { t ->
                    when (t) {
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
        // 任务3②：pushed 子页 Fade+Scale 浮层；reduced-motion 退化为 fade
        AnimatedVisibility(
            visible = pushed != null,
            enter = if (reduceMotion) fadeIn() else fadeIn(animationSpec = tween(Motion.Durations.MEDIUM)) + scaleIn(initialScale = 0.94f, animationSpec = tween(Motion.Durations.MEDIUM)),
            exit = if (reduceMotion) fadeOut() else fadeOut(animationSpec = tween(Motion.Durations.SHORT)) + scaleOut(targetScale = 0.94f, animationSpec = tween(Motion.Durations.SHORT))
        ) {
            pushed?.let { p ->
                when (p) {
                    PushedScreen.ADD_ACCOUNT -> AddAccountScreen(vm, isFirst = false, onDone = { pushed = null })
                    PushedScreen.NOTIFICATIONS -> NotificationLogScreen(vm, onBack = { pushed = null })
                }
            }
        }
        // 任务8：snackbar 提升到顶层；v3.9 Toast 方案 C：scale 弹性入场（reduced-motion 退化 fade）
        AnimatedVisibility(
            visible = snackbarMsg != null,
            enter = if (reduceMotion) fadeIn() else scaleIn(initialScale = 0.88f, animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy)) + fadeIn(),
            exit = if (reduceMotion) fadeOut() else scaleOut(targetScale = 0.88f, animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy)) + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 72.dp)
        ) {
            snackbarMsg?.let { msg ->
                GlintSnackbar(
                    msg = msg,
                    onAction = {
                        if (msg.level == SnackbarLevel.ERROR) vm.refresh()
                        snackbarMsg = null
                    }
                )
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
                icon = {
                    // 选中画 solid 填充、未选画 outline 描边；tint 由 NavigationBarItem 的 LocalContentColor 染色（selected=primary）
                    Icon(
                        painter = painterResource(if (selected == t) t.solid else t.outline),
                        contentDescription = null
                    )
                },
                label = { Text(t.label) }
            )
        }
    }
}

@Composable
private fun LoadingView() {
    // v3.8 任务1 方案 B：spinner + 文案，替代裸 CircularProgressIndicator
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(36.dp)
        )
        Spacer(Modifier.height(14.dp))
        Text(
            "正在拉取用量…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
