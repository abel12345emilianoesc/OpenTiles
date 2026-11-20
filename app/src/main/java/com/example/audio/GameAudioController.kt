package com.example.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.example.domain.SfxProfile
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Controlador de audio en tiempo real durante la partida, vista previa de canciones y tienda.
 * - Inicializa SoundPool en segundo plano y rastrea muestras cargadas para evitar errores de log y lag inicial.
 * - Protege todas las llamadas de MediaPlayer con bandera `isPrepared` para evitar errores de estado en Logcat.
 * - Reproduce SFX de latencia ultrabaja y retroalimentación háptica precisa.
 */
class GameAudioController(private val context: Context?) {

    private val bgExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "CustomTile-AudioInit").apply { isDaemon = true }
    }

    @Volatile
    private var mediaPlayer: MediaPlayer? = null

    @Volatile
    private var isPrepared: Boolean = false

    @Volatile
    private var isReleased: Boolean = false

    @Volatile
    private var soundPool: SoundPool? = null

    private val sfxSoundIds = ConcurrentHashMap<String, Int>()
    private val loadedSoundIds: MutableSet<Int> = Collections.newSetFromMap(ConcurrentHashMap())

    @Volatile
    private var missSoundId: Int = 0

    @Volatile
    private var metronomeAccentId: Int = 0

    @Volatile
    private var metronomeBeatId: Int = 0

    @Volatile
    private var musicVolume: Float = 0.90f

    @Volatile
    private var sfxVolume: Float = 0.85f

    @Volatile
    private var vibrationEnabled: Boolean = true

    @Volatile
    private var isDucked: Boolean = false

    private val vibrator: Vibrator? by lazy {
        if (context == null) null
        else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vm?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    init {
        if (context != null) {
            val appCtx = context.applicationContext ?: context
            bgExecutor.execute {
                if (!isReleased) {
                    initSoundPool(appCtx)
                }
            }
        }
    }

    private fun initSoundPool(ctx: Context) {
        try {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val pool = SoundPool.Builder()
                .setMaxStreams(8)
                .setAudioAttributes(attrs)
                .build()

            pool.setOnLoadCompleteListener { _, sampleId, status ->
                if (status == 0) {
                    loadedSoundIds.add(sampleId)
                }
            }
            if (isReleased) {
                pool.release()
                return
            }
            soundPool = pool

            val sfxDir = File(ctx.filesDir, "sfx_cache")
            sfxDir.mkdirs()

            for (profile in SfxProfile.entries) {
                if (isReleased) return
                for (lane in 0..3) {
                    val key = "${profile.id}_$lane"
                    val file = File(sfxDir, "$key.wav")
                    if (!file.exists() || file.length() == 0L) {
                        SynthesizedAudioEngine.writeLaneHitSfxWav(file, profile, lane)
                    }
                    sfxSoundIds[key] = pool.load(file.absolutePath, 1)
                }
            }

            val missFile = File(sfxDir, "miss_sfx.wav")
            if (!missFile.exists() || missFile.length() == 0L) {
                SynthesizedAudioEngine.writeMissSfxWav(missFile)
            }
            missSoundId = pool.load(missFile.absolutePath, 1)

            val metroAccentFile = File(sfxDir, "metro_accent.wav")
            if (!metroAccentFile.exists() || metroAccentFile.length() == 0L) {
                SynthesizedAudioEngine.writeMetronomeClickWav(metroAccentFile, isAccent = true)
            }
            metronomeAccentId = pool.load(metroAccentFile.absolutePath, 1)

            val metroBeatFile = File(sfxDir, "metro_beat.wav")
            if (!metroBeatFile.exists() || metroBeatFile.length() == 0L) {
                SynthesizedAudioEngine.writeMetronomeClickWav(metroBeatFile, isAccent = false)
            }
            metronomeBeatId = pool.load(metroBeatFile.absolutePath, 1)
        } catch (_: Exception) {
        }
    }

    fun updateSettings(musicVol: Float, sfxVol: Float, vibrate: Boolean) {
        musicVolume = musicVol.coerceIn(0f, 1f)
        sfxVolume = sfxVol.coerceIn(0f, 1f)
        vibrationEnabled = vibrate
        applyCurrentMusicVolume()
    }

    fun prepareAndStartSong(audioFilePath: String, startPaused: Boolean = false): Boolean {
        stopSong()
        if (context == null || audioFilePath.isBlank() || isReleased) return false
        val file = File(audioFilePath)
        if (!file.exists() || file.length() < 64L) return false

        var mp: MediaPlayer? = null
        return try {
            mp = MediaPlayer()
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            mp.setDataSource(file.absolutePath)
            mp.isLooping = false
            mp.setOnErrorListener { _, _, _ -> true }
            mp.prepare()
            isDucked = false
            isPrepared = true
            mp.setVolume(musicVolume, musicVolume)
            if (!startPaused) {
                mp.start()
            }
            mediaPlayer = mp
            true
        } catch (_: Exception) {
            isPrepared = false
            try {
                mp?.release()
            } catch (_: Exception) {
            }
            false
        }
    }

    fun seekToMs(positionMs: Long) {
        if (!isPrepared) return
        try {
            mediaPlayer?.let { mp ->
                val target = positionMs.coerceAtLeast(0L).toInt()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    mp.seekTo(target.toLong(), MediaPlayer.SEEK_CLOSEST)
                } else {
                    mp.seekTo(target)
                }
            }
        } catch (_: Exception) {
        }
    }

    /**
     * Evita vaciar el búfer del decodificador si MediaPlayer ya está estacionado
     * dentro de la tolerancia al reanudar tras una pausa.
     */
    fun seekToMsIfNeeded(positionMs: Long, toleranceMs: Long = 45L) {
        if (!isPrepared) return
        try {
            val mp = mediaPlayer ?: return
            val target = positionMs.coerceAtLeast(0L)
            val current = mp.currentPosition.toLong()
            if (kotlin.math.abs(current - target) > toleranceMs) {
                seekToMs(target)
            }
        } catch (_: Exception) {
        }
    }

    fun startPlaybackIfReady() {
        if (!isPrepared) return
        try {
            mediaPlayer?.let {
                if (!it.isPlaying) {
                    it.start()
                }
            }
        } catch (_: Exception) {
        }
    }

    fun pauseSong() {
        if (!isPrepared) return
        try {
            mediaPlayer?.let {
                if (it.isPlaying) {
                    it.pause()
                }
            }
        } catch (_: Exception) {
        }
    }

    fun resumeSong() {
        if (!isPrepared) return
        try {
            mediaPlayer?.let {
                if (!it.isPlaying) {
                    it.start()
                }
            }
        } catch (_: Exception) {
        }
    }

    fun stopSong() {
        val current = mediaPlayer
        mediaPlayer = null
        val wasPrepared = isPrepared
        isPrepared = false
        if (current != null) {
            try {
                if (wasPrepared && current.isPlaying) {
                    current.stop()
                }
            } catch (_: Exception) {
            }
            try {
                current.release()
            } catch (_: Exception) {
            }
        }
    }

    fun getCurrentPositionMs(): Long? {
        if (!isPrepared) return null
        return try {
            val mp = mediaPlayer ?: return null
            val fallbackMs = mp.currentPosition.toLong()
            if (mp.isPlaying && fallbackMs > 0L) {
                val ts = mp.timestamp
                if (ts != null && ts.mediaClockRate > 0f && ts.anchorMediaTimeUs > 0L) {
                    val elapsedNs = System.nanoTime() - ts.anchorSytemNanoTime
                    if (elapsedNs in 0L..250_000_000L) {
                        val interpolatedMs = (ts.anchorMediaTimeUs / 1000L) +
                            ((elapsedNs / 1_000_000.0) * ts.mediaClockRate.toDouble()).toLong()
                        if (kotlin.math.abs(interpolatedMs - fallbackMs) <= 80L) {
                            return interpolatedMs.coerceAtLeast(0L)
                        }
                    }
                }
            }
            fallbackMs
        } catch (_: Exception) {
            null
        }
    }

    fun isPlaying(): Boolean {
        if (!isPrepared) return false
        return try {
            mediaPlayer?.isPlaying == true
        } catch (_: Exception) {
            false
        }
    }

    fun playLaneHitSfx(profile: SfxProfile, lane: Int, isFever: Boolean = false) {
        if (isDucked) {
            isDucked = false
            applyCurrentMusicVolume()
        }
        val key = "${profile.id}_${lane.coerceIn(0, 3)}"
        val soundId = sfxSoundIds[key] ?: 0
        if (soundId != 0 && soundId in loadedSoundIds && sfxVolume > 0.01f) {
            val rate = if (isFever) 1.04f else 1.0f
            soundPool?.play(soundId, sfxVolume, sfxVolume, 1, 0, rate)
        }
        triggerHaptic(isMiss = false)
    }

    fun playMetronomeClick(isAccent: Boolean) {
        val soundId = if (isAccent) metronomeAccentId else metronomeBeatId
        if (soundId != 0 && soundId in loadedSoundIds) {
            val vol = sfxVolume.coerceAtLeast(0.55f)
            soundPool?.play(soundId, vol, vol, 1, 0, 1.0f)
        }
    }

    fun playMissFeedback() {
        isDucked = true
        applyCurrentMusicVolume()
        val id = missSoundId
        if (id != 0 && id in loadedSoundIds && sfxVolume > 0.01f) {
            soundPool?.play(id, sfxVolume * 0.75f, sfxVolume * 0.75f, 1, 0, 1.0f)
        }
        triggerHaptic(isMiss = true)
    }

    private fun applyCurrentMusicVolume() {
        if (!isPrepared) return
        try {
            val effective = if (isDucked) musicVolume * 0.42f else musicVolume
            mediaPlayer?.setVolume(effective, effective)
        } catch (_: Exception) {
        }
    }

    private fun triggerHaptic(isMiss: Boolean) {
        if (!vibrationEnabled) return
        try {
            val vib = vibrator ?: return
            if (!vib.hasVibrator()) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val duration = if (isMiss) 38L else 12L
                val amplitude = if (isMiss) 180 else 90
                vib.vibrate(VibrationEffect.createOneShot(duration, amplitude))
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(if (isMiss) 35L else 10L)
            }
        } catch (_: Exception) {
        }
    }

    fun release() {
        isReleased = true
        stopSong()
        try {
            soundPool?.release()
        } catch (_: Exception) {
        } finally {
            soundPool = null
            loadedSoundIds.clear()
        }
        try {
            bgExecutor.shutdownNow()
        } catch (_: Exception) {
        }
    }
}
