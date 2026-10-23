# 基础属性
-keepattributes *Annotation*, InnerClasses, EnclosingMethod, Signature, Exceptions

# Android组件（Application / Activity / Service / Receiver / Provider）
# 默认proguard-android-optimize.txt应该有这些规则，但AGP 8.13.2+R8激进模式下偶发失效，显式keep避免ClassNotFoundException启动崩溃
-keep public class * extends android.app.Application
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider

# 自己写的Application类
-keep class com.miniichatNext.carter.MiniiChatApplication { *; }

# Mermaid WebView的JS桥：@JavascriptInterface方法名不能被混淆，否则JS调用失败
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.miniichatNext.carter.MainActivity { *; }
-keep class com.miniichatNext.carter.AppDataDocumentsProvider { *; }

# kotlinx.serialization
-keep class kotlinx.serialization.** { *; }
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# 应用自己的@Serializable类+编译器生成的 $$serializer
-keep,includedescriptorclasses class com.miniichatNext.carter.**$$serializer { *; }
-keepclassmembers class com.miniichatNext.carter.** {
    *** Companion;
}
-keepclasseswithmembers class com.miniichatNext.carter.** {
    kotlinx.serialization.KSerializer serializer(...);
}
# 参与序列化的枚举不能改名/删除
-keepclassmembers enum com.miniichatNext.carter.** { *; }

-keep class io.ktor.** { *; }
-keep class * implements io.ktor.client.engine.HttpClientEngineContainer { *; }

-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
-keepclassmembers class kotlin.coroutines.SafeContinuation {
    volatile <fields>;
}

# MCP客户端为纯JDK HttpURLConnection实现，无SDK、无反射，不需要额外keep规则

# 诊断用，异常类名会显示在Dialog/Toast里
-keepnames class * extends java.lang.Exception

# 可选依赖缺失导致的警告（不加会直接中断构建）
-dontwarn org.slf4j.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn io.ktor.**
-dontwarn androidx.compose.ui.tooling.**

# jlatexmath native bridge（jlamex-math-android内含native库，需保留反射入口）
-keep class org.scilab.forge.jlatexmath.** { *; }
-keep class ru.noties.jlatexmath.** { *; }

# intellij-markdown GFM解析器（元素类型注册表/惰性单例，混淆后AST类型比较可能失效）
-keep class org.intellij.markdown.** { *; }
