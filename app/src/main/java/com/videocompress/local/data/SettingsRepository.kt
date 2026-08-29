package com.videocompress.local.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** DataStore 文件名 */
private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "compressor_settings"
)

/**
 * App 设置仓库（DataStore）。
 *
 * 只保存轻量配置，不放任何视频数据。
 */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val QUALITY = stringPreferencesKey("quality")
        val RESOLUTION = stringPreferencesKey("resolution")
        val DELETE_ORIGINAL = booleanPreferencesKey("delete_original")
        val MIN_SAVINGS = intPreferencesKey("min_savings_percent")
        val SORT_ORDER = stringPreferencesKey("sort_order")
        val MIN_SIZE_MB = intPreferencesKey("min_size_mb")
        val SKIP_HDR = booleanPreferencesKey("skip_hdr")
        val SKIP_CODEC = booleanPreferencesKey("skip_unsupported_codec")
        val ONLY_CHARGING = booleanPreferencesKey("only_when_charging")
        val BATTERY_FLOOR = intPreferencesKey("battery_floor_percent")
        val THERMAL_GUARD = booleanPreferencesKey("thermal_guard")
        val BATCH_SIZE = intPreferencesKey("batch_size")
    }

    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { prefs ->
        AppSettings(
            quality = prefs[Keys.QUALITY]?.let {
                runCatching { QualityPreset.valueOf(it) }.getOrNull()
            } ?: QualityPreset.MEDIUM,
            resolution = prefs[Keys.RESOLUTION]?.let {
                runCatching { ResolutionOption.valueOf(it) }.getOrNull()
            } ?: ResolutionOption.ORIGINAL,
            deleteOriginal = prefs[Keys.DELETE_ORIGINAL] ?: true,
            minSavingsPercent = prefs[Keys.MIN_SAVINGS] ?: 5,
            sortOrder = prefs[Keys.SORT_ORDER]?.let {
                runCatching { SortOrder.valueOf(it) }.getOrNull()
            } ?: SortOrder.DATE_DESC,
            minSizeMb = prefs[Keys.MIN_SIZE_MB] ?: 10,
            skipHdr = prefs[Keys.SKIP_HDR] ?: true,
            skipUnsupportedCodec = prefs[Keys.SKIP_CODEC] ?: true,
            onlyWhenCharging = prefs[Keys.ONLY_CHARGING] ?: false,
            batteryFloorPercent = prefs[Keys.BATTERY_FLOOR] ?: 15,
            thermalGuard = prefs[Keys.THERMAL_GUARD] ?: true,
            batchSize = prefs[Keys.BATCH_SIZE] ?: 2
        )
    }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        val current = settingsOnce()
        val next = transform(current)
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.QUALITY] = next.quality.name
            prefs[Keys.RESOLUTION] = next.resolution.name
            prefs[Keys.DELETE_ORIGINAL] = next.deleteOriginal
            prefs[Keys.MIN_SAVINGS] = next.minSavingsPercent
            prefs[Keys.SORT_ORDER] = next.sortOrder.name
            prefs[Keys.MIN_SIZE_MB] = next.minSizeMb
            prefs[Keys.SKIP_HDR] = next.skipHdr
            prefs[Keys.SKIP_CODEC] = next.skipUnsupportedCodec
            prefs[Keys.ONLY_CHARGING] = next.onlyWhenCharging
            prefs[Keys.BATTERY_FLOOR] = next.batteryFloorPercent
            prefs[Keys.THERMAL_GUARD] = next.thermalGuard
            prefs[Keys.BATCH_SIZE] = next.batchSize
        }
    }

    /** 读取一次当前设置（只取首帧，不保持订阅） */
    private suspend fun settingsOnce(): AppSettings = settings.first()
}
