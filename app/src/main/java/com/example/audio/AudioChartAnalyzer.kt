package com.example.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.example.domain.DifficultyLevel
import com.example.domain.NoteKind
import com.example.domain.RhythmNote
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

data class BeatPhaseSegment(
    val centerTimeMs: Long,
    val beatOffsetMs: Long,
    val confidence: Float = 1f
)

data class HpssResult(
    val harmonicSpec: Array<FloatArray>,
    val percussiveSpec: Array<FloatArray>
)

data class AnalyzedSongResult(
    val durationMs: Long,
    val estimatedBpm: Int,
    val chartsByDifficulty: Map<DifficultyLevel, List<RhythmNote>>,
    val beatOffsetMs: Long = 0L,
    val beatSegments: List<BeatPhaseSegment> = emptyList()
)

/**
 * Candidato acústico detectado por el motor DSP sobre la señal de audio real.
 */
data class OnsetCandidate(
    val timeMs: Long,
    val strength: Float,           // 0f..1f normalizado respecto a la dinámica de la canción
    val dominantBand: Int,         // 0: Grave, 1: Medio-Grave, 2: Melodía Media, 3: Agudo/Brillo
    val secondaryBand: Int?,       // Presente si hay acorde armónico fuerte simultáneo
    val sustainDurationMs: Long,
    val percussiveStrength: Float = strength,
    val melodicStrength: Float = strength,
    val isPercussiveAccent: Boolean = false,
    val spectralCentroidHz: Float = 0f
)

/**
 * Motor DSP de alta precisión rítmica en el dispositivo:
 * - Decodifica MP3, M4A, WAV y OGG con filtro boxcar anti-aliasing y compensación de timestamp inicial.
 * - Aplica STFT centrada (ventana Hann 1024, hop 256) y separación ligera HPSS (Harmonic-Percussive Source Separation)
 *   sobre el espectrograma logarítmico: mediana horizontal en tiempo (componente armónico) y vertical en frecuencia
 *   (componente percusivo) con máscaras suaves de Wiener.
 * - Usa flujo percusivo para ataques TAP/BEAT_PULSE y envolvente armónica para medir sustainDurationMs real en HOLD
 *   sin que golpes de batería corten notas sostenidas.
 * - Aplica Beat Tracking segmentado (ventanas solapadas de 12 s con interpolación circular de fase),
 *   asignación tonal por contorno espectral relativo y puerta de ruido RMS dinámica.
 */
object AudioChartAnalyzer {

    private const val ANALYSIS_SAMPLE_RATE = 22050
    private const val LOG_COMPRESSION_C = 100.0f

    fun analyzeAudioFile(
        audioFile: File,
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ): AnalyzedSongResult {
        onProgress(0.10f, "Decodificando flujo de audio...")
        val decoded = decodeAudioFileToMonoPcm(audioFile) { decodeProgress ->
            onProgress(0.10f + decodeProgress * 0.45f, "Decodificando muestras acústicas...")
        }
        onProgress(0.60f, "Analizando espectro Log-Flux y cuadrícula rítmica...")
        return analyzePcmSamples(
            samples = decoded.samples,
            sampleRate = decoded.sampleRate,
            durationMs = decoded.durationMs,
            startOffsetMs = decoded.startOffsetMs,
            onProgress = onProgress
        )
    }

    /**
     * Calcula el instante exacto en ms de un fotograma usando aritmética en coma flotante
     * para evitar drift acumulado en frecuencias como 44.1 kHz / 22.05 kHz.
     */
    fun frameIndexToTimeMs(
        frameIndex: Int,
        hopSize: Int,
        sampleRate: Int,
        startOffsetMs: Long = 0L
    ): Long {
        if (sampleRate <= 0) return startOffsetMs
        return startOffsetMs + ((frameIndex.toLong() * hopSize.toLong()).toDouble() * 1000.0 / sampleRate.toDouble()).roundToLong()
    }

    /**
     * Cuantización suave hacia la subdivisión válida del beat más cercana si el error cae dentro de ±maxSnapMs.
     */
    fun softQuantizeToBeatGrid(
        timeMs: Long,
        beatOffsetMs: Long,
        beatPeriodMs: Double,
        subdivisionsPerBeat: Int,
        maxSnapMs: Double = 25.0,
        pullStrength: Double = 0.80
    ): Long {
        if (beatPeriodMs <= 0.0 || subdivisionsPerBeat <= 0) return timeMs
        val subBeatMs = beatPeriodMs / subdivisionsPerBeat.toDouble()
        val k = round((timeMs.toDouble() - beatOffsetMs.toDouble()) / subBeatMs)
        val gridMs = beatOffsetMs.toDouble() + k * subBeatMs
        val err = gridMs - timeMs.toDouble()
        return if (abs(err) <= maxSnapMs) {
            (timeMs.toDouble() + err * pullStrength).roundToLong().coerceAtLeast(0L)
        } else {
            timeMs
        }
    }

    fun analyzePcmSamples(
        samples: FloatArray,
        sampleRate: Int,
        durationMs: Long = ((samples.size.toDouble() * 1000.0) / max(1, sampleRate).toDouble()).roundToLong(),
        startOffsetMs: Long = 0L,
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ): AnalyzedSongResult {
        val safeDurationMs = max(3000L, durationMs)
        if (samples.isEmpty() || sampleRate <= 0) {
            return createFallbackResult(safeDurationMs, 120)
        }

        val windowSize = if (sampleRate >= 16000) 1024 else 512
        val hopSize = windowSize / 4 // 256 muestras a 22,050 Hz (~11.61 ms por hop)
        if (samples.size < windowSize + hopSize * 8) {
            return createFallbackResult(safeDurationMs, 120)
        }

        val halfWindow = windowSize / 2
        val frameCount = (samples.size / hopSize) + 1
        val hopDurationMs = (hopSize.toDouble() * 1000.0) / sampleRate.toDouble()
        val halfBins = windowSize / 2

        // Precalcular ventana Hann y tablas FFT
        val hann = FloatArray(windowSize) { i ->
            (0.5 - 0.5 * cos(2.0 * PI * i / (windowSize - 1))).toFloat()
        }
        val fft = Radix2Fft(windowSize)
        val re = FloatArray(windowSize)
        val im = FloatArray(windowSize)

        // Construir banco compacto (~90 bins) sobre 40 Hz..8000 Hz para HPSS ligero en tiempo y memoria
        val hzPerBin = sampleRate.toFloat() / windowSize.toFloat()
        val binSpecs = buildCompactBinSpecs(halfBins, hzPerBin)
        val compactBins = binSpecs.size
        val compactBinFreqHz = FloatArray(compactBins) { binSpecs[it].centerHz }
        val compactBinLogFreq = FloatArray(compactBins) { ln(binSpecs[it].centerHz.coerceAtLeast(20f)) }
        val compactBinBand = IntArray(compactBins) { binSpecs[it].band }

        val logSpec = Array(frameCount) { FloatArray(compactBins) }
        val bandEnergies = Array(frameCount) { FloatArray(4) }
        val totalRms = FloatArray(frameCount)
        val invWindow = 1.0f / windowSize.toFloat()

        // STFT centrada en `frameIdx * hopSize` para eliminar el adelanto sistemático de media ventana
        for (frameIdx in 0 until frameCount) {
            val windowStart = (frameIdx * hopSize) - halfWindow
            var sumSq = 0f
            for (i in 0 until windowSize) {
                val sampleIdx = windowStart + i
                val x = if (sampleIdx in samples.indices) samples[sampleIdx] else 0f
                sumSq += x * x
                re[i] = x * hann[i]
                im[i] = 0f
            }
            totalRms[frameIdx] = sqrt(sumSq * invWindow)

            fft.transform(re, im)

            var e0 = 0f
            var e1 = 0f
            var e2 = 0f
            var e3 = 0f
            val frameLog = logSpec[frameIdx]

            for (cb in 0 until compactBins) {
                val spec = binSpecs[cb]
                var sumMagSq = 0f
                var count = 0
                for (bin in spec.startBin..spec.endBin) {
                    val magSq = (re[bin] * re[bin] + im[bin] * im[bin]) * invWindow
                    sumMagSq += magSq
                    count++
                }
                val meanMagSq = if (count > 0) sumMagSq / count.toFloat() else 0f
                frameLog[cb] = ln(1.0f + LOG_COMPRESSION_C * meanMagSq)

                when (spec.band) {
                    0 -> e0 += sumMagSq * 1.15f
                    1 -> e1 += sumMagSq * 1.65f
                    2 -> e2 += sumMagSq * 2.35f
                    3 -> e3 += sumMagSq * 2.85f
                }
            }

            bandEnergies[frameIdx][0] = sqrt(e0)
            bandEnergies[frameIdx][1] = sqrt(e1)
            bandEnergies[frameIdx][2] = sqrt(e2)
            bandEnergies[frameIdx][3] = sqrt(e3)
        }

        // Separación ligero HPSS (Harmonic-Percussive Source Separation) sobre el espectrograma logarítmico
        onProgress(0.68f, "Separando armónicos y percusión (HPSS)...")
        val hpss = computeHpss(logSpec, timeRadius = 4, freqRadius = 4)
        val harmSpec = hpss.harmonicSpec
        val percSpec = hpss.percussiveSpec

        // Extraer flujos percusivo/armónico, envolventes armónicas por banda y centroide espectral armónico
        val rawPercussiveFlux = FloatArray(frameCount)
        val rawHarmonicFlux = FloatArray(frameCount)
        val harmonicEnv = FloatArray(frameCount)
        val percussiveEnv = FloatArray(frameCount)
        val harmonicBandEnv = Array(frameCount) { FloatArray(4) }
        val harmonicCentroidHz = FloatArray(frameCount)

        for (t in 0 until frameCount) {
            val hFrame = harmSpec[t]
            val pFrame = percSpec[t]
            val hPrev = if (t > 0) harmSpec[t - 1] else null
            val pPrev = if (t > 0) percSpec[t - 1] else null

            var percFlux = 0f
            var harmFlux = 0f
            var hTotal = 0f
            var pTotal = 0f
            var h0 = 0f
            var h1 = 0f
            var h2 = 0f
            var h3 = 0f
            var centroidWeightSum = 0f
            var centroidLogSum = 0f

            for (cb in 0 until compactBins) {
                val hVal = hFrame[cb]
                val pVal = pFrame[cb]
                val band = compactBinBand[cb]

                hTotal += hVal
                pTotal += pVal

                when (band) {
                    0 -> h0 += hVal
                    1 -> h1 += hVal
                    2 -> h2 += hVal
                    3 -> h3 += hVal
                }

                // Centroide espectral geométrico (log-Hz) calculado sobre la componente armónica
                if (hVal > 0.02f) {
                    val w = hVal * hVal
                    centroidWeightSum += w
                    centroidLogSum += w * compactBinLogFreq[cb]
                }

                if (hPrev != null && pPrev != null) {
                    val pDiff = max(0f, pVal - pPrev[cb])
                    val hDiff = max(0f, hVal - hPrev[cb])
                    when (band) {
                        0 -> {
                            percFlux += pDiff * 1.45f
                            harmFlux += hDiff * 0.45f
                        }
                        1 -> {
                            percFlux += pDiff * 1.15f
                            harmFlux += hDiff * 0.95f
                        }
                        2 -> {
                            percFlux += pDiff * 0.70f
                            harmFlux += hDiff * 1.35f
                        }
                        3 -> {
                            percFlux += pDiff * 0.55f
                            harmFlux += hDiff * 0.80f
                        }
                    }
                }
            }

            rawPercussiveFlux[t] = percFlux
            rawHarmonicFlux[t] = harmFlux
            harmonicEnv[t] = hTotal
            percussiveEnv[t] = pTotal
            harmonicBandEnv[t][0] = h0
            harmonicBandEnv[t][1] = h1
            harmonicBandEnv[t][2] = h2
            harmonicBandEnv[t][3] = h3
            harmonicCentroidHz[t] = if (centroidWeightSum > 0.001f) {
                exp(centroidLogSum / centroidWeightSum).coerceIn(40f, 8000f)
            } else {
                0f
            }
        }

        // Normalizar independientemente los flujos HPSS percusivo y armónico
        val percNorm = normalizeFluxProfile(rawPercussiveFlux)
        val harmNorm = normalizeFluxProfile(rawHarmonicFlux)
        val combinedFlux = FloatArray(frameCount) { i ->
            (percNorm[i] * 0.58f + harmNorm[i] * 0.42f)
        }
        val beatTrackFlux = FloatArray(frameCount) { i ->
            (percNorm[i] * 0.78f + combinedFlux[i] * 0.22f)
        }

        // Puerta de ruido RMS dinámica (bloquea intros silenciosas, pausas suaves y ruido MP3)
        val rmsNoiseGate = computeDynamicRmsNoiseGate(totalRms)

        onProgress(0.76f, "Estimando BPM y seguimiento de fase por segmentos...")
        val exactBpm = estimateExactBpm(beatTrackFlux, hopDurationMs)
        val estimatedBpm = exactBpm.roundToInt().coerceIn(65, 180)
        val beatPeriodMs = 60_000.0 / exactBpm
        val beatOffsetMs = estimateBeatOffsetMs(
            flux = beatTrackFlux,
            hopDurationMs = hopDurationMs,
            beatPeriodMs = beatPeriodMs,
            startOffsetMs = startOffsetMs
        )
        val beatSegments = estimateSegmentedBeatPhases(
            flux = beatTrackFlux,
            hopDurationMs = hopDurationMs,
            beatPeriodMs = beatPeriodMs,
            startOffsetMs = startOffsetMs,
            globalBeatOffsetMs = beatOffsetMs
        )

        // Referencia armónica activa para distinguir notas realmente sostenidas de golpes secos
        val activeHarmValues = harmonicEnv.indices
            .filter { totalRms[it] >= rmsNoiseGate && harmonicEnv[it] > 0.01f }
            .map { harmonicEnv[it] }
            .sorted()
        val harmonicFloor = if (activeHarmValues.isNotEmpty()) {
            val medianH = activeHarmValues[activeHarmValues.size / 2]
            max(0.15f, medianH * 0.22f)
        } else {
            0.15f
        }

        // Peak Picking con umbral adaptativo y puerta de ruido RMS dinámica
        val localRadius = max(6, (140.0 / hopDurationMs).roundToInt())
        val rawPeaks = ArrayList<Int>()
        val peakScores = HashMap<Int, Float>()
        var maxPeakScore = 0.0001f

        for (i in 2 until frameCount - 2) {
            val localRms = max(totalRms[i], totalRms[i + 1])
            if (localRms < rmsNoiseGate) continue

            val c = combinedFlux[i]
            val p = percNorm[i]
            val m = harmNorm[i]

            val isCombMax = c >= combinedFlux[i - 1] && c > combinedFlux[i + 1] && c >= combinedFlux[i - 2] && c >= combinedFlux[i + 2]
            val isPercMax = p >= percNorm[i - 1] && p > percNorm[i + 1] && p > 0.14f
            val isHarmMax = m >= harmNorm[i - 1] && m > harmNorm[i + 1] && m > 0.15f

            if (!isCombMax && !isPercMax && !isHarmMax) continue

            val startW = max(0, i - localRadius)
            val endW = min(frameCount - 1, i + localRadius)
            var sumC = 0f
            var sumP = 0f
            var sumM = 0f
            for (k in startW..endW) {
                sumC += combinedFlux[k]
                sumP += percNorm[k]
                sumM += harmNorm[k]
            }
            val invCount = 1f / (endW - startW + 1).toFloat()
            val meanC = sumC * invCount
            val meanP = sumP * invCount
            val meanM = sumM * invCount

            val passC = isCombMax && c > meanC * 1.24f + 0.025f
            val passP = isPercMax && p > meanP * 1.30f + 0.03f
            val passM = isHarmMax && m > meanM * 1.30f + 0.03f

            if (passC || passP || passM) {
                val score = max(c, max(p * 0.96f, m * 0.92f))
                rawPeaks.add(i)
                peakScores[i] = score
                if (score > maxPeakScore) maxPeakScore = score
            }
        }

        // Supresión de no-máximos inmediata en micro-ventana (~55 ms) para eliminar dobles disparos del mismo ataque
        val microWindowFrames = max(2, (55.0 / hopDurationMs).roundToInt())
        val dedupedPeaks = nonMaxSuppressFrames(rawPeaks, peakScores, microWindowFrames)

        // Construir candidatos acústicos reales con sustainDurationMs basado en HPSS armónico y centroide tonal
        val rawCandidates = ArrayList<OnsetCandidate>(dedupedPeaks.size)
        val maxSustainFrames = (1300.0 / hopDurationMs).roundToInt()
        val minNewAttackOffsetFrames = max(4, (75.0 / hopDurationMs).roundToInt())
        val fluxLeadCompensationMs = hopDurationMs.roundToLong()

        for (frame in dedupedPeaks) {
            val timeMs = frameIndexToTimeMs(frame, hopSize, sampleRate, startOffsetMs) + fluxLeadCompensationMs
            if (timeMs < 180L || timeMs > safeDurationMs - 320L) continue

            val energies = bandEnergies[frame]
            var bestBand = 0
            var secondBand = 1
            if (energies[1] > energies[0]) {
                bestBand = 1
                secondBand = 0
            }
            for (b in 2..3) {
                if (energies[b] > energies[bestBand]) {
                    secondBand = bestBand
                    bestBand = b
                } else if (energies[b] > energies[secondBand]) {
                    secondBand = b
                }
            }

            val chordRatio = if (energies[bestBand] > 0.0001f) {
                energies[secondBand] / energies[bestBand]
            } else 0f

            // Centroide espectral armónico promedio en el cuerpo inicial de la nota (evita ruido del transitorio exacto)
            var centroidWeightedSum = 0f
            var centroidEnergySum = 0f
            val cEnd = min(frameCount - 1, frame + 3)
            for (cf in frame..cEnd) {
                val cHz = harmonicCentroidHz[cf]
                val hE = harmonicEnv[cf]
                if (cHz > 1f && hE > 0.02f) {
                    centroidWeightedSum += ln(cHz) * hE
                    centroidEnergySum += hE
                }
            }
            val candidateCentroidHz = if (centroidEnergySum > 0.02f) {
                exp(centroidWeightedSum / centroidEnergySum).coerceIn(40f, 8000f)
            } else {
                0f
            }

            // Medir sustainDurationMs exclusivamente sobre la componente ARMÓNICA (HPSS):
            // así un golpe percusivo de tarola/batería en medio de la nota sostenida jamás corta el HOLD.
            val settleEnd = min(frameCount - 1, frame + 3)
            var bestHarmBand = bestBand
            var attackHarmBandLevel = 0f
            var attackHarmTotal = 0f
            for (sf in frame..settleEnd) {
                if (harmonicEnv[sf] > attackHarmTotal) {
                    attackHarmTotal = harmonicEnv[sf]
                }
                for (b in 0..3) {
                    if (harmonicBandEnv[sf][b] > attackHarmBandLevel) {
                        attackHarmBandLevel = harmonicBandEnv[sf][b]
                        bestHarmBand = b
                    }
                }
            }

            var sustainFrames = 0
            val hasHarmonicBody = attackHarmTotal >= harmonicFloor &&
                (attackHarmTotal >= percussiveEnv[frame] * 0.28f ||
                    (settleEnd > frame && harmonicEnv[settleEnd] >= percussiveEnv[settleEnd] * 0.60f))

            if (hasHarmonicBody) {
                val maxLookahead = min(frameCount - 1, frame + maxSustainFrames)
                var percussiveGraceUsed = 0
                for (f in (frame + 1)..maxLookahead) {
                    if (totalRms[f] < rmsNoiseGate) break

                    val curBandH = harmonicBandEnv[f][bestHarmBand]
                    val curTotalH = harmonicEnv[f]
                    val prevBandH = harmonicBandEnv[f - 1][bestHarmBand]

                    // Solo un nuevo ataque ARMÓNICO fuerte interrumpe el sostenimiento (no un golpe percusivo)
                    val newHarmonicAttack = (f - frame) >= minNewAttackOffsetFrames &&
                        harmNorm[f] > 0.50f &&
                        curBandH > prevBandH * 1.40f &&
                        harmNorm[f] >= percNorm[f] * 0.85f

                    if (newHarmonicAttack) break

                    val bandMaintained = curBandH >= attackHarmBandLevel * 0.36f
                    val totalMaintained = curTotalH >= attackHarmTotal * 0.36f && curTotalH >= harmonicFloor * 0.70f

                    if (bandMaintained || totalMaintained) {
                        sustainFrames++
                        percussiveGraceUsed = 0
                    } else if (percussiveEnv[f] > curTotalH * 1.15f && percussiveGraceUsed < 4) {
                        // Breve ráfaga percusiva (~45 ms) que atenúa momentáneamente la máscara Wiener: no cortar HOLD
                        sustainFrames++
                        percussiveGraceUsed++
                    } else {
                        break
                    }
                }
            }
            val sustainDurationMs = (sustainFrames.toDouble() * hopDurationMs).roundToLong()

            val baseScore = peakScores[frame] ?: combinedFlux[frame]
            val normalizedStrength = (baseScore / maxPeakScore).coerceIn(0.08f, 1.0f)
            val localOffset = localBeatOffsetAt(timeMs, beatSegments, beatOffsetMs, beatPeriodMs)
            val isBeatAligned = distanceToMainBeatMs(timeMs, localOffset, beatPeriodMs) <= 32.0
            val isPercAccent = percNorm[frame] >= 0.42f && (percNorm[frame] >= harmNorm[frame] * 0.85f || isBeatAligned)

            rawCandidates.add(
                OnsetCandidate(
                    timeMs = timeMs,
                    strength = normalizedStrength,
                    dominantBand = bestBand,
                    secondaryBand = if (chordRatio >= 0.62f) secondBand else null,
                    sustainDurationMs = sustainDurationMs,
                    percussiveStrength = percNorm[frame],
                    melodicStrength = harmNorm[frame],
                    isPercussiveAccent = isPercAccent,
                    spectralCentroidHz = candidateCentroidHz
                )
            )
        }

        val energySampler: (Long) -> Float = { queryTimeMs ->
            val relMs = (queryTimeMs - startOffsetMs).toDouble()
            val fIdx = (relMs / hopDurationMs).roundToInt()
            if (fIdx in 0 until frameCount) {
                totalRms[fIdx]
            } else {
                0f
            }
        }

        onProgress(0.90f, "Mapeando 5 dificultades con contorno tonal y fase local...")
        val charts = generateChartsFromCandidates(
            candidates = rawCandidates,
            durationMs = safeDurationMs,
            bpm = estimatedBpm,
            exactBpm = exactBpm,
            beatOffsetMs = beatOffsetMs,
            energyAtTimeMs = energySampler,
            minRmsThreshold = rmsNoiseGate,
            beatSegments = beatSegments
        )
        onProgress(1.0f, "¡Partituras sincronizadas listas!")
        return AnalyzedSongResult(
            durationMs = safeDurationMs,
            estimatedBpm = estimatedBpm,
            chartsByDifficulty = charts,
            beatOffsetMs = beatOffsetMs,
            beatSegments = beatSegments
        )
    }

    /**
     * Genera las 5 dificultades aplicando:
     * - Selección por importancia (NMS por fuerza + alineación a la fase local del beat dentro de `minIntervalMs`).
     * - Cuantización suave (±25 ms) usando la fase local segmentada del beat.
     * - Asignación de carril 0..3 por contorno tonal relativo (centroide espectral armónico).
     * - HOLD y BEAT_PULSE basados estrictamente en HPSS armónico/percusivo real.
     */
    fun generateChartsFromCandidates(
        candidates: List<OnsetCandidate>,
        durationMs: Long,
        bpm: Int,
        exactBpm: Double = bpm.toDouble(),
        beatOffsetMs: Long = 0L,
        energyAtTimeMs: ((Long) -> Float)? = null,
        minRmsThreshold: Float = 0.012f,
        beatSegments: List<BeatPhaseSegment> = emptyList()
    ): Map<DifficultyLevel, List<RhythmNote>> {
        val safeExactBpm = exactBpm.coerceIn(60.0, 190.0)
        val beatPeriodMs = 60_000.0 / safeExactBpm

        val effectiveCandidates = if (candidates.size >= 8) {
            candidates.sortedBy { it.timeMs }
        } else {
            synthesizeBeatCandidates(durationMs, bpm, beatOffsetMs)
        }

        val sortedStrengths = effectiveCandidates.map { it.strength }.sorted()
        val result = LinkedHashMap<DifficultyLevel, List<RhythmNote>>()
        var previousDifficultyCount = 0

        for (difficulty in DifficultyLevel.entries) {
            val subdivisionsPerBeat = when (difficulty.level) {
                1 -> 2 // 1/4 y 1/8
                2, 3 -> 2 // 1/8
                else -> 4 // 1/16
            }

            // Aplicar cuantización suave (±25 ms) a la fase local del beat en cada segmento
            val quantizedCandidates = effectiveCandidates.map { cand ->
                val localOffset = localBeatOffsetAt(cand.timeMs, beatSegments, beatOffsetMs, beatPeriodMs)
                val snappedTime = softQuantizeToBeatGrid(
                    timeMs = cand.timeMs,
                    beatOffsetMs = localOffset,
                    beatPeriodMs = beatPeriodMs,
                    subdivisionsPerBeat = subdivisionsPerBeat,
                    maxSnapMs = 25.0,
                    pullStrength = 0.80
                ).coerceIn(180L, max(180L, durationMs - 300L))
                cand.copy(timeMs = snappedTime)
            }

            val percentileIdx = ((sortedStrengths.size - 1) * difficulty.energyPercentileThreshold)
                .roundToInt()
                .coerceIn(0, max(0, sortedStrengths.size - 1))
            val minStrength = if (sortedStrengths.isNotEmpty()) {
                sortedStrengths[percentileIdx] * 0.85f
            } else 0.1f

            // 1) Peak Picking con supresión de no-máximos por IMPORTANCIA (elige el más fuerte en cada ventana minIntervalMs)
            val eligible = quantizedCandidates.filter { it.strength >= minStrength }
            val selectedCandidates = selectCandidatesByImportance(
                candidates = eligible,
                minIntervalMs = difficulty.minIntervalMs,
                beatOffsetMs = beatOffsetMs,
                beatPeriodMs = beatPeriodMs,
                beatSegments = beatSegments
            ).toMutableList()

            // 2) Rellenar huecos largos (>1500 ms) solo con candidatos REALES existentes de mayor fuerza
            fillLongGapsWithRealCandidates(
                selected = selectedCandidates,
                allCandidates = quantizedCandidates,
                minIntervalMs = difficulty.minIntervalMs,
                maxGapMs = 1500L
            )

            // Ordenar cronológicamente para asignar carriles por contorno tonal, sostenimientos reales y acordes sin solapes
            selectedCandidates.sortBy { it.timeMs }

            val notes = ArrayList<RhythmNote>()
            var activeHoldEndMs = -10_000L
            val lastLaneEndTimes = LongArray(4) { -10_000L }
            var lastLaneUsed = -1
            var lastMelodicCentroidHz = 0f
            var lastNoteTimeMs = -10_000L
            var nextId = 1

            for (idx in selectedCandidates.indices) {
                val candidate = selectedCandidates[idx]
                val timeSinceLast = candidate.timeMs - lastNoteTimeMs

                var primaryLane = assignContourLane(
                    candidate = candidate,
                    prevLane = lastLaneUsed,
                    prevCentroidHz = lastMelodicCentroidHz,
                    timeSinceLastMs = timeSinceLast
                )
                if (candidate.timeMs < lastLaneEndTimes[primaryLane] + 95L) {
                    val freeLane = (0..3).firstOrNull { candidate.timeMs >= lastLaneEndTimes[it] + 95L }
                    if (freeLane != null) {
                        primaryLane = freeLane
                    } else {
                        continue
                    }
                }

                val nextAcceptedTime = if (idx + 1 < selectedCandidates.size) {
                    selectedCandidates[idx + 1].timeMs
                } else {
                    durationMs - 300L
                }
                // Dejar >= 160 ms libres antes de la siguiente nota para que no colisione con el margen de salida (140 ms) del sanitizador
                val gapToNextAccepted = (nextAcceptedTime - candidate.timeMs - 160L).coerceAtLeast(0L)
                val maxRemainingSongMs = (durationMs - 320L - candidate.timeMs).coerceAtLeast(0L)

                val holdBreatherMs = when {
                    difficulty.level <= 2 -> 460L
                    difficulty.level >= 4 -> 480L
                    else -> 340L
                }
                val canStartHoldHere = candidate.timeMs >= activeHoldEndMs + holdBreatherMs &&
                    maxRemainingSongMs >= difficulty.holdMinDurationMs

                // HOLD estrictamente basado en sostenimiento armónico HPSS real
                val maxHoldCapMs = if (difficulty.level >= 4) 850L else 1100L
                val rawSustain = candidate.sustainDurationMs
                val holdDuration = if (canStartHoldHere && rawSustain >= difficulty.holdMinDurationMs) {
                    val maxAllowed = if (difficulty.level <= 2) {
                        min(rawSustain, gapToNextAccepted)
                    } else {
                        min(rawSustain, min(maxHoldCapMs, maxRemainingSongMs))
                    }
                    if (maxAllowed >= difficulty.holdMinDurationMs) {
                        min(maxAllowed, min(maxHoldCapMs, maxRemainingSongMs))
                    } else 0L
                } else {
                    0L
                }

                val wantsChord = difficulty.allowChords &&
                    candidate.strength >= difficulty.chordIntensityThreshold &&
                    candidate.secondaryBand != null &&
                    holdDuration == 0L &&
                    candidate.timeMs >= activeHoldEndMs + 160L

                val localOffset = localBeatOffsetAt(candidate.timeMs, beatSegments, beatOffsetMs, beatPeriodMs)
                val isMainBeatHit = distanceToMainBeatMs(candidate.timeMs, localOffset, beatPeriodMs) <= 32.0
                val isRealBeatPulse = (candidate.isPercussiveAccent && candidate.strength >= 0.68f) ||
                    (isMainBeatHit && candidate.strength >= 0.78f)

                val noteKind = when {
                    holdDuration >= 180L -> NoteKind.HOLD
                    wantsChord -> NoteKind.CHORD
                    isRealBeatPulse -> NoteKind.BEAT_PULSE
                    else -> NoteKind.TAP
                }

                notes.add(
                    RhythmNote(
                        id = nextId++,
                        timeMs = candidate.timeMs,
                        durationMs = holdDuration,
                        lane = primaryLane,
                        isChord = wantsChord,
                        intensity = candidate.strength,
                        frequencyBand = candidate.dominantBand,
                        kind = noteKind
                    )
                )
                lastLaneEndTimes[primaryLane] = candidate.timeMs + holdDuration
                if (holdDuration > 0L) {
                    activeHoldEndMs = candidate.timeMs + holdDuration
                }
                lastLaneUsed = primaryLane
                if (candidate.spectralCentroidHz > 1f) {
                    lastMelodicCentroidHz = candidate.spectralCentroidHz
                }
                lastNoteTimeMs = candidate.timeMs

                if (wantsChord) {
                    // Acorde ergonómico dividido en dos manos: izquierda (0,1) + derecha (2,3)
                    val preferredSecond = candidate.secondaryBand!!.coerceIn(0, 3)
                    val oppositeHandLanes = if (primaryLane <= 1) listOf(2, 3) else listOf(0, 1)
                    val chordLane = when {
                        preferredSecond in oppositeHandLanes && candidate.timeMs >= lastLaneEndTimes[preferredSecond] + 120L -> preferredSecond
                        else -> oppositeHandLanes.firstOrNull { candidate.timeMs >= lastLaneEndTimes[it] + 120L }
                    }
                    if (chordLane != null) {
                        notes.add(
                            RhythmNote(
                                id = nextId++,
                                timeMs = candidate.timeMs,
                                durationMs = 0L,
                                lane = chordLane,
                                isChord = true,
                                intensity = candidate.strength * 0.92f,
                                frequencyBand = chordLane,
                                kind = NoteKind.CHORD
                            )
                        )
                        lastLaneEndTimes[chordLane] = candidate.timeMs
                    }
                }
            }

            // Sanitizar primero para garantizar jugabilidad real a dos pulgares en teléfono
            val sanitizedInitial = sanitizeChartForPhonePlayability(notes, difficulty).toMutableList()

            // Si la dificultad superior empató en cantidad con la anterior, añadir solo onsets reales
            // o subdivisiones de la fase local donde exista energía real por encima de la puerta RMS
            if (sanitizedInitial.size <= previousDifficultyCount) {
                val extraNeeded = (previousDifficultyCount - sanitizedInitial.size) + max(3, difficulty.level * 2)
                supplementNotesForFidelity(
                    notes = sanitizedInitial,
                    candidates = quantizedCandidates,
                    difficulty = difficulty,
                    durationMs = durationMs,
                    beatOffsetMs = beatOffsetMs,
                    beatPeriodMs = beatPeriodMs,
                    extraCount = extraNeeded,
                    energyAtTimeMs = energyAtTimeMs,
                    minRmsThreshold = minRmsThreshold,
                    beatSegments = beatSegments
                )
            }

            var finalNotes = sanitizeChartForPhonePlayability(sanitizedInitial, difficulty)
            if (finalNotes.size <= previousDifficultyCount) {
                val secondPass = finalNotes.toMutableList()
                val stillNeeded = (previousDifficultyCount - finalNotes.size) + max(4, difficulty.level * 2)
                supplementNotesForFidelity(
                    notes = secondPass,
                    candidates = quantizedCandidates,
                    difficulty = difficulty,
                    durationMs = durationMs,
                    beatOffsetMs = beatOffsetMs,
                    beatPeriodMs = beatPeriodMs,
                    extraCount = stillNeeded,
                    energyAtTimeMs = energyAtTimeMs,
                    minRmsThreshold = minRmsThreshold,
                    beatSegments = beatSegments
                )
                finalNotes = sanitizeChartForPhonePlayability(secondPass, difficulty)
            }

            previousDifficultyCount = finalNotes.size
            result[difficulty] = finalNotes
        }

        return result
    }

    /**
     * Validador y sanitizador ergonómico para pantallas táctiles de teléfono (máximo 2 pulgares):
     * 1) Jamás permite 3 o más toques/carriles activos simultáneos (nunca acorde durante una tecla HOLD).
     * 2) Nunca permite dos teclas HOLD superpuestas al mismo tiempo.
     * 3) Mientras hay una tecla HOLD activa en una mano (izq: 0–1, der: 2–3):
     *    - En niveles 1–2 (Suave/Ritmo): no solapa ninguna otra tecla durante el arrastre.
     *    - En niveles 3–5: solo permite toques individuales espaciados en la mano OPUESTA,
     *      respetando márgenes cómodos al inicio (+180 ms) y antes de soltar (-150 ms).
     * 4) Garantiza que los acordes tengan exactamente 2 notas separadas (1 mano izquierda + 1 mano derecha).
     * 5) Evita ráfagas imposibles en el mismo carril (< 220 ms) reubicando a un carril cómodo alterno.
     */
    fun sanitizeChartForPhonePlayability(
        rawNotes: List<RhythmNote>,
        difficulty: DifficultyLevel
    ): List<RhythmNote> {
        if (rawNotes.isEmpty()) return emptyList()

        val sorted = rawNotes.sortedWith(
            compareBy<RhythmNote> { it.timeMs }
                .thenByDescending { if (it.isHold) 1 else 0 }
                .thenByDescending { it.intensity }
                .thenBy { it.lane }
        )

        val accepted = ArrayList<RhythmNote>(sorted.size)
        val lastLaneEndMs = LongArray(4) { -10_000L }
        var activeHold: RhythmNote? = null
        var lastTapDuringHoldMs = -10_000L
        val sameLaneMinGapMs = 220L
        val holdOppositeTapMinGapMs = max(220L, difficulty.minIntervalMs)

        // Agrupar notas casi simultáneas (±35 ms) para controlar estrictamente el límite de 2 pulgares
        var idx = 0
        while (idx < sorted.size) {
            val groupTime = sorted[idx].timeMs
            val group = ArrayList<RhythmNote>(4)
            while (idx < sorted.size && sorted[idx].timeMs - groupTime <= 35L) {
                group.add(sorted[idx])
                idx++
            }

            // Verificar si el HOLD previo ya terminó
            val currentHold = activeHold?.takeIf { groupTime < it.endTimeMs + 140L }
            if (currentHold == null) {
                activeHold = null
            }

            if (currentHold != null) {
                val holdInLeftHand = currentHold.lane <= 1
                val oppositeLanes = if (holdInLeftHand) listOf(2, 3) else listOf(0, 1)

                // En niveles 1–2 o en márgenes de entrada/salida de un HOLD, no insertar toques superpuestos
                val insideComfortableHoldWindow = difficulty.level >= 3 &&
                    groupTime >= currentHold.timeMs + 180L &&
                    groupTime <= currentHold.endTimeMs - 150L &&
                    groupTime - lastTapDuringHoldMs >= holdOppositeTapMinGapMs

                if (insideComfortableHoldWindow) {
                    // Permitir como máximo UNA tecla simple en la mano opuesta (nunca acorde ni otro HOLD)
                    val bestSingle = group.maxByOrNull { it.intensity }
                    if (bestSingle != null) {
                        val chosenLane = oppositeLanes.firstOrNull { lane ->
                            groupTime >= lastLaneEndMs[lane] + sameLaneMinGapMs
                        }
                        if (chosenLane != null) {
                            val cleanNote = bestSingle.copy(
                                timeMs = groupTime,
                                durationMs = 0L,
                                lane = chosenLane,
                                isChord = false,
                                kind = if (bestSingle.kind == NoteKind.BEAT_PULSE) NoteKind.BEAT_PULSE else NoteKind.TAP
                            )
                            accepted.add(cleanNote)
                            lastLaneEndMs[chosenLane] = groupTime
                            lastTapDuringHoldMs = groupTime
                        }
                    }
                }
                continue
            }

            // Sin HOLD activo: evaluar si entra un HOLD o 1 tecla o 1 acorde ergonómico de 2 manos
            val holdCandidate = group.firstOrNull { it.isHold }
            if (holdCandidate != null) {
                val chosenLane = pickComfortableLane(
                    preferred = holdCandidate.lane.coerceIn(0, 3),
                    timeMs = groupTime,
                    lastLaneEndMs = lastLaneEndMs,
                    minSameLaneGapMs = sameLaneMinGapMs
                )
                if (chosenLane != null) {
                    val cleanHold = holdCandidate.copy(
                        timeMs = groupTime,
                        lane = chosenLane,
                        isChord = false,
                        kind = NoteKind.HOLD
                    )
                    accepted.add(cleanHold)
                    lastLaneEndMs[chosenLane] = cleanHold.endTimeMs
                    activeHold = cleanHold
                    lastTapDuringHoldMs = groupTime
                }
                continue
            }

            val wantsTwoNoteChord = difficulty.allowChords && group.size >= 2 && group.any { it.isChord }
            if (wantsTwoNoteChord) {
                val sortedGroup = group.sortedByDescending { it.intensity }
                val first = sortedGroup[0]
                val second = sortedGroup[1]

                // Garantizar estrictamente 1 carril de mano izquierda (0..1) y 1 carril de mano derecha (2..3)
                val leftPref = if (first.lane <= 1) first.lane else if (second.lane <= 1) second.lane else 0
                val rightPref = if (first.lane >= 2) first.lane else if (second.lane >= 2) second.lane else 2

                val leftLane = listOf(leftPref, 1 - leftPref).firstOrNull { groupTime >= lastLaneEndMs[it] + sameLaneMinGapMs }
                val rightLane = listOf(rightPref, if (rightPref == 2) 3 else 2).firstOrNull { groupTime >= lastLaneEndMs[it] + sameLaneMinGapMs }

                if (leftLane != null && rightLane != null) {
                    accepted.add(
                        first.copy(
                            timeMs = groupTime,
                            durationMs = 0L,
                            lane = leftLane,
                            isChord = true,
                            kind = NoteKind.CHORD
                        )
                    )
                    accepted.add(
                        second.copy(
                            timeMs = groupTime,
                            durationMs = 0L,
                            lane = rightLane,
                            isChord = true,
                            kind = NoteKind.CHORD
                        )
                    )
                    lastLaneEndMs[leftLane] = groupTime
                    lastLaneEndMs[rightLane] = groupTime
                    continue
                }
            }

            // Tecla individual: elegir la más intensa del grupo y evitar jack spam en el mismo carril
            val primary = group.maxByOrNull { it.intensity } ?: continue
            val chosenLane = pickComfortableLane(
                preferred = primary.lane.coerceIn(0, 3),
                timeMs = groupTime,
                lastLaneEndMs = lastLaneEndMs,
                minSameLaneGapMs = sameLaneMinGapMs
            ) ?: continue

            val cleanKind = when {
                primary.kind == NoteKind.BEAT_PULSE -> NoteKind.BEAT_PULSE
                else -> NoteKind.TAP
            }
            accepted.add(
                primary.copy(
                    timeMs = groupTime,
                    durationMs = 0L,
                    lane = chosenLane,
                    isChord = false,
                    kind = cleanKind
                )
            )
            lastLaneEndMs[chosenLane] = groupTime
        }

        // Recortar cualquier HOLD que por ajuste hubiera quedado pegado a una nota del mismo carril o mano
        val finalList = accepted
            .sortedWith(compareBy<RhythmNote> { it.timeMs }.thenBy { it.lane })
            .mapIndexed { index, note -> note.copy(id = index + 1) }

        return finalList
    }

    private fun pickComfortableLane(
        preferred: Int,
        timeMs: Long,
        lastLaneEndMs: LongArray,
        minSameLaneGapMs: Long
    ): Int? {
        val pref = preferred.coerceIn(0, 3)
        if (timeMs >= lastLaneEndMs[pref] + minSameLaneGapMs) return pref
        // Priorizar alternancia hacia la mano opuesta para fluidez en pulgares
        val order = when (pref) {
            0 -> listOf(2, 1, 3)
            1 -> listOf(3, 2, 0)
            2 -> listOf(0, 3, 1)
            else -> listOf(1, 0, 2)
        }
        return order.firstOrNull { timeMs >= lastLaneEndMs[it] + minSameLaneGapMs }
    }

    /**
     * Asigna carril (0..3) siguiendo el contorno tonal relativo (centroide espectral armónico):
     * - Si el tono/centroide sube respecto a la nota anterior (> +1 semitono), desplaza hacia la derecha.
     * - Si el tono/centroide baja (< -1 semitono), desplaza hacia la izquierda.
     * - Mantiene siempre la regla anti-repetición del mismo carril en notas rápidas (< 260 ms).
     */
    internal fun assignContourLane(
        candidate: OnsetCandidate,
        prevLane: Int,
        prevCentroidHz: Float,
        timeSinceLastMs: Long
    ): Int {
        val hasCentroid = candidate.spectralCentroidHz > 1f
        val isPurePercussive = candidate.isPercussiveAccent &&
            candidate.percussiveStrength > candidate.melodicStrength * 1.45f &&
            !hasCentroid

        if (isPurePercussive || !hasCentroid) {
            var lane = candidate.dominantBand.coerceIn(0, 3)
            if (lane == prevLane && timeSinceLastMs < 260L) {
                lane = (lane + 1) % 4
            }
            return lane
        }

        // Primera nota melódica: ubicar carril inicial según rango logarítmico del centroide armónico
        if (prevLane !in 0..3 || prevCentroidHz <= 1f) {
            return when {
                candidate.spectralCentroidHz < 240f -> 0
                candidate.spectralCentroidHz < 520f -> 1
                candidate.spectralCentroidHz < 1150f -> 2
                else -> 3
            }
        }

        val deltaSemitones = 12.0 * ln((candidate.spectralCentroidHz / prevCentroidHz).toDouble()) / ln(2.0)
        return when {
            deltaSemitones > 1.0 -> {
                val step = if (deltaSemitones >= 6.0) 2 else 1
                val target = (prevLane + step).coerceAtMost(3)
                if (target == prevLane && timeSinceLastMs < 260L) {
                    (prevLane - 1).coerceAtLeast(0)
                } else {
                    target
                }
            }
            deltaSemitones < -1.0 -> {
                val step = if (deltaSemitones <= -6.0) 2 else 1
                val target = (prevLane - step).coerceAtLeast(0)
                if (target == prevLane && timeSinceLastMs < 260L) {
                    (prevLane + 1).coerceAtMost(3)
                } else {
                    target
                }
            }
            else -> {
                if (timeSinceLastMs < 260L) {
                    if (prevLane < 3) prevLane + 1 else prevLane - 1
                } else {
                    prevLane
                }
            }
        }
    }

    /**
     * Selección por importancia con supresión de no-máximos:
     * ordena los candidatos por prioridad (fuerza + proximidad a la cuadrícula local del beat) y acepta
     * cada pico si no existe ya un pico más fuerte dentro de ±minIntervalMs.
     */
    internal fun selectCandidatesByImportance(
        candidates: List<OnsetCandidate>,
        minIntervalMs: Long,
        beatOffsetMs: Long,
        beatPeriodMs: Double,
        beatSegments: List<BeatPhaseSegment> = emptyList()
    ): List<OnsetCandidate> {
        val ranked = candidates.sortedByDescending { cand ->
            val localOffset = localBeatOffsetAt(cand.timeMs, beatSegments, beatOffsetMs, beatPeriodMs)
            val beatDist = distanceToMainBeatMs(cand.timeMs, localOffset, beatPeriodMs)
            val beatBonus = if (beatDist <= 25.0) 0.08f else 0f
            cand.strength + beatBonus
        }

        val accepted = ArrayList<OnsetCandidate>()
        for (cand in ranked) {
            val blocked = accepted.any { abs(it.timeMs - cand.timeMs) < minIntervalMs }
            if (!blocked) {
                accepted.add(cand)
            }
        }
        return accepted.sortedBy { it.timeMs }
    }

    private fun fillLongGapsWithRealCandidates(
        selected: MutableList<OnsetCandidate>,
        allCandidates: List<OnsetCandidate>,
        minIntervalMs: Long,
        maxGapMs: Long
    ) {
        if (allCandidates.isEmpty()) return
        val remaining = allCandidates
            .filter { cand -> selected.none { abs(it.timeMs - cand.timeMs) < minIntervalMs } }
            .sortedByDescending { it.strength }

        for (cand in remaining) {
            if (selected.isEmpty()) {
                selected.add(cand)
                continue
            }
            val nearestDist = selected.minOf { abs(it.timeMs - cand.timeMs) }
            if (nearestDist >= maxGapMs) {
                selected.add(cand)
            }
        }
    }

    private fun supplementNotesForFidelity(
        notes: MutableList<RhythmNote>,
        candidates: List<OnsetCandidate>,
        difficulty: DifficultyLevel,
        durationMs: Long,
        beatOffsetMs: Long,
        beatPeriodMs: Double,
        extraCount: Int,
        energyAtTimeMs: ((Long) -> Float)?,
        minRmsThreshold: Float,
        beatSegments: List<BeatPhaseSegment> = emptyList()
    ) {
        var added = 0
        val minSpacingMs = max(95L, difficulty.minIntervalMs)

        fun pickPlayableLane(preferredLane: Int, timeMs: Long): Int? {
            // Verificar si hay un HOLD activo en o cerca de timeMs
            val overlappingHold = notes.firstOrNull { n ->
                n.isHold && timeMs >= n.timeMs - 140L && timeMs <= n.endTimeMs + 140L
            }
            if (overlappingHold != null) {
                if (difficulty.level <= 2) return null
                // En niveles 3–5 solo permitir en ventana cómoda y mano opuesta
                if (timeMs < overlappingHold.timeMs + 180L || timeMs > overlappingHold.endTimeMs - 150L) {
                    return null
                }
                val oppositeLanes = if (overlappingHold.lane <= 1) listOf(2, 3) else listOf(0, 1)
                return oppositeLanes.firstOrNull { lane ->
                    notes.none { it.lane == lane && timeMs in (it.timeMs - 220L)..(it.endTimeMs + 220L) }
                }
            }
            return findFreeLane(notes, preferredLane, timeMs)
        }

        // 1) Priorizar siempre los siguientes onsets REALES más fuertes
        for (cand in candidates.sortedByDescending { it.strength }) {
            if (added >= extraCount) break
            val clashesTiming = notes.any { abs(it.timeMs - cand.timeMs) < minSpacingMs }
            if (clashesTiming) continue

            val lane = pickPlayableLane(cand.dominantBand.coerceIn(0, 3), cand.timeMs) ?: continue
            val localOffset = localBeatOffsetAt(cand.timeMs, beatSegments, beatOffsetMs, beatPeriodMs)
            val isMainBeat = distanceToMainBeatMs(cand.timeMs, localOffset, beatPeriodMs) <= 32.0
            notes.add(
                RhythmNote(
                    id = notes.size + 1,
                    timeMs = cand.timeMs,
                    durationMs = 0L,
                    lane = lane,
                    isChord = false,
                    intensity = cand.strength,
                    frequencyBand = cand.dominantBand,
                    kind = if (cand.isPercussiveAccent && cand.strength >= 0.70f || (isMainBeat && cand.strength >= 0.78f)) {
                        NoteKind.BEAT_PULSE
                    } else {
                        NoteKind.TAP
                    }
                )
            )
            added++
        }

        // 2) Evaluar puntos alineados a la fase local del beat donde exista energía real por encima de la puerta RMS
        if (added < extraCount) {
            val subdivisions = if (difficulty.level >= 4) 4 else 2
            val subBeatMs = max(minSpacingMs.toDouble(), beatPeriodMs / subdivisions.toDouble())
            val gridCandidates = ArrayList<Pair<Long, Float>>()

            var k = 0
            while (true) {
                val approxT = (beatOffsetMs.toDouble() + k * subBeatMs).roundToLong()
                k++
                if (approxT >= durationMs - 320L) break
                val localOffset = localBeatOffsetAt(approxT, beatSegments, beatOffsetMs, beatPeriodMs)
                val localK = round((approxT.toDouble() - localOffset.toDouble()) / subBeatMs)
                val t = (localOffset.toDouble() + localK * subBeatMs).roundToLong()
                if (t < 200L || t >= durationMs - 320L) continue
                if (notes.any { abs(it.timeMs - t) < minSpacingMs }) continue

                val energy = energyAtTimeMs?.invoke(t) ?: (minRmsThreshold * 2f)
                if (energy >= minRmsThreshold) {
                    gridCandidates.add(t to energy)
                }
            }

            var laneCycle = 0
            for ((t, energy) in gridCandidates.sortedByDescending { it.second }) {
                if (added >= extraCount) break
                if (notes.any { abs(it.timeMs - t) < minSpacingMs }) continue
                val preferredLane = (laneCycle++ * 3 + difficulty.level) % 4
                val lane = pickPlayableLane(preferredLane, t) ?: continue
                val localOffset = localBeatOffsetAt(t, beatSegments, beatOffsetMs, beatPeriodMs)
                val isMainBeat = distanceToMainBeatMs(t, localOffset, beatPeriodMs) <= 20.0
                notes.add(
                    RhythmNote(
                        id = notes.size + 1,
                        timeMs = t,
                        durationMs = 0L,
                        lane = lane,
                        isChord = false,
                        intensity = (energy * 2.5f).coerceIn(0.45f, 0.85f),
                        frequencyBand = lane,
                        kind = if (isMainBeat && energy >= minRmsThreshold * 2.5f) NoteKind.BEAT_PULSE else NoteKind.TAP
                    )
                )
                added++
            }
        }

        // 3) En dificultades con acordes (niveles 3–5), si los huecos individuales ya están cubiertos,
        //    añadir acordes ergonómicos de 2 manos (1 izq + 1 der) en los golpes fuertes fuera de cualquier HOLD
        if (added < extraCount && difficulty.allowChords) {
            val existingTimestamps = notes.groupBy { it.timeMs }
            val singleAccents = notes
                .filter { !it.isHold && !it.isChord && (existingTimestamps[it.timeMs]?.size ?: 0) == 1 }
                .sortedByDescending { it.intensity }

            for (baseNote in singleAccents) {
                if (added >= extraCount) break
                val nearHold = notes.any { n ->
                    n.isHold && baseNote.timeMs >= n.timeMs - 160L && baseNote.timeMs <= n.endTimeMs + 160L
                }
                if (nearHold) continue

                val oppositeLanes = if (baseNote.lane <= 1) listOf(2, 3) else listOf(0, 1)
                val partnerLane = oppositeLanes.firstOrNull { lane ->
                    notes.none { it.lane == lane && baseNote.timeMs in (it.timeMs - 220L)..(it.endTimeMs + 220L) }
                } ?: continue

                val idx = notes.indexOfFirst { it.id == baseNote.id }
                if (idx >= 0) {
                    notes[idx] = baseNote.copy(isChord = true, kind = NoteKind.CHORD)
                    notes.add(
                        RhythmNote(
                            id = notes.size + 1,
                            timeMs = baseNote.timeMs,
                            durationMs = 0L,
                            lane = partnerLane,
                            isChord = true,
                            intensity = baseNote.intensity * 0.92f,
                            frequencyBand = partnerLane,
                            kind = NoteKind.CHORD
                        )
                    )
                    added++
                }
            }
        }
    }

    private fun findFreeLane(notes: List<RhythmNote>, preferredLane: Int, timeMs: Long): Int? {
        val isFree: (Int) -> Boolean = { candidateLane ->
            notes.none { it.lane == candidateLane && timeMs in (it.timeMs - 220L)..(it.endTimeMs + 220L) }
        }
        return if (isFree(preferredLane)) {
            preferredLane
        } else {
            (0..3).firstOrNull(isFree)
        }
    }

    private fun distanceToMainBeatMs(timeMs: Long, beatOffsetMs: Long, beatPeriodMs: Double): Double {
        if (beatPeriodMs <= 0.0) return Double.MAX_VALUE
        val k = round((timeMs.toDouble() - beatOffsetMs.toDouble()) / beatPeriodMs)
        val nearestBeat = beatOffsetMs.toDouble() + k * beatPeriodMs
        return abs(timeMs.toDouble() - nearestBeat)
    }

    /**
     * Estima el BPM exacto usando autocorrelación sin media sobre el flujo rítmico,
     * interpolación parabólica y verificación explícita de octava/doble tiempo
     * para evitar detectar 160 BPM cuando el pulso real es 80 BPM.
     */
    internal fun estimateExactBpm(flux: FloatArray, hopDurationMs: Double): Double {
        val minLag = floor(60_000.0 / (190.0 * hopDurationMs)).toInt().coerceAtLeast(2)
        val maxLag = ceil(60_000.0 / (60.0 * hopDurationMs)).toInt().coerceAtLeast(minLag + 2)
        if (flux.size <= maxLag + 4) return 120.0

        var mean = 0.0
        for (v in flux) mean += v
        mean /= flux.size.toDouble()

        val centered = DoubleArray(flux.size) { flux[it] - mean }
        val rawCorr = DoubleArray(maxLag + 2)
        val weightedCorr = DoubleArray(maxLag + 2)

        for (lag in minLag..maxLag) {
            var sum = 0.0
            val limit = centered.size - lag
            for (i in 0 until limit) {
                sum += centered[i] * centered[i + lag]
            }
            val norm = sum / limit.toDouble()
            rawCorr[lag] = norm

            // Prior suave en log-BPM centrado cerca de 116 BPM (estándar psicoacústico MIR)
            val candidateBpm = 60_000.0 / (lag.toDouble() * hopDurationMs)
            val logDist = ln(candidateBpm / 116.0)
            val tempoPrior = exp(-0.5 * (logDist / 0.55) * (logDist / 0.55))
            weightedCorr[lag] = if (norm > 0.0) norm * tempoPrior else norm
        }

        var bestLag = minLag
        var bestScore = Double.NEGATIVE_INFINITY
        for (lag in minLag..maxLag) {
            if (weightedCorr[lag] > bestScore) {
                bestScore = weightedCorr[lag]
                bestLag = lag
            }
        }

        // Verificación de octava / doble tiempo (evitar 160 en vez de 80 BPM o 64 en vez de 128 BPM)
        val initialBpm = 60_000.0 / (bestLag.toDouble() * hopDurationMs)
        val doubleLag = bestLag * 2
        if (initialBpm > 146.0 && doubleLag <= maxLag && rawCorr[doubleLag] > 0.0) {
            // Buscar máximo local cerca de doubleLag (±2 frames)
            val bestDoubleLag = ((doubleLag - 2).coerceAtLeast(minLag)..(doubleLag + 2).coerceAtMost(maxLag))
                .maxByOrNull { rawCorr[it] } ?: doubleLag
            if (rawCorr[bestDoubleLag] >= rawCorr[bestLag] * 0.78) {
                bestLag = bestDoubleLag
            }
        } else {
            val halfLag = bestLag / 2
            if (initialBpm < 74.0 && halfLag >= minLag && rawCorr[halfLag] > 0.0) {
                val bestHalfLag = ((halfLag - 2).coerceAtLeast(minLag)..(halfLag + 2).coerceAtMost(maxLag))
                    .maxByOrNull { rawCorr[it] } ?: halfLag
                if (rawCorr[bestHalfLag] >= rawCorr[bestLag] * 0.88) {
                    bestLag = bestHalfLag
                }
            }
        }

        // Refinamiento parabólico alrededor de bestLag
        var refinedLag = bestLag.toDouble()
        if (bestLag > minLag && bestLag < maxLag) {
            val y0 = rawCorr[bestLag - 1]
            val y1 = rawCorr[bestLag]
            val y2 = rawCorr[bestLag + 1]
            val denom = (y0 - 2.0 * y1 + y2)
            if (abs(denom) > 1e-9) {
                val delta = (0.5 * (y0 - y2) / denom).coerceIn(-0.5, 0.5)
                refinedLag += delta
            }
        }

        val periodMs = refinedLag * hopDurationMs
        return if (periodMs > 0.0) (60_000.0 / periodMs).coerceIn(65.0, 180.0) else 120.0
    }

    /**
     * Calcula la fase del primer beat (beatOffsetMs) maximizando la suma de energía de flujo
     * sobre todos los pulsos t = phase + k * beatPeriodMs a lo largo de la canción.
     */
    internal fun estimateBeatOffsetMs(
        flux: FloatArray,
        hopDurationMs: Double,
        beatPeriodMs: Double,
        startOffsetMs: Long = 0L
    ): Long {
        if (flux.size < 8 || beatPeriodMs <= 0.0) return startOffsetMs.coerceAtLeast(0L)
        val totalSongMs = flux.size * hopDurationMs
        val steps = max(60, (beatPeriodMs / 2.0).roundToInt()).coerceAtMost(280)
        var bestPhaseRelMs = 0.0
        var bestScore = Double.NEGATIVE_INFINITY

        for (s in 0 until steps) {
            val phaseMs = (s.toDouble() / steps.toDouble()) * beatPeriodMs
            var score = 0.0
            var t = phaseMs
            while (t < totalSongMs) {
                val fPos = t / hopDurationMs
                val f0 = fPos.toInt()
                if (f0 in 1 until flux.size - 1) {
                    val frac = fPos - f0
                    val interp = flux[f0] * (1.0 - frac) + flux[f0 + 1] * frac
                    // Kernel triangular suave (±1 hop) para tolerar micro-variaciones humanas de tempo
                    score += interp + 0.30 * flux[f0 - 1] + 0.30 * flux[min(flux.size - 1, f0 + 1)]
                }
                t += beatPeriodMs
            }
            if (score > bestScore) {
                bestScore = score
                bestPhaseRelMs = phaseMs
            }
        }

        // Compensar 1 hop (~11.6 ms) de adelanto propio del flanco derecho de la ventana Hann en Log-Flux
        val alignedPhaseMs = (bestPhaseRelMs + hopDurationMs) % beatPeriodMs
        val absoluteOffset = startOffsetMs + alignedPhaseMs.roundToLong()
        return absoluteOffset.coerceAtLeast(0L)
    }

    /**
     * Divide la pista en segmentos solapados (por defecto 12 s con salto de 6 s) y re-estima la fase
     * local del beat en cada segmento para absorber variaciones de tempo y cambios de puente sin drift.
     */
    internal fun estimateSegmentedBeatPhases(
        flux: FloatArray,
        hopDurationMs: Double,
        beatPeriodMs: Double,
        startOffsetMs: Long = 0L,
        globalBeatOffsetMs: Long = startOffsetMs,
        segmentWindowMs: Double = 12_000.0,
        segmentHopMs: Double = 6_000.0
    ): List<BeatPhaseSegment> {
        if (flux.size < 16 || beatPeriodMs <= 0.0) {
            return listOf(BeatPhaseSegment(startOffsetMs, globalBeatOffsetMs, 1f))
        }
        val totalSongMs = flux.size * hopDurationMs
        if (totalSongMs < segmentWindowMs * 1.15) {
            return listOf(
                BeatPhaseSegment(
                    centerTimeMs = startOffsetMs + (totalSongMs * 0.5).roundToLong(),
                    beatOffsetMs = globalBeatOffsetMs,
                    confidence = 1f
                )
            )
        }

        val globalFluxMean = flux.average()
        val steps = max(60, (beatPeriodMs / 2.5).roundToInt()).coerceAtMost(240)
        val segments = ArrayList<BeatPhaseSegment>()

        var prevPhaseRelMs = positiveMod((globalBeatOffsetMs - startOffsetMs).toDouble() - hopDurationMs, beatPeriodMs)
        var segStartMs = 0.0
        while (segStartMs < totalSongMs - segmentWindowMs * 0.35) {
            val segEndMs = min(totalSongMs, segStartMs + segmentWindowMs)
            val startFrame = (segStartMs / hopDurationMs).toInt().coerceIn(0, flux.size - 1)
            val endFrame = (segEndMs / hopDurationMs).toInt().coerceIn(startFrame + 1, flux.size)

            var segFluxSum = 0.0
            for (f in startFrame until endFrame) segFluxSum += flux[f]
            val segFluxMean = segFluxSum / max(1, endFrame - startFrame).toDouble()
            val centerMs = startOffsetMs + ((segStartMs + segEndMs) * 0.5).roundToLong()

            if (globalFluxMean > 0.0 && segFluxMean < globalFluxMean * 0.14) {
                // Segmento silencioso o puente ambiental: preservar la fase anterior para evitar saltos erráticos
                val heldOffset = startOffsetMs + positiveMod(prevPhaseRelMs + hopDurationMs, beatPeriodMs).roundToLong()
                segments.add(BeatPhaseSegment(centerMs, heldOffset.coerceAtLeast(0L), confidence = 0.15f))
                segStartMs += segmentHopMs
                continue
            }

            var bestPhaseRelMs = prevPhaseRelMs
            var bestScore = Double.NEGATIVE_INFINITY
            var rawBestScore = 0.0

            for (s in 0 until steps) {
                val phaseMs = (s.toDouble() / steps.toDouble()) * beatPeriodMs
                var score = 0.0
                val firstK = ceil((segStartMs - phaseMs) / beatPeriodMs).toInt().coerceAtLeast(0)
                var t = phaseMs + firstK * beatPeriodMs
                while (t < segEndMs) {
                    val fPos = t / hopDurationMs
                    val f0 = fPos.toInt()
                    if (f0 in 1 until flux.size - 1) {
                        val frac = fPos - f0
                        val interp = flux[f0] * (1.0 - frac) + flux[f0 + 1] * frac
                        score += interp + 0.30 * flux[f0 - 1] + 0.30 * flux[min(flux.size - 1, f0 + 1)]
                    }
                    t += beatPeriodMs
                }
                // Pequeña preferencia circular (+6%) hacia la fase previa solo para desempatar tramos ambiguos
                val phaseDiffRad = 2.0 * PI * (phaseMs - prevPhaseRelMs) / beatPeriodMs
                val continuityWeight = 1.0 + 0.06 * cos(phaseDiffRad)
                val weighted = score * continuityWeight
                if (weighted > bestScore) {
                    bestScore = weighted
                    rawBestScore = score
                    bestPhaseRelMs = phaseMs
                }
            }

            prevPhaseRelMs = bestPhaseRelMs
            val alignedPhaseMs = positiveMod(bestPhaseRelMs + hopDurationMs, beatPeriodMs)
            val absoluteOffset = (startOffsetMs + alignedPhaseMs.roundToLong()).coerceAtLeast(0L)
            val confidence = (rawBestScore / max(0.01, segFluxMean * ((segEndMs - segStartMs) / beatPeriodMs))).toFloat().coerceIn(0.2f, 1f)
            segments.add(BeatPhaseSegment(centerMs, absoluteOffset, confidence))
            segStartMs += segmentHopMs
        }

        return if (segments.isEmpty()) {
            listOf(BeatPhaseSegment(startOffsetMs, globalBeatOffsetMs, 1f))
        } else {
            segments
        }
    }

    /**
     * Interpola circularmente (módulo `beatPeriodMs`) la fase del beat en `timeMs` entre segmentos vecinos.
     */
    internal fun localBeatOffsetAt(
        timeMs: Long,
        segments: List<BeatPhaseSegment>,
        fallbackOffsetMs: Long,
        beatPeriodMs: Double
    ): Long {
        if (segments.isEmpty() || beatPeriodMs <= 0.0) return fallbackOffsetMs
        if (segments.size == 1 || timeMs <= segments.first().centerTimeMs) {
            return segments.first().beatOffsetMs
        }
        if (timeMs >= segments.last().centerTimeMs) {
            return segments.last().beatOffsetMs
        }
        for (i in 0 until segments.size - 1) {
            val s0 = segments[i]
            val s1 = segments[i + 1]
            if (timeMs in s0.centerTimeMs..s1.centerTimeMs) {
                val span = (s1.centerTimeMs - s0.centerTimeMs).coerceAtLeast(1L).toDouble()
                val alpha = ((timeMs - s0.centerTimeMs).toDouble() / span).coerceIn(0.0, 1.0)
                val p0 = positiveMod(s0.beatOffsetMs.toDouble(), beatPeriodMs)
                val p1 = positiveMod(s1.beatOffsetMs.toDouble(), beatPeriodMs)
                var delta = p1 - p0
                if (delta > beatPeriodMs * 0.5) delta -= beatPeriodMs
                if (delta < -beatPeriodMs * 0.5) delta += beatPeriodMs
                val interp = positiveMod(p0 + alpha * delta, beatPeriodMs)
                return interp.roundToLong()
            }
        }
        return fallbackOffsetMs
    }

    private fun positiveMod(value: Double, mod: Double): Double {
        if (mod <= 0.0) return 0.0
        val r = value % mod
        return if (r < 0.0) r + mod else r
    }

    private data class CompactBinSpec(
        val startBin: Int,
        val endBin: Int,
        val centerHz: Float,
        val band: Int
    )

    private fun buildCompactBinSpecs(halfBins: Int, hzPerBin: Float): List<CompactBinSpec> {
        val specs = ArrayList<CompactBinSpec>(96)
        var bin = 1
        while (bin <= halfBins) {
            val hz = bin * hzPerBin
            if (hz < 40f) {
                bin++
                continue
            }
            if (hz > 8000f) break

            val groupWidth = when {
                hz < 1000f -> 1
                hz < 2600f -> 3
                else -> 10
            }
            val endBin = min(halfBins, bin + groupWidth - 1)
            val centerHz = ((bin + endBin) * 0.5f) * hzPerBin
            val band = when {
                centerHz < 220f -> 0
                centerHz < 650f -> 1
                centerHz < 2200f -> 2
                else -> 3
            }
            specs.add(CompactBinSpec(bin, endBin, centerHz, band))
            bin = endBin + 1
        }
        return specs
    }

    /**
     * Separación ligera Armónico-Percusiva (HPSS) sobre espectrograma de magnitudes logarítmicas:
     * - Mediana horizontal en el tiempo (`2 * timeRadius + 1` fotogramas) -> energía armónica sostenida.
     * - Mediana vertical en frecuencia (`2 * freqRadius + 1` bins) -> golpes percusivos de banda ancha.
     * - Máscaras suaves de Wiener (`H^2 / (H^2 + P^2)` y `P^2 / (H^2 + P^2)`).
     */
    internal fun computeHpss(
        logSpec: Array<FloatArray>,
        timeRadius: Int = 4,
        freqRadius: Int = 4
    ): HpssResult {
        val frameCount = logSpec.size
        if (frameCount == 0 || logSpec[0].isEmpty()) {
            return HpssResult(emptyArray(), emptyArray())
        }
        val binCount = logSpec[0].size
        val harmonic = Array(frameCount) { FloatArray(binCount) }
        val percussive = Array(frameCount) { FloatArray(binCount) }

        val timeBuf = FloatArray(timeRadius * 2 + 1)
        val freqBuf = FloatArray(freqRadius * 2 + 1)

        for (t in 0 until frameCount) {
            val row = logSpec[t]
            val tStart = max(0, t - timeRadius)
            val tEnd = min(frameCount - 1, t + timeRadius)
            val tLen = tEnd - tStart + 1

            for (b in 0 until binCount) {
                // 1) Mediana horizontal (tiempo) para aislar la componente armónica
                var ti = 0
                for (k in tStart..tEnd) {
                    timeBuf[ti++] = logSpec[k][b]
                }
                val hMed = fastMedian(timeBuf, tLen)

                // 2) Mediana vertical (frecuencia) para aislar la componente percusiva
                val fStart = max(0, b - freqRadius)
                val fEnd = min(binCount - 1, b + freqRadius)
                val fLen = fEnd - fStart + 1
                var fi = 0
                for (k in fStart..fEnd) {
                    freqBuf[fi++] = row[k]
                }
                val pMed = fastMedian(freqBuf, fLen)

                // 3) Máscaras suaves de Wiener
                val h2 = hMed * hMed
                val p2 = pMed * pMed
                val denom = h2 + p2 + 1e-6f
                val s = row[b]
                harmonic[t][b] = s * (h2 / denom)
                percussive[t][b] = s * (p2 / denom)
            }
        }
        return HpssResult(harmonicSpec = harmonic, percussiveSpec = percussive)
    }

    private fun fastMedian(buf: FloatArray, len: Int): Float {
        // Insertion sort in-place sobre ventana pequeña (<= 11 elementos), cero asignaciones
        for (i in 1 until len) {
            val key = buf[i]
            var j = i - 1
            while (j >= 0 && buf[j] > key) {
                buf[j + 1] = buf[j]
                j--
            }
            buf[j + 1] = key
        }
        return buf[len ushr 1]
    }

    private fun normalizeFluxProfile(raw: FloatArray): FloatArray {
        if (raw.isEmpty()) return raw
        val nonZero = raw.filter { it > 0.0001f }.sorted()
        if (nonZero.isEmpty()) return FloatArray(raw.size)
        // Usar percentil 98 para evitar que un único clic extremo aplaste toda la dinámica
        val p98Idx = ((nonZero.size - 1) * 0.98f).roundToInt().coerceIn(0, nonZero.size - 1)
        val scale = max(nonZero[p98Idx], 0.0001f)
        return FloatArray(raw.size) { i -> (raw[i] / scale).coerceIn(0f, 1.35f) }
    }

    /**
     * Puerta de ruido RMS dinámica respecto al volumen promedio/activo de la canción:
     * evita generar notas fantasma en intros silenciosas, pausas ambientales o ruido de compresión MP3.
     */
    internal fun computeDynamicRmsNoiseGate(rms: FloatArray): Float {
        if (rms.isEmpty()) return 0.015f
        val active = rms.filter { it > 0.002f }.sorted()
        if (active.isEmpty()) return 0.015f
        val activeMean = active.average().toFloat()
        val p75 = active[((active.size - 1) * 0.75f).roundToInt().coerceIn(0, active.size - 1)]
        val activeRef = max(activeMean, p75 * 0.72f)
        val dynamicGate = max(0.012f, activeRef * 0.22f)
        return max(0.0015f, dynamicGate.coerceAtMost(activeRef * 0.45f))
    }

    private fun nonMaxSuppressFrames(
        peaks: List<Int>,
        scores: Map<Int, Float>,
        radiusFrames: Int
    ): List<Int> {
        val ranked = peaks.sortedByDescending { scores[it] ?: 0f }
        val kept = ArrayList<Int>()
        for (frame in ranked) {
            if (kept.none { abs(it - frame) <= radiusFrames }) {
                kept.add(frame)
            }
        }
        kept.sort()
        return kept
    }

    private fun synthesizeBeatCandidates(
        durationMs: Long,
        bpm: Int,
        beatOffsetMs: Long = 500L
    ): List<OnsetCandidate> {
        val beatMs = (60_000L / bpm.coerceIn(70, 180)).coerceAtLeast(250L)
        val subBeatMs = beatMs / 2L
        val list = ArrayList<OnsetCandidate>()
        var t = max(400L, beatOffsetMs)
        var step = 0
        while (t < durationMs - 400L) {
            val isDownbeat = (step % 8 == 0)
            val isMainBeat = (step % 2 == 0)
            val strength = when {
                isDownbeat -> 0.96f
                isMainBeat -> 0.74f
                step % 4 == 1 -> 0.48f
                else -> 0.28f
            }
            val band = ((step * 3) + (step / 4)) % 4
            val sustain = if (isDownbeat) (beatMs * 0.9f).toLong() else 0L
            list.add(
                OnsetCandidate(
                    timeMs = t,
                    strength = strength,
                    dominantBand = band,
                    secondaryBand = if (isDownbeat) (band + 2) % 4 else null,
                    sustainDurationMs = sustain,
                    isPercussiveAccent = isMainBeat
                )
            )
            t += subBeatMs
            step++
        }
        return list
    }

    private fun createFallbackResult(durationMs: Long, bpm: Int): AnalyzedSongResult {
        val candidates = synthesizeBeatCandidates(durationMs, bpm)
        return AnalyzedSongResult(
            durationMs = durationMs,
            estimatedBpm = bpm,
            chartsByDifficulty = generateChartsFromCandidates(candidates, durationMs, bpm, beatOffsetMs = 500L),
            beatOffsetMs = 500L
        )
    }

    private class DecodedPcmData(
        val samples: FloatArray,
        val sampleRate: Int,
        val durationMs: Long,
        val startOffsetMs: Long = 0L
    )

    /**
     * Decodifica MP3/M4A/OGG/WAV a PCM mono aplicando filtro boxcar anti-aliasing antes de
     * bajar la frecuencia de muestreo y capturando el presentationTimeUs inicial / encoder delay.
     */
    private fun decodeAudioFileToMonoPcm(
        file: File,
        onDecodeProgress: (Float) -> Unit
    ): DecodedPcmData {
        if (!file.exists() || file.length() < 64L) {
            return DecodedPcmData(FloatArray(0), ANALYSIS_SAMPLE_RATE, 30_000L)
        }

        val wavResult = tryReadWavFileDirectly(file, onDecodeProgress)
        if (wavResult != null && wavResult.samples.isNotEmpty()) {
            return wavResult
        }

        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            var audioTrackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val trackFormat = extractor.getTrackFormat(i)
                val mime = trackFormat.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    format = trackFormat
                    break
                }
            }
            if (audioTrackIndex < 0 || format == null) {
                return DecodedPcmData(FloatArray(0), ANALYSIS_SAMPLE_RATE, 45_000L)
            }

            extractor.selectTrack(audioTrackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return DecodedPcmData(FloatArray(0), ANALYSIS_SAMPLE_RATE, 45_000L)
            var inputSampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                format.getInteger(MediaFormat.KEY_SAMPLE_RATE).coerceAtLeast(8000)
            } else 44100
            var channelCount = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            } else 2
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                format.getLong(MediaFormat.KEY_DURATION)
            } else 60_000_000L
            val encoderDelaySamples = if (format.containsKey(MediaFormat.KEY_ENCODER_DELAY)) {
                format.getInteger(MediaFormat.KEY_ENCODER_DELAY).coerceAtLeast(0)
            } else 0

            var downsampleStep = max(1, inputSampleRate / ANALYSIS_SAMPLE_RATE)
            var targetSampleRate = inputSampleRate / downsampleStep
            val expectedOutSamples = ((durationUs / 1_000_000.0) * targetSampleRate).toInt().coerceIn(4096, 6_000_000)
            val outBuffer = ArrayList<Float>(expectedOutSamples)

            var firstPresentationTimeUs: Long? = null
            var codec: MediaCodec? = null
            try {
                codec = MediaCodec.createDecoderByType(mime)
                codec.configure(format, null, null, 0)
                codec.start()

                val bufferInfo = MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false
                var emptyDrainRetries = 0

                // Acumulador de filtro boxcar anti-aliasing para downsampling
                var boxcarSum = 0f
                var boxcarCount = 0

                while (!outputDone) {
                    if (!inputDone) {
                        val inIndex = codec.dequeueInputBuffer(5_000L)
                        if (inIndex >= 0) {
                            val inputBuffer = codec.getInputBuffer(inIndex)
                            if (inputBuffer != null) {
                                val sampleSize = extractor.readSampleData(inputBuffer, 0)
                                if (sampleSize < 0) {
                                    codec.queueInputBuffer(
                                        inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                    )
                                    inputDone = true
                                } else {
                                    val presentationTimeUs = extractor.sampleTime
                                    codec.queueInputBuffer(inIndex, 0, sampleSize, presentationTimeUs, 0)
                                    extractor.advance()
                                    if (durationUs > 0) {
                                        onDecodeProgress((presentationTimeUs.toFloat() / durationUs.toFloat()).coerceIn(0f, 1f))
                                    }
                                }
                            }
                        }
                    }

                    val outIndex = codec.dequeueOutputBuffer(bufferInfo, 5_000L)
                    if (outIndex >= 0) {
                        emptyDrainRetries = 0
                        val outputBuffer = codec.getOutputBuffer(outIndex)
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            if (firstPresentationTimeUs == null) {
                                firstPresentationTimeUs = bufferInfo.presentationTimeUs
                            }
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            outputBuffer.order(ByteOrder.LITTLE_ENDIAN)
                            val shortBuffer = outputBuffer.asShortBuffer()
                            val shortsCount = shortBuffer.remaining()
                            var idx = 0
                            while (idx + channelCount <= shortsCount) {
                                var monoSum = 0f
                                for (c in 0 until channelCount) {
                                    monoSum += shortBuffer.get(idx + c) / 32768f
                                }
                                boxcarSum += (monoSum / channelCount)
                                boxcarCount++
                                if (boxcarCount >= downsampleStep) {
                                    outBuffer.add(boxcarSum / boxcarCount.toFloat())
                                    boxcarSum = 0f
                                    boxcarCount = 0
                                }
                                idx += channelCount
                            }
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true
                        }
                    } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val newFormat = codec.outputFormat
                        if (newFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            inputSampleRate = newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE).coerceAtLeast(8000)
                            downsampleStep = max(1, inputSampleRate / ANALYSIS_SAMPLE_RATE)
                            targetSampleRate = inputSampleRate / downsampleStep
                            boxcarSum = 0f
                            boxcarCount = 0
                        }
                        if (newFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            channelCount = newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                        }
                    } else if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER && inputDone) {
                        emptyDrainRetries++
                        if (emptyDrainRetries >= 40) {
                            break
                        }
                    }
                }
            } finally {
                try {
                    codec?.stop()
                } catch (_: Exception) {
                }
                try {
                    codec?.release()
                } catch (_: Exception) {
                }
            }

            // Compensar presentationTimeUs inicial o encoder delay para alinear t=0 ms con MediaPlayer
            val startOffsetMs = computeDecoderStartOffsetMs(
                firstPresentationTimeUs = firstPresentationTimeUs,
                encoderDelaySamples = encoderDelaySamples,
                inputSampleRate = inputSampleRate
            )

            val floatSamples = FloatArray(outBuffer.size) { outBuffer[it] }
            val actualDurationMs = if (floatSamples.isNotEmpty()) {
                ((floatSamples.size.toDouble() * 1000.0) / targetSampleRate.toDouble()).roundToLong() + max(0L, startOffsetMs)
            } else {
                durationUs / 1000L
            }
            return DecodedPcmData(
                samples = floatSamples,
                sampleRate = targetSampleRate,
                durationMs = max(3000L, actualDurationMs),
                startOffsetMs = startOffsetMs
            )
        } catch (_: Exception) {
            return DecodedPcmData(FloatArray(0), ANALYSIS_SAMPLE_RATE, 45_000L)
        } finally {
            try {
                extractor.release()
            } catch (_: Exception) {
            }
        }
    }

    internal fun computeDecoderStartOffsetMs(
        firstPresentationTimeUs: Long?,
        encoderDelaySamples: Int,
        inputSampleRate: Int
    ): Long {
        val ptsMs = if (firstPresentationTimeUs != null) {
            (firstPresentationTimeUs.toDouble() / 1000.0).roundToLong()
        } else 0L

        return when {
            ptsMs != 0L -> ptsMs.coerceIn(-250L, 500L)
            encoderDelaySamples > 0 && inputSampleRate > 0 -> {
                -((encoderDelaySamples.toDouble() * 1000.0) / inputSampleRate.toDouble()).roundToLong()
                    .coerceIn(0L, 120L)
            }
            else -> 0L
        }
    }

    /**
     * Lee archivos WAV PCM 16-bit aplicando filtro de promediado boxcar antes de bajar frecuencia.
     */
    private fun tryReadWavFileDirectly(
        file: File,
        onDecodeProgress: (Float) -> Unit
    ): DecodedPcmData? {
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val header = ByteArray(44)
                if (raf.read(header) < 44) return null
                val riff = String(header, 0, 4, Charsets.US_ASCII)
                val wave = String(header, 8, 4, Charsets.US_ASCII)
                if (riff != "RIFF" || wave != "WAVE") return null

                val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                val audioFormat = bb.getShort(20).toInt()
                val channels = bb.getShort(22).toInt().coerceAtLeast(1)
                val sampleRate = bb.getInt(24)
                val bitsPerSample = bb.getShort(34).toInt()
                if (audioFormat != 1 || bitsPerSample != 16 || sampleRate <= 0) return null

                val dataBytes = (file.length() - 44L).coerceAtLeast(0L)
                val bytesPerFrame = channels * 2
                val totalFrames = (dataBytes / bytesPerFrame).toInt()
                val downsampleStep = max(1, sampleRate / ANALYSIS_SAMPLE_RATE)
                val targetSampleRate = sampleRate / downsampleStep
                val outSamples = FloatArray(max(1, totalFrames / downsampleStep))

                val chunkBytes = ByteArray(16384)
                var outIdx = 0
                var boxcarSum = 0f
                var boxcarCount = 0
                var bytesReadTotal = 0L

                while (true) {
                    val read = raf.read(chunkBytes)
                    if (read <= 0) break
                    bytesReadTotal += read
                    val shortBuf = ByteBuffer.wrap(chunkBytes, 0, read)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .asShortBuffer()
                    val shortsAvailable = shortBuf.remaining()
                    var sIdx = 0
                    while (sIdx + channels <= shortsAvailable) {
                        var sum = 0f
                        for (c in 0 until channels) {
                            sum += shortBuf.get(sIdx + c) / 32768f
                        }
                        boxcarSum += (sum / channels)
                        boxcarCount++
                        if (boxcarCount >= downsampleStep && outIdx < outSamples.size) {
                            outSamples[outIdx++] = boxcarSum / boxcarCount.toFloat()
                            boxcarSum = 0f
                            boxcarCount = 0
                        }
                        sIdx += channels
                    }
                    if (dataBytes > 0) {
                        onDecodeProgress((bytesReadTotal.toFloat() / dataBytes.toFloat()).coerceIn(0f, 1f))
                    }
                }
                val durationMs = ((totalFrames.toDouble() * 1000.0) / sampleRate.toDouble()).roundToLong()
                val trimmed = if (outIdx == outSamples.size) outSamples else outSamples.copyOf(outIdx)
                DecodedPcmData(trimmed, targetSampleRate, max(3000L, durationMs), startOffsetMs = 0L)
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Filtro de promediado boxcar puro para downsampling anti-aliasing (también verificable en pruebas).
     */
    internal fun downsampleBoxcar(input: FloatArray, step: Int): FloatArray {
        if (step <= 1 || input.isEmpty()) return input.copyOf()
        val outLen = input.size / step
        if (outLen <= 0) return FloatArray(0)
        val out = FloatArray(outLen)
        val invStep = 1f / step.toFloat()
        for (i in 0 until outLen) {
            val base = i * step
            var acc = 0f
            for (k in 0 until step) {
                acc += input[base + k]
            }
            out[i] = acc * invStep
        }
        return out
    }

    /**
     * FFT Radix-2 iterativa in-place con tablas precalculadas de inversión de bits y senos/cosenos.
     */
    private class Radix2Fft(private val n: Int) {
        private val bitRev = IntArray(n)
        private val cosTable = FloatArray(n / 2)
        private val sinTable = FloatArray(n / 2)

        init {
            val bits = Integer.numberOfTrailingZeros(n)
            for (i in 0 until n) {
                var x = i
                var r = 0
                for (b in 0 until bits) {
                    r = (r shl 1) or (x and 1)
                    x = x ushr 1
                }
                bitRev[i] = r
            }
            for (i in 0 until n / 2) {
                val angle = -2.0 * PI * i / n
                cosTable[i] = cos(angle).toFloat()
                sinTable[i] = sin(angle).toFloat()
            }
        }

        fun transform(re: FloatArray, im: FloatArray) {
            for (i in 0 until n) {
                val j = bitRev[i]
                if (j > i) {
                    val tr = re[i]
                    re[i] = re[j]
                    re[j] = tr
                    val ti = im[i]
                    im[i] = im[j]
                    im[j] = ti
                }
            }

            var size = 2
            while (size <= n) {
                val halfSize = size ushr 1
                val tableStep = n / size
                var i = 0
                while (i < n) {
                    var k = 0
                    for (j in i until i + halfSize) {
                        val l = j + halfSize
                        val wRe = cosTable[k]
                        val wIm = sinTable[k]
                        val tRe = re[l] * wRe - im[l] * wIm
                        val tIm = re[l] * wIm + im[l] * wRe
                        re[l] = re[j] - tRe
                        im[l] = im[j] - tIm
                        re[j] += tRe
                        im[j] += tIm
                        k += tableStep
                    }
                    i += size
                }
                size = size shl 1
            }
        }
    }
}
