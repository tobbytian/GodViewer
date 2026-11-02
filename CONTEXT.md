# GodViewer 领域术语契约

跨会话（人或 AI 协作）开发时统一用语，避免把"规则定义、运行时效果、还原基线、
协议边界"混为一谈。术语与代码实体一一对应；改代码前先对齐这里的定义。

## 规则定义（ViewRule）

规则是持久化的"应做什么"：**匹配身份**（`activityClass` + `viewClass` + `depth` +
`resourceName`/`text`/`description`）描述目标是谁，**效果**（`modified` + `changed*`
范围标志）描述写什么。匹配身份不承载效果内容，效果字段不参与定位。
规则创建时还会截缩略图（`ViewRuleThumbnails`，仅展示用，非运行时身份）。

_Avoid_: 用 `modified` 的当前值反推目标；把缩略图当作规则身份的一部分。

## 普通规则的 depth

自 Activity decorView 向下逐层 childIndex 的路径（`ViewRuleUtil.getViewHierarchyDepth`）。
创建时记录，重放时按同路径重定位。depth 是主锚，可漂移（宿主升级、异步布局）；
漂移时的恢复与取舍见"双锚匹配"。

## repeatable 规则与 depth 双义

`repeatable=true`（`itemRootClass` 非空）的规则是**列表项规则**：对该 Activity 内所有
结构/文本匹配的同类项生效（RecyclerView/ListView 项）。此时 **`depth` 字段语义变为
"自 item 根到目标视图的路径"**（itemPath），`RuleKey` 结构不变——这是有意复用，
不是疏漏。新增规则字段必须走"增量 + 默认值"方式（旧端 Gson 忽略未知字段），
schemaVersion 保持不变。

**两条应用路径**（2026-11 引入 bind hook 后）：

- **绑定期精确应用（主路径，仅 RecyclerView）**：`RecyclerBindingHooker` hook
  `RecyclerView$Adapter.bindViewHolder` / `onViewRecycled`，布局前应用（无首帧
  闪现）。仅当包内存在 repeatable 规则或编辑模式开启时安装；bind+recycle 成对
  安装成功才算激活（`ViewRuleApplier.bindHookActive`）。
- **布局期对账（`RepeatableRuleReconciler`，兜底）**：复用 OnGlobalLayout 重放
  时机，单次遍历收集 + 登记表还原。hook 激活时对账器**跳过 RecyclerView 子树**
  （让位 bind hook），保留 ListView 等无 bind hook 的容器与 hook 安装失败场景。

`viewType`（增量字段，默认 0 = 不过滤）：捕获时记 `Adapter.getItemViewType(position)`，
bind 路径按它过滤混类型信息流列表；ListView 规则恒为 0。

**安全锚（错杀零容忍，2026-11-03）**：repeatable 规则必须带至少一个区分锚——
`text` 或 `resourceName` 非空。两者皆无的规则会命中列表里**所有**同路径视图，
混合 item 类型时即错杀。三处强制：创建侧无锚降级普通规则（`hasRepeatableAnchor`）、
匹配/导航侧无锚刻底不命中（`matchRepeatableView` / `findTargetInItem`，兜底宿主
编辑与导入下发的规则）、候选 viewType 读不到按不匹配处理（宁漏勿错）。
`viewType` 在对账路径同样校验（`matchRepeatableView`），bind 路径经
`viewTypeMatches`——两条路径标准一致。

_规则变更的即时生效_：bind hook 激活时对账器让位了 RecyclerView 子树，新规则
保存 / 换值后由 `ViewRuleManager.reconcileImmediately` 经
`ViewRuleApplier.boundItemRefresher` 回调 hook 重应用可见 item（回调住中立对象，
rule 侧不反向依赖 hook）。

_Avoid_: 对 repeatable 规则调用 `findViewBestMatch`（它按绝对 depth 解析 itemPath，
会定位到错误视图）；把 itemPath 存到独立字段破坏 `RuleKey` 兼容；对账器与 bind
hook 争抢同一视图的登记项；放宽或绕过安全锚检查（含"临时调试"）；还原时无差别
写回整个基线。

## 效果基线（两条来源）

还原时恢复到的状态边界，按规则类型分两条来源：

- **普通规则**：`rule.original` —— 创建规则那一刻该视图的快照（`ViewRuleApplier`）。
- **repeatable 规则**：**每视图自己的基线**（进程内 `WeakHashMap` 记账）—— 各列表项
  原文/尺寸不同，用创建例的 `original` 还原其他项会写坏它们的内容。基线在首次应用
  前捕获，还原后清除；进程死亡即失（重启后重新捕获，安全）。

**绑定代次（epoch）**：repeatable 登记项带 epoch（`ViewRuleApplier.appliedEpochs`），
区分"对账器（0）"与"某次 ViewHolder 绑定（>0）"两个写者。两种还原动作不同：

- **revoke（回收/分离/删除）**：按基线还原——`onViewRecycled`、detach、规则删除。
- **discard（重绑定）**：`bindViewHolder` before 只清记账**不还原**——宿主 adapter
  紧接着写下一行内容，按旧基线还原会把旧行内容写进新行（借鉴 GodMode-Pro
  `discardBindingEffects`）。

_Avoid_: 用 `rule.original` 还原 repeatable 命中的非创建例视图；把 repeatable 基线
持久化到 JSON；重绑定时按基线还原（会写脏新行内容）。

**掩码还原（错杀防线）**：repeatable 的还原只碰规则声明过（`changed*`）的属性——
应用时把 changed 掩码记进登记表（`ViewRuleApplier.repeatableChangedMasks`），还原
逐属性判断。只改可见性的规则绝不回写文本：列表项 rebind 后宿主刚写入的新内容
不属于规则，回写旧基线文本就是错杀的另一种形态。普通规则不受此约束（目标是
单个稳定视图，整基线还原即"重置"语义）。

## 应用目标与效果所有权

规则生效后实际被写入的视图是"应用目标"。还原（删除/撤销/对账失效）只恢复**规则
自己写入过的属性**，以基线为界。宿主应用在规则生效后自行改变的属性不属于该规则
（效果所有权归后写者）——v1 对 repeatable 的文本/可见性还原按基线整体恢复，尚未
逐属性追踪所有权，属已知取舍，不要在文档或注释里夸大为"精确所有权"。

_Avoid_: "重置"被理解成把视图恢复到"最近一次的状态"；把宿主后续的改动也算进规则
的还原范围。

## 权威与镜像

规则唯一权威在**目标应用私有目录** `files/godviewer/rules.json`（`RuleStore`），
只在目标进程读写。宿主侧一切数据（`godviewer/mirror/<pkg>/`）都是 best-effort
广播镜像（`RuleMirror`，soft token `godviewer-rule-mirror-v1`），丢失可由
`ACTION_REQUEST_MIRROR_PUSH` 重推。宿主→目标的编辑/删除走
`HostControlBridge` + `PendingCommandStore` 待同步队列（目标未运行先入队）。
**不要**重新引入 Service/ContentProvider 冷启动投递或 system_server hooking
（均已试过并否决）。

_Avoid_: 宿主直接读目标私有数据；把镜像当权威做业务判断；目标不在线时假设命令已执行。

## 协议冻结面

外部协议冻结（改 = 必须双向兼容）：Manifest 组件 FQCN（`.data.RuleMirrorReceiver`
等三个）、广播 action/token、`rules.json` schema、`content://com.godviewer.app.hostprefs`、
`com.godviewer.app.util.ModuleStatus` 类名字符串、libxposed 入口
`META-INF/xposed/java_init.list` → `com.godviewer.app.target.hook.GodViewerModule`。
包边界由 `scripts/verify-architecture.sh` 自动校验（shared/target/host 依赖方向、
libxposed import 收敛、冻结文件存在），改动边界前先跑它。

_Avoid_: 重命名冻结 FQCN"顺手清理"；新增 shared→host/target 依赖。

## 双锚匹配（宁漏勿错）

depth 主锚命中后必须通过 `resourceName` 交叉验证（存在即校验，严格模式不豁免）；
验证不符判失配；带资源名锚的规则不做 text 兜底。原则：**错配宿主内容的代价高于
暂时漏应用**——漏应用由下一帧布局重放兜底，错配不可恢复。失配必须带诊断码
（`MatchMiss`：`depth_path_missing` / `class_mismatch` / `name_mismatch` /
`name_anchor_failed` / `text_miss` / `no_locator`），落在 replay miss 日志里，
是"规则没生效"排查的第一入口。

_Avoid_: 匹配函数静默返回 null 不带原因；为提高命中率放宽锚验证。

## 注入 UI 资源红线

注入（target 侧）布局**严禁**引用模块 `@drawable` / `@color` / `@mipmap`（会被目标
资源表解析而炸，adapter 内炸 = 宿主闪退）；`@string` 经 `moduleRes` 可以。背景/图标
一律代码 `moduleRes.getDrawable(id, null)` 注入。字符串 EN/zh-rCN 双文件并行维护。

_Avoid_: "只是加个图标"直接写进 layout_*.xml。
