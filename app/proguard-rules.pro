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

# 以下具体 -keep 已被底部 `com.godviewer.app.** { *; }` 全部覆盖，保留该条即可。
# Xposed 模块自身代码全部保留：点击分发器通过 Class.newInstance() 反射创建 handler，
# 弹窗 / 工具类经反射访问，混淆会破坏这些路径
-keep class com.godviewer.app.** { *; }

# libxposed/service：Binder/AIDL + XposedProvider，防 R8 破坏跨进程接口
-keep class io.github.libxposed.service.** { *; }

# libxposed API 102（官方 README 规则）：保留入口类无参构造，并在入口类混淆时改写 java_init.list
-dontwarn io.github.libxposed.annotation.**
-adaptresourcefilecontents META-INF/xposed/java_init.list
-keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}