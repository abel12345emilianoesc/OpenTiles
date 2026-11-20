package com.example.audio

import com.example.domain.SfxProfile
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * Especificación de pista musical original integrada en Neon Resonance.
 */
data class BuiltInSongSpec(
    val id: String,
    val title: String,
    val artist: String,
    val bpm: Int,
    val durationMs: Long,
    val genreTag: String,
    val primaryColorHex: Long,
    val secondaryColorHex: Long,
    val scaleNotesHz: FloatArray,
    val bassNotesHz: FloatArray,
    val melodyPattern: IntArray
)

/**
 * Sintetizador de audio PCM/WAV sin dependencias externas para:
 * 1) Generar las 4 canciones originales incluidas en el juego (con bajo, arpegio, melodía, notas sostenidas y batería)
 *    para que suenen con claridad y sean analizadas por el motor DSP real.
 * 2) Generar los efectos de sonido (SFX) por carril y perfil de la tienda para `SoundPool`.
 */
object SynthesizedAudioEngine {

    private const val SAMPLE_RATE = 22050

    val builtInSongs = listOf(
        BuiltInSongSpec(
            id = "builtin_quartz_horizon",
            title = "Horizonte de Cuarzo",
            artist = "Aether Synth",
            bpm = 120,
            durationMs = 36_000L,
            genreTag = "Synthwave Cristalino",
            primaryColorHex = 0xFF06B6D4,
            secondaryColorHex = 0xFF8B5CF6,
            scaleNotesHz = floatArrayOf(261.63f, 293.66f, 329.63f, 392.00f, 440.00f, 523.25f, 587.33f, 659.25f),
            bassNotesHz = floatArrayOf(130.81f, 98.00f, 110.00f, 87.31f),
            melodyPattern = intArrayOf(0, 2, 4, 5, 4, 2, 3, 1, 2, 4, 7, 6, 5, 4, 2, 0)
        ),
        BuiltInSongSpec(
            id = "builtin_cyber_pulse",
            title = "Pulso Cibernético",
            artist = "Kinetik Void",
            bpm = 136,
            durationMs = 38_000L,
            genreTag = "Electro Darksynth",
            primaryColorHex = 0xFFF43F5E,
            secondaryColorHex = 0xFF8B5CF6,
            scaleNotesHz = floatArrayOf(293.66f, 329.63f, 349.23f, 440.00f, 466.16f, 587.33f, 659.25f, 698.46f),
            bassNotesHz = floatArrayOf(146.83f, 116.54f, 130.81f, 110.00f),
            melodyPattern = intArrayOf(0, 3, 5, 4, 3, 1, 2, 0, 3, 5, 7, 6, 5, 3, 4, 2)
        ),
        BuiltInSongSpec(
            id = "builtin_violet_nebula",
            title = "Nebulosa Violeta",
            artist = "Lumina Ensemble",
            bpm = 108,
            durationMs = 36_000L,
            genreTag = "Piano & Chillstep",
            primaryColorHex = 0xFFD0BCFF,
            secondaryColorHex = 0xFF06B6D4,
            scaleNotesHz = floatArrayOf(220.00f, 246.94f, 261.63f, 329.63f, 349.23f, 440.00f, 493.88f, 523.25f),
            bassNotesHz = floatArrayOf(110.00f, 87.31f, 130.81f, 98.00f),
            melodyPattern = intArrayOf(5, 4, 2, 0, 2, 4, 5, 7, 6, 4, 2, 1, 2, 4, 3, 0)
        ),
        BuiltInSongSpec(
            id = "builtin_abyssal_resonance",
            title = "Resonancia Abisal",
            artist = "Hypernova Core",
            bpm = 148,
            durationMs = 40_000L,
            genreTag = "Artcore & Trance",
            primaryColorHex = 0xFF10B981,
            secondaryColorHex = 0xFF06B6D4,
            scaleNotesHz = floatArrayOf(329.63f, 369.99f, 392.00f, 493.88f, 523.25f, 659.25f, 739.99f, 783.99f),
            bassNotesHz = floatArrayOf(164.81f, 130.81f, 146.83f, 123.47f),
            melodyPattern = intArrayOf(0, 4, 3, 5, 2, 6, 5, 7, 4, 3, 2, 5, 4, 2, 1, 0)
        )
    )

    /**
     * Genera la señal PCM normalizada (-1f..1f) de una canción integrada.
     */
    fun generateSongSamples(spec: BuiltInSongSpec, sampleRate: Int = SAMPLE_RATE): FloatArray {
        val totalSamples = ((spec.durationMs * sampleRate) / 1000L).toInt()
        val samples = FloatArray(totalSamples)
        val beatDurationSec = 60.0 / spec.bpm.toDouble()
        val sixteenthDurationSec = beatDurationSec / 4.0
        val twoPi = 2.0 * PI

        for (i in 0 until totalSamples) {
            val t = i.toDouble() / sampleRate.toDouble()
            val beatIndex = (t / beatDurationSec).toInt()
            val beatTime = t - (beatIndex * beatDurationSec)

            val sixteenthIndex = (t / sixteenthDurationSec).toInt()
            val sixteenthTime = t - (sixteenthIndex * sixteenthDurationSec)

            val barIndex = beatIndex / 4
            val bassFreq = spec.bassNotesHz[barIndex % spec.bassNotesHz.size]

            // 1. Bombo rítmico (Kick) en cada pulso principal (Banda 0)
            val kickEnv = exp(-beatTime * 18.0)
            val kickFreq = 52.0 + 110.0 * exp(-beatTime * 35.0)
            val kick = sin(twoPi * kickFreq * beatTime) * kickEnv * 0.45

            // 2. Bajo electrónico pulsante en octavas (Banda 0 y 1)
            val bassEnv = exp(- (t % (beatDurationSec / 2.0)) * 6.5)
            val bass = (sin(twoPi * bassFreq * t) + 0.4 * sin(twoPi * bassFreq * 2.0 * t)) * bassEnv * 0.28

            // 3. Melodía y notas sostenidas (Banda 2 y 3)
            val patternStep = (sixteenthIndex / 2) % spec.melodyPattern.size
            val melodyNoteIdx = spec.melodyPattern[patternStep]
            val melodyFreq = spec.scaleNotesHz[melodyNoteIdx % spec.scaleNotesHz.size]

            // Cada 8 pasos creamos una nota melódica sostenida (Hold) con vibrato suave
            val isSustainPhrase = (sixteenthIndex % 16) in 0..5
            val noteLocalTime = if (isSustainPhrase) {
                t - ((sixteenthIndex / 16) * 16 * sixteenthDurationSec)
            } else {
                t - ((sixteenthIndex / 2) * 2 * sixteenthDurationSec)
            }
            val melodyDecayRate = if (isSustainPhrase) 1.4 else 7.5
            val melodyEnv = exp(-noteLocalTime * melodyDecayRate)
            val melodyWave = (
                sin(twoPi * melodyFreq * t) +
                    0.5 * sin(twoPi * melodyFreq * 2.0 * t) +
                    0.25 * sin(twoPi * melodyFreq * 3.0 * t)
                ) * melodyEnv * 0.36

            // 4. Subdivisión rápida de arpegio/hi-hat en semicorcheas (para dificultades 4 y 5)
            val arpFreq = spec.scaleNotesHz[(sixteenthIndex * 3) % spec.scaleNotesHz.size] * 2.0f
            val arpEnv = exp(-sixteenthTime * 22.0)
            val arp = sin(twoPi * arpFreq * sixteenthTime) * arpEnv * 0.16

            // Fade-in inicial y fade-out final suave
            val durationSec = spec.durationMs / 1000.0
            val masterFade = when {
                t < 0.4 -> (t / 0.4)
                t > durationSec - 1.2 -> ((durationSec - t) / 1.2).coerceAtLeast(0.0)
                else -> 1.0
            }

            val mix = ((kick + bass + melodyWave + arp) * masterFade).toFloat()
            samples[i] = mix.coerceIn(-0.98f, 0.98f)
        }
        return samples
    }

    /**
     * Escribe un archivo WAV mono de 16 bits a disco a partir de muestras normalizadas.
     */
    fun writeMonoWavFile(targetFile: File, samples: FloatArray, sampleRate: Int = SAMPLE_RATE) {
        targetFile.parentFile?.mkdirs()
        val dataSize = samples.size * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(36 + dataSize)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16) // PCM chunk size
        header.putShort(1) // AudioFormat = 1 (PCM)
        header.putShort(1) // NumChannels = 1 (Mono)
        header.putInt(sampleRate)
        header.putInt(sampleRate * 2) // ByteRate
        header.putShort(2) // BlockAlign
        header.putShort(16) // BitsPerSample
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(dataSize)

        BufferedOutputStream(FileOutputStream(targetFile), 16384).use { bos ->
            bos.write(header.array())
            val chunk = ByteArray(4096)
            var byteIdx = 0
            for (sample in samples) {
                val pcm16 = (sample.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                chunk[byteIdx++] = (pcm16.toInt() and 0xFF).toByte()
                chunk[byteIdx++] = ((pcm16.toInt() shr 8) and 0xFF).toByte()
                if (byteIdx >= chunk.size) {
                    bos.write(chunk, 0, byteIdx)
                    byteIdx = 0
                }
            }
            if (byteIdx > 0) {
                bos.write(chunk, 0, byteIdx)
            }
            bos.flush()
        }
    }

    /**
     * Genera un archivo WAV corto de efecto de sonido (SFX) para un perfil y carril (0..3).
     */
    fun writeLaneHitSfxWav(targetFile: File, profile: SfxProfile, lane: Int) {
        val durationMs = 150
        val numSamples = (SAMPLE_RATE * durationMs) / 1000
        val samples = FloatArray(numSamples)
        val baseFreq = profile.baseFreqsHz[lane.coerceIn(0, 3)]
        val twoPi = 2.0 * PI

        for (i in 0 until numSamples) {
            val t = i.toDouble() / SAMPLE_RATE.toDouble()
            val sampleVal = when (profile) {
                SfxProfile.CRISTAL_ETEREO -> {
                    val env = exp(-t * 24.0)
                    (sin(twoPi * baseFreq * t) + 0.6 * sin(twoPi * baseFreq * 2.0 * t) + 0.3 * sin(twoPi * baseFreq * 3.0 * t)) * env * 0.45
                }
                SfxProfile.PIANO_COLA -> {
                    val env = exp(-t * 18.0)
                    val hammer = exp(-t * 95.0) * sin(twoPi * baseFreq * 4.0 * t) * 0.4
                    (sin(twoPi * baseFreq * t) + 0.35 * sin(twoPi * baseFreq * 2.0 * t) + hammer) * env * 0.52
                }
                SfxProfile.MARIMBA_NEON -> {
                    val env = exp(-t * 26.0)
                    val body = sin(twoPi * baseFreq * t) + 0.28 * sin(twoPi * baseFreq * 3.98 * t) * exp(-t * 45.0)
                    body * env * 0.56
                }
                SfxProfile.SINTE_RETRO -> {
                    val env = exp(-t * 20.0)
                    val saw = (sin(twoPi * baseFreq * t) - 0.5 * sin(twoPi * baseFreq * 2.0 * t) + 0.33 * sin(twoPi * baseFreq * 3.0 * t))
                    saw * env * 0.50
                }
                SfxProfile.CAMPANA_ASTRAL -> {
                    val env = exp(-t * 14.0)
                    val bell = sin(twoPi * baseFreq * t) +
                        0.45 * sin(twoPi * baseFreq * 2.76 * t) * exp(-t * 18.0) +
                        0.25 * sin(twoPi * baseFreq * 5.40 * t) * exp(-t * 32.0)
                    bell * env * 0.46
                }
                SfxProfile.PERCUSION_LASER -> {
                    val env = exp(-t * 32.0)
                    val sweepFreq = baseFreq * (1.0 + 1.4 * exp(-t * 45.0))
                    sin(twoPi * sweepFreq * t) * env * 0.55
                }
                SfxProfile.BAJO_SUB_PULSE -> {
                    val env = exp(-t * 16.0)
                    val punch = sin(twoPi * (baseFreq * (1.0 + 0.5 * exp(-t * 40.0))) * t)
                    (punch + 0.35 * sin(twoPi * baseFreq * 2.0 * t)) * env * 0.60
                }
                SfxProfile.CHIPTUNE_8BIT -> {
                    val env = exp(-t * 22.0)
                    val sq = if (sin(twoPi * baseFreq * t) >= 0.0) 1.0 else -1.0
                    val subSq = if (sin(twoPi * baseFreq * 2.0 * t) >= 0.0) 0.35 else -0.35
                    (sq * 0.65 + subSq) * env * 0.42
                }
            }
            samples[i] = sampleVal.toFloat().coerceIn(-0.98f, 0.98f)
        }
        writeMonoWavFile(targetFile, samples, SAMPLE_RATE)
    }

    /**
     * Genera un sonido grave corto de retroalimentación cuando el jugador falla una tecla.
     */
    fun writeMissSfxWav(targetFile: File) {
        val durationMs = 170
        val numSamples = (SAMPLE_RATE * durationMs) / 1000
        val samples = FloatArray(numSamples)
        val twoPi = 2.0 * PI
        for (i in 0 until numSamples) {
            val t = i.toDouble() / SAMPLE_RATE.toDouble()
            val env = exp(-t * 19.0)
            val wave = (sin(twoPi * 115.0 * t) + 0.7 * sin(twoPi * 123.0 * t)) * env * 0.45
            samples[i] = wave.toFloat().coerceIn(-0.95f, 0.95f)
        }
        writeMonoWavFile(targetFile, samples, SAMPLE_RATE)
    }

    /**
     * Genera un clic percusivo ultra-corto y nítido de metrónomo para la calibración interactiva de latencia.
     * `isAccent = true` produce un pulso agudo en el tiempo 1 del compás (1500 Hz); los demás pulsos suenan a 980 Hz.
     */
    fun writeMetronomeClickWav(targetFile: File, isAccent: Boolean) {
        val durationMs = 55
        val numSamples = (SAMPLE_RATE * durationMs) / 1000
        val samples = FloatArray(numSamples)
        val baseFreq = if (isAccent) 1520.0 else 980.0
        val twoPi = 2.0 * PI
        for (i in 0 until numSamples) {
            val t = i.toDouble() / SAMPLE_RATE.toDouble()
            // Ataque instantáneo (1 ms) + decaimiento seco para percepción exacta del pulso
            val attack = (t / 0.001).coerceIn(0.0, 1.0)
            val env = attack * exp(-t * 95.0)
            val clickTransient = exp(-t * 320.0) * sin(twoPi * baseFreq * 2.5 * t) * 0.35
            val body = sin(twoPi * baseFreq * t) * 0.75 + clickTransient
            samples[i] = (body * env).toFloat().coerceIn(-0.96f, 0.96f)
        }
        writeMonoWavFile(targetFile, samples, SAMPLE_RATE)
    }
}
