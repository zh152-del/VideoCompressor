package com.videocompress.local.data

import androidx.room.TypeConverter

/** Room 类型转换器：枚举以名称字符串落库，便于直接排查数据 */
class Converters {

    @TypeConverter
    fun fromStatus(status: TaskStatus?): String? = status?.name

    @TypeConverter
    fun toStatus(value: String?): TaskStatus? =
        value?.let { runCatching { TaskStatus.valueOf(it) }.getOrNull() }
}
