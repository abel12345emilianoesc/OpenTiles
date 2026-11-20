package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.audio.GameAudioController
import com.example.domain.HitJudgment
import com.example.domain.HitTrailStyle
import com.example.domain.NoteKind
import com.example.domain.RhythmNote
import com.example.domain.TileThemePalette
import com.example.ui.theme.LocalAppStyleTokens
import com.example.ui.theme.OnSurfaceVariantLight
import com.example.ui.theme.PrimaryViolet
import com.example.ui.theme.SecondaryCyanLight
import com.example.ui.theme.StarAmberGold
import com.example.ui.theme.SurfaceContainerHigh
import com.example.ui.theme.TertiaryMagenta
import com.example.viewmodel.ActiveNoteState
import com.example.viewmodel.GameEngine
import com.example.viewmodel.PlaybackClockSynchronizer
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

data class TileColorPair(
    val top: Color,
    val bottom: Color,
    val glow: Color
)

/**
 * Calcula la coordenada superior (`topY`) de una tecla para que su centro visual
 * coincida exactamente con `headY` (y por tanto con `hitLineY` cuando `dtHead == 0`).
 */
fun computeTileTopY(headY: Float, tileHeight: Float): Float = headY - (tileHeight * 0.5f)

/**
 * Escala, color y etiqueta visual del contador de combos central según la racha activa.
 */
data class ComboVisualTier(
    val tierIndex: Int,
    val baseScale: Float,
    val accentColor: Color,
    val alpha: Float,
    val badgeLabel: String
)

fun computeComboVisualTier(combo: Int): ComboVisualTier = when {
    combo <= 0 -> ComboVisualTier(
        tierIndex = 0,
        baseScale = 0.85f,
        accentColor = SecondaryCyanLight,
        alpha = 0f,
        badgeLabel = ""
    )
    combo < 10 -> ComboVisualTier(
        tierIndex = 1,
        baseScale = 1.00f + ((combo - 1).coerceAtLeast(0) * 0.01f),
        accentColor = SecondaryCyanLight,
        alpha = 0.28f,
        badgeLabel = "COMBO"
    )
    combo < 25 -> ComboVisualTier(
        tierIndex = 2,
        baseScale = 1.14f + ((combo - 10) * 0.006f),
        accentColor = PrimaryViolet,
        alpha = 0.35f,
        badgeLabel = "RITMO x2"
    )
    combo < 40 -> ComboVisualTier(
        tierIndex = 3,
        baseScale = 1.28f + ((combo - 25) * 0.006f),
        accentColor = StarAmberGold,
        alpha = 0.42f,
        badgeLabel = "FIEBRE x4"
    )
    else -> ComboVisualTier(
        tierIndex = 4,
        baseScale = (1.42f + min(0.16f, (combo - 40) * 0.004f)),
        accentColor = TertiaryMagenta,
        alpha = 0.48f,
        badgeLabel = "RESONANCIA x8"
    )
}

/**
 * Calcula el color dinámico de cada tecla combinando la tonalidad equipada en la Tienda,
 * el estilo cromático de la canción, la banda de frecuencia de la nota, el tipo de nota y el pulso del beat.
 */
fun computeBeatReactiveTileColors(
    note: RhythmNote,
    theme: TileThemePalette,
    songPrimary: Color,
    songSecondary: Color,
    beatPulse: Float,
    isFever: Boolean,
    beatReactiveEnabled: Boolean
): TileColorPair {
    if (isFever) {
        return TileColorPair(
            top = lerp(theme.feverColor, Color.White, beatPulse * 0.25f),
            bottom = theme.tileTopColor,
            glow = theme.feverColor
        )
    }

    if (!beatReactiveEnabled) {
        return TileColorPair(theme.tileTopColor, theme.tileBottomColor, theme.glowColor)
    }

    // Mezcla según la banda acústica (Grave/Medio-Grave/Melodía/Agudo) y el estilo de la canción
    val bandRatio = (note.frequencyBand.coerceIn(0, 3) / 3f)
    val styleMixTop = lerp(songPrimary, songSecondary, bandRatio)
    val styleMixBottom = lerp(songSecondary, theme.tileBottomColor, 0.55f)

    var top = lerp(theme.tileTopColor, styleMixTop, 0.45f)
    var bottom = lerp(theme.tileBottomColor, styleMixBottom, 0.40f)
    var glow = lerp(theme.glowColor, styleMixTop, 0.45f)

    when (note.kind) {
        NoteKind.HOLD -> {
            top = lerp(theme.sustainColor, songPrimary, 0.35f)
            bottom = lerp(theme.tileBottomColor, theme.sustainColor, 0.5f)
            glow = theme.sustainColor
        }
        NoteKind.BEAT_PULSE -> {
            // Teclas de pulso fuerte brillan hacia oro/acento con el beat
            top = lerp(top, StarAmberGold, 0.35f + beatPulse * 0.30f)
            bottom = lerp(bottom, theme.feverColor, 0.30f)
            glow = lerp(glow, StarAmberGold, 0.5f)
        }
        NoteKind.CHORD -> {
            top = lerp(top, Color.White, 0.18f + beatPulse * 0.14f)
            glow = lerp(glow, Color.White, 0.25f)
        }
        NoteKind.TAP -> {
            // Brillo sutil pulsante sincronizado al compás
            top = lerp(top, Color.White, beatPulse * 0.16f)
        }
    }
    return TileColorPair(top, bottom, glow)
}

@Composable
fun GameplayScreen(
    engine: GameEngine,
    themePalette: TileThemePalette,
    trailStyle: HitTrailStyle,
    audioOffsetMs: Long,
    highBloomEnabled: Boolean,
    beatReactiveTiles: Boolean,
    showLaneGuides: Boolean,
    audioController: GameAudioController,
    onNoteJudgment: (HitJudgment, Int, Boolean) -> Unit,
    onSessionFinished: () -> Unit,
    onRestartClick: () -> Unit,
    onQuitToLibrary: () -> Unit,
    modifier: Modifier = Modifier
) {
    val styleTokens = LocalAppStyleTokens.current
    var isPaused by remember(engine) { mutableStateOf(false) }
    var countdownValue by remember(engine) { mutableIntStateOf(3) }
    var frameTick by remember(engine) { mutableLongStateOf(0L) }
    val clockSync = remember(engine) { PlaybackClockSynchronizer(engine.currentTimeMs) }

    val songPrimaryColor = remember(engine.songId) { Color(engine.primaryStyleColorHex or 0xFF000000L) }
    val songSecondaryColor = remember(engine.songId) { Color(engine.secondaryStyleColorHex or 0xFF000000L) }

    BackHandler {
        if (!isPaused) {
            engine.clearActiveInputs()
            isPaused = true
            audioController.pauseSong()
        } else {
            onQuitToLibrary()
        }
    }

    LaunchedEffect(engine, isPaused, countdownValue) {
        if (!isPaused && countdownValue > 0) {
            delay(500L)
            countdownValue -= 1
        }
    }

    LaunchedEffect(engine, isPaused, countdownValue) {
        if (isPaused || countdownValue > 0) return@LaunchedEffect
        var startedAudioInLoop = engine.currentTimeMs >= 0L
        if (startedAudioInLoop) {
            if (engine.currentTimeMs > 0L) {
                audioController.seekToMsIfNeeded((engine.currentTimeMs - audioOffsetMs).coerceAtLeast(0L))
            }
            audioController.startPlaybackIfReady()
        }
        clockSync.resetForStartOrResume(engine.currentTimeMs, audioAlreadyRunning = startedAudioInLoop)

        while (!isPaused && countdownValue == 0) {
            var shouldStartAudioNow = false
            withFrameNanos { nowNanos ->
                val syncedClockMs = clockSync.stepFrame(
                    frameNanos = nowNanos,
                    mediaPlayerPosMs = if (startedAudioInLoop) audioController.getCurrentPositionMs() else null,
                    isMediaPlaying = startedAudioInLoop && audioController.isPlaying(),
                    audioOffsetMs = audioOffsetMs
                )

                if (!startedAudioInLoop && syncedClockMs >= 0L) {
                    shouldStartAudioNow = true
                }

                val missedInFrame = engine.updateClock(syncedClockMs)
                if (missedInFrame) {
                    onNoteJudgment(HitJudgment.MISS, 0, false)
                }
                frameTick = nowNanos
            }

            if (shouldStartAudioNow) {
                // Disparar MediaPlayer fuera del callback de Choreographer evita tirones en el primer fotograma
                audioController.startPlaybackIfReady()
                clockSync.onAudioPlaybackStarted()
                startedAudioInLoop = true
            }

            if (engine.currentTimeMs >= engine.durationMs + 450L) {
                onSessionFinished()
                break
            }
        }
    }

    val currentClockMs = remember(frameTick) { engine.currentTimeMs }
    val score = remember(frameTick) { engine.score }
    val combo = remember(frameTick) { engine.combo }
    val multiplier = remember(frameTick) { engine.multiplier }
    val isFever = remember(frameTick) { engine.isFeverActive }
    val accuracy = remember(frameTick) { engine.calculateAccuracyPercent() }
    val lastJudgment = remember(frameTick) { engine.lastJudgment }
    val lastJudgmentAgeMs = currentClockMs - engine.lastJudgmentTimeMs
    val progress = remember(frameTick) { engine.progressFraction }
    val crowns = remember(frameTick) { engine.checkpointsReached }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(styleTokens.highwayBackground)
            .testTag("gameplay_root")
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth()
                .widthIn(max = 600.dp)
                .align(Alignment.Center)
                .pointerInput(engine, isPaused, countdownValue) {
                    if (isPaused || countdownValue > 0) return@pointerInput
                    awaitEachGesture {
                        val activePointerLanes = HashMap<Long, Int>()
                        fun syncPressedLanes() {
                            for (l in 0..3) {
                                val stillHeld = activePointerLanes.values.any { it == l }
                                if (stillHeld) {
                                    engine.activeLanePresses[l] = true
                                } else {
                                    engine.onLaneUp(l)
                                }
                            }
                        }

                        while (true) {
                            val event = awaitPointerEvent()
                            val widthPx = size.width.toFloat().coerceAtLeast(1f)
                            val laneWidthPx = widthPx / 4f
                            for (change in event.changes) {
                                val pid = change.id.value
                                val rawLane = ((change.position.x / widthPx) * 4f).toInt().coerceIn(0, 3)
                                if (change.pressed && !change.previousPressed) {
                                    activePointerLanes[pid] = rawLane
                                    val judgment = engine.onLaneDown(rawLane, engine.currentTimeMs)
                                    if (judgment != null) {
                                        onNoteJudgment(judgment, rawLane, engine.isFeverActive)
                                    } else {
                                        onNoteJudgment(HitJudgment.GOOD, rawLane, false)
                                    }
                                    syncPressedLanes()
                                    frameTick = System.nanoTime()
                                } else if (change.pressed && change.previousPressed) {
                                    val prevLane = activePointerLanes[pid]
                                    if (prevLane != null && prevLane != rawLane) {
                                        // Tolerancia lateral generosa si este dedo sostiene activamente una tecla HOLD
                                        val isHoldingInPrevLane = engine.noteStates.any { it.isHolding && it.note.lane == prevLane }
                                        val prevCenterX = (prevLane + 0.5f) * laneWidthPx
                                        val maxDriftPx = if (isHoldingInPrevLane) laneWidthPx * 0.92f else laneWidthPx * 0.55f
                                        if (abs(change.position.x - prevCenterX) > maxDriftPx) {
                                            activePointerLanes[pid] = rawLane
                                            syncPressedLanes()
                                        }
                                    }
                                } else if (!change.pressed && change.previousPressed) {
                                    activePointerLanes.remove(pid)
                                    syncPressedLanes()
                                    frameTick = System.nanoTime()
                                }
                            }
                            if (event.changes.none { it.pressed }) {
                                activePointerLanes.clear()
                                syncPressedLanes()
                                break
                            }
                        }
                    }
                }
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("gameplay_canvas")
            ) {
                val _tick = frameTick
                drawGameplayScene(
                    engine = engine,
                    theme = themePalette,
                    songPrimary = songPrimaryColor,
                    songSecondary = songSecondaryColor,
                    trailStyle = trailStyle,
                    highBloom = highBloomEnabled,
                    beatReactiveEnabled = beatReactiveTiles
                )
            }

            // Receptores semánticos por carril (soportan TalkBack y pruebas UI)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(92.dp)
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 28.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                for (lane in 0..3) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .semantics {
                                contentDescription = "Carril ${lane + 1}"
                                onClick {
                                    if (!isPaused && countdownValue == 0) {
                                        val j = engine.onLaneDown(lane, engine.currentTimeMs)
                                        if (j != null) {
                                            onNoteJudgment(j, lane, engine.isFeverActive)
                                        }
                                        engine.onLaneUp(lane)
                                        frameTick = System.nanoTime()
                                    }
                                    true
                                }
                            }
                            .testTag("gameplay_lane_$lane"),
                        contentAlignment = Alignment.BottomCenter
                    ) {
                        if (showLaneGuides) {
                            Text(
                                text = "${lane + 1}",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.35f),
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                        }
                    }
                }
            }
        }

        // HUD Superior limpio (sin contador superpuesto sobre la pista)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .align(Alignment.TopCenter)
        ) {
            SegmentedSongProgressBar(
                progress = progress,
                crowns = crowns
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = engine.songTitle,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${engine.difficulty.displayName} · ${String.format(Locale.US, "%.0f%%", accuracy)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = SecondaryCyanLight,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                IconButton(
                    onClick = {
                        engine.clearActiveInputs()
                        isPaused = true
                        audioController.pauseSong()
                    },
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(SurfaceContainerHigh.copy(alpha = 0.85f))
                        .testTag("pause_game_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Pause,
                        contentDescription = "Pausar partida",
                        tint = Color.White
                    )
                }
            }

            // Puntaje y contador dinámico de combo únicamente en la zona superior
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = String.format(Locale.US, "%06d", score),
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White,
                    modifier = Modifier.testTag("gameplay_score_text")
                )

                TopHudComboPill(
                    combo = combo,
                    multiplier = multiplier,
                    isFever = isFever,
                    feverColor = themePalette.feverColor
                )
            }

            if (lastJudgment != null && lastJudgmentAgeMs in 0L..600L) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = lastJudgment.displayText,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color(lastJudgment.colorHex or 0xFF000000L),
                        modifier = Modifier.testTag("judgment_feedback_text")
                    )
                }
            }
        }

        if (!isPaused && countdownValue > 0) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = countdownValue.toString(),
                    style = MaterialTheme.typography.displayLarge,
                    color = SecondaryCyanLight,
                    modifier = Modifier.testTag("countdown_text")
                )
            }
        } else if (!isPaused && countdownValue == 0 && currentClockMs < 0L) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.Center)
                    .padding(bottom = 140.dp),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    shape = CircleShape,
                    color = SurfaceContainerHigh.copy(alpha = 0.78f),
                    border = BorderStroke(1.dp, SecondaryCyanLight.copy(alpha = 0.55f))
                ) {
                    Text(
                        text = "¡PREPÁRATE!",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = SecondaryCyanLight,
                        modifier = Modifier
                            .padding(horizontal = 18.dp, vertical = 6.dp)
                            .testTag("preroll_indicator")
                    )
                }
            }
        }

        if (isPaused) {
            PauseOverlayModal(
                songTitle = engine.songTitle,
                difficultyName = engine.difficulty.displayName,
                onResume = {
                    isPaused = false
                    countdownValue = 3
                },
                onRestart = {
                    isPaused = false
                    onRestartClick()
                },
                onQuit = onQuitToLibrary
            )
        }
    }
}

@Composable
private fun TopHudComboPill(
    combo: Int,
    multiplier: Int,
    isFever: Boolean,
    feverColor: Color
) {
    val tier = remember(combo) { computeComboVisualTier(combo) }
    val targetAccent = when {
        isFever -> feverColor
        combo > 0 -> tier.accentColor
        else -> SecondaryCyanLight
    }
    val animatedAccent by animateColorAsState(
        targetValue = targetAccent,
        animationSpec = tween(durationMillis = 220),
        label = "topComboAccent"
    )
    val hitPop = remember { Animatable(1f) }
    LaunchedEffect(combo) {
        if (combo > 0) {
            hitPop.snapTo(1.14f)
            hitPop.animateTo(
                targetValue = 1f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMedium
                )
            )
        }
    }
    val tierScale = 1f + (tier.tierIndex * 0.035f)

    Surface(
        modifier = Modifier.graphicsLayer {
            val s = tierScale * hitPop.value
            scaleX = s
            scaleY = s
        },
        shape = CircleShape,
        color = animatedAccent.copy(alpha = if (combo > 0) 0.24f else 0.14f),
        border = BorderStroke(1.dp, animatedAccent.copy(alpha = 0.85f))
    ) {
        Text(
            text = if (isFever) "FIEBRE x$multiplier · $combo COMBO" else "x$multiplier · $combo COMBO",
            style = MaterialTheme.typography.labelLarge,
            color = Color.White,
            maxLines = 1,
            modifier = Modifier
                .padding(horizontal = 14.dp, vertical = 4.dp)
                .testTag("gameplay_combo_pill")
        )
    }
}

@Composable
private fun SegmentedSongProgressBar(
    progress: Float,
    crowns: Int
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .background(Color.White.copy(alpha = 0.12f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(progress.coerceIn(0f, 1f))
                    .background(
                        Brush.horizontalGradient(
                            listOf(PrimaryViolet, SecondaryCyanLight, StarAmberGold)
                        )
                    )
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Spacer(modifier = Modifier.width(1.dp))
            for (c in 1..3) {
                val achieved = crowns >= c
                Icon(
                    imageVector = Icons.Default.WorkspacePremium,
                    contentDescription = "Corona $c",
                    tint = if (achieved) StarAmberGold else OnSurfaceVariantLight.copy(alpha = 0.35f),
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

private fun DrawScope.drawGameplayScene(
    engine: GameEngine,
    theme: TileThemePalette,
    songPrimary: Color,
    songSecondary: Color,
    trailStyle: HitTrailStyle,
    highBloom: Boolean,
    beatReactiveEnabled: Boolean
) {
    val w = size.width
    val h = size.height
    val laneWidth = w / 4f
    val gutterPx = 4.dp.toPx()
    val hitLineY = h - 92.dp.toPx()
    val fallDurationMs = engine.difficulty.fallDurationMs.toFloat().coerceAtLeast(450f)
    val nowMs = engine.currentTimeMs
    val beatPulse = if (beatReactiveEnabled) engine.currentBeatPulse() else 0f

    // 1. Halo ambiental reactivo al pulso del beat y modo fiebre
    val ambientColor = when {
        engine.isFeverActive -> theme.feverColor
        beatReactiveEnabled -> lerp(songPrimary, theme.tileBottomColor, 0.5f)
        else -> theme.tileBottomColor
    }
    val baseAlpha = if (engine.isFeverActive) 0.25f else 0.12f
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                ambientColor.copy(alpha = baseAlpha + beatPulse * 0.12f),
                Color.Transparent
            ),
            center = Offset(w * 0.5f, hitLineY * 0.65f),
            radius = w * (0.80f + beatPulse * 0.08f)
        ),
        center = Offset(w * 0.5f, hitLineY * 0.65f),
        radius = w * (0.80f + beatPulse * 0.08f)
    )

    // 2. Carriles verticales con líneas guía de compás en el fondo y respuesta al toque
    for (lane in 0..3) {
        val left = lane * laneWidth + (gutterPx * 0.5f)
        val right = (lane + 1) * laneWidth - (gutterPx * 0.5f)
        val colWidth = right - left

        if (engine.activeLanePresses[lane]) {
            val pressColor = if (beatReactiveEnabled) {
                lerp(theme.tileTopColor, songPrimary, 0.35f)
            } else {
                theme.tileTopColor
            }
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.Transparent,
                        theme.glowColor.copy(alpha = 0.16f),
                        pressColor.copy(alpha = 0.38f)
                    ),
                    startY = hitLineY * 0.25f,
                    endY = h
                ),
                topLeft = Offset(left, 0f),
                size = Size(colWidth, h)
            )
        }

        if (lane > 0) {
            drawLine(
                color = Color.White.copy(alpha = 0.07f + beatPulse * 0.04f),
                start = Offset(lane * laneWidth, 0f),
                end = Offset(lane * laneWidth, h),
                strokeWidth = 1.dp.toPx()
            )
        }
    }

    // 3. Línea de impacto luminosa reactiva al BPM
    val hitLineThickness = (2.8f + beatPulse * 1.4f).dp.toPx()
    drawLine(
        brush = Brush.horizontalGradient(
            listOf(
                theme.tileBottomColor,
                lerp(theme.glowColor, Color.White, beatPulse * 0.4f),
                theme.tileTopColor,
                theme.tileBottomColor
            )
        ),
        start = Offset(0f, hitLineY),
        end = Offset(w, hitLineY),
        strokeWidth = hitLineThickness
    )

    for (lane in 0..3) {
        val centerX = (lane + 0.5f) * laneWidth
        val isPressed = engine.activeLanePresses[lane]
        drawRoundRect(
            color = if (isPressed) theme.glowColor.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.08f + beatPulse * 0.05f),
            topLeft = Offset(lane * laneWidth + 10.dp.toPx(), hitLineY - 8.dp.toPx()),
            size = Size(laneWidth - 20.dp.toPx(), 16.dp.toPx()),
            cornerRadius = CornerRadius(8.dp.toPx(), 8.dp.toPx())
        )
        if (isPressed) {
            drawCircle(
                color = Color.White,
                radius = 5.dp.toPx(),
                center = Offset(centerX, hitLineY)
            )
        }
    }

    // 4. Recolectar notas visibles y dibujar puentes visuales para acordes simultáneos
    val visibleNotes = ArrayList<ActiveNoteState>()
    for (state in engine.noteStates) {
        if (state.holdCompleted || state.isMissed) continue
        val dtHead = state.note.timeMs - nowMs
        if (dtHead > fallDurationMs * 1.15f) continue
        val dtTail = state.note.endTimeMs - nowMs
        if (dtTail < -320L) continue
        visibleNotes.add(state)
    }

    // Dibujar enlace horizontal entre notas de un mismo acorde alineado al centro exacto de impacto (headY)
    for (i in 0 until visibleNotes.size - 1) {
        val a = visibleNotes[i]
        val b = visibleNotes[i + 1]
        if (a.note.isChord && b.note.isChord && abs(a.note.timeMs - b.note.timeMs) <= 20L && !a.isHit && !b.isHit) {
            val yPos = hitLineY * (1f - ((a.note.timeMs - nowMs).toFloat() / fallDurationMs))
            val x1 = (a.note.lane + 0.5f) * laneWidth
            val x2 = (b.note.lane + 0.5f) * laneWidth
            drawLine(
                color = Color.White.copy(alpha = 0.52f),
                start = Offset(x1, yPos),
                end = Offset(x2, yPos),
                strokeWidth = 2.5.dp.toPx()
            )
        }
    }

    // 5. Dibujar cada tecla según su variedad (TAP, HOLD, CHORD, BEAT_PULSE) y color dinámico
    val standardTileHeight = 76.dp.toPx()
    val cornerRadius = CornerRadius(10.dp.toPx(), 10.dp.toPx())

    for (state in visibleNotes) {
        val note = state.note
        val dtHead = note.timeMs - nowMs
        val dtTail = note.endTimeMs - nowMs

        val laneLeft = note.lane * laneWidth + 6.dp.toPx()
        val tileWidth = laneWidth - 12.dp.toPx()
        val centerX = laneLeft + tileWidth * 0.5f

        val colors = computeBeatReactiveTileColors(
            note = note,
            theme = theme,
            songPrimary = songPrimary,
            songSecondary = songSecondary,
            beatPulse = beatPulse,
            isFever = engine.isFeverActive,
            beatReactiveEnabled = beatReactiveEnabled
        )

        val headY = if (state.isHolding) {
            hitLineY
        } else {
            hitLineY * (1f - (dtHead.toFloat() / fallDurationMs))
        }

        // Renderizado de tecla de MANTENER PULSADO (HOLD)
        if (note.isHold) {
            val tailY = hitLineY * (1f - (dtTail.toFloat() / fallDurationMs))
            val topTailY = min(tailY, headY)
            val bottomTailY = max(tailY, headY)
            val tailHeight = (bottomTailY - topTailY).coerceAtLeast(10f)

            // Cuerpo translúcido de la estela
            drawRoundRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        colors.top.copy(alpha = 0.24f),
                        colors.bottom.copy(alpha = if (state.isHolding) 0.78f else 0.48f)
                    ),
                    startY = topTailY,
                    endY = bottomTailY
                ),
                topLeft = Offset(laneLeft + tileWidth * 0.16f, topTailY),
                size = Size(tileWidth * 0.68f, tailHeight),
                cornerRadius = cornerRadius
            )

            // Espina de onda animada que vibra más rápido mientras el jugador mantiene pulsado
            val wavePath = Path()
            val steps = 16
            for (s in 0..steps) {
                val frac = s.toFloat() / steps.toFloat()
                val yPos = topTailY + tailHeight * frac
                val phase = (nowMs * (if (state.isHolding) 0.024f else 0.014f)) + (frac * 9f)
                val amp = if (state.isHolding) tileWidth * 0.22f else tileWidth * 0.10f
                val xPos = centerX + sin(phase) * amp
                if (s == 0) wavePath.moveTo(xPos, yPos) else wavePath.lineTo(xPos, yPos)
            }
            drawPath(
                path = wavePath,
                color = Color.White.copy(alpha = if (state.isHolding) 0.95f else 0.65f),
                style = Stroke(width = if (state.isHolding) 3.dp.toPx() else 2.2.dp.toPx())
            )

            // Relleno progresivo luminoso mientras el jugador mantiene pulsada la tecla
            if (state.isHolding) {
                val activeHeight = tailHeight * state.holdProgress.coerceIn(0.05f, 1f)
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(colors.glow.copy(alpha = 0.85f), Color.White.copy(alpha = 0.90f)),
                        startY = bottomTailY - activeHeight,
                        endY = bottomTailY
                    ),
                    topLeft = Offset(laneLeft + tileWidth * 0.22f, bottomTailY - activeHeight),
                    size = Size(tileWidth * 0.56f, activeHeight),
                    cornerRadius = cornerRadius
                )
            }

            // Tapa final luminosa donde se suelta la tecla sostenida
            drawCircle(
                color = if (state.isHolding) Color.White else colors.glow,
                radius = 7.dp.toPx(),
                center = Offset(centerX, topTailY + 6.dp.toPx())
            )
            if (state.isHolding) {
                drawCircle(
                    color = colors.glow.copy(alpha = 0.75f),
                    radius = (18.dp.toPx()) + (beatPulse * 5.dp.toPx()),
                    center = Offset(centerX, hitLineY),
                    style = Stroke(width = 2.6.dp.toPx())
                )
            }
        }

        if (!state.isHit || state.isHolding) {
            val tileTopY = computeTileTopY(headY, standardTileHeight)
            val tileBottomY = tileTopY + standardTileHeight

            if (highBloom) {
                drawRoundRect(
                    color = colors.glow.copy(alpha = 0.26f + beatPulse * 0.10f),
                    topLeft = Offset(laneLeft - 3.dp.toPx(), tileTopY - 3.dp.toPx()),
                    size = Size(tileWidth + 6.dp.toPx(), standardTileHeight + 6.dp.toPx()),
                    cornerRadius = CornerRadius(13.dp.toPx(), 13.dp.toPx())
                )
            }

            drawRoundRect(
                brush = Brush.verticalGradient(
                    colors = listOf(colors.top, colors.bottom),
                    startY = tileTopY,
                    endY = tileBottomY
                ),
                topLeft = Offset(laneLeft, tileTopY),
                size = Size(tileWidth, standardTileHeight),
                cornerRadius = cornerRadius
            )

            // Franja central de impacto alineada exactamente a `dtHead == 0` (`midY == headY`)
            val midY = tileTopY + standardTileHeight * 0.5f
            val stripeHeight = 6.dp.toPx()
            drawRect(
                color = Color.White.copy(alpha = if (state.isHolding) 0.42f else 0.24f),
                topLeft = Offset(laneLeft + 3.dp.toPx(), midY - stripeHeight * 0.5f),
                size = Size(tileWidth - 6.dp.toPx(), stripeHeight)
            )
            drawLine(
                color = Color.White.copy(alpha = 0.90f),
                start = Offset(laneLeft + 6.dp.toPx(), midY),
                end = Offset(laneLeft + tileWidth - 6.dp.toPx(), midY),
                strokeWidth = 1.8.dp.toPx()
            )

            // Borde brillante
            drawRoundRect(
                color = Color.White.copy(alpha = if (state.isHolding) 0.95f else 0.58f),
                topLeft = Offset(laneLeft, tileTopY),
                size = Size(tileWidth, standardTileHeight),
                cornerRadius = cornerRadius,
                style = Stroke(width = if (state.isHolding) 2.4.dp.toPx() else 1.5.dp.toPx())
            )

            // Símbolos distintivos centrados en la zona de impacto
            when (note.kind) {
                NoteKind.HOLD -> {
                    drawCircle(
                        color = Color.White.copy(alpha = 0.92f),
                        radius = 8.dp.toPx(),
                        center = Offset(centerX, midY),
                        style = Stroke(width = 2.dp.toPx())
                    )
                    drawLine(
                        color = Color.White,
                        start = Offset(centerX, midY - 14.dp.toPx()),
                        end = Offset(centerX, midY + 4.dp.toPx()),
                        strokeWidth = 2.4.dp.toPx()
                    )
                }
                NoteKind.CHORD -> {
                    drawCircle(
                        color = Color.White.copy(alpha = 0.92f),
                        radius = 7.dp.toPx(),
                        center = Offset(centerX, midY),
                        style = Stroke(width = 2.dp.toPx())
                    )
                    drawCircle(
                        color = Color.White,
                        radius = 3.dp.toPx(),
                        center = Offset(centerX, midY)
                    )
                }
                NoteKind.BEAT_PULSE -> {
                    val r = (9.dp.toPx()) * (1f + beatPulse * 0.2f)
                    val diamond = Path().apply {
                        moveTo(centerX, midY - r)
                        lineTo(centerX + r, midY)
                        lineTo(centerX, midY + r)
                        lineTo(centerX - r, midY)
                        close()
                    }
                    drawPath(
                        path = diamond,
                        color = Color.White.copy(alpha = 0.95f),
                        style = Stroke(width = 2.dp.toPx())
                    )
                }
                NoteKind.TAP -> {
                    // Punto central limpio sobre la franja de impacto
                    drawCircle(
                        color = Color.White.copy(alpha = 0.95f),
                        radius = 3.5.dp.toPx(),
                        center = Offset(centerX, midY)
                    )
                }
            }
        }
    }

    // 6. Efectos de partículas y ondas al impactar o fallar teclas
    for (fx in engine.visualEffects) {
        val ageMs = (nowMs - fx.createdTimeMs).coerceAtLeast(0L)
        val t = (ageMs / 520f).coerceIn(0f, 1f)
        val alpha = (1f - t).coerceIn(0f, 1f)
        val fxX = (fx.lane + 0.5f) * laneWidth
        val fxColor = when {
            fx.judgment == HitJudgment.MISS -> TertiaryMagenta
            fx.isFever -> theme.feverColor
            beatReactiveEnabled -> lerp(theme.glowColor, songPrimary, 0.35f)
            else -> theme.glowColor
        }

        if (fx.judgment == HitJudgment.MISS) {
            drawRect(
                color = TertiaryMagenta.copy(alpha = alpha * 0.25f),
                topLeft = Offset(fx.lane * laneWidth, 0f),
                size = Size(laneWidth, h)
            )
        }

        when (trailStyle) {
            HitTrailStyle.ONDAS_PRISMA -> {
                val radius = (18.dp.toPx()) + (t * laneWidth * 0.72f)
                drawCircle(
                    color = fxColor.copy(alpha = alpha * 0.85f),
                    radius = radius,
                    center = Offset(fxX, hitLineY),
                    style = Stroke(width = 3.dp.toPx())
                )
                drawCircle(
                    color = Color.White.copy(alpha = alpha * 0.6f),
                    radius = radius * 0.55f,
                    center = Offset(fxX, hitLineY)
                )
            }
            HitTrailStyle.ESTALLIDO_ESTELAR -> {
                val particleCount = 8
                val maxDist = laneWidth * 0.85f * t
                for (p in 0 until particleCount) {
                    val angle = (p.toFloat() / particleCount) * (2f * PI.toFloat())
                    val px = fxX + cos(angle) * maxDist
                    val py = hitLineY + sin(angle) * maxDist
                    drawCircle(
                        color = if (p % 2 == 0) Color.White.copy(alpha = alpha) else fxColor.copy(alpha = alpha),
                        radius = (5.dp.toPx()) * (1f - t * 0.5f),
                        center = Offset(px, py)
                    )
                }
            }
            HitTrailStyle.PULSO_HEXAGONAL -> {
                val beamHeight = 180.dp.toPx() * (1f - t * 0.4f)
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color.Transparent, fxColor.copy(alpha = alpha * 0.70f)),
                        startY = hitLineY - beamHeight,
                        endY = hitLineY
                    ),
                    topLeft = Offset(fx.lane * laneWidth + 8.dp.toPx(), hitLineY - beamHeight),
                    size = Size(laneWidth - 16.dp.toPx(), beamHeight)
                )
            }
            HitTrailStyle.VORTICE_ESPIRAL -> {
                val arms = 3
                val maxR = laneWidth * 0.72f * (0.3f + t * 0.9f)
                val baseAngle = t * 5.2f
                for (a in 0 until arms) {
                    val armOffset = (a.toFloat() / arms) * (2f * PI.toFloat())
                    val spiral = Path()
                    for (step in 0..8) {
                        val frac = step / 8f
                        val ang = baseAngle + armOffset + frac * 1.9f
                        val rad = maxR * frac
                        val px = fxX + cos(ang) * rad
                        val py = hitLineY + sin(ang) * rad * 0.65f
                        if (step == 0) spiral.moveTo(px, py) else spiral.lineTo(px, py)
                    }
                    drawPath(
                        path = spiral,
                        color = if (a == 0) Color.White.copy(alpha = alpha * 0.85f) else fxColor.copy(alpha = alpha * 0.9f),
                        style = Stroke(width = 2.6.dp.toPx())
                    )
                }
            }
            HitTrailStyle.LLUVIA_CHISPAS -> {
                val sparkCount = 10
                for (s in 0 until sparkCount) {
                    val spread = ((s - sparkCount / 2f) / (sparkCount / 2f))
                    val vx = spread * laneWidth * 0.75f
                    val vy = -(95.dp.toPx() + (s % 3) * 32.dp.toPx())
                    val gravity = 210.dp.toPx()
                    val sx = fxX + vx * t
                    val sy = hitLineY + vy * t + 0.5f * gravity * t * t
                    val prevT = (t - 0.08f).coerceAtLeast(0f)
                    val psx = fxX + vx * prevT
                    val psy = hitLineY + vy * prevT + 0.5f * gravity * prevT * prevT
                    drawLine(
                        color = if (s % 2 == 0) Color.White.copy(alpha = alpha) else fxColor.copy(alpha = alpha),
                        start = Offset(psx, psy),
                        end = Offset(sx, sy),
                        strokeWidth = 2.4.dp.toPx()
                    )
                }
            }
            HitTrailStyle.ONDA_SISMICA -> {
                val halfSpan = (laneWidth * 0.35f) + (t * laneWidth * 1.15f)
                val rise = t * 44.dp.toPx()
                drawLine(
                    color = fxColor.copy(alpha = alpha * 0.9f),
                    start = Offset((fxX - halfSpan).coerceAtLeast(0f), hitLineY - rise),
                    end = Offset((fxX + halfSpan).coerceAtMost(w), hitLineY - rise),
                    strokeWidth = (4.dp.toPx()) * (1f - t * 0.5f)
                )
                drawLine(
                    color = Color.White.copy(alpha = alpha * 0.75f),
                    start = Offset((fxX - halfSpan * 0.7f).coerceAtLeast(0f), hitLineY - rise * 0.45f),
                    end = Offset((fxX + halfSpan * 0.7f).coerceAtMost(w), hitLineY - rise * 0.45f),
                    strokeWidth = 2.dp.toPx()
                )
                drawCircle(
                    color = fxColor.copy(alpha = alpha * 0.55f),
                    radius = (12.dp.toPx()) + t * laneWidth * 0.55f,
                    center = Offset(fxX, hitLineY),
                    style = Stroke(width = 2.dp.toPx())
                )
            }
        }
    }
}

@Composable
private fun PauseOverlayModal(
    songTitle: String,
    difficultyName: String,
    onResume: () -> Unit,
    onRestart: () -> Unit,
    onQuit: () -> Unit
) {
    val tokens = LocalAppStyleTokens.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.80f))
            .windowInsetsPadding(WindowInsets.navigationBars),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
                .widthIn(max = 380.dp),
            shape = RoundedCornerShape(22.dp),
            color = tokens.cardSurface,
            border = BorderStroke(1.dp, tokens.borderBright)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "PAUSA",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = tokens.textPrimary,
                    maxLines = 1
                )
                Text(
                    text = "$songTitle · $difficultyName",
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.textSecondary,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(6.dp))

                Button(
                    onClick = onResume,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("resume_game_button"),
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = tokens.actionPrimaryBg,
                        contentColor = tokens.actionPrimaryText
                    )
                ) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = tokens.actionPrimaryText
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Reanudar",
                        color = tokens.actionPrimaryText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                OutlinedButton(
                    onClick = onRestart,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("restart_game_button"),
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp),
                    border = BorderStroke(1.dp, tokens.accentSecondary)
                ) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = null,
                        tint = tokens.accentSecondary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Reiniciar",
                        color = tokens.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                OutlinedButton(
                    onClick = onQuit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("quit_game_button"),
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp),
                    border = BorderStroke(1.dp, tokens.accentTertiary.copy(alpha = 0.75f))
                ) {
                    Text(
                        text = "Salir a Biblioteca",
                        color = tokens.accentTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
