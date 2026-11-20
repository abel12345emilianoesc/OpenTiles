package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.domain.DifficultyLevel
import com.example.ui.theme.LocalAppStyleTokens
import com.example.ui.theme.PrimaryVioletLight
import com.example.ui.theme.SecondaryCyanLight
import com.example.ui.theme.TertiaryMagenta
import com.example.viewmodel.GameSessionResult
import java.util.Locale

@Composable
fun ResultsScreen(
    result: GameSessionResult,
    onRetry: () -> Unit,
    onNextDifficulty: (DifficultyLevel) -> Unit,
    onOpenShop: () -> Unit,
    onBackToLibrary: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler {
        onBackToLibrary()
    }

    val tokens = LocalAppStyleTokens.current

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(tokens.canvasBackground)
            .testTag("results_screen"),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 540.dp)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (result.isNewRecord) {
                Surface(
                    shape = CircleShape,
                    color = TertiaryMagenta.copy(alpha = 0.25f),
                    border = BorderStroke(1.dp, TertiaryMagenta)
                ) {
                    Text(
                        text = "¡NUEVO RÉCORD PERSONAL!",
                        style = MaterialTheme.typography.labelLarge,
                        color = tokens.textPrimary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
            }

            Text(
                text = result.songTitle,
                style = MaterialTheme.typography.headlineLarge,
                color = tokens.textPrimary,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${result.artist} · Nivel ${result.difficulty.level}: ${result.difficulty.displayName}",
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.accentSecondary,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(16.dp))

            Surface(
                shape = CircleShape,
                color = tokens.cardSelectedSurface,
                border = BorderStroke(
                    width = 2.dp,
                    brush = Brush.linearGradient(listOf(SecondaryCyanLight, PrimaryVioletLight, TertiaryMagenta))
                ),
                modifier = Modifier.size(110.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = result.rank,
                        style = MaterialTheme.typography.displayLarge,
                        fontWeight = FontWeight.ExtraBold,
                        color = tokens.textPrimary,
                        modifier = Modifier.testTag("result_rank_text")
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (i in 1..3) {
                    val active = i <= result.stars
                    Icon(
                        imageVector = if (active) Icons.Default.Star else Icons.Default.StarBorder,
                        contentDescription = null,
                        tint = if (active) tokens.warningAccent else tokens.textSecondary.copy(alpha = 0.4f),
                        modifier = Modifier.size(36.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = String.format(Locale.US, "%,d", result.finalScore),
                style = MaterialTheme.typography.displayMedium,
                color = tokens.textPrimary,
                modifier = Modifier.testTag("result_score_text")
            )
            Text(
                text = String.format(Locale.US, "Precisión: %.1f%% · Combo Máx: %dx", result.accuracyPercent, result.maxCombo),
                style = MaterialTheme.typography.titleMedium,
                color = tokens.accentPrimary,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(18.dp))

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
                    JudgmentStatRow("¡PERFECTO!", result.perfectCount, tokens.accentSecondary)
                    JudgmentStatRow("¡GENIAL!", result.greatCount, tokens.accentPrimary)
                    JudgmentStatRow("BIEN", result.goodCount, tokens.warningAccent)
                    JudgmentStatRow("FALLO", result.missCount, tokens.accentTertiary)
                    JudgmentStatRow("SOSTENIDAS", result.holdsCompleted, tokens.successAccent)
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = tokens.cardSelectedSurface,
                border = BorderStroke(1.dp, tokens.successAccent.copy(alpha = 0.55f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f).padding(end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = tokens.accentSecondary
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Fragmentos ganados",
                            style = MaterialTheme.typography.bodyMedium,
                            color = tokens.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        text = "+${result.earnedFragments}",
                        style = MaterialTheme.typography.titleMedium,
                        color = tokens.successAccent,
                        maxLines = 1,
                        modifier = Modifier.testTag("result_fragments_earned")
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onRetry,
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp)
                        .testTag("result_retry_button"),
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp),
                    border = BorderStroke(1.dp, tokens.accentSecondary)
                ) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = null,
                        tint = tokens.accentSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Reintentar",
                        color = tokens.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Button(
                    onClick = onBackToLibrary,
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp)
                        .testTag("result_library_button"),
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = tokens.actionPrimaryBg,
                        contentColor = tokens.actionPrimaryText
                    )
                ) {
                    Icon(
                        Icons.Default.Home,
                        contentDescription = null,
                        tint = tokens.actionPrimaryText,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Biblioteca",
                        color = tokens.actionPrimaryText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onOpenShop,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("result_shop_button"),
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp),
                    border = BorderStroke(1.dp, tokens.borderBright)
                ) {
                    Icon(
                        Icons.Default.Palette,
                        contentDescription = null,
                        tint = tokens.accentSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Tienda",
                        color = tokens.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (result.difficulty.level < 5) {
                    val nextDiff = DifficultyLevel.fromLevel(result.difficulty.level + 1)
                    OutlinedButton(
                        onClick = { onNextDifficulty(nextDiff) },
                        modifier = Modifier
                            .weight(1.3f)
                            .height(48.dp)
                            .testTag("result_next_difficulty_button"),
                        shape = RoundedCornerShape(14.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp),
                        border = BorderStroke(1.dp, tokens.accentPrimary)
                    ) {
                        Text(
                            text = "Nivel ${nextDiff.level}: ${nextDiff.displayName}",
                            color = tokens.accentPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = null,
                            tint = tokens.accentPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun JudgmentStatRow(label: String, count: Int, accentColor: Color) {
    val tokens = LocalAppStyleTokens.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = accentColor
        )
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.titleMedium,
            color = tokens.textPrimary
        )
    }
}
