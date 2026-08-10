# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

-keep class com.godviewer.app.hook.AnyHookPackage
-keep class com.godviewer.app.hook.AnyHookZygote

# Gson 反射序列化持久化规则
-keep class com.godviewer.app.data.** { *; }

# Xposed 模块自身代码全部保留：点击分发器通过 Class.newInstance() 反射创建 handler，
# 弹窗 / 工具类经 XModuleResources 与 XposedHelpers 反射访问，混淆会破坏这些路径
-keep class com.godviewer.app.** { *; }