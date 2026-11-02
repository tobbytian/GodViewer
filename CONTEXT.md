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
结构/文本匹配的同类项生效（RecyclerView/ListView 项），由
`RepeatableRuleReconciler` 在布局期对账（无任何 Xposed hook）。
此时 **`depth` 字段语义变为"自 item 根到目标视图的路径"**（itemPath），`RuleKey`
结构不变——这是有意复用，不是疏漏。新增规则字段必须走"增量 + 默认值"方式
（旧端 Gson 忽略未知字段），schemaVersion 保持不变。

_Avoid_: 对 repeatable 规则调用 `findViewBestMatch`（它按绝对 depth 解析 itemPath，
会定位到错误视图）；把 itemPath 存到独立字段破坏 `RuleKey` 兼容。

## 效果基线（两条来源）

还原时恢复到的状态边界，按规则类型分两条来源：

- **普通规则**：`rule.original` —— 创建规则那一刻该视图的快照（`ViewRuleApplier`）。
- **repeatable 规则**：**每视图自己的基线**（进程内 `WeakHashMap` 记账）—— 各列表项
  原文/尺寸不同，用创建例的 `original` 还原其他项会写坏它们的内容。基线在首次应用
  前捕获，还原后清除；进程死亡即失（重启后重新捕获，安全）。

_Avoid_: 用 `rule.original` 还原 repeatable 命中的非创建例视图；把 repeatable 基线
持久化到 JSON。

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
