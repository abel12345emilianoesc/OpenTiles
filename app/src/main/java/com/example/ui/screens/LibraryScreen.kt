package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.ChartEntity
import com.example.data.ScoreRecordEntity
import com.example.data.SongEntity
import com.example.domain.DifficultyLevel
import com.example.ui.theme.LocalAppStyleTokens
import com.example.ui.theme.PrimaryViolet
import com.example.ui.theme.SecondaryCyan
import com.example.ui.theme.StarAmberGold
import com.example.ui.theme.TertiaryMagenta
import com.example.viewmodel.ImportDialogState
import com.example.viewmodel.LibraryFilter
import com.example.viewmodel.MainUiState
import java.util.Locale

@Composable
fun LibraryScreen(
    uiState: MainUiState,
    onSelectFilter: (LibraryFilter) -> Unit,
    onSelectSong: (String) -> Unit,
    onSelectDifficulty: (DifficultyLevel) -> Unit,
    onTogglePreview: (SongEntity) -> Unit,
    onStartGame: () -> Unit,
    onImportAudioClick: () -> Unit,
    onDeleteImportedSong: (String) -> Unit,
    onDismissImportDialog: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = LocalAppStyleTokens.current
    val selectedSong = uiState.selectedSong
    val selectedDiff = uiState.selectedDifficulty
    val selectedChart = selectedSong?.let {
        uiState.chartsBySongAndDiff[it.id to selectedDiff.level]
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(tokens.canvasBackground)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 680.dp)
                .align(Alignment.TopCenter)
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(12.dp))

            LibraryHeader(
                fragments = uiState.preferences.resonanceFragments,
                totalStars = uiState.totalStarsEarned,
                onImportClick = onImportAudioClick
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Filtros limpios de biblioteca
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LibraryFilter.entries.forEach { filter ->
                    val isSelected = uiState.libraryFilter == filter
                    val count = when (filter) {
                        LibraryFilter.ALL -> uiState.songs.size
                        LibraryFilter.BUILTIN -> uiState.songs.count { !it.isImported }
                        LibraryFilter.IMPORTED -> uiState.songs.count { it.isImported }
                    }
                    Surface(
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable { onSelectFilter(filter) }
                            .testTag("library_filter_${filter.id}"),
                        shape = CircleShape,
                        color = if (isSelected) tokens.actionPrimaryBg.copy(alpha = 0.22f) else tokens.cardSurface,
                        border = BorderStroke(
                            width = 1.dp,
                            color = if (isSelected) tokens.accentPrimary else tokens.borderSubtle
                        )
                    ) {
                        Text(
                            text = "${filter.label} ($count)",
                            style = MaterialTheme.typography.labelLarge,
                            color = if (isSelected) tokens.textPrimary else tokens.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (uiState.filteredSongs.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(horizontal = 24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.LibraryMusic,
                            contentDescription = null,
                            tint = tokens.accentSecondary,
                            modifier = Modifier.size(44.dp)
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "Aún no tienes canciones importadas",
                            style = MaterialTheme.typography.titleLarge,
                            color = tokens.textPrimary,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Toca «Importar audio local» para añadir archivos MP3, M4A, WAV u OGG desde tu dispositivo.",
                            style = MaterialTheme.typography.bodySmall,
                            color = tokens.textSecondary,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .testTag("songs_list"),
                    contentPadding = PaddingValues(bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(uiState.filteredSongs, key = { it.id }) { song ->
                        val isSelected = song.id == selectedSong?.id
                        val isPreviewing = uiState.isPreviewPlayingSongId == song.id
                        val scoreForDiff = uiState.scoresBySongAndDiff[song.id to selectedDiff.level]
                        val chartForDiff = uiState.chartsBySongAndDiff[song.id to selectedDiff.level]

                        SongSelectionCard(
                            song = song,
                            isSelected = isSelected,
                            isPreviewing = isPreviewing,
                            chart = chartForDiff,
                            scoreRecord = scoreForDiff,
                            onSelect = { onSelectSong(song.id) },
                            onTogglePreview = { onTogglePreview(song) },
                            onDelete = if (song.isImported) {
                                { onDeleteImportedSong(song.id) }
                            } else null
                        )
                    }
                }
            }

            if (selectedSong != null) {
                DifficultyAndLaunchPanel(
                    selectedSong = selectedSong,
                    selectedDifficulty = selectedDiff,
                    chart = selectedChart,
                    onSelectDifficulty = onSelectDifficulty,
                    onPlayClick = onStartGame
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
        }

        AnimatedVisibility(
            visible = uiState.importDialog != null,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            uiState.importDialog?.let { dlg ->
                ImportSongProgressModal(
                    dialogState = dlg,
                    onDismiss = onDismissImportDialog
                )
            }
        }
    }
}

@Composable
private fun LibraryHeader(
    fragments: Int,
    totalStars: Int,
    onImportClick: () -> Unit
) {
    val tokens = LocalAppStyleTokens.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "CUSTOM TILE",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.ExtraBold,
                color = tokens.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = CircleShape,
                    color = tokens.cardSurface,
                    border = BorderStroke(1.dp, tokens.borderSubtle)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = "Fragmentos",
                            tint = tokens.accentSecondary,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = fragments.toString(),
                            style = MaterialTheme.typography.labelLarge,
                            color = tokens.textPrimary,
                            maxLines = 1,
                            modifier = Modifier.testTag("fragments_balance_text")
                        )
                    }
                }

                Surface(
                    shape = CircleShape,
                    color = tokens.cardSurface,
                    border = BorderStroke(1.dp, tokens.borderSubtle)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Star,
                            contentDescription = "Estrellas",
                            tint = tokens.warningAccent,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = totalStars.toString(),
                            style = MaterialTheme.typography.labelLarge,
                            color = tokens.textPrimary,
                            maxLines = 1
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Botón limpio para importar archivos de audio locales (MP3, M4A, WAV, OGG)
        OutlinedButton(
            onClick = onImportClick,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .testTag("import_song_button"),
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, tokens.borderBright),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = tokens.cardSurface,
                contentColor = tokens.textPrimary
            ),
            contentPadding = PaddingValues(horizontal = 14.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = null,
                tint = tokens.accentSecondary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Importar audio local (MP3, M4A, WAV, OGG)",
                style = MaterialTheme.typography.labelLarge,
                color = tokens.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun SongSelectionCard(
    song: SongEntity,
    isSelected: Boolean,
    isPreviewing: Boolean,
    chart: ChartEntity?,
    scoreRecord: ScoreRecordEntity?,
    onSelect: () -> Unit,
    onTogglePreview: () -> Unit,
    onDelete: (() -> Unit)?
) {
    val tokens = LocalAppStyleTokens.current
    val primaryAccent = Color(song.primaryColorHex or 0xFF000000L)
    val secondaryAccent = Color(song.secondaryColorHex or 0xFF000000L)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onSelect)
            .testTag("song_card_${song.id}"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) tokens.cardSelectedSurface else tokens.cardSurface
        ),
        border = BorderStroke(
            width = if (isSelected) 1.5.dp else 1.dp,
            color = if (isSelected) tokens.accentSecondary else tokens.borderSubtle
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(50.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        Brush.linearGradient(
                            colors = listOf(primaryAccent.copy(alpha = 0.9f), secondaryAccent.copy(alpha = 0.9f))
                        )
                    )
                    .clickable(onClick = onTogglePreview)
                    .testTag("preview_button_${song.id}"),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isPreviewing) Icons.Default.Stop else Icons.Default.MusicNote,
                    contentDescription = if (isPreviewing) "Detener vista previa" else "Escuchar vista previa",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = song.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = tokens.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Text(
                    text = "${song.artist} · ${song.bpm} BPM · ${formatDuration(song.durationMs)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val earnedStars = scoreRecord?.stars ?: 0
                    Row {
                        for (s in 1..3) {
                            Icon(
                                imageVector = if (s <= earnedStars) Icons.Default.Star else Icons.Default.StarBorder,
                                contentDescription = null,
                                tint = if (s <= earnedStars) tokens.warningAccent else tokens.textSecondary.copy(alpha = 0.4f),
                                modifier = Modifier.size(15.dp)
                            )
                        }
                    }

                    if (chart != null) {
                        Text(
                            text = "${chart.noteCount} teclas",
                            style = MaterialTheme.typography.labelSmall,
                            color = tokens.accentPrimary,
                            maxLines = 1
                        )
                    }

                    if (song.isImported) {
                        Text(
                            text = "· Local",
                            style = MaterialTheme.typography.labelSmall,
                            color = tokens.accentSecondary,
                            maxLines = 1
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            Column(horizontalAlignment = Alignment.End) {
                if (scoreRecord != null) {
                    Text(
                        text = String.format(Locale.US, "%,d", scoreRecord.highScore),
                        style = MaterialTheme.typography.labelLarge,
                        color = tokens.accentSecondary,
                        maxLines = 1
                    )
                    Text(
                        text = "${scoreRecord.bestRank} · ${scoreRecord.bestAccuracy.toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = tokens.textSecondary,
                        maxLines = 1
                    )
                }

                if (onDelete != null) {
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier
                            .size(40.dp)
                            .testTag("delete_song_${song.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = "Eliminar canción",
                            tint = tokens.accentTertiary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DifficultyAndLaunchPanel(
    selectedSong: SongEntity,
    selectedDifficulty: DifficultyLevel,
    chart: ChartEntity?,
    onSelectDifficulty: (DifficultyLevel) -> Unit,
    onPlayClick: () -> Unit
) {
    val tokens = LocalAppStyleTokens.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = tokens.panelSurface,
        border = BorderStroke(1.dp, tokens.borderSubtle)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                DifficultyLevel.entries.forEach { diff ->
                    val isSelected = diff == selectedDifficulty
                    val badgeColor = when (diff.level) {
                        1 -> SecondaryCyan
                        2 -> Color(0xFF10B981)
                        3 -> PrimaryViolet
                        4 -> Color(0xFFF59E0B)
                        else -> TertiaryMagenta
                    }
                    val shortName = if (diff == DifficultyLevel.RESONANCIA_MAESTRA) "Maestra" else diff.displayName

                    Surface(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onSelectDifficulty(diff) }
                            .testTag("difficulty_chip_${diff.level}"),
                        shape = RoundedCornerShape(12.dp),
                        color = if (isSelected) badgeColor.copy(alpha = 0.24f) else tokens.cardSurface,
                        border = BorderStroke(
                            width = if (isSelected) 1.5.dp else 1.dp,
                            color = if (isSelected) badgeColor else tokens.borderSubtle
                        )
                    ) {
                        Text(
                            text = "${diff.level} · $shortName",
                            style = MaterialTheme.typography.labelLarge,
                            color = if (isSelected) tokens.textPrimary else tokens.textSecondary,
                            maxLines = 1,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = selectedDifficulty.fidelityLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = tokens.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (chart != null) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "${chart.noteCount} teclas · x${selectedDifficulty.scoreMultiplier}",
                        style = MaterialTheme.typography.labelSmall,
                        color = tokens.accentSecondary,
                        maxLines = 1
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Button(
                onClick = onPlayClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .testTag("play_song_button"),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = tokens.actionPrimaryBg,
                    contentColor = tokens.actionPrimaryText
                ),
                contentPadding = PaddingValues(horizontal = 16.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = tokens.actionPrimaryText,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Jugar «${selectedSong.title}»",
                    style = MaterialTheme.typography.titleMedium,
                    color = tokens.actionPrimaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun ImportSongProgressModal(
    dialogState: ImportDialogState,
    onDismiss: () -> Unit
) {
    val tokens = LocalAppStyleTokens.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.76f))
            .clickable(enabled = !dialogState.isAnalyzing, onClick = onDismiss),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
                .widthIn(max = 420.dp),
            shape = RoundedCornerShape(20.dp),
            color = tokens.cardSelectedSurface,
            border = BorderStroke(1.dp, tokens.borderBright)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = if (dialogState.completedSongTitle != null) {
                        Icons.Default.CheckCircle
                    } else {
                        Icons.Default.GraphicEq
                    },
                    contentDescription = null,
                    tint = if (dialogState.errorMessage != null) tokens.accentTertiary else tokens.accentSecondary,
                    modifier = Modifier.size(40.dp)
                )

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = when {
                        dialogState.isAnalyzing -> "Analizando canción"
                        dialogState.errorMessage != null -> "Error de importación"
                        else -> "¡Lista para tocar!"
                    },
                    style = MaterialTheme.typography.titleLarge,
                    color = tokens.textPrimary,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = dialogState.errorMessage ?: dialogState.statusText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.textSecondary,
                    textAlign = TextAlign.Center
                )

                if (dialogState.isAnalyzing) {
                    Spacer(modifier = Modifier.height(14.dp))
                    LinearProgressIndicator(
                        progress = { dialogState.progress.coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(CircleShape),
                        color = tokens.accentSecondary,
                        trackColor = tokens.cardSurface
                    )
                }

                if (dialogState.noteCountsByDifficulty.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(tokens.cardSurface)
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        DifficultyLevel.entries.forEach { diff ->
                            val count = dialogState.noteCountsByDifficulty[diff] ?: 0
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "${diff.level}. ${diff.displayName}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = tokens.textPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = "$count teclas",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = tokens.accentSecondary,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }

                if (!dialogState.isAnalyzing) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("dismiss_import_dialog_button"),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = tokens.actionPrimaryBg,
                            contentColor = tokens.actionPrimaryText
                        )
                    ) {
                        Text(
                            text = "Continuar",
                            color = tokens.actionPrimaryText,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

private fun formatDuration(durationMs: Long): String {
    val totalSec = (durationMs / 1000L).coerceAtLeast(1L)
    val mins = totalSec / 60L
    val secs = totalSec % 60L
    return String.format(Locale.US, "%d:%02d", mins, secs)
}
