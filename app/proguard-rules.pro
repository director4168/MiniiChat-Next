# 基础属性
-keepattributes *Annotation*, InnerClasses, EnclosingMethod, Signature, Exceptions

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
# 参与序列化的枚举不能改名 / 删除
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

# MCP 客户端为纯 JDK HttpURLConnection 实现，无 SDK、无反射，不需要额外 keep 规则

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
