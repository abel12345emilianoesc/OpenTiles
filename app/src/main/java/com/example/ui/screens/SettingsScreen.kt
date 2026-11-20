package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.AppUpdateChecker
import com.example.data.PlayerPreferences
import com.example.domain.AppVisualStyle
import com.example.ui.theme.LocalAppStyleTokens
import com.example.ui.theme.PrimaryViolet
import com.example.ui.theme.SecondaryCyan
import com.example.ui.theme.StarAmberGold
import com.example.viewmodel.AppUpdateUiState
import kotlinx.coroutines.delay
import kotlin.math.roundToLong

private const val METRONOME_INTERVAL_MS = 500L // 120 BPM exactos
private const val CALIBRATION_TARGET_TAPS = 8

/**
 * Calcula el `audioOffsetMs` recomendado a partir de los errores con signo medidos frente al metrónomo.
 * - Un error positivo (+110 ms) indica que el usuario escuchó el clic 110 ms tarde (p. ej. Bluetooth)
 *   y tocó 110 ms después del beat programado.
 * - Como `PlaybackClockSynchronizer` calcula `targetClock = mediaPlayerPos + audioOffsetMs`,
 *   compensamos retraso de salida con un offset negativo (`-mediaRecortada`).
 * - Descarta el valor mínimo y máximo cuando hay >= 5 toques para eliminar toques accidentales.
 */
fun computeRecommendedAudioOffsetMs(signedTapErrorsMs: List<Long>): Long {
    if (signedTapErrorsMs.isEmpty()) return 0L
    val sorted = signedTapErrorsMs.sorted()
    val trimmed = if (sorted.size >= 5) {
        sorted.subList(1, sorted.size - 1)
    } else {
        sorted
    }
    val avgSignedError = trimmed.average()
    return (-avgSignedError).roundToLong().coerceIn(-250L, 250L)
}

@Composable
fun SettingsScreen(
    preferences: PlayerPreferences,
    updateState: AppUpdateUiState = AppUpdateUiState(),
    onSelectVisualStyle: (AppVisualStyle) -> Unit,
    onUpdateBeatReactiveTiles: (Boolean) -> Unit,
    onUpdateShowLaneGuides: (Boolean) -> Unit,
    onUpdateOffsetMs: (Long) -> Unit,
    onUpdateMusicVolume: (Float) -> Unit,
    onUpdateSfxVolume: (Float) -> Unit,
    onUpdateVibration: (Boolean) -> Unit,
    onUpdateHighBloom: (Boolean) -> Unit,
    onPlayMetronomeClick: (Boolean) -> Unit,
    onCheckForUpdates: () -> Unit = {},
    onBackToLibrary: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler {
        onBackToLibrary()
    }

    val tokens = LocalAppStyleTokens.current
    var isCalibrating by remember { mutableStateOf(false) }
    var calibrationStartNanos by remember { mutableLongStateOf(0L) }
    var metronomeBeatIndex by remember { mutableIntStateOf(0) }
    var pulseActive by remember { mutableStateOf(false) }
    val signedTapErrorsMs = remember { mutableStateListOf<Long>() }
    var lastTapErrorMs by remember { mutableStateOf<Long?>(null) }
    var recommendedOffsetMs by remember { mutableStateOf<Long?>(null) }
    var calibrationSavedNotice by remember { mutableStateOf(false) }

    val pulseScale by animateFloatAsState(
        targetValue = if (pulseActive) 1.28f else 1.0f,
        animationSpec = tween(durationMillis = 90),
        label = "metronomePulse"
    )

    LaunchedEffect(isCalibrating) {
        if (!isCalibrating) {
            pulseActive = false
            return@LaunchedEffect
        }
        val startNanos = if (calibrationStartNanos > 0L) calibrationStartNanos else System.nanoTime()
        calibrationStartNanos = startNanos
        var beatIdx = 0
        while (isCalibrating) {
            val targetBeatNanos = startNanos + (beatIdx.toLong() * METRONOME_INTERVAL_MS * 1_000_000L)
            val waitMs = ((targetBeatNanos - System.nanoTime()) / 1_000_000L).coerceAtLeast(0L)
            if (waitMs > 0L) {
                delay(waitMs)
            }
            if (!isCalibrating) break
            metronomeBeatIndex = beatIdx
            onPlayMetronomeClick(beatIdx % 4 == 0)
            pulseActive = true
            delay(110L)
            pulseActive = false
            beatIdx++
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(tokens.canvasBackground)
            .testTag("settings_screen")
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 640.dp)
                .align(Alignment.TopCenter)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Column {
                Text(
                    text = "AJUSTES",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = tokens.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "Apariencia, teclas, latencia y volumen",
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // 1. Estilos Generales de la Aplicación (Resonancia Oscura, OLED Puro, Púrpura Synthwave, Claro Cristal)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = tokens.cardSurface,
                border = BorderStroke(1.dp, tokens.borderBright)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Palette,
                            contentDescription = null,
                            tint = tokens.accentSecondary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Estilo General de la Aplicación",
                            style = MaterialTheme.typography.titleLarge,
                            color = tokens.textPrimary
                        )
                    }

                    AppVisualStyle.entries.forEach { style ->
                        val isSelected = preferences.visualStyle == style
                        val swatchBg = Color(style.previewBackgroundHex or 0xFF000000L)
                        val swatchAccent = Color(style.previewPrimaryHex or 0xFF000000L)

                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .clickable { onSelectVisualStyle(style) }
                                .testTag("visual_style_${style.id}"),
                            shape = RoundedCornerShape(14.dp),
                            color = if (isSelected) tokens.cardSelectedSurface else tokens.panelSurface,
                            border = BorderStroke(
                                width = if (isSelected) 1.6.dp else 1.dp,
                                color = if (isSelected) tokens.accentSecondary else tokens.borderSubtle
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Muestra visual del estilo general (fondo + acento)
                                Box(
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clip(CircleShape)
                                        .background(swatchBg)
                                        .border(2.dp, swatchAccent, CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(14.dp)
                                            .clip(CircleShape)
                                            .background(swatchAccent)
                                    )
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = style.displayName,
                                        style = MaterialTheme.typography.titleLarge,
                                        color = tokens.textPrimary
                                    )
                                    Text(
                                        text = style.subtitle,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = tokens.textSecondary
                                    )
                                }

                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = "Estilo seleccionado",
                                        tint = tokens.accentSecondary
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 2. Variedad Visual de Teclas, Respuesta Táctil y Neon Bloom
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = tokens.cardSurface,
                border = BorderStroke(1.dp, tokens.borderBright)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Teclas y Efectos Visuales",
                        style = MaterialTheme.typography.titleLarge,
                        color = tokens.textPrimary
                    )

                    SettingsToggleRow(
                        title = "Colores reactivos al beat y estilo",
                        subtitle = "Las teclas cambian su tonalidad según el pulso BPM, la banda sonora y el género",
                        checked = preferences.beatReactiveTiles,
                        onCheckedChange = onUpdateBeatReactiveTiles,
                        testTag = "beat_reactive_switch"
                    )

                    SettingsToggleRow(
                        title = "Guías numéricas en carriles (1–4)",
                        subtitle = "Muestra indicadores discretos en la zona inferior de impacto",
                        checked = preferences.showLaneGuides,
                        onCheckedChange = onUpdateShowLaneGuides,
                        testTag = "lane_guides_switch"
                    )

                    SettingsToggleRow(
                        title = "Resplandor Espectral (Neon Bloom)",
                        subtitle = "Halos luminosos multicapa en teclas, modo fiebre y partículas",
                        checked = preferences.highBloomEnabled,
                        onCheckedChange = onUpdateHighBloom,
                        testTag = "bloom_switch"
                    )

                    SettingsToggleRow(
                        title = "Vibración Háptica al Tocar Teclas",
                        subtitle = "Impulso táctil corto en aciertos y alerta en fallos",
                        checked = preferences.vibrationEnabled,
                        onCheckedChange = onUpdateVibration,
                        testTag = "vibration_switch"
                    )
                }
            }

            // 3. Calibración Interactiva de Latencia (-250 ms a +250 ms) con Metrónomo de 8 Toques
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = tokens.cardSurface,
                border = BorderStroke(1.dp, tokens.borderBright)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Calibración de latencia",
                            style = MaterialTheme.typography.titleLarge,
                            color = tokens.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(end = 8.dp)
                        )
                        Text(
                            text = "${if (preferences.audioOffsetMs >= 0) "+" else ""}${preferences.audioOffsetMs} ms",
                            style = MaterialTheme.typography.titleMedium,
                            color = tokens.accentSecondary,
                            maxLines = 1,
                            modifier = Modifier.testTag("offset_value_text")
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = "Toca al ritmo del metrónomo 8 veces para ajustar el retraso de audio o Bluetooth.",
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.textSecondary
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Slider(
                        value = preferences.audioOffsetMs.toFloat(),
                        onValueChange = {
                            calibrationSavedNotice = false
                            onUpdateOffsetMs(it.roundToLong())
                        },
                        valueRange = -250f..250f,
                        colors = SliderDefaults.colors(
                            thumbColor = tokens.accentSecondary,
                            activeTrackColor = SecondaryCyan
                        ),
                        modifier = Modifier.testTag("offset_slider")
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Panel interactivo de metrónomo (120 BPM · 8 toques)
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        color = tokens.panelSurface,
                        border = BorderStroke(
                            width = 1.dp,
                            color = if (isCalibrating) tokens.accentSecondary else tokens.borderSubtle
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    val isAccentBeat = metronomeBeatIndex % 4 == 0
                                    Box(
                                        modifier = Modifier
                                            .size(20.dp)
                                            .scale(pulseScale)
                                            .clip(CircleShape)
                                            .background(
                                                when {
                                                    !isCalibrating -> tokens.textSecondary.copy(alpha = 0.25f)
                                                    pulseActive && isAccentBeat -> StarAmberGold
                                                    pulseActive -> tokens.accentSecondary
                                                    else -> PrimaryViolet.copy(alpha = 0.45f)
                                                }
                                            )
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column {
                                        Text(
                                            text = if (isCalibrating) {
                                                "Metrónomo activo (120 BPM)"
                                            } else {
                                                "Prueba rápida de audio (8 toques)"
                                            },
                                            style = MaterialTheme.typography.titleMedium,
                                            color = tokens.textPrimary
                                        )
                                        val detailText = when {
                                            isCalibrating && lastTapErrorMs != null -> {
                                                val err = lastTapErrorMs!!
                                                "Último toque: ${if (err >= 0) "+" else ""}$err ms · Faltan ${CALIBRATION_TARGET_TAPS - signedTapErrorsMs.size}"
                                            }
                                            isCalibrating -> "Escucha el clic y pulsa el botón inferior 8 veces al ritmo"
                                            recommendedOffsetMs != null -> {
                                                val rec = recommendedOffsetMs!!
                                                "Offset calculado: ${if (rec >= 0) "+" else ""}$rec ms"
                                            }
                                            calibrationSavedNotice -> "Calibración guardada correctamente"
                                            else -> "Calcula automáticamente tu offset ideal"
                                        }
                                        Text(
                                            text = detailText,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (recommendedOffsetMs != null || calibrationSavedNotice) {
                                                tokens.accentSecondary
                                            } else {
                                                tokens.textSecondary
                                            },
                                            modifier = Modifier.testTag("calibration_status_text")
                                        )
                                    }
                                }
                            }

                            // 8 indicadores visuales de progreso de toques
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                for (i in 0 until CALIBRATION_TARGET_TAPS) {
                                    val filled = i < signedTapErrorsMs.size
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(6.dp)
                                            .clip(RoundedCornerShape(3.dp))
                                            .background(
                                                if (filled) tokens.accentSecondary else tokens.borderSubtle
                                            )
                                    )
                                }
                            }

                            if (!isCalibrating) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Button(
                                        onClick = {
                                            signedTapErrorsMs.clear()
                                            lastTapErrorMs = null
                                            recommendedOffsetMs = null
                                            calibrationSavedNotice = false
                                            metronomeBeatIndex = 0
                                            calibrationStartNanos = System.nanoTime()
                                            isCalibrating = true
                                        },
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(48.dp)
                                            .testTag("start_calibration_test_button"),
                                        shape = RoundedCornerShape(12.dp),
                                        contentPadding = PaddingValues(horizontal = 12.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = tokens.actionPrimaryBg,
                                            contentColor = tokens.actionPrimaryText
                                        )
                                    ) {
                                        Icon(
                                            Icons.Default.PlayArrow,
                                            contentDescription = null,
                                            tint = tokens.actionPrimaryText,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = if (recommendedOffsetMs != null) "Repetir (8)" else "Iniciar (8)",
                                            color = tokens.actionPrimaryText,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }

                                    OutlinedButton(
                                        onClick = {
                                            signedTapErrorsMs.clear()
                                            lastTapErrorMs = null
                                            recommendedOffsetMs = null
                                            calibrationSavedNotice = false
                                            onUpdateOffsetMs(0L)
                                        },
                                        modifier = Modifier
                                            .height(48.dp)
                                            .testTag("reset_offset_button"),
                                        shape = RoundedCornerShape(12.dp),
                                        contentPadding = PaddingValues(horizontal = 14.dp),
                                        border = BorderStroke(1.dp, tokens.accentSecondary)
                                    ) {
                                        Text(
                                            text = "0 ms",
                                            color = tokens.textPrimary,
                                            maxLines = 1
                                        )
                                    }
                                }

                                if (recommendedOffsetMs != null) {
                                    val rec = recommendedOffsetMs!!
                                    Button(
                                        onClick = {
                                            onUpdateOffsetMs(rec)
                                            recommendedOffsetMs = null
                                            calibrationSavedNotice = true
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(48.dp)
                                            .testTag("save_calibration_button"),
                                        shape = RoundedCornerShape(12.dp),
                                        contentPadding = PaddingValues(horizontal = 14.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = tokens.actionSecondaryBg,
                                            contentColor = tokens.actionSecondaryText
                                        )
                                    ) {
                                        Icon(
                                            Icons.Default.Save,
                                            contentDescription = null,
                                            tint = tokens.actionSecondaryText,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "Guardar (${if (rec >= 0) "+" else ""}$rec ms)",
                                            color = tokens.actionSecondaryText,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            } else {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Button(
                                        onClick = {
                                            val nowNanos = System.nanoTime()
                                            val elapsedMs = ((nowNanos - calibrationStartNanos) / 1_000_000.0)
                                                .coerceAtLeast(0.0)
                                            val nearestBeatMs = (elapsedMs / METRONOME_INTERVAL_MS.toDouble())
                                                .roundToLong() * METRONOME_INTERVAL_MS
                                            val signedErrorMs = (elapsedMs - nearestBeatMs.toDouble())
                                                .roundToLong()
                                                .coerceIn(-240L, 240L)
                                            signedTapErrorsMs.add(signedErrorMs)
                                            lastTapErrorMs = signedErrorMs

                                            if (signedTapErrorsMs.size >= CALIBRATION_TARGET_TAPS) {
                                                isCalibrating = false
                                                recommendedOffsetMs = computeRecommendedAudioOffsetMs(signedTapErrorsMs.toList())
                                            }
                                        },
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(52.dp)
                                            .testTag("calibration_tap_button"),
                                        shape = RoundedCornerShape(12.dp),
                                        contentPadding = PaddingValues(horizontal = 12.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = tokens.actionSecondaryBg,
                                            contentColor = tokens.actionSecondaryText
                                        )
                                    ) {
                                        Icon(
                                            Icons.Default.TouchApp,
                                            contentDescription = null,
                                            tint = tokens.actionSecondaryText,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "Tocar (${signedTapErrorsMs.size}/$CALIBRATION_TARGET_TAPS)",
                                            color = tokens.actionSecondaryText,
                                            fontWeight = FontWeight.ExtraBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }

                                    OutlinedButton(
                                        onClick = {
                                            isCalibrating = false
                                            signedTapErrorsMs.clear()
                                            lastTapErrorMs = null
                                        },
                                        modifier = Modifier
                                            .height(52.dp)
                                            .testTag("stop_calibration_button"),
                                        shape = RoundedCornerShape(12.dp),
                                        border = BorderStroke(1.dp, tokens.borderBright)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Stop,
                                            contentDescription = "Detener metrónomo",
                                            tint = tokens.textPrimary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 4. Mezclador de Audio (Canción Original vs Efectos de Toque)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = tokens.cardSurface,
                border = BorderStroke(1.dp, tokens.borderBright)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.GraphicEq,
                            contentDescription = null,
                            tint = tokens.accentPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Balance Acústico de Partida",
                            style = MaterialTheme.typography.titleLarge,
                            color = tokens.textPrimary
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Volumen de música",
                            color = tokens.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(end = 8.dp)
                        )
                        Text("${(preferences.musicVolume * 100).toInt()}%", color = tokens.accentSecondary)
                    }
                    Slider(
                        value = preferences.musicVolume,
                        onValueChange = onUpdateMusicVolume,
                        valueRange = 0f..1f,
                        colors = SliderDefaults.colors(
                            thumbColor = tokens.accentPrimary,
                            activeTrackColor = PrimaryViolet
                        )
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Volumen de efectos",
                            color = tokens.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(end = 8.dp)
                        )
                        Text("${(preferences.sfxVolume * 100).toInt()}%", color = tokens.accentSecondary)
                    }
                    Slider(
                        value = preferences.sfxVolume,
                        onValueChange = onUpdateSfxVolume,
                        valueRange = 0f..1f,
                        colors = SliderDefaults.colors(
                            thumbColor = tokens.accentSecondary,
                            activeTrackColor = SecondaryCyan
                        )
                    )
                }
            }

            // 5. Actualizaciones desde GitHub Releases
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = tokens.cardSurface,
                border = BorderStroke(1.dp, tokens.borderBright)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier.weight(1f).padding(end = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.SystemUpdate,
                                contentDescription = null,
                                tint = tokens.accentSecondary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Actualizaciones",
                                style = MaterialTheme.typography.titleLarge,
                                color = tokens.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Text(
                            text = "v${updateState.currentVersion}",
                            style = MaterialTheme.typography.titleMedium,
                            color = tokens.accentSecondary,
                            maxLines = 1,
                            modifier = Modifier.testTag("settings_version_text")
                        )
                    }

                    Text(
                        text = "Repositorio: ${AppUpdateChecker.DEFAULT_GITHUB_REPO}",
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    val feedbackText = updateState.errorMessage ?: updateState.statusMessage
                    if (!feedbackText.isNullOrBlank()) {
                        Text(
                            text = feedbackText,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (updateState.errorMessage != null) tokens.accentTertiary else tokens.successAccent,
                            modifier = Modifier.testTag("update_status_text")
                        )
                    }

                    Button(
                        onClick = onCheckForUpdates,
                        enabled = !updateState.isChecking && !updateState.isDownloading,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("check_updates_button"),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = tokens.actionPrimaryBg,
                            contentColor = tokens.actionPrimaryText
                        )
                    ) {
                        if (updateState.isChecking) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = tokens.actionPrimaryText
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Buscando...",
                                color = tokens.actionPrimaryText,
                                maxLines = 1
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.SystemUpdate,
                                contentDescription = null,
                                tint = tokens.actionPrimaryText,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Buscar actualizaciones",
                                color = tokens.actionPrimaryText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun SettingsToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String
) {
    val tokens = LocalAppStyleTokens.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = tokens.textPrimary
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = tokens.textSecondary
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(checkedThumbColor = tokens.accentSecondary),
            modifier = Modifier.testTag(testTag)
        )
    }
}
