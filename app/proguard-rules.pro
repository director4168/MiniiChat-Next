# ========== kotlinx.serialization ==========
# 保留 @Serializable 类的生成序列化器与注解信息（否则运行时抛 SerializationException）
-keep class kotlinx.serialization.** { *; }
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class * { @kotlinx.serialization.Serializable *; }
-keepattributes *Annotation*, InnerClasses, EnclosingMethod, Signature

# ========== 异常类名 ==========
# Dialog / Toast 里会显示 e.javaClass.simpleName，保留名字便于定位问题
-keepnames class * extends java.lang.Exception

# ========== 可选依赖缺失导致的警告（不加会直接中断构建） ==========
-dontwarn org.slf4j.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn io.ktor.**
-dontwarn androidx.compose.ui.tooling.**
