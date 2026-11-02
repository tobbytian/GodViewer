# Flutter 支持可行性研究（Semantics 桥接方案）

> 前置文档：`FLUTTER_INCOMPAT.md`（为什么纯 Flutter App 检测不到弹窗、隐藏即整屏消失）。
> 本文回答：**在不重写 GodViewer、不 hook Dart 运行时的前提下，现有功能能否覆盖 Flutter App，怎么覆盖，代价是什么。**
> 全部结论基于对 `农夫山泉.apk` 的静态验证（2026-10-30），未运行目标 App。

## 0. 一句话结论

**可行，且工程量可控。** 路线 = 强制开启 Flutter 语义树（Semantics），通过 `FlutterJNI` 的
JNI 保留锚点拿到 widget 的文字/角色/**屏幕矩形**，然后：

- **隐藏** → 在目标矩形上叠一个 GodViewer 自己的遮罩 View（视觉等效 `GONE`，不需要改渲染树）；
- **选控件** → 语义矩形做 hitTest，替代原生 View 树 hitTest；
- **检测弹窗** → 语义树出现 `scopesRoute`/`namesRoute` 新路由域即为弹窗（Flutter 弹窗 = 新语义路由）；
- **点击/自动关闭** → 走公共无障碍 API `performAction(ACTION_CLICK)`，由引擎路由回 Dart。

做不到的（原理性）：改渲染文字/图片、真 `GONE`（渲染树在 Dart 侧）、无 Semantics 的装饰性 widget。

## 1. 对目标 APK 的验证结果（关键事实）

| 验证项 | 结果 | 意义 |
|---|---|---|
| 引擎 | `libflutter.so`，Dart VM **3.13.1 stable（2026-08）**，Impeller 计数高 | 现代引擎，语义协议为带 `identifier` 字段的新格式 |
| 混淆 | `io.flutter.*` 绝大多数被 R8 改名为 `a`/`b`/…（仅 107 个类名可见） | **不能依赖改过名的内部类**（`AccessibilityBridge`、`FlutterEngine` 等均不可寻址） |
| `io.flutter.embedding.engine.FlutterJNI` 类名 | **保留** | 唯一稳定的 Java 侧锚点（引擎 C++ 用类名+方法名做 JNI 解析，R8 不能动） |
| `nativeSetSemanticsEnabled` | **保留**（private native，x1） | 反射 invoke 即可**强制开启语义树**，无需真实的无障碍服务 |
| `updateSemantics(ByteBuffer, String[], int[][])` | **保留**（native→Java 回调） | 语义树**全部**更新流经此方法的 choke point |
| `updateCustomAccessibilityActions` / `dispatchSemanticsAction` | **保留** | 自定义动作 / Dart 侧动作请求（可反向触发点击） |
| `setAccessibilityDelegate` / `setPlatformMessageHandler` | **保留**（x3 / x1） | 稳定的**实例捕获点**（被改名后的桥接类调用） |
| 频道字符串 `flutter/accessibility`、`flutter/navigation`、`flutter/platform` | 存在 | 可选的辅助信息源（路由名等） |

> R8 之所以必须放行这些名字：`FlutterJNI` 的 native 方法由引擎 C++ 按名注册/解析，
> native→Java 回调（`updateSemantics` 等）也按名 `GetMethodID`。**任何** Flutter App 的 release 包
> 都保留这组锚点，与混淆配置无关 —— 这是本方案跨 App 成立的根基。

## 2. 技术方案

### 2.1 链路总览

```
GodViewer (target 进程内, libxposed)
  ├─ ① 捕获实例   hook FlutterJNI.setAccessibilityDelegate / setPlatformMessageHandler / nativeAttach
  │               → 得到 FlutterJNI 实例（弱引用持有，随引擎生灭）
  ├─ ② 强制开启   反射 invoke flutterJNI.nativeSetSemanticsEnabled(true)
  │               引擎 → Dart: kAccessibilityFeatureEnabled → Dart 框架构建语义树
  ├─ ③ 观察       hook FlutterJNI.updateSemantics(buffer, strings, actions)
  │               先放行(super，喂给 App 自己的桥) → post-call 通过公共 API 读结果：
  │               flutterView.getAccessibilityNodeProvider().createAccessibilityNodeInfo(id)
  │               → AccessibilityNodeInfo{getText, getContentDescription, getBoundsInScreen
  │                                            (变换已由桥算好), getViewIdResourceName(identifier),
  │                                             getClassName(角色映射), actionList}
  │               BFS 自 HOST_VIEW_ID(-1) 起按 parent/child 遍历，无需解析私有 ByteBuffer
  ├─ ④ 选控件     语义矩形 hitTest（自顶向下取最上层命中节点）→ 复用 ModuleDialogUi 编辑弹窗
  ├─ ⑤ 隐藏       向 App 自己窗口的 FlutterView 父布局 addView 一个遮罩 View 盖住该矩形；
  │               每次 updateSemantics 后重定位（Flutter 列表滚动/动画时矩形会变）
  ├─ ⑥ 弹窗检测   语义树新增 scopesRoute/namesRoute 节点域 = 出现弹窗路由
  │               → 复用现有弹窗通知链路（detect-notify）；可选自动关闭：
  │               在关闭按钮节点上 performAction(ACTION_CLICK)
  └─ ⑦ 退出恢复   编辑模式关闭时 nativeSetSemanticsEnabled(false) + 移除遮罩
```

要点：

- **零字节码解析**：不自己解 `updateSemantics` 的私有 ByteBuffer（格式随引擎版本追加字段、易碎）。
  走公共无障碍 API 读 App 自己的桥解析结果 —— 版本漂移由 Google/Flutter 引擎自己兜底，GodViewer 无感。
  （备选：若个别引擎版本桥异常，再实现 ByteBuffer 解析兜底，格式为 append-only。）
- **零改名依赖**：全程只触碰 `FlutterJNI`（保留名）+ 公共 `android.view.accessibility.*` API。
  被混淆的 `AccessibilityBridge`/`FlutterView` 从不直接寻址（FlutterView 通过 provider 的宿主反查或遮罩挂载点用 decorView）。
- **不要真无障碍服务**：强制开启走 `nativeSetSemanticsEnabled`，与系统 TalkBack/GKD 是否开启解耦；
  也不会让 App 出现在系统无障碍列表里。

### 2.2 现有功能 → Flutter 映射

| GodViewer 现有功能 | Flutter 下实现 | 粒度/效果 |
|---|---|---|
| 触摸选 View | 语义矩形 hitTest | 按节点（通常 = 有语义的 widget），非像素 |
| 隐藏控件 | 语义矩形 + 遮罩 View | **视觉等效 GONE**；滚动/动画时跟随重定位 |
| 改 TextView 文字 | ❌ | 渲染在 Dart 侧，Java 层改不了（语义里能**读**到文字） |
| 改 ImageView 图源 | ❌ | 同上 |
| 改尺寸/边距 | ❌ | 同上（遮罩即"隐藏"是唯一可写操作） |
| 弹窗检测 | 语义路由域观察 | 有效；文字内容可从语义 label 提取 |
| 弹窗拦截（自动关） | 关闭按钮节点 `performAction(CLICK)` | 有效，等效李跳跳/GKD 的无障碍方案但**在进程内**，无需用户开无障碍服务 |
| 规则持久化/重放 | 新增 `flutter_rules.json`（不动 `rules.json` 冻结 schema） | 键 = activity + 语义 identifier(`getViewIdResourceName`) + 文本 + 父链路径，仍带 `matchVersionCode` |
| 规则镜像/托管删除 | 不变（broadcast 链路复用） | 需扩展 mirror 消息类型或镜像第二个文件 |

### 2.3 已知限制（须在 UI 上明示）

1. **无 Semantics 的 widget 不可见**：装饰性 `Image`（无 `semanticLabel`）、自绘 Canvas 内容、
   `ExcludeSemantics` 区域。选不到、也遮不了（除非退化为区域遮罩模式）。
2. **改属性类功能整体缺席**：编辑弹窗对 Flutter 节点应只展示"隐藏/恢复"两个动作，
   其余输入项禁用并注明"自绘界面不支持"。
3. **开启语义树有运行时开销**：Flutter 框架要维护语义树（正常使用无感知，低端机列表滚动或略增耗时）。
   仅在编辑模式/有 flutter 规则生效时开启，退出即关。
4. **遮罩遮挡交互**：被遮罩矩形会拦截该区域触摸（这正是"隐藏"），但若 App 后续把该 widget 挪走，
   遮罩会在下一次语义更新时移走 —— 极端高频动画场景以帧级重定位尽力跟随。

## 3. 风险与对策

| 风险 | 对策 |
|---|---|
| 引擎版本差异（`nativeSetSemanticsEnabled` 签名未来变动） | 反射按参数 `(Z)V` 扫描匹配；找不到则降级为"不支持该 App"并日志说明（`GvLog`） |
| App 内多引擎（add-to-app）/ 多 FlutterView | FlutterJNI 实例按进程弱引用集合持有，每个实例独立开关；遮罩挂在各自 FlutterView 父层 |
| 与系统无障碍服务（TalkBack/GKD）叠加 | 只读不抢占；`updateSemantics` hook 仅旁路观察，always `super` 放行 |
| `addAccessibilityDelegate` 被我们 hook 后影响 App 原桥 | 只观察参数不替换、不拦截返回值 |
| R8 混淆下 FlutterJNI 万一被改名（非标准 keep 配置的极端 App） | 类不可寻址 = 功能自动不启用（按 `Class.forName` 失败静默降级），绝不影响原生功能 |
| 语义 identifier 为空（widget 未显式命名） | 规则键退化为 文本+父链+矩形锚点，沿用现有 fallback 思路 |

## 4. 工程落地（建议分期）

新代码全部收敛在 `target/flutter/`（含 `shared/model` 增量），遵循既有分层与冻结协议。

| 期 | 内容 | 验收 |
|---|---|---|
| **F1 探针** | `FlutterProbe`：捕获 FlutterJNI 实例、`nativeSetSemanticsEnabled(true)`、hook `updateSemantics` 旁路、`GvLog` 打印语义树（id/text/rect/flags） | 真机开农夫山泉 → logcat 看到按钮/文本语义树 |
| **F2 选择+隐藏** | 语义 hitTest 接入编辑模式触摸拦截；`ModuleDialogUi` 增加 Flutter 节点分支（仅 隐藏/恢复）；遮罩 View 管理（挂载/重定位/移除）；`flutter_rules.json` 读写+重放 | 在农夫山泉上隐藏任意带语义控件，重启 App 后规则自动重放 |
| **F3 弹窗** | 语义路由域观察接现有弹窗检测通知；自动关闭（可选开关）；规则镜像扩展 | Flutter 弹窗出现→宿主通知→可自动点关闭 |
| **F4 打磨** | 识别 Flutter App 时编辑弹窗禁用不支持项；多引擎；ByteBuffer 解析兜底；文档/教程 | — |

体量评估：F1 ≈ 2–3 天（含真机联调）；F2 ≈ 1–1.5 周；F3 ≈ 3–5 天；F4 弹性。
**主要不确定性集中在 F1 的真机验证**（静态验证已闭环，动态行为如 UI 线程时序、引擎开关副作用需实测）。

## 5. 其他自绘框架的边界（顺带结论）

- **React Native**：渲染就是原生 View → GodViewer **现状已支持**，无需改动。
- **WebView 页面**：可另立 "WebView 模式"（`evaluateJavascript` 注入 JS 按 DOM 隐藏/改文字）——
  独立小特性，与 Flutter 方案互不依赖。
- **Unity / 游戏引擎**：无语义树，只能退化为屏幕区域遮罩，优先级最低。

## 6. F1 探针验证清单（实施时逐项确认）

- [ ] `Class.forName("io.flutter.embedding.engine.FlutterJNI")` 在目标 App classloader 下成功
- [ ] `setAccessibilityDelegate` hook 捕获到实例（App attach 桥时）
- [ ] `nativeSetSemanticsEnabled(true)` 反射调用后，`updateSemantics` hook 开始收到数据
- [ ] provider BFS：`createAccessibilityNodeInfo(-1)` → 子节点文字与农夫山泉界面实际控件一致
- [ ] `getBoundsInScreen` 数值与屏幕坐标吻合（旋转/折叠屏各验一次）
- [ ] `performAction(ACTION_CLICK)` 能真实触发按钮（找一个"跳过/关闭"验证）
- [ ] `nativeSetSemanticsEnabled(false)` 后 App 行为无残留异常
- [ ] 开启语义树前后，目标 App 帧率/CPU 无可感劣化（开发者选项 GPU 渲染条对比）
