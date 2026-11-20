package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.ui.theme.LocalAppStyleTokens
import com.example.viewmodel.AppUpdateUiState
import java.util.Locale

@Composable
fun AppUpdateDialog(
    state: AppUpdateUiState,
    onDownloadClick: () -> Unit,
    onInstallClick: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val info = state.availableUpdate ?: return
    val tokens = LocalAppStyleTokens.current
    val downloadedPath = state.downloadedApkPath

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.78f))
            .testTag("app_update_dialog"),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
                .widthIn(max = 420.dp),
            shape = RoundedCornerShape(22.dp),
            color = tokens.cardSurface,
            border = BorderStroke(1.dp, tokens.borderBright)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (downloadedPath != null) Icons.Default.InstallMobile else Icons.Default.SystemUpdate,
                        contentDescription = null,
                        tint = tokens.accentSecondary,
                        modifier = Modifier.size(26.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Nueva actualización",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = tokens.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val sizeSuffix = if (info.apkSizeBytes > 0L) {
                            val mb = info.apkSizeBytes.toDouble() / (1024.0 * 1024.0)
                            String.format(Locale.US, " · %.1f MB", mb)
                        } else ""
                        Text(
                            text = "v${state.currentVersion} → ${info.tagName}$sizeSuffix",
                            style = MaterialTheme.typography.bodySmall,
                            color = tokens.accentSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                if (info.releaseNotes.isNotBlank()) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = tokens.panelSurface,
                        border = BorderStroke(1.dp, tokens.borderSubtle)
                    ) {
                        Text(
                            text = info.releaseNotes,
                            style = MaterialTheme.typography.bodySmall,
                            color = tokens.textSecondary,
                            modifier = Modifier
                                .padding(12.dp)
                                .verticalScroll(rememberScrollState()),
                            maxLines = 8,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                if (state.isDownloading) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Descargando APK...",
                                style = MaterialTheme.typography.bodySmall,
                                color = tokens.textPrimary
                            )
                            Text(
                                text = "${(state.downloadProgress * 100f).toInt().coerceIn(0, 100)}%",
                                style = MaterialTheme.typography.labelMedium,
                                color = tokens.accentSecondary
                            )
                        }
                        LinearProgressIndicator(
                            progress = { state.downloadProgress.coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(CircleShape)
                                .testTag("update_download_progress"),
                            color = tokens.accentSecondary,
                            trackColor = tokens.panelSurface
                        )
                    }
                }

                if (!state.errorMessage.isNullOrBlank()) {
                    Text(
                        text = state.errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.accentTertiary,
                        modifier = Modifier.testTag("update_dialog_error")
                    )
                } else if (!state.statusMessage.isNullOrBlank() && !state.isDownloading) {
                    Text(
                        text = state.statusMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.successAccent,
                        modifier = Modifier.testTag("update_dialog_status")
                    )
                }

                Spacer(modifier = Modifier.height(2.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        enabled = !state.isDownloading,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("dismiss_update_dialog_button"),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        border = BorderStroke(1.dp, tokens.borderBright)
                    ) {
                        Text(
                            text = "Más tarde",
                            color = tokens.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Button(
                        onClick = {
                            if (downloadedPath != null) {
                                onInstallClick(downloadedPath)
                            } else {
                                onDownloadClick()
                            }
                        },
                        enabled = !state.isDownloading,
                        modifier = Modifier
                            .weight(1.2f)
                            .height(48.dp)
                            .testTag("confirm_update_action_button"),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = tokens.actionPrimaryBg,
                            contentColor = tokens.actionPrimaryText
                        )
                    ) {
                        Text(
                            text = when {
                                state.isDownloading -> "Descargando..."
                                downloadedPath != null -> "Instalar APK"
                                else -> "Descargar"
                            },
                            color = tokens.actionPrimaryText,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}
