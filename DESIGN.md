---
name: Ride Ledger
description: 面向骑行与小队旅行的离线原生安卓账本设计系统（纯 Java Android SDK View）
colors:
  primary: "#186448"
  icon-bg: "#123D31"
  title: "#154B3C"
  ink: "#25372D"
  muted: "#53695C"
  hint: "#677970"
  page: "#EFF3EE"
  page-glow: "#DFECE3"
  surface: "#FFFFFF"
  soft: "#F2F6F1"
  selected: "#E4F1E6"
  stroke: "#DCE6DE"
  field-line: "#9DADA2"
  divider: "#E0EAE2"
  pressed: "#24186448"
  danger: "#9D3E34"
  danger-stroke: "#E7BEB7"
  warning: "#885615"
  pending-record: "#E9EBED"
  confirmed-record: "#F4F8F5"
typography:
  display:
    fontFamily: "sans-serif-medium"
    fontSize: "32sp"
    fontWeight: 500
  headline:
    fontFamily: "sans-serif"
    fontSize: "24sp"
    fontWeight: 700
  title:
    fontFamily: "sans-serif"
    fontSize: "18sp"
    fontWeight: 700
  body:
    fontFamily: "sans-serif"
    fontSize: "15sp"
    fontWeight: 400
  label:
    fontFamily: "sans-serif-medium"
    fontSize: "14sp"
    fontWeight: 500
  caption:
    fontFamily: "sans-serif"
    fontSize: "13sp"
    fontWeight: 400
rounded:
  record: "8dp"
  control: "12dp"
  menu: "12dp"
  card: "18dp"
  sheet: "20dp"
  pill: "20dp"
  receipt-button: "12dp"
spacing:
  item-gap: "4dp"
  control-gap: "8dp"
  section-gap: "12dp"
  card-inset: "14dp"
  page-inset: "16dp"
  sheet-inset: "20dp"
components:
  button-primary:
    backgroundColor: "{colors.primary}"
    textColor: "{colors.surface}"
    rounded: "{rounded.control}"
    height: "48dp"
    padding: "8dp 12dp"
  button-outline:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.primary}"
    rounded: "{rounded.control}"
    height: "48dp"
    padding: "8dp 12dp"
  button-tint:
    backgroundColor: "{colors.selected}"
    textColor: "{colors.primary}"
    rounded: "{rounded.control}"
    height: "48dp"
    padding: "8dp 12dp"
  button-quiet:
    backgroundColor: "transparent"
    textColor: "{colors.primary}"
    rounded: "{rounded.control}"
    height: "48dp"
    padding: "8dp 12dp"
  button-danger:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.danger}"
    rounded: "{rounded.control}"
    height: "48dp"
    padding: "8dp 12dp"
  card:
    backgroundColor: "{colors.surface}"
    rounded: "{rounded.card}"
    padding: "14dp"
  sheet:
    backgroundColor: "{colors.surface}"
    rounded: "{rounded.sheet}"
    padding: "20dp"
  nav-item-active:
    backgroundColor: "{colors.selected}"
    textColor: "{colors.primary}"
    rounded: "{rounded.control}"
    height: "48dp"
---

# Design System: Ride Ledger

## Overview

**Creative North Star: "山野便携账本（The Field Ledger）"**

Ride Ledger 是一款在离线、弱网、户外骑行与小队出行场景下高频使用的原生 Android 记账工具。界面属于典型的 **Operate（任务操作型）** 表面：信息清晰度、单手触达效率、离线/同步状态的明确反馈，永远优先于装饰性表达。

整体视觉采用“温润纸面 + 深林绿墨水”的低刺激色调：以极浅米绿纸色作为画布，纯白卡片与柔和浅绿区块承载高频表单和统计表格，深林绿只用于主操作、关键金额与选中态，让用户在路途颠簸或户外强光下也能一眼定位数字与核心动作。

**Key Characteristics:**
- **零重依赖原生构建**：全部界面基于 Android 原生 `View` 与 `LedgerStyle` 工厂方法绘制，拒绝额外 UI 框架带来的体积与启动开销。
- **扁平色调分层**：不依赖重投影，通过“纸面 → 卡片 → 浅绿高亮区”的明度阶梯与 `1dp` 细描边建立秩序。
- **数字优先与多币种对齐**：金额输入框拥有全场最大视觉权重，报表金额强制启用等宽数字（`tnum`）并支持窄屏自动转竖排。
- **诚实的离线与同步状态**：所有记录先落本机，待同步、小票待补传与并发冲突必须以显式状态传达。

## Colors

以单一深林绿为品牌主色，搭配同色相低饱和中性灰绿与克制的砖红/琥珀语义色，全部权威定义位于 `app/src/main/res/values/colors.xml` 并通过 `LedgerStyle` 注入。

### Primary
- **深林绿 / Primary** (`#186448`, `ledger_primary`)：主按钮填充、选中态文字、图标强调与聚焦下划线；白底对比度达 7.1:1（AAA）。
- **深松绿 / Icon Background** (`#123D31`, `ride_ledger_icon_background`)：应用自适应图标底色。
- **柔绿选中 / Selected** (`#E4F1E6`, `ledger_selected`)：底部导航选中胶囊、筛选 Chip 选中态、TINT 按钮底色与图标底托；与 Primary 文字对比度达 6.1:1（AA+）。
- **浅雾绿 / Soft** (`#F2F6F1`, `ledger_soft`)：快速记账金额高亮盒、现金操作磁贴、分类胶囊与报告汇总底色。
- **波纹反馈 / Pressed** (`#24186448`, `ledger_pressed`)：所有浅底控件的 Ripple 点击反馈色（主色 14% 透明度）。

### Neutral
- **纸面底色 / Page** (`#EFF3EE`, `ledger_page`) 与 **顶部雾光 / Page Glow** (`#DFECE3`, `ledger_page_glow`)：所有页面的全局鼠尾草画布与顶部渐变呼吸光晕。
- **卡片白 / Surface** (`#FFFFFF`, `ledger_surface`)：卡片、底部弹窗、底部导航栏与系统导航栏底色。
- **标题深墨 / Title** (`#154B3C`, `ledger_title`)：页面/区块标题、核心金额与重点数值（白底对比度 9.9:1）。
- **正文深灰绿 / Ink** (`#25372D`, `ledger_ink`)：表单输入文字、成员列表与正文内容（白底对比度 12.2:1）。
- **次级说明 / Muted** (`#53695C`, `ledger_muted`)：字段标签、时间、未选中导航、空态说明（白底对比度 5.9:1）。
- **占位提示 / Hint** (`#677970`, `ledger_hint`)：输入框 Hint 与禁用态文字（白底对比度 4.6:1）。
- **卡片描边 / Stroke** (`#DCE6DE`, `ledger_stroke`)：`1dp` 卡片边框、次级按钮边框、底部弹窗拖拽手柄。
- **输入底线 / Field Line** (`#9DADA2`, `ledger_field_line`)：未聚焦输入框下划线与滑块刻度点。
- **内部分隔线 / Divider** (`#E0EAE2`, `ledger_divider`)：`1dp` 卡片内分隔线、弹窗底栏分隔线。

### Semantic
- **危险砖红 / Danger** (`#9D3E34`, `ledger_danger`)：删除旅行、批量删除记录与表单内联错误提示（白底对比度 6.6:1）。
- **危险描边 / Danger Stroke** (`#E7BEB7`, `ledger_danger_stroke`)：危险操作按钮的 `1dp` 描边。
- **警示琥珀 / Warning** (`#885615`, `ledger_warning`)：并发修改冲突提示、待同步统计口径提醒、超范围金额警告（白底对比度 6.3:1）。
- **待同步记录底色 / Pending Record** (`#E9EBED`, `ledger_pending_record`)：尚未获队友确认的流水记录卡片底色。
- **已确认记录底色 / Confirmed Record** (`#F4F8F5`, `ledger_confirmed_record`)：已同步确认的流水记录卡片底色。

### Named Rules
**The Single-Source Token Rule.** 所有颜色必须定义在 `colors.xml` 并通过 `LedgerStyle` 读取；禁止在业务 View 中写死 `Color.BLACK`、`Color.WHITE` 或裸十六进制色值（如 `ReceiptUi` 与 `ReviewPanel` 现存硬编码需回收到 Token 中）。

**The Never-Color-Alone Rule.** 任何同步状态（待同步/已确认）、冲突或错误不得仅靠背景色差表达，必须同时配有可读文字标签或图标。

## Typography

**Display & Numeric Emphasis:** 系统 `sans-serif-medium`（Android 原生中等字重）  
**Body & Headings:** 系统 `sans-serif`（常规 / 粗体）

**Character:** 零外挂字体包，完全依托 Android 系统中文与数字渲染，靠清晰的字号跨度（13sp → 15sp → 18sp → 24sp → 32sp+）与字重对比建立层级，并严格使用 `sp` 单位支持系统字体缩放。

### Hierarchy
- **Display / Quick Amount** (`sans-serif-medium`, `32sp` 紧凑 / `42sp–50sp` 宽裕态)：快速记账主金额输入，超长数字时按比例自动缩放到不低于 74%。
- **Headline / Page Title** (`sans-serif` Bold, `24sp`；二级页/弹窗顶栏 `22sp`)：旅行名称、页面主标题、BottomSheet 标题，开启 `accessibilityHeading`。
- **Title / Section Title** (`sans-serif` Bold, `18sp`；金额高亮 `20sp` Medium)：卡片标题、结算金额、旅行卡片名称。
- **Body** (`sans-serif` Regular, `15sp`；输入框 `16sp`)：正文说明、按钮文案、流水明细主行、空态文案。
- **Label** (`sans-serif-medium`, `14sp`)：字段标签、底部导航项、筛选 Chip、币种标签。
- **Caption** (`sans-serif` Regular, `13sp`)：辅助说明、表单紧凑标签、时间戳、同步状态摘要。

### Named Rules
**The Tabular-Numbers Rule.** 所有多行对比金额与统计报表列必须设置 `setFontFeatureSettings("tnum")` 并右对齐，确保小数点与千分位纵向整齐。

**The 12sp Floor Rule.** 界面中任何面向人眼阅读的文字不得低于 `12sp`（需逐步清理现有 `10sp`/`11sp` 的微型状态文案）。

## Layout

系统采用单列居中流式布局，兼顾单手竖屏、大字号、横屏与平板宽屏：

- **页面边距与最大宽**：页面水平最小边距 `16dp`（`PAGE_INSET`），表单与复盘内容区最大宽度 `560dp`（`FORM_MAX_WIDTH`），旅行列表与报告最大宽度 `600dp`（`PAGE_MAX_WIDTH`），超宽时通过 `LedgerStyle.contentInset()` 自动居中留白。
- **节奏梯度**：严格使用 `4dp`（元素内微间距 `ITEM_GAP`）→ `8dp`（控件间距 `CONTROL_GAP`）→ `12dp`（卡片/区块间距 `SECTION_GAP`）→ `14dp`（卡片内边距 `CARD_INSET`）→ `16dp`（页边距 `PAGE_INSET`）→ `20dp`（BottomSheet 水平内边距）。
- **自适应防截断策略**：
  - 宽裕竖屏且键盘收起时（高度 ≥ `590dp`），`QuickEntryPanel` 动态放大金额字号、按钮高度与图标尺寸，提升单手点击舒适度；键盘弹出后立即切回紧凑态确保主按钮可见。
  - 当大字体或窄屏导致横向放不下时，金额+币种列、现金管理三按钮、分摊规则三按钮、记录卡标题+时间行、统计多列金额表均在 `onMeasure` 中自动切换为纵向堆叠。
- **安全区与键盘处理**：Activity 级页面通过 `FormInput.fitActivityWindow()` 在 Android 15+ 上消费 `systemBars | displayCutout | ime`；BottomSheet 需同步补齐底部导航栏安全区适配。

## Elevation & Depth

本系统采用 **扁平色调分层（Flat Tonal Layering）**，页面内所有按钮、卡片、输入区均显式关闭默认阴影（`setStateListAnimator(null)`），不引入高程投影。

深度层次通过三种机制表达：
1. **大气画布与表面色阶**：`pageBackdrop()`（`page #EFF3EE` + 顶部 `220dp` `pageGlow #DFECE3` 渐变）→ `surface (#FFFFFF) + 1dp stroke (#DCE6DE) + 1dp 轻抬升` → `soft (#F2F6F1)` / `selected (#E4F1E6)`。
2. **模态遮罩**：BottomSheet 弹出时使用 `dimAmount = 0.28f` 的轻度背景压暗，保持上下文感知。
3. **悬浮菜单**：下拉菜单与历史记录弹窗使用 `ledger_menu_background.xml`（白底 + `1dp` `stroke` + `12dp` 圆角）。

### Named Rules
**The Flat-By-Default Rule.** 普通按钮永远保持扁平无阴影，卡片仅允许极轻的 `1dp` 抬升配合 `1dp` `ledger_stroke` 建立与背景画布的剥离感，禁止堆叠夸张的投影。

## Shapes

圆角按容器尺度严格分级，保持克制的几何一致性：

- **数字与排版规则**：全部金额表格、总支出、结算金额及流水明细均启用等宽数字（`tnum`），并通过 `LedgerStyle.styledMoney()` / `styledDecimal()` 适度弱化小数位（`0.80–0.84x`）与三字母币种（`0.68–0.70x`）字号与色彩，突出整数重心。
- **底部弹窗（BottomSheet）**：仅顶部双圆角 `20dp`（`SHEET_RADIUS`），底部贴边 `0dp`，顶部居中配置 `36dp × 4dp` 圆角 `PILL_RADIUS` 的 `ledger_stroke` 拖拽指示条。
- **分类快捷胶囊与状态徽标（Pill）**：四边圆角 `20dp`（`PILL_RADIUS`），用于分类快捷选项、当前旅行徽标、全部旅行记录页多人同步状态胶囊及付款分摊“已调整”防呆标记。
- **信息卡片（Card）**：四边圆角 `18dp`（`CARD_RADIUS`）+ `1dp` `ledger_stroke` + `1dp` 轻抬升；活跃旅行卡片边框切换为 `ledger_primary`。
- **下拉菜单（Popup / Menu）**：四边圆角 `12dp` + `1dp` `ledger_stroke` + `4dp` 内边距。
- **常规控件（Button / Chip / Spinner / Highlight Box / RecordCard）**：四边圆角 `12dp`（`CONTROL_RADIUS`）；`RecordCard` 统一 `12dp` 圆角 + `1dp` `ledger_stroke`，左侧嵌入 `3dp` 竖向状态条（待同步/冲突为 `warning`，已确认为柔和 `primary`）。
- **空状态与过渡动效**：统一采用 `LedgerStyle.emptyState()`（柔绿圆形图标底托 + 主副文案 + 可选 `TINT` 引导按钮）；底部三栏 Tab 切换提供 `140ms` 轻微淡入位移动效（遵循系统动画开关）与单行防截断省略。
- **小票相机/缩略图按钮**：`12dp` 圆角（`LedgerStyle.CONTROL_RADIUS`，与付款设置按钮及行高 `48dp` 保持一致），缩略图启用 `setClipToOutline(true)` 裁切。

## Components

### Buttons
克制、清晰、无全大写转换（`setAllCaps(false)`），全部通过 `LedgerStyle.button()` 与 `Action` 枚举创建，内置 `RippleDrawable` 与禁用态样式：
- **Primary**：`primary` 填充、`surface` 文字、`12dp` 圆角、最小高度 `48dp`、内边距 `12dp × 8dp`；禁用态切换为 `selected` 底色 + `hint` 文字。
- **Outline**：`surface` 填充、`1dp` `stroke` 边框、`primary` 文字、`48dp` 高。
- **Tint**：`selected` 填充、无边框、`primary` 文字、`48dp` 高（用于“显示更多 / 查看全部”等次主操作）。
- **Quiet**：透明底、`primary` 文字、用于卡片标题栏右侧轻操作（如“查看报告”“汇率结算”）。
- **Danger**：`surface` 填充、`1dp` `dangerStroke` 边框、`danger` 文字，专用于删除等破坏性操作。

### Cards
- 统一采用 `LedgerStyle.card()`：`surface` 背景、`1dp` `stroke` 边框、`18dp` 圆角、`16dp` 水平内边距，严禁卡片内再套带边框卡片（去嵌套化）。
- 带标题的卡片顶部为“左图标（`LedgerIcon`）+ 18sp Bold 标题 + 可选右侧 `Quiet` 按钮”，下方以 `1dp` `divider` 分隔正文。

### Inputs & Fields
- 默认使用无框底线风格（`style.input()`）：`16sp` `ink` 文字、`hint` 占位色、未聚焦 `fieldLine` 下划线、聚焦切换 `primary` 下划线，必须通过 `caption.setLabelFor(field.getId())` 绑定标签。
- **金额输入框**：统一使用 `FormInput.AmountField`（启用 `tnum` 等宽数字并禁用系统软键盘），配合 `FormInput.next()` / `FormInput.done()` 串联焦点；获取焦点时在屏幕底部唤起内置 `LedgerKeyboard` 4×4 数字键盘（含 `00`、`000`、`⌫` 长按清空、`⌄` 收起及 `下一项/完成` 主键）。
- **货币输入框**：统一使用 `FormInput.CurrencyField`（ASCII 字母过滤 + 自动大写 + 3 位长度限制，`Locale.US` 提示），获取焦点时在同一容器内无跳动切换为内置大写英文 QWERTY 键盘（`Q–P` / `A–L` / `Z–M` + `⌫` + `清空` + `⌄` + `下一项/完成`），取代旧版 `AmountShortcuts`。
- 备注输入框（`NoteInput`）将右侧分类菜单（交通/住宿/餐食）与底部最近 5 条历史弹窗融合在单一下划线输入域内，右侧紧邻 `48dp` 圆形小票拍照/缩略图按钮。

### Bottom Sheets & Dialogs
- 表单类操作统一通过 `LedgerForms.showSheet()` 呈现底部弹窗：顶部拖拽条 + `22sp` 标题 + 可选副标题 + 可滚动表单区 + `1dp` `divider` + 固定底栏（无障碍实时朗读的 `danger` 内联错误区 + 左侧 `Outline` 取消按钮 + 右侧撑满的 `Primary` 确认按钮）。
- 破坏性确认与轻量选择器使用 `AlertDialog` 并调用 `style.alertActions()` 统一按钮外观。

### Navigation
- 主界面采用底部三栏固定导航（记账 / 复盘 / 旅行）：白底，选中项使用 `selected` 胶囊背景 + `primary` Medium 文字，未选中项为 `muted` 文字，最小高度 `48dp`。
- 二级全屏页（`TeamSetupActivity`、`TripReportView`）采用 `LedgerStyle.topBar()`：左侧 `Outline` 返回按钮 + `22sp` Bold 标题。

### Signature Components
- **QuickEntryPanel**：采用双卡分层结构——主记账卡（`entryCard`：浅雾绿金额/币种区 → 无边框付款与分摊行 + 分隔线 → 备注+胶囊分类+圆形小票按钮 → 主保存按钮）与独立现金管理卡（`cashCard`：现金入账 / 成员还款 / 换汇三枚柔绿磁贴），支持草稿自动暂存与视口自适应缩放。
- **RecordCard**：嵌套于复盘卡片与全部流水页的紧凑记录单元，顶部为“类型 + 时间 + 箭头”，中部为富文本金额/成员/分摊/并发冲突提醒，右侧为 `36dp` 小票缩略图与同步角标。
- **ConnectionBadge**：复盘页右上角自绘矢量图标徽标（Wi-Fi / 热点 / 蓝牙 / 离线 / 旋转搜索 / 刷新），支持 `ValueAnimator.areAnimatorsEnabled()` 尊重减弱动效；点按重连、长按打开 `SyncDetailsDialog`。
- **AmountTable**：旅行报告与现金余额复用的多币种表格，自动计算列宽对齐；超宽时无感切换为“按币种分组的纵向卡片”，并合并整行无障碍朗读。

## Do's and Don'ts

### Do:
- **Do** 所有新控件与页面优先复用 `LedgerStyle` 的颜色、圆角、间距与按钮工厂方法，保持全应用零阴影、深林绿纸面质感。
- **Do** 确保所有可点击目标（按钮、Chip、复选框、快捷单位键、图标按钮）触控区域不小于 `48dp × 48dp`，且所有表单字段通过 `setLabelFor` 或 `contentDescription` 提供完整 TalkBack 语义。
- **Do** 在 `onMeasure` 或布局层为大字体、多币种、长成员名提供自动换行或横向转纵向的兜底方案，不截断关键金额与操作按钮。
- **Do** 对所有破坏性操作（删除旅行、批量删除记录）使用 `Action.DANGER` 并提供二次确认弹窗，明确告知本地与同步影响范围。
- **Do** 对待同步记录、并发修改冲突与待补传小票提供清晰可见的文字/图标状态标记，并在提交异步操作时提供“保存中”反馈。

### Don't:
- **Don't** 在 Java UI 代码中硬编码颜色值（如 `Color.BLACK`、`0xff28634e`）或引入与深林绿体系冲突的高饱和装饰色。
- **Don't** 为普通卡片或按钮添加 Material 阴影（`elevation`）或多余的装饰性渐变。
- **Don't** 仅靠相近背景色差（如 `#E9EBED` vs `#F4F8F5`）来区分“待同步”与“已确认”等关键业务状态。
- **Don't** 将核心次级功能（如查看同步详情、从相册选择小票）完全隐藏在无任何视觉提示的长按手势后面。
- **Don't** 在未引入对应依赖的情况下生成 Jetpack Compose、Material Components XML 或 Web CSS 样式代码；所有界面改动必须严格落在纯 Android SDK Java View 体系内。
