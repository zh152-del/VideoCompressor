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

        /** 设置结构版本号，用于默认值迁移 */
        val SCHEMA_VERSION = intPreferencesKey("schema_version")

        /** 1.1 版本的旧默认值（用于判断"用户没手动改过"） */
        val LEGACY_MIN_SAVINGS_DEFAULT = 5
        val LEGACY_MIN_SIZE_DEFAULT = 10
        val LEGACY_SKIP_HDR_DEFAULT = true
    }

    /** 当前设置结构版本 */
    private val CURRENT_SCHEMA = 2

    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { prefs ->
        AppSettings(
            quality = prefs[Keys.QUALITY]?.let {
                runCatching { QualityPreset.valueOf(it) }.getOrNull()
            } ?: QualityPreset.MEDIUM,
            resolution = prefs[Keys.RESOLUTION]?.let {
                runCatching { ResolutionOption.valueOf(it) }.getOrNull()
            } ?: ResolutionOption.ORIGINAL,
            deleteOriginal = prefs[Keys.DELETE_ORIGINAL] ?: true,
            minSavingsPercent = prefs[Keys.MIN_SAVINGS] ?: 30,
            sortOrder = prefs[Keys.SORT_ORDER]?.let {
                runCatching { SortOrder.valueOf(it) }.getOrNull()
            } ?: SortOrder.DATE_DESC,
            minSizeMb = prefs[Keys.MIN_SIZE_MB] ?: 0,
            skipHdr = prefs[Keys.SKIP_HDR] ?: false,
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

    /**
     * 把 1.1 版本的旧默认值升级为新默认值。
     *
     * 只在「用户从来没手动改过这一项」时才改（值仍等于旧默认值），
     * 用户主动设过的值一律保留 —— 不能因为升级就覆盖用户的意愿。
     *
     * 需要迁移的三项都是被实测证明不合理的默认值：
     *  · 最低收益 5%  → 30%（5% 等于"白忙一场"）
     *  · 最小体积 10MB → 0  （体积阈值不准确，码率判断才准）
     *  · 跳过 HDR 开  → 关  （默认跳过等于不支持这批视频）
     */
    suspend fun migrateLegacyDefaults() {
        val prefs = context.settingsDataStore.data.first()
        val version = prefs[Keys.SCHEMA_VERSION] ?: 1
        if (version >= CURRENT_SCHEMA) return

        context.settingsDataStore.edit { p ->
            val savedSavings = p[Keys.MIN_SAVINGS]
            val savedMinSize = p[Keys.MIN_SIZE_MB]
            val savedSkipHdr = p[Keys.SKIP_HDR]

            if (savedSavings == null || savedSavings == Keys.LEGACY_MIN_SAVINGS_DEFAULT) {
                p[Keys.MIN_SAVINGS] = AppSettings().minSavingsPercent
            }
            if (savedMinSize == null || savedMinSize == Keys.LEGACY_MIN_SIZE_DEFAULT) {
                p[Keys.MIN_SIZE_MB] = AppSettings().minSizeMb
            }
            if (savedSkipHdr == null || savedSkipHdr == Keys.LEGACY_SKIP_HDR_DEFAULT) {
                p[Keys.SKIP_HDR] = AppSettings().skipHdr
            }
            p[Keys.SCHEMA_VERSION] = CURRENT_SCHEMA
        }
    }
}
