# ADR-0006：UI 配色统一 NordVPN 蓝（v3.3）

- 状态：Accepted（v3.3 已实施；浅色基底复用进 [ADR-0008](0008-widget-theme-follow.md) widget 调色板）
- 日期：2026-07-21
- 关联：[ADR-0005](0005-settings-system-guide.md) / [ADR-0007](0007-range-linear-3tab.md) / [ADR-0008](0008-widget-theme-follow.md) / [REVIEW.md](../REVIEW.md)

---

## 背景（Context）

v2.x 的视觉是「科技青蓝」基调，但在几个维度暴露问题：

1. **品牌辨识度弱**：青蓝色是大众 default，缺少能记住的品牌锚点。
2. **「正常用量绿」与「品牌选中蓝」撞色**：用量档位用青绿表「正常」、交互选中用青蓝，两者色相邻近，用户在「账户选中」与「用量正常」之间会产生语义混淆——同一种蓝绿色既表示「这个被选中」又表示「这个用量健康」，信息表达打架。
3. **M3 角色不全 + 圆角散乱**：只设了零散几个 `colorScheme` 角色，卡底/容器层级靠临时硬编码灰值；圆角 14/16/18/20dp 各处随手写，不成体系。
4. **type scale 层级塌缩**：M3 默认只覆盖 `bodyLarge`，导致 `titleMedium(16sp)` 与 `bodyLarge(16sp)` 同号，标题与正文分不开。

v3.3 参考 NordVPN iOS 的视觉语言重塑：一个强品牌蓝 + 冷调中性基底 + 与交互色彻底分离的用量状态色。

---

## 决策（Decision）

### 1. 品牌主色：NordVPN 蓝 #4687FF

```kotlin
val BrandPrimary    = Color(0xFF4687FF)  // 主蓝
val BrandPrimaryDark = Color(0xFF3A6FE0) // 按压态
val BrandAccent     = Color(0xFF6BA3FF)  // 辅助亮蓝
```

`primary = BrandPrimary` 同时用在浅色 / 深色 colorScheme（深色模式不提亮主色，保持品牌一致）。**关闭 dynamicColor**（不用 Material You 取系统壁纸色），保证品牌视觉在所有设备一致。

### 2. 用量状态色与品牌蓝分离（核心决策）

用量档位色刻意避开蓝色系，用「绿 / 橙 / 红」交通灯语义，与「品牌交互蓝」从色相上切断关联：

| 档位 | 色 | 触发 |
|---|---|---|
| SAFE 正常 | `UsageSafe = #00B894`（绿） | `< 60%` |
| WARN 偏紧 | `UsageWarn = #F5A623`（橙） | `60% – 85%` |
| DANGER 告急 | `UsageDanger = #FF6B6B`（红） | `> 85%` |

这样「这个账户被选中（蓝）」与「这个账户用量健康（绿）」是两种完全不同的视觉信号，不再撞色。阈值 `60/85` 由 [ADR-0007](0007-range-linear-3tab.md) 的 `UsageThresholds` 单一真源驱动。

### 3. 冷调中性基底（深 / 浅双套完整 M3 角色）

**深色（NordVPN 深蓝黑）**：`SurfaceDark #131826` / `CardDark #1A1F2E` / `CardDarkElevated #222B3E` / `OnSurfaceDark #E8EDF5` / `OnSurfaceMuted #8A93A6`。

**浅色（NordVPN 冷灰白）**：`SurfaceLight #F7F8FA` / `CardLight #FFFFFF` / `OnSurfaceLight #1A1F2E` / `OnSurfaceMutedLight #6B7280` / `BorderLight #E8EAEE` / `DividerLight #EEF0F3`。

两套 colorScheme 都补齐完整 `surfaceContainerLowest / surfaceContainerLow / surfaceContainer / surfaceContainerHigh` 层级，卡片底统一走 `surfaceContainerLow`（见 `SettingsScreen` / `RangePrimaryCard` / 趋势卡）。

### 4. Shapes：8dp 网格 token

把散乱的 14/16/18/20 收敛成 8dp 倍数体系：

```kotlin
val AppShapes = Shapes(
    extraSmall = 4.dp, small = 8.dp, medium = 12.dp,
    large = 16.dp, extraLarge = 20.dp
)
```

### 5. Type scale：标题-正文-注释三级清晰跳跃

显式设全 9 档（`titleLarge 22 / titleMedium 18 / titleSmall 15 / bodyLarge 16 / bodyMedium 14 / bodySmall 12 / labelLarge 14 / labelMedium 12 / labelSmall 11`），关键是用 `fontSize` 拉开 `titleMedium(18)` 与 `bodyLarge(16)` 的层级，消除默认值导致的标题正文同号塌缩。

---

## 备选方案（Alternatives，均已否决）

- **沿用 v2.x 科技青蓝**：撞色问题不解决，品牌锚点仍弱。
- **用量色也用品牌蓝梯度**（浅蓝/中蓝/红）：正常档与交互蓝仍撞，否决。
- **开启 dynamicColor（Material You）**：系统壁纸会覆盖品牌蓝，视觉不可控，与「NordVPN 蓝品牌锚点」目标冲突。

---

## 后果（Consequences）

**正面**
- 视觉统一，深 / 浅双模式都有一致品牌锚点（#4687FF）。
- **用量色语义独立于交互蓝**：「选中」与「健康」不再视觉打架，信息传达准确。
- M3 角色齐全 + 8dp 形状网格 + 完整 type scale，后续加 UI 不再临时硬编码颜色 / 圆角 / 字号。
- 浅色基底 token 直接复用进 widget 调色板（[ADR-0008](0008-widget-theme-follow.md)），App 与 widget 视觉一致。

**负面 / 风险**
- 关闭 dynamicColor 失去「跟随壁纸」的个性化，但对工具型 App（用户看用量，不看皮肤）是正确取舍。
- `#FF6B6B` 既是 `UsageDanger` 又作 `error`，语义复用（都是「需注意」），刻意为之，不算冲突。

---

## 关联代码

- `ui/theme/Color.kt` —— 品牌蓝 + 用量三色 + 深 / 浅中性基底 token。
- `ui/theme/Theme.kt` —— `LightColors` / `DarkColors`（完整 surfaceContainer 角色）+ `AppShapes`（8dp 网格）+ `MyApplicationTheme`（关闭 dynamicColor、读 `themeMode`）。
- `ui/theme/Type.kt` —— 完整 9 档 type scale。
- 消费方：`MainActivity.kt`（`usageColorFor` / `TierPill` / `RangePrimaryCard`）、`SettingsScreen.kt`（`surfaceContainerLow` 卡底）、`widget/UsageColors.kt` + `widget/WidgetPalette.kt`（[ADR-0008](0008-widget-theme-follow.md)）。

## 参考依据

- Material 3 color roles（surfaceContainerLow 等）：https://m3.material.io/styles/color/the-roles-of-colors
- NordVPN iOS 视觉语言：品牌锚点蓝 #4687FF + 冷调中性基底（实机参考）。
