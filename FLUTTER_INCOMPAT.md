# Flutter / 自绘 UI 与 GodViewer 的兼容性说明

> 本文档记录对「农夫山泉」APK（`G:\Download\农夫山泉.apk`，约 87 MB）的逆向探查结论，
> 以及由此引出的「为什么 GodViewer 在纯自绘 App 上检测不到弹窗、隐藏控件会整屏消失」的原理说明。
> 不涉及任何代码改动，仅作分析归档。

## 1. 探查方法

仅做 **静态结构扫描**（不执行任何 APK 内代码）：

- 解包 APK，列出 `lib/` 下所有 `.so`，检查渲染引擎特征库；
- 统计 `assets/flutter_assets/` 资源、原生 `res/layout/*.xml` 数量；
- 检索 AndroidView/PlatformView 混合痕迹。

## 2. 「农夫山泉」APK 的引擎判定

| 检查项 | 结果 |
|---|---|
| `libflutter.so` / `libdartjni.so` / `libapp.so` | **存在**（Flutter 运行时 + Dart 编译产物） |
| `assets/flutter_assets/`（AssetManifest.bin / FontManifest.json / NOTICES.Z 等） | **存在**（共 37 条，典型 Flutter 资源布局） |
| 原生 `res/layout/*.xml` 数量 | **0**（没有任何 Android 原生 XML 布局） |
| AndroidView / PlatformView 混合痕迹 | **无** |

**结论：该 App 为纯 Flutter 应用，整块界面由 Dart 在 Skia/Impeller 画布上自绘完成，
对外只暴露一个原生 Surface（`FlutterView` / `SurfaceView`），不创建任何原生 View 层级，
也不创建原生 `Dialog` / `PopupWindow`。**

## 3. 为什么会出现这两个现象

GodViewer 的全部能力都建立在 **Android 原生 View 树** 之上：

| 能力 | 实现方式 | 在纯 Flutter 下的结果 |
|---|---|---|
| 检测弹窗 | hook 原生 `Dialog` / `PopupWindow` | 抓不到 —— Flutter 弹窗是 widget，运行在 Canvas 上，无原生 Dialog 对象 |
| 隐藏控件 | 对某个 `View` 调 `setVisibility(GONE)` | 只能拿到**那一个** Flutter Surface → `GONE` 让整屏消失 |
| 触摸选 View | hitTest 原生 View，取 `TextView` / `ImageView` 实例 | 点哪儿都命中同一个 Surface，无内部 widget |
| 改文字 / 图片 | `TextView.setText` / `ImageView` 加载 | 拿不到任何原生文本/图片控件 |

- **「检测不到弹窗」** = 没有原生 Dialog 可 hook；
- **「一隐藏就全部隐藏」** = 能 `setVisibility` 的只有那个唯一的 Surface。

**这不是 GodViewer 的 bug，而是架构不匹配。** LSPosed 能勾住该 App、模块也能激活（勾的是 Java 层），
但 Java 层够不到 Dart 运行时里的 widget 树。同理，Unity / 游戏引擎 / 纯 WebView 这类「自绘 UI」
也都不在 GodViewer 的作用域内（WebView 能勾到的也只有那一个 WebView View，读不到网页内部 DOM）。

## 4. 若要在自绘 App 上「兼容现有功能」的可行性

原理性矛盾：**现有功能粒度是 View，而 Flutter 没有 View**。

- **路线 A — 标注不支持（成本最低）**
  识别到 Flutter / 自绘 App 时，主动提示「此应用为自绘 UI，无法按 View 编辑 / 检测弹窗」，
  避免用户误以为是功能损坏。不改变任何现有能力。

- **路线 B — 屏幕矩形区域遮罩（中等成本，部分兼容）**
  不再按 View，改为让用户在屏幕上「框选一个矩形区域」并叠加浮层盖住 / 隐藏它。
  粒度退化为像素级、不语义、不认 widget；规则 schema（`activityClass + depth[] + viewClass`）
  需新增一套坐标模式。**弹窗检测仍做不了**（除非再接无障碍服务去读 Semantics 节点）。

- **路线 C — widget 级真兼容（大工程）**
  hook Flutter Engine / Dart VM 去读取 widget 树，实现按 widget 隐藏 / 改属性 / 检测弹窗。
  等于在另一套运行时上重写 GodViewer，不在现有 Xposed-Java 体系内，基本是另一个项目。

## 5. 其他可行的去广告 / 关弹窗手段（非 GodViewer）

- **无障碍服务（AccessibilityService）**：读取 App **标注了 Semantics 的节点** 去点击关闭；
  未标注语义的 widget 同样读不到。
- **屏幕级遮罩**：在指定坐标叠加半透明/不透明浮层盖住某区域（定位困难、维护成本高）。
- **网络层拦截**：若广告来自特定域名，可用 VPN / 代理规则在请求层屏蔽（与该 App 渲染无关）。

---

*创建日期：2026-10-30。依据为静态结构扫描，未运行目标 App，结论限于打包形态判定。*

> **后续**：兼容可行性已完成研究，见 `FLUTTER_FEASIBILITY.md` —— 通过 `FlutterJNI` 的 JNI 保留锚点
> 强制开启语义树（Semantics），用"语义矩形 + 遮罩 View"近似实现隐藏，语义路由域检测弹窗；
> 结论为可行、工程量可控（F1 探针 → F4 打磨四期）。
