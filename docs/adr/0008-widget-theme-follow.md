# ADR-0008：widget 深浅色跟随 App 主题（v3.6）

- 状态：Accepted（v3.6 已实施；App 侧主题开关见 [ADR-0005](0005-settings-system-guide.md)，配色 token 见 [ADR-0006](0006-nordvpn-color-system.md)）
- 日期：2026-07-21
- 关联：[ADR-0005](0005-settings-system-guide.md) / [ADR-0006](0006-nordvpn-color-system.md) / [REVIEW.md](../REVIEW.md)

---

## 背景（Context）

v3.3 给 App 侧统一了 NordVPN 蓝配色 + 深 / 浅双模式（[ADR-0006](0006-nordvpn-color-system.md)），用户在 [ADR-0005](0005-settings-system-guide.md) 的设置页可切「浅色 / 深色 / 跟随系统」。但 **widget 一直是深色固定**，App 切浅色后 widget 仍是深蓝黑，视觉割裂。

让 widget 跟随 App 主题有两个天然难点：

1. **widget 是 RemoteViews**，由桌面 launcher 进程渲染，**不能跑 Compose、不能用 `MaterialTheme` / `isSystemInDarkTheme`**。所有颜色必须由代码在 `RemoteViews` 上显式设（`setInt` / `setTextColor`），或由 XML 静态给定。
2. 用户要的是**跟随 App 的 `themeMode`**（包括「强制浅色 / 强制深色」），而非系统的夜间模式——`system` 模式才看系统 `uiMode`。

实现过程中踩到一个华为 ROM 的硬坑（见 Decision §3），直接决定了进度条颜色的实现方式。

---

## 决策（Decision）

### 1. WidgetPalette：代码驱动调色板

新增 `widget/WidgetPalette.kt`，一个 `data class` 装一组 RemoteViews 可设的色 token：

```kotlin
internal data class WidgetPalette(
    val bgDrawable: Int,       // 根背景 drawable（保留圆角 + stroke）
    val itemDrawable: Int,     // 列表 item 背景 drawable
    val textPrimary: Int,
    val textSecondary: Int,
    val accent: Int
)
```

两套静态实例：

- `DARK` —— 现状深色（`widget_background` / `item_card_background`，字 `#E8EDF5` / `#8A94A6`）。
- `LIGHT` —— App NordVPN 浅色 token（`widget_background_light` / `item_card_background_light`，字 `#1A1F2E` / `#6B7280`）。

两套的 `accent` 统一 `#4687FF`（v3.6 与 App 对齐，弃早期 widget 青绿 `#3DD6D0`）。

### 2. forContext：读 App themeMode

```kotlin
fun forContext(context: Context): WidgetPalette {
    val mode = SettingsStore(context).themeMode()
    val dark = when (mode) {
        THEME_DARK  -> true
        THEME_LIGHT -> false
        else        -> 看 context.resources.configuration.uiMode 的 UI_MODE_NIGHT_MASK
    }
    return if (dark) DARK else LIGHT
}
```

`light / dark` 强制覆盖系统；`system` 才看系统 `uiMode`。注意读的是 launcher 进程传入的 context 的 `uiMode`（launcher 在系统夜间模式下会带夜间配置），保证「跟随系统」与桌面环境一致。

### 3. 浅色 drawable + 切主题 API

新增浅色 drawable `widget_background_light.xml` / `item_card_background_light.xml`（保留圆角 + stroke，与深色同结构、换底色）。Provider 渲染时切主题：

```kotlin
val p = WidgetPalette.forContext(context)
views.setInt(R.id.widget_root, "setBackgroundResource", p.bgDrawable)   // 根背景切深/浅 drawable
views.setTextColor(R.id.widget_title, p.textPrimary)                    // 文字色
views.setTextColor(R.id.widget_dot, p.accent)
// … label / value / updated 各 TextView 都按 palette 设色
```

`onDataSetChanged`（列表 widget factory）里重读一次 `palette`，themeMode 变化触发的 `notifyAppWidgetViewDataChanged` 会走这里刷新主题。

---

## 关键坑（必记）：华为 ROM 的 ProgressBar 删了 tint @RemotableViewMethod

这是本 ADR 最重要的工程教训。

AOSP 的 `ProgressBar` 声明了两个 `@RemotableViewMethod`：`setProgressTint(int)` 和 `setProgressBackgroundTint(int)`，让 RemoteViews 能代码动态改进度条颜色。但**华为 / HarmonyOS ROM 的 `ProgressBar` 实现删掉了这两个方法**（AOSP 有、华为删）。

后果：在 RemoteViews 上调 `setProgressTint(...)` 或 `setProgressBackgroundTint(...)` 任一，`AppWidgetManager` 在 launcher 进程 inflate RemoteViews 时找不到对应 @RemotableViewMethod → **inflate 失败、widget 白屏**（显示「加载出现问题」/空白），且无崩溃日志，极难定位。

**解法**：进度条颜色**只用 XML 静态值**，绝不调 tint 的 RemoteViews 方法：

```xml
<ProgressBar …
    android:progressTint="@color/widget_accent"            <!-- 进度色：静态 XML -->
    android:progressBackgroundTint="@color/widget_bar_track" />  <!-- track 色：静态 XML -->
```

`widget_accent`（`#4687FF`）和 `widget_bar_track`（`#2C3447`）在 `values/widget_colors.xml` 静态给定，深 / 浅背景上都可见（track 用深灰，深底浅底都能区分）。

**数字用量色不受影响**——`TextView` 的 `setTextColor` 在所有 ROM 都正常实现，所以「剩余 N%」的数字仍可按用量档位动态变色（`usageColorInt(usedPercent)`，绿 / 橙 / 红）。也就是说：widget 上**文字随用量变色、进度条固定色**，这是华为 ROM 限制下的妥协。

---

## 备选方案（Alternatives，均已否决）

- **widget 跟随系统夜间模式（不看 App themeMode）**：用户在 App 强制选「浅色」时 widget 不跟随，割裂，违背诉求。
- **用 XML `night` qualifier 自动切深 / 浅 drawable**：RemoteViews 的 `setBackgroundResource` 在 launcher 进程 inflate 时确实会走 qualifier，但无法响应「App 强制浅色 / 强制深色」（非系统夜间），故改代码读 `themeMode` 显式设。
- **代码 `setProgressTint` 动态改进度条用量色**：华为 ROM 白屏（见上坑），否决。
- **为深 / 浅各出一份 layout XML**：layout 膨胀两份维护负债，且 tint 坑仍在；不如一份 layout + `setBackgroundResource` 切 drawable + XML 静态 tint。

---

## 后果（Consequences）

**正面**
- widget 跟随 App 主题（浅 / 深 / 跟随系统），App 与桌面视觉统一，补齐 v3.3 配色重塑的最后一块。
- 浅色 drawable 复用 App NordVPN 浅色 token，品牌一致。
- accent 统一 `#4687FF`，弃早期青绿，与 App 彻底对齐。

**负面 / 风险**
- **进度条固定色（华为 ROM 妥协）**：widget 进度条不能随用量档位变色（只能 XML 静态 `widget_accent` 蓝），数字仍可变色。功能受限但优于白屏。
- **`system` 模式下系统夜间变化不自动更新 widget**：launcher 不会因系统切夜间就重 inflate 已添加的 widget。靠 `QuotaRefreshWorker` 每 30min 兜底（`onDataSetChanged` 重读 palette），最坏 30min 延迟才切主题；用户手动改 themeMode 时 App 侧会主动 `notifyAppWidgetViewDataChanged` 触发即时刷新。

---

## 关联代码

- `widget/WidgetPalette.kt` —— 深 / 浅调色板 + `forContext`（读 `SettingsStore.themeMode`）。
- `widget/QuotaWidgetProvider.kt` —— `WidgetRenderer.render`：`setBackgroundResource` + `setTextColor` 切主题；进度条**不调 tint**（注释记录华为坑）。
- `widget/QuotaListRemoteViewsService.kt` —— `QuotaListFactory`：`onDataSetChanged` 重读 palette，item 背景 / 文字按 palette 设色。
- `res/layout/widget_quota.xml` / `widget_quota_list.xml` —— ProgressBar 用 XML 静态 `progressTint` / `progressBackgroundTint`。
- `res/values/widget_colors.xml` —— `widget_accent #4687FF` / `widget_bar_track #2C3447`。
- `res/drawable/widget_background_light.xml` / `item_card_background_light.xml` —— 浅色 drawable（圆角 + stroke）。

## 参考依据

- RemoteViews 限制：https://developer.android.com/guide/topics/appwidgets#creating-layout
- `@RemotableViewMethod` 机制：RemoteViews 仅能调用了标注该注解的方法；华为 ROM 的 `ProgressBar` 删去了 `setProgressTint/setProgressBackgroundTint` 两个 AOSP 原有方法（实机验证：调用即 inflate 失败白屏）。
