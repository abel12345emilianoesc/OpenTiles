package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.domain.AppVisualStyle
import com.example.domain.BuiltInCatalog
import com.example.domain.HitTrailStyle
import com.example.domain.SfxProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "neon_resonance_prefs")

data class PlayerPreferences(
    val resonanceFragments: Int = 350, // Saldo inicial generoso para probar la tienda
    val equippedThemeId: String = BuiltInCatalog.tileThemes.first().id,
    val equippedSfxId: String = SfxProfile.CRISTAL_ETEREO.id,
    val equippedTrailId: String = HitTrailStyle.ONDAS_PRISMA.id,
    val appVisualStyleId: String = AppVisualStyle.NEON_VOID.id,
    val beatReactiveTiles: Boolean = true,
    val showLaneGuides: Boolean = true,
    val audioOffsetMs: Long = 0L,
    val musicVolume: Float = 0.90f,
    val sfxVolume: Float = 0.85f,
    val vibrationEnabled: Boolean = true,
    val highBloomEnabled: Boolean = true
) {
    val visualStyle: AppVisualStyle
        get() = AppVisualStyle.fromId(appVisualStyleId)
}

class UserPreferencesRepository(private val context: Context? = null) {

    // Respaldo en memoria cuando se instancia en pruebas unitarias sin Context
    private val inMemoryState = MutableStateFlow(PlayerPreferences())

    private object Keys {
        val FRAGMENTS = intPreferencesKey("resonance_fragments")
        val THEME_ID = stringPreferencesKey("equipped_theme_id")
        val SFX_ID = stringPreferencesKey("equipped_sfx_id")
        val TRAIL_ID = stringPreferencesKey("equipped_trail_id")
        val APP_STYLE_ID = stringPreferencesKey("app_visual_style_id")
        val BEAT_REACTIVE_TILES = booleanPreferencesKey("beat_reactive_tiles")
        val SHOW_LANE_GUIDES = booleanPreferencesKey("show_lane_guides")
        val AUDIO_OFFSET_MS = longPreferencesKey("audio_offset_ms")
        val MUSIC_VOLUME = floatPreferencesKey("music_volume")
        val SFX_VOLUME = floatPreferencesKey("sfx_volume")
        val VIBRATION = booleanPreferencesKey("vibration_enabled")
        val HIGH_BLOOM = booleanPreferencesKey("high_bloom_enabled")
    }

    val preferencesFlow: Flow<PlayerPreferences> = if (context != null) {
        context.dataStore.data.map { prefs ->
            PlayerPreferences(
                resonanceFragments = prefs[Keys.FRAGMENTS] ?: 350,
                equippedThemeId = prefs[Keys.THEME_ID] ?: BuiltInCatalog.tileThemes.first().id,
                equippedSfxId = prefs[Keys.SFX_ID] ?: SfxProfile.CRISTAL_ETEREO.id,
                equippedTrailId = prefs[Keys.TRAIL_ID] ?: HitTrailStyle.ONDAS_PRISMA.id,
                appVisualStyleId = prefs[Keys.APP_STYLE_ID] ?: AppVisualStyle.NEON_VOID.id,
                beatReactiveTiles = prefs[Keys.BEAT_REACTIVE_TILES] ?: true,
                showLaneGuides = prefs[Keys.SHOW_LANE_GUIDES] ?: true,
                audioOffsetMs = prefs[Keys.AUDIO_OFFSET_MS] ?: 0L,
                musicVolume = prefs[Keys.MUSIC_VOLUME] ?: 0.90f,
                sfxVolume = prefs[Keys.SFX_VOLUME] ?: 0.85f,
                vibrationEnabled = prefs[Keys.VIBRATION] ?: true,
                highBloomEnabled = prefs[Keys.HIGH_BLOOM] ?: true
            )
        }
    } else {
        inMemoryState
    }

    suspend fun addFragments(amount: Int) {
        if (amount <= 0) return
        if (context != null) {
            context.dataStore.edit { prefs ->
                val current = prefs[Keys.FRAGMENTS] ?: 350
                prefs[Keys.FRAGMENTS] = current + amount
            }
        } else {
            inMemoryState.value = inMemoryState.value.copy(
                resonanceFragments = inMemoryState.value.resonanceFragments + amount
            )
        }
    }

    suspend fun trySpendFragments(cost: Int): Boolean {
        if (cost < 0) return false
        var success = false
        if (context != null) {
            context.dataStore.edit { prefs ->
                val current = prefs[Keys.FRAGMENTS] ?: 350
                if (current >= cost) {
                    prefs[Keys.FRAGMENTS] = current - cost
                    success = true
                }
            }
        } else {
            val current = inMemoryState.value.resonanceFragments
            if (current >= cost) {
                inMemoryState.value = inMemoryState.value.copy(resonanceFragments = current - cost)
                success = true
            }
        }
        return success
    }

    suspend fun setEquippedItem(category: String, itemId: String) {
        if (context != null) {
            context.dataStore.edit { prefs ->
                when (category) {
                    "THEME" -> prefs[Keys.THEME_ID] = itemId
                    "SFX" -> prefs[Keys.SFX_ID] = itemId
                    "TRAIL" -> prefs[Keys.TRAIL_ID] = itemId
                }
            }
        } else {
            inMemoryState.value = when (category) {
                "THEME" -> inMemoryState.value.copy(equippedThemeId = itemId)
                "SFX" -> inMemoryState.value.copy(equippedSfxId = itemId)
                "TRAIL" -> inMemoryState.value.copy(equippedTrailId = itemId)
                else -> inMemoryState.value
            }
        }
    }

    suspend fun updateAppVisualStyle(style: AppVisualStyle) {
        if (context != null) {
            context.dataStore.edit { it[Keys.APP_STYLE_ID] = style.id }
        } else {
            inMemoryState.value = inMemoryState.value.copy(appVisualStyleId = style.id)
        }
    }

    suspend fun updateBeatReactiveTiles(enabled: Boolean) {
        if (context != null) {
            context.dataStore.edit { it[Keys.BEAT_REACTIVE_TILES] = enabled }
        } else {
            inMemoryState.value = inMemoryState.value.copy(beatReactiveTiles = enabled)
        }
    }

    suspend fun updateShowLaneGuides(enabled: Boolean) {
        if (context != null) {
            context.dataStore.edit { it[Keys.SHOW_LANE_GUIDES] = enabled }
        } else {
            inMemoryState.value = inMemoryState.value.copy(showLaneGuides = enabled)
        }
    }

    suspend fun updateAudioOffsetMs(offsetMs: Long) {
        val clamped = offsetMs.coerceIn(-250L, 250L)
        if (context != null) {
            context.dataStore.edit { it[Keys.AUDIO_OFFSET_MS] = clamped }
        } else {
            inMemoryState.value = inMemoryState.value.copy(audioOffsetMs = clamped)
        }
    }

    suspend fun updateMusicVolume(volume: Float) {
        val clamped = volume.coerceIn(0f, 1f)
        if (context != null) {
            context.dataStore.edit { it[Keys.MUSIC_VOLUME] = clamped }
        } else {
            inMemoryState.value = inMemoryState.value.copy(musicVolume = clamped)
        }
    }

    suspend fun updateSfxVolume(volume: Float) {
        val clamped = volume.coerceIn(0f, 1f)
        if (context != null) {
            context.dataStore.edit { it[Keys.SFX_VOLUME] = clamped }
        } else {
            inMemoryState.value = inMemoryState.value.copy(sfxVolume = clamped)
        }
    }

    suspend fun updateVibration(enabled: Boolean) {
        if (context != null) {
            context.dataStore.edit { it[Keys.VIBRATION] = enabled }
        } else {
            inMemoryState.value = inMemoryState.value.copy(vibrationEnabled = enabled)
        }
    }

    suspend fun updateHighBloom(enabled: Boolean) {
        if (context != null) {
            context.dataStore.edit { it[Keys.HIGH_BLOOM] = enabled }
        } else {
            inMemoryState.value = inMemoryState.value.copy(highBloomEnabled = enabled)
        }
    }
}
