# 混淆规则
#
# release 构建默认未开启混淆（isMinifyEnabled = false）。
# 如需开启，至少要保留以下两类：
#  1. kotlinx.serialization 生成的代码依赖反射查找伴生对象；
#  2. Room 生成的 DAO 实现类通过反射实例化。

-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod

# kotlinx.serialization
-keepclassmembers class com.qbot.core.model.** {
    *** Companion;
}
-keepclasseswithmembers class com.qbot.core.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp / Okio
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# Room
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**
