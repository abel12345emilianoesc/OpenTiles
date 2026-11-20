package com.example.viewmodel

import com.example.domain.DifficultyLevel
import com.example.domain.HitJudgment
import com.example.domain.NoteKind
import com.example.domain.RhythmNote
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Estado de cada nota durante la partida.
 */
data class ActiveNoteState(
    val note: RhythmNote,
    var isHit: Boolean = false,
    var isMissed: Boolean = false,
    var isHolding: Boolean = false,
    var holdCompleted: Boolean = false,
    var holdProgress: Float = 0f,
    var hitTimestampMs: Long = -1L
)

/**
 * Efecto visual efímero al impactar una tecla en un carril.
 */
data class HitVisualEffect(
    val id: Long,
    val lane: Int,
    val judgment: HitJudgment,
    val createdTimeMs: Long,
    val isFever: Boolean,
    val scoreText: String = ""
)

/**
 * Resumen final al terminar la canción.
 */
data class GameSessionResult(
    val songId: String,
    val songTitle: String,
    val artist: String,
    val difficulty: DifficultyLevel,
    val finalScore: Int,
    val accuracyPercent: Float,
    val stars: Int,
    val rank: String,
    val maxCombo: Int,
    val perfectCount: Int,
    val greatCount: Int,
    val goodCount: Int,
    val missCount: Int,
    val holdsCompleted: Int,
    val earnedFragments: Int,
    val isNewRecord: Boolean = false
)

/**
 * Sincronizador de reloj continuo (PLL suave y estrictamente monotónico) para renderizado a 60–120 fps.
 * - Soporta pre-roll negativo (p. ej. -950 ms) para que las primeras teclas caigan suavemente antes del beat 0.
 * - Ancla el tiempo en el primer `frameNanos` real de Choreographer y limita `dtMs` a 48 ms máximo.
 * - Ignora el jitter inicial de `MediaPlayer` durante los primeros 220 ms tras arrancar/despausar el audio.
 * - Garantía monotónica estricta: el reloj visual NUNCA retrocede ni se congela (si el audio arranca
 *   con latencia detrás del reloj visual, desacelera suavemente a mínimo 55% de velocidad hasta empatar
 *   con el audio real, evitando que la primera tecla se regrese o se trabe).
 */
class PlaybackClockSynchronizer(
    initialClockMs: Long = 0L,
    private val warmupWindowMs: Long = 220L,
    private val maxFrameStepMs: Double = 48.0,
    private val pllGain: Double = 0.12,
    private val pllDeadbandMs: Double = 8.0,
    private val hardSnapThresholdMs: Double = 120.0,
    private val minNegativePllRate: Double = 0.55,
    private val maxPositivePllRate: Double = 1.45
) {
    private var clockMs: Double = initialClockMs.toDouble()
    private var lastFrameNanos: Long? = null
    private var elapsedSinceAudioStartMs: Double = 0.0
    private var audioStarted: Boolean = initialClockMs >= 0L

    val currentClockMs: Long
        get() = clockMs.toLong()

    val isInPreRoll: Boolean
        get() = clockMs < 0.0

    fun resetForStartOrResume(
        currentEngineTimeMs: Long,
        audioAlreadyRunning: Boolean = currentEngineTimeMs >= 0L
    ) {
        clockMs = currentEngineTimeMs.toDouble()
        lastFrameNanos = null
        elapsedSinceAudioStartMs = 0.0
        audioStarted = audioAlreadyRunning
    }

    fun onAudioPlaybackStarted() {
        if (!audioStarted) {
            audioStarted = true
            elapsedSinceAudioStartMs = 0.0
        }
    }

    fun stepFrame(
        frameNanos: Long,
        mediaPlayerPosMs: Long?,
        isMediaPlaying: Boolean,
        audioOffsetMs: Long
    ): Long {
        val prevNanos = lastFrameNanos
        lastFrameNanos = frameNanos
        if (prevNanos == null) {
            return clockMs.toLong()
        }

        val rawDtMs = (frameNanos - prevNanos) / 1_000_000.0
        val clampedDtMs = rawDtMs.coerceIn(0.0, maxFrameStepMs)
        var nextClock = clockMs + clampedDtMs

        if (audioStarted) {
            elapsedSinceAudioStartMs += clampedDtMs
        }

        if (audioStarted &&
            nextClock >= 0.0 &&
            elapsedSinceAudioStartMs >= warmupWindowMs &&
            isMediaPlaying &&
            mediaPlayerPosMs != null &&
            mediaPlayerPosMs > 0L
        ) {
            val targetAudioClock = (mediaPlayerPosMs + audioOffsetMs).coerceAtLeast(0L).toDouble()
            val errorMs = targetAudioClock - nextClock
            when {
                // Solo salto directo hacia ADELANTE ante un bloqueo severo del hilo UI (>120 ms).
                // Nunca retroceder el reloj visual.
                errorMs > hardSnapThresholdMs -> {
                    nextClock = targetAudioClock
                }
                // El audio va ligeramente adelantado: acelerar suavemente sin tirones
                errorMs > pllDeadbandMs -> {
                    val maxExtraStep = clampedDtMs * (maxPositivePllRate - 1.0)
                    val correction = min(errorMs * pllGain, maxExtraStep)
                    nextClock = clockMs + clampedDtMs + correction
                }
                // El reloj visual va adelantado respecto al arranque del audio:
                // NUNCA retroceder ni detener la tecla; frenar suavemente (>= 55% de velocidad)
                // hasta converger con exactitud al audio real.
                errorMs < -pllDeadbandMs -> {
                    val desiredStep = clampedDtMs + (errorMs * pllGain)
                    val minForwardStep = clampedDtMs * minNegativePllRate
                    nextClock = clockMs + max(minForwardStep, desiredStep)
                }
                else -> {
                    nextClock = max(clockMs, nextClock)
                }
            }
        } else {
            nextClock = max(clockMs, nextClock)
        }

        clockMs = max(clockMs, nextClock)
        return clockMs.toLong()
    }
}


/**
 * Motor determinista de partida de 4 carriles.
 * Evalúa ventanas de precisión (Perfecto, Genial, Bien, Fallo), notas sostenidas (HOLD),
 * acordes (CHORD), teclas de pulso fuerte (BEAT_PULSE), multiplicadores y Modo Fiebre.
 */
class GameEngine(
    val songId: String,
    val songTitle: String,
    val artist: String,
    val durationMs: Long,
    val bpm: Int = 120,
    val primaryStyleColorHex: Long = 0xFF06B6D4L,
    val secondaryStyleColorHex: Long = 0xFF8B5CF6L,
    val difficulty: DifficultyLevel,
    notes: List<RhythmNote>,
    val initialPreRollMs: Long = 0L
) {
    val noteStates: List<ActiveNoteState> = notes
        .sortedWith(compareBy<RhythmNote> { it.timeMs }.thenBy { it.lane })
        .map { ActiveNoteState(it) }

    var currentTimeMs: Long = -initialPreRollMs.coerceAtLeast(0L)
        private set

    var score: Int = 0
        private set

    var combo: Int = 0
        private set

    var maxCombo: Int = 0
        private set

    var perfectCount: Int = 0
        private set

    var greatCount: Int = 0
        private set

    var goodCount: Int = 0
        private set

    var missCount: Int = 0
        private set

    var holdsCompletedCount: Int = 0
        private set

    var lastJudgment: HitJudgment? = null
        private set

    var lastJudgmentTimeMs: Long = -1000L
        private set

    val activeLanePresses = BooleanArray(4)
    val visualEffects = ArrayList<HitVisualEffect>()
    private var effectIdCounter = 1L

    val multiplier: Int
        get() = when {
            combo >= 40 -> 8
            combo >= 25 -> 4
            combo >= 10 -> 2
            else -> 1
        }

    val isFeverActive: Boolean
        get() = combo >= 25

    val progressFraction: Float
        get() = if (durationMs > 0L) (currentTimeMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f

    val checkpointsReached: Int
        get() {
            val p = progressFraction
            return when {
                p >= 0.99f -> 3
                p >= 0.66f -> 2
                p >= 0.33f -> 1
                else -> 0
            }
        }

    /**
     * Pulso musical normalizado (1.0 justo en el beat, decrece suavemente hacia 0.0)
     * para animar la pista, los carriles y los colores de las teclas al ritmo de la canción.
     */
    fun currentBeatPulse(): Float {
        val beatMs = (60_000f / bpm.coerceIn(70, 180).toFloat())
        val safeMs = currentTimeMs.coerceAtLeast(0L)
        val phase = (safeMs.toFloat() % beatMs) / beatMs
        return (1f - phase * 1.4f).coerceIn(0f, 1f)
    }

    /**
     * Avanza el reloj de la partida a `newTimeMs`.
     * Devuelve true si en este fotograma se produjo algún fallo automático por dejar pasar una tecla.
     */
    fun updateClock(newTimeMs: Long): Boolean {
        val prevTime = currentTimeMs
        currentTimeMs = max(-initialPreRollMs.coerceAtLeast(0L), newTimeMs)
        val dtMs = (currentTimeMs - prevTime).coerceAtLeast(0L)
        var anyMissedNow = false

        for (state in noteStates) {
            if (state.isMissed || state.holdCompleted) continue
            val note = state.note

            if (state.isHolding) {
                val progress = ((currentTimeMs - note.timeMs).toFloat() / max(1L, note.durationMs).toFloat()).coerceIn(0f, 1f)
                if (!activeLanePresses[note.lane]) {
                    // Soltó antes de completar el Hold
                    state.isHolding = false
                    state.holdProgress = progress
                    state.holdCompleted = true
                    if (progress >= 0.68f) {
                        holdsCompletedCount++
                        val bonus = (130 * multiplier * difficulty.scoreMultiplier).roundToInt()
                        score += bonus
                        addEffect(note.lane, HitJudgment.PERFECT, "+$bonus")
                    } else {
                        val partial = (45 * multiplier * difficulty.scoreMultiplier).roundToInt()
                        score += partial
                        addEffect(note.lane, HitJudgment.GOOD, "+$partial")
                    }
                } else {
                    state.holdProgress = progress
                    if (dtMs > 0L) {
                        score += max(1, ((dtMs * 0.28f) * multiplier * difficulty.scoreMultiplier).roundToInt())
                    }
                    if (currentTimeMs >= note.endTimeMs) {
                        state.isHolding = false
                        state.holdCompleted = true
                        state.holdProgress = 1f
                        holdsCompletedCount++
                        val finishBonus = (180 * multiplier * difficulty.scoreMultiplier).roundToInt()
                        score += finishBonus
                        addEffect(note.lane, HitJudgment.PERFECT, "¡SOSTENIDA +$finishBonus!")
                    }
                }
            } else if (!state.isHit) {
                if (currentTimeMs - note.timeMs > HitJudgment.MISS.windowMs) {
                    state.isMissed = true
                    registerJudgment(HitJudgment.MISS, note.lane, 0)
                    anyMissedNow = true
                }
            }
        }

        visualEffects.removeAll { currentTimeMs - it.createdTimeMs > 600L }
        return anyMissedNow
    }

    /**
     * Procesa el toque de un jugador sobre uno de los 4 carriles (0..3) en el instante actual.
     */
    fun onLaneDown(lane: Int, atTimeMs: Long = currentTimeMs): HitJudgment? {
        val safeLane = lane.coerceIn(0, 3)
        activeLanePresses[safeLane] = true
        currentTimeMs = max(currentTimeMs, atTimeMs)

        var bestCandidate: ActiveNoteState? = null
        var bestDelta = Long.MAX_VALUE

        for (state in noteStates) {
            if (state.isHit || state.isMissed) continue
            if (state.note.lane != safeLane) continue
            val delta = abs(atTimeMs - state.note.timeMs)
            if (delta <= HitJudgment.MISS.windowMs && delta < bestDelta) {
                bestDelta = delta
                bestCandidate = state
            }
        }

        val target = bestCandidate ?: return null
        val judgment = when {
            bestDelta <= HitJudgment.PERFECT.windowMs -> HitJudgment.PERFECT
            bestDelta <= HitJudgment.GREAT.windowMs -> HitJudgment.GREAT
            bestDelta <= HitJudgment.GOOD.windowMs -> HitJudgment.GOOD
            else -> HitJudgment.MISS
        }

        val kindBonusMultiplier = when (target.note.kind) {
            NoteKind.BEAT_PULSE -> 1.25f
            NoteKind.CHORD -> 1.15f
            NoteKind.HOLD -> 1.10f
            NoteKind.TAP -> 1.0f
        }

        if (judgment == HitJudgment.MISS) {
            target.isMissed = true
            registerJudgment(judgment, safeLane, 0)
        } else {
            target.isHit = true
            target.hitTimestampMs = atTimeMs
            if (target.note.isHold) {
                target.isHolding = true
                target.holdProgress = 0.05f
            } else {
                target.holdCompleted = true
            }
            val gained = (judgment.baseScore * multiplier * difficulty.scoreMultiplier * kindBonusMultiplier).roundToInt()
            registerJudgment(judgment, safeLane, gained)
        }

        return judgment
    }

    fun onLaneUp(lane: Int) {
        val safeLane = lane.coerceIn(0, 3)
        activeLanePresses[safeLane] = false
    }

    fun clearActiveInputs() {
        for (i in activeLanePresses.indices) {
            activeLanePresses[i] = false
        }
    }

    private fun registerJudgment(judgment: HitJudgment, lane: Int, gainedPoints: Int) {
        lastJudgment = judgment
        lastJudgmentTimeMs = currentTimeMs

        when (judgment) {
            HitJudgment.PERFECT -> {
                combo++
                perfectCount++
            }
            HitJudgment.GREAT -> {
                combo++
                greatCount++
            }
            HitJudgment.GOOD -> {
                combo++
                goodCount++
            }
            HitJudgment.MISS -> {
                combo = 0
                missCount++
            }
        }

        if (combo > maxCombo) {
            maxCombo = combo
        }

        if (gainedPoints > 0) {
            score += gainedPoints
        }

        val popup = if (gainedPoints > 0) "+$gainedPoints" else "FALLO"
        addEffect(lane, judgment, popup)
    }

    private fun addEffect(lane: Int, judgment: HitJudgment, text: String) {
        visualEffects.add(
            HitVisualEffect(
                id = effectIdCounter++,
                lane = lane,
                judgment = judgment,
                createdTimeMs = currentTimeMs,
                isFever = isFeverActive,
                scoreText = text
            )
        )
    }

    fun calculateAccuracyPercent(): Float {
        val totalResolved = perfectCount + greatCount + goodCount + missCount
        if (totalResolved == 0) return 100f
        val weightedSum =
            perfectCount * HitJudgment.PERFECT.accuracyWeight +
                greatCount * HitJudgment.GREAT.accuracyWeight +
                goodCount * HitJudgment.GOOD.accuracyWeight
        return ((weightedSum / totalResolved.toFloat()) * 100f).coerceIn(0f, 100f)
    }

    fun buildSessionResult(isNewRecord: Boolean = false): GameSessionResult {
        val accuracy = calculateAccuracyPercent()
        val stars = when {
            accuracy >= 92f -> 3
            accuracy >= 76f -> 2
            accuracy >= 50f || score > 0 -> 1
            else -> 0
        }
        val rank = when {
            accuracy >= 95f && missCount <= 2 -> "S"
            accuracy >= 85f -> "A"
            accuracy >= 70f -> "B"
            else -> "C"
        }
        val baseFragments = (stars * 25) + (difficulty.level * 12) + min(60, maxCombo / 2) + (holdsCompletedCount * 2)
        return GameSessionResult(
            songId = songId,
            songTitle = songTitle,
            artist = artist,
            difficulty = difficulty,
            finalScore = score,
            accuracyPercent = accuracy,
            stars = stars,
            rank = rank,
            maxCombo = maxCombo,
            perfectCount = perfectCount,
            greatCount = greatCount,
            goodCount = goodCount,
            missCount = missCount,
            holdsCompleted = holdsCompletedCount,
            earnedFragments = baseFragments,
            isNewRecord = isNewRecord
        )
    }
}
