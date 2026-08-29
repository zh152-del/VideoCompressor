# ============================================================================
# 本地视频压缩器 - 混淆规则
#
# 原则：Media3 / Room / DataStore 只做必要的保护，其余交给 R8 裁剪。
#      任何被反射使用或需要保留字段名的数据类都必须 keep。
# ============================================================================

# ---------------------------------------------------------------- Room
# Room 需要保留实体字段名与 DAO 接口，否则生成代码中的列名会丢失
-keep class com.videocompress.local.data.** { *; }
-keepclassmembers class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }

# ---------------------------------------------------------------- Media3
# Transformer / ExoPlayer 内部存在按名称查找编解码器的逻辑，整包保护最稳妥
-keep class androidx.media3.** { *; }
-keep interface androidx.media3.** { *; }

# Guava（Media3 的传递依赖）在 Android 构建下会产生大量 harmless 警告
-dontwarn com.google.common.**
-dontwarn com.google.errorprone.**
-dontwarn org.checkerframework.**
-dontwarn org.codehaus.mojo.animal_sniffer.*

# ---------------------------------------------------------------- 其它
# 保留数据类序列化相关信息
-keepclassmembers class com.videocompress.local.** {
    <init>(...);
}

# 保留异常堆栈行号，便于排查线上崩溃
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
