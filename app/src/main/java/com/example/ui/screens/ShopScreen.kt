package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.ShopItemEntity
import com.example.domain.HitTrailStyle
import com.example.domain.ShopCategory
import com.example.domain.TileThemePalette
import com.example.ui.theme.LocalAppStyleTokens
import com.example.ui.theme.PrimaryViolet
import com.example.viewmodel.MainUiState
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun ShopScreen(
    uiState: MainUiState,
    onSelectCategory: (ShopCategory) -> Unit,
    onPreviewItem: (ShopItemEntity) -> Unit,
    onPurchaseOrEquip: (ShopItemEntity) -> Unit,
    onTestPadTap: (Int) -> Unit,
    onBackToLibrary: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler {
        onBackToLibrary()
    }

    val tokens = LocalAppStyleTokens.current
    val listState = rememberLazyListState()
    androidx.compose.runtime.LaunchedEffect(uiState.selectedShopCategory) {
        listState.scrollToItem(0)
    }
    val filteredItems = uiState.shopItems.filter {
        it.category == uiState.selectedShopCategory.id
    }
    val unlockedCount = uiState.shopItems.count { it.isUnlocked }
    val totalCount = uiState.shopItems.size

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(tokens.canvasBackground)
            .testTag("shop_screen")
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 680.dp)
                .align(Alignment.TopCenter)
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(
                        text = "TIENDA",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = tokens.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "$unlockedCount / $totalCount desbloqueados",
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Surface(
                    shape = CircleShape,
                    color = tokens.cardSelectedSurface,
                    border = BorderStroke(1.dp, tokens.accentSecondary.copy(alpha = 0.5f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = tokens.accentSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "${uiState.preferences.resonanceFragments}",
                            style = MaterialTheme.typography.titleMedium,
                            color = tokens.textPrimary,
                            modifier = Modifier.testTag("shop_fragments_balance")
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Vista previa interactiva (tocar la tecla o cualquier artículo anima la tonalidad, SFX y estela)
            LiveTileThemePreviewBanner(
                activeTheme = uiState.equippedTheme,
                activeSfxName = uiState.equippedSfx.displayName,
                activeTrail = uiState.equippedTrail,
                previewNonce = uiState.shopPreviewNonce,
                feedbackMessage = uiState.shopFeedbackMessage,
                onTapTestPad = onTestPadTap
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ShopCategory.entries.forEach { category ->
                    val isSelected = category == uiState.selectedShopCategory
                    Surface(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onSelectCategory(category) }
                            .testTag("shop_tab_${category.id}"),
                        shape = RoundedCornerShape(12.dp),
                        color = if (isSelected) PrimaryViolet.copy(alpha = 0.28f) else tokens.cardSurface,
                        border = BorderStroke(
                            width = if (isSelected) 1.5.dp else 1.dp,
                            color = if (isSelected) tokens.accentPrimary else tokens.borderSubtle
                        )
                    ) {
                        Text(
                            text = category.displayName,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (isSelected) tokens.textPrimary else tokens.textSecondary,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .testTag("shop_items_list"),
                contentPadding = PaddingValues(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(filteredItems, key = { it.id }) { item ->
                    ShopItemCard(
                        item = item,
                        canAfford = uiState.preferences.resonanceFragments >= item.price,
                        onPreview = { onPreviewItem(item) },
                        onPurchaseOrEquip = { onPurchaseOrEquip(item) }
                    )
                }
            }
        }
    }
}

@Composable
private fun LiveTileThemePreviewBanner(
    activeTheme: TileThemePalette,
    activeSfxName: String,
    activeTrail: HitTrailStyle,
    previewNonce: Int,
    feedbackMessage: String?,
    onTapTestPad: (Int) -> Unit
) {
    val tokens = LocalAppStyleTokens.current
    val pulseAnim = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var tapLaneCounter by remember { mutableIntStateOf(0) }

    androidx.compose.runtime.LaunchedEffect(previewNonce) {
        if (previewNonce > 0) {
            pulseAnim.snapTo(1f)
            pulseAnim.animateTo(0f, tween(420))
        }
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = tokens.panelSurface,
        border = BorderStroke(1.dp, tokens.borderBright)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                    Text(
                        text = "VISTA PREVIA · TOCA LA TECLA",
                        style = MaterialTheme.typography.labelSmall,
                        color = tokens.accentSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = activeTheme.name,
                        style = MaterialTheme.typography.titleLarge,
                        color = tokens.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "$activeSfxName · ${activeTrail.displayName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Mini-pad interactivo que reacciona al toque mostrando la estela seleccionada
                Canvas(
                    modifier = Modifier
                        .width(126.dp)
                        .height(68.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(tokens.highwayBackground)
                        .clickable {
                            onTapTestPad(tapLaneCounter % 4)
                            tapLaneCounter++
                            scope.launch {
                                pulseAnim.snapTo(1f)
                                pulseAnim.animateTo(0f, tween(420))
                            }
                        }
                        .testTag("shop_preview_pad")
                ) {
                    val w = size.width
                    val h = size.height
                    val p = pulseAnim.value
                    val progress = 1f - p
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(activeTheme.glowColor.copy(alpha = 0.35f + p * 0.45f), Color.Transparent),
                            center = Offset(w * 0.5f, h * 0.5f),
                            radius = w * (0.55f + p * 0.3f)
                        ),
                        center = Offset(w * 0.5f, h * 0.5f),
                        radius = w * (0.55f + p * 0.3f)
                    )
                    val tileW = w * 0.36f
                    val tileH = h * 0.72f
                    val topLeft = Offset((w - tileW) * 0.5f, (h - tileH) * 0.5f)
                    drawRoundRect(
                        brush = Brush.verticalGradient(
                            listOf(activeTheme.tileTopColor, activeTheme.tileBottomColor)
                        ),
                        topLeft = topLeft,
                        size = Size(tileW, tileH),
                        cornerRadius = CornerRadius(8.dp.toPx(), 8.dp.toPx())
                    )
                    drawRoundRect(
                        color = Color.White.copy(alpha = 0.72f + p * 0.28f),
                        topLeft = topLeft,
                        size = Size(tileW, tileH),
                        cornerRadius = CornerRadius(8.dp.toPx(), 8.dp.toPx()),
                        style = Stroke(width = 1.5.dp.toPx())
                    )
                    if (p > 0.02f) {
                        when (activeTrail) {
                            HitTrailStyle.ONDAS_PRISMA -> {
                                drawCircle(
                                    color = activeTheme.glowColor.copy(alpha = p),
                                    radius = (14.dp.toPx()) + progress * 28.dp.toPx(),
                                    center = Offset(w * 0.5f, h * 0.5f),
                                    style = Stroke(width = 2.5.dp.toPx())
                                )
                            }
                            HitTrailStyle.ESTALLIDO_ESTELAR -> {
                                val dist = 10.dp.toPx() + progress * 34.dp.toPx()
                                for (k in 0 until 8) {
                                    val ang = (k / 8f) * (2f * PI.toFloat())
                                    drawCircle(
                                        color = if (k % 2 == 0) Color.White.copy(alpha = p) else activeTheme.glowColor.copy(alpha = p),
                                        radius = 3.5.dp.toPx() * p + 1.dp.toPx(),
                                        center = Offset(w * 0.5f + cos(ang) * dist, h * 0.5f + sin(ang) * dist * 0.65f)
                                    )
                                }
                            }
                            HitTrailStyle.PULSO_HEXAGONAL -> {
                                drawRect(
                                    brush = Brush.verticalGradient(
                                        colors = listOf(Color.Transparent, activeTheme.glowColor.copy(alpha = p * 0.85f))
                                    ),
                                    topLeft = Offset(topLeft.x, 0f),
                                    size = Size(tileW, h)
                                )
                            }
                            HitTrailStyle.VORTICE_ESPIRAL -> {
                                val maxR = 12.dp.toPx() + progress * 24.dp.toPx()
                                val baseAng = progress * 5f
                                for (arm in 0 until 3) {
                                    val offsetAng = (arm / 3f) * (2f * PI.toFloat())
                                    val spiral = Path()
                                    for (s in 0..6) {
                                        val frac = s / 6f
                                        val a = baseAng + offsetAng + frac * 1.8f
                                        val r = maxR * frac
                                        val px = w * 0.5f + cos(a) * r
                                        val py = h * 0.5f + sin(a) * r * 0.65f
                                        if (s == 0) spiral.moveTo(px, py) else spiral.lineTo(px, py)
                                    }
                                    drawPath(
                                        path = spiral,
                                        color = if (arm == 0) Color.White.copy(alpha = p) else activeTheme.glowColor.copy(alpha = p),
                                        style = Stroke(width = 2.dp.toPx())
                                    )
                                }
                            }
                            HitTrailStyle.LLUVIA_CHISPAS -> {
                                for (k in 0 until 8) {
                                    val spread = (k - 3.5f) / 3.5f
                                    val vx = spread * w * 0.36f
                                    val vy = -(h * 0.72f)
                                    val grav = h * 1.4f
                                    val sx = w * 0.5f + vx * progress
                                    val sy = h * 0.68f + vy * progress + 0.5f * grav * progress * progress
                                    drawCircle(
                                        color = if (k % 2 == 0) Color.White.copy(alpha = p) else activeTheme.glowColor.copy(alpha = p),
                                        radius = 2.6.dp.toPx(),
                                        center = Offset(sx, sy)
                                    )
                                }
                            }
                            HitTrailStyle.ONDA_SISMICA -> {
                                val halfW = tileW * 0.45f + progress * w * 0.42f
                                val yBar = h * 0.5f - progress * 16.dp.toPx()
                                drawLine(
                                    color = activeTheme.glowColor.copy(alpha = p),
                                    start = Offset((w * 0.5f - halfW).coerceAtLeast(0f), yBar),
                                    end = Offset((w * 0.5f + halfW).coerceAtMost(w), yBar),
                                    strokeWidth = 3.dp.toPx()
                                )
                                drawCircle(
                                    color = Color.White.copy(alpha = p * 0.8f),
                                    radius = 8.dp.toPx() + progress * 20.dp.toPx(),
                                    center = Offset(w * 0.5f, h * 0.5f),
                                    style = Stroke(width = 1.8.dp.toPx())
                                )
                            }
                        }
                    }
                }
            }

            if (!feedbackMessage.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = feedbackMessage,
                    style = MaterialTheme.typography.labelLarge,
                    color = tokens.accentSecondary,
                    modifier = Modifier.testTag("shop_feedback_text")
                )
            }
        }
    }
}

@Composable
private fun ShopItemCard(
    item: ShopItemEntity,
    canAfford: Boolean,
    onPreview: () -> Unit,
    onPurchaseOrEquip: () -> Unit
) {
    val tokens = LocalAppStyleTokens.current
    val c1 = Color(item.primaryColorHex or 0xFF000000L)
    val c2 = Color(item.secondaryColorHex or 0xFF000000L)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("shop_item_${item.id}"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (item.isEquipped) tokens.cardSelectedSurface else tokens.cardSurface
        ),
        border = BorderStroke(
            width = if (item.isEquipped) 1.6.dp else 1.dp,
            color = if (item.isEquipped) tokens.successAccent else tokens.borderSubtle
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
                    .background(Brush.verticalGradient(listOf(c1, c2)))
                    .clickable(onClick = onPreview)
                    .testTag("preview_shop_item_${item.id}"),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Probar ${item.title}",
                    tint = Color.White
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleLarge,
                    color = tokens.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = item.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.textSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            when {
                item.isEquipped -> {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = tokens.successAccent.copy(alpha = 0.16f),
                        border = BorderStroke(1.dp, tokens.successAccent)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = tokens.successAccent,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "EQUIPADO",
                                style = MaterialTheme.typography.labelSmall,
                                color = tokens.successAccent,
                                maxLines = 1
                            )
                        }
                    }
                }
                item.isUnlocked -> {
                    OutlinedButton(
                        onClick = onPurchaseOrEquip,
                        modifier = Modifier.testTag("action_shop_item_${item.id}"),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                        border = BorderStroke(1.dp, tokens.accentSecondary)
                    ) {
                        Text(
                            text = "Equipar",
                            color = tokens.textPrimary,
                            maxLines = 1
                        )
                    }
                }
                else -> {
                    Button(
                        onClick = onPurchaseOrEquip,
                        modifier = Modifier.testTag("action_shop_item_${item.id}"),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (canAfford) tokens.actionPrimaryBg else tokens.panelSurface,
                            contentColor = if (canAfford) tokens.actionPrimaryText else tokens.textSecondary
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.LockOpen,
                            contentDescription = null,
                            tint = if (canAfford) tokens.actionPrimaryText else tokens.textSecondary,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "${item.price}",
                            style = MaterialTheme.typography.labelLarge,
                            color = if (canAfford) tokens.actionPrimaryText else tokens.textSecondary,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}
