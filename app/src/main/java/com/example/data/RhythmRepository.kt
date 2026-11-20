package com.example.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.example.audio.AudioChartAnalyzer
import com.example.audio.SynthesizedAudioEngine
import com.example.domain.BuiltInCatalog
import com.example.domain.DifficultyLevel
import com.example.domain.HitTrailStyle
import com.example.domain.RhythmNote
import com.example.domain.SfxProfile
import com.example.domain.ShopCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.max

data class ImportedSongSummary(
    val song: SongEntity,
    val noteCountsByDifficulty: Map<DifficultyLevel, Int>
)

class RhythmRepository(
    private val dao: AppDao,
    private val prefsRepo: UserPreferencesRepository,
    private val context: Context? = null
) {

    val songsFlow: Flow<List<SongEntity>> = dao.observeAllSongs()
    val allChartsFlow: Flow<List<ChartEntity>> = dao.observeAllCharts()
    val allScoresFlow: Flow<List<ScoreRecordEntity>> = dao.observeAllScores()
    val shopItemsFlow: Flow<List<ShopItemEntity>> = dao.observeShopItems()
    val preferencesFlow: Flow<PlayerPreferences> = prefsRepo.preferencesFlow

    /**
     * Inicializa y sincroniza incrementalmente el catálogo de la tienda (conservando
     * compras existentes) y las 4 canciones originales con sus 5 dificultades.
     */
    suspend fun ensureSeeded() = withContext(Dispatchers.IO) {
        syncShopCatalog()

        val currentSongs = dao.getSongCount()
        val builtInHolds = dao.getBuiltInHoldTotal() ?: 0
        if (currentSongs == 0 || builtInHolds == 0) {
            seedBuiltInSongs()
        }
    }

    private suspend fun syncShopCatalog() {
        val existingById = dao.getShopItemsOnce().associateBy { it.id }
        val fullCatalog = buildFullShopCatalog()
        val missingItems = fullCatalog.filter { it.id !in existingById }
        if (missingItems.isNotEmpty()) {
            dao.insertShopItems(missingItems)
        }
    }

    private fun buildFullShopCatalog(): List<ShopItemEntity> {
        val items = ArrayList<ShopItemEntity>()
        var order = 0

        // 1. Tonalidades de Teclas (11 temas)
        BuiltInCatalog.tileThemes.forEachIndexed { index, theme ->
            items.add(
                ShopItemEntity(
                    id = theme.id,
                    category = ShopCategory.TILE_THEME.id,
                    title = theme.name,
                    subtitle = theme.subtitle,
                    price = theme.price,
                    isUnlocked = index == 0,
                    isEquipped = index == 0,
                    primaryColorHex = theme.tileTopColor.value.toLong() ushr 32,
                    secondaryColorHex = theme.tileBottomColor.value.toLong() ushr 32,
                    sortOrder = order++
                )
            )
        }

        // 2. Efectos de Sonido (8 packs sintetizados)
        val sfxColors = listOf(
            0xFF06B6D4L to 0xFF8B5CF6L,
            0xFFFFC83DL to 0xFFF59E0BL,
            0xFFFB923CL to 0xFFEC4899L,
            0xFFF43F5EL to 0xFFA855F7L,
            0xFFBAE6FDL to 0xFF6366F1L,
            0xFF10B981L to 0xFF06B6D4L,
            0xFF38BDF8L to 0xFF1E1B4BL,
            0xFFA3E635L to 0xFF14B8A6L
        )
        SfxProfile.entries.forEachIndexed { index, sfx ->
            val (c1, c2) = sfxColors[index % sfxColors.size]
            items.add(
                ShopItemEntity(
                    id = sfx.id,
                    category = ShopCategory.SFX_PACK.id,
                    title = sfx.displayName,
                    subtitle = sfx.subtitle,
                    price = sfx.price,
                    isUnlocked = index == 0,
                    isEquipped = index == 0,
                    primaryColorHex = c1,
                    secondaryColorHex = c2,
                    sortOrder = order++
                )
            )
        }

        // 3. Estelas de Impacto (6 efectos visuales)
        val trailColors = listOf(
            0xFF4CD7F6L to 0xFFD0BCFFL,
            0xFFFFB2B7L to 0xFFF43F5EL,
            0xFFA855F7L to 0xFF06B6D4L,
            0xFF34D399L to 0xFF38BDF8L,
            0xFFFBBF24L to 0xFFFB7185L,
            0xFF22D3EEL to 0xFF4F46E5L
        )
        HitTrailStyle.entries.forEachIndexed { index, trail ->
            val (c1, c2) = trailColors[index % trailColors.size]
            items.add(
                ShopItemEntity(
                    id = trail.id,
                    category = ShopCategory.HIT_TRAIL.id,
                    title = trail.displayName,
                    subtitle = trail.subtitle,
                    price = trail.price,
                    isUnlocked = index == 0,
                    isEquipped = index == 0,
                    primaryColorHex = c1,
                    secondaryColorHex = c2,
                    sortOrder = order++
                )
            )
        }

        return items
    }

    private suspend fun seedBuiltInSongs() {
        val songsDir = if (context != null) {
            File(context.filesDir, "builtin_tracks").apply { mkdirs() }
        } else null

        val songEntities = ArrayList<SongEntity>()
        val chartEntities = ArrayList<ChartEntity>()

        for (spec in SynthesizedAudioEngine.builtInSongs) {
            val samples = SynthesizedAudioEngine.generateSongSamples(spec)
            val wavFile = if (songsDir != null) {
                File(songsDir, "${spec.id}.wav").also { file ->
                    if (!file.exists() || file.length() < 1024L) {
                        SynthesizedAudioEngine.writeMonoWavFile(file, samples)
                    }
                }
            } else null

            val analyzed = AudioChartAnalyzer.analyzePcmSamples(
                samples = samples,
                sampleRate = 22050,
                durationMs = spec.durationMs
            )

            songEntities.add(
                SongEntity(
                    id = spec.id,
                    title = spec.title,
                    artist = spec.artist,
                    durationMs = spec.durationMs,
                    bpm = spec.bpm,
                    genreTag = spec.genreTag,
                    isImported = false,
                    audioFilePath = wavFile?.absolutePath ?: "",
                    primaryColorHex = spec.primaryColorHex,
                    secondaryColorHex = spec.secondaryColorHex
                )
            )

            for ((diff, notes) in analyzed.chartsByDifficulty) {
                chartEntities.add(
                    ChartEntity(
                        songId = spec.id,
                        difficultyLevel = diff.level,
                        serializedNotes = ChartSerializer.serialize(notes),
                        noteCount = notes.size,
                        holdCount = notes.count { it.isHold },
                        chordCount = notes.count { it.isChord } / 2
                    )
                )
            }
        }

        dao.insertSongs(songEntities)
        dao.insertCharts(chartEntities)
    }

    /**
     * Lógica de análisis DSP e inserción en Room a partir de un archivo de audio local (MP3, M4A, WAV, OGG).
     */
    suspend fun importSongFromLocalFile(
        file: File,
        title: String,
        artist: String,
        genreTagPrefix: String? = null,
        songId: String = "imp_${UUID.randomUUID().toString().take(8)}",
        deleteSourceIfCopied: Boolean = false,
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ): Result<ImportedSongSummary> = withContext(Dispatchers.IO) {
        try {
            if (!file.exists() || file.length() < 64L) {
                return@withContext Result.failure(
                    IllegalArgumentException("El archivo de audio no existe o está vacío.")
                )
            }

            val ext = file.extension.lowercase().takeIf { it in SUPPORTED_EXTENSIONS } ?: "m4a"
            val persistedFile = if (context != null) {
                val importDir = File(context.filesDir, "imported_tracks").apply { mkdirs() }
                val alreadyInImports = file.parentFile?.canonicalPath == importDir.canonicalPath
                if (alreadyInImports) {
                    file
                } else {
                    val destFile = File(importDir, "$songId.$ext")
                    file.copyTo(destFile, overwrite = true)
                    if (deleteSourceIfCopied) {
                        try {
                            file.delete()
                        } catch (_: Exception) {
                        }
                    }
                    destFile
                }
            } else {
                file
            }

            val analyzed = AudioChartAnalyzer.analyzeAudioFile(persistedFile, onProgress)

            val palettes = listOf(
                0xFF06B6D4L to 0xFF8B5CF6L,
                0xFFF43F5EL to 0xFFA855F7L,
                0xFF10B981L to 0xFF06B6D4L,
                0xFFFFC83DL to 0xFFF43F5EL,
                0xFF38BDF8L to 0xFF6366F1L
            )
            val cleanTitle = title.trim().ifBlank { "Pista Importada" }
            val cleanArtist = artist.trim().ifBlank { "Audio Local (${ext.uppercase()})" }
            val (primaryHex, secondaryHex) = palettes[(cleanTitle.hashCode() and 0x7FFFFFFF) % palettes.size]
            val tagPrefix = genreTagPrefix?.takeIf { it.isNotBlank() } ?: "Audio ${ext.uppercase()}"

            val songEntity = SongEntity(
                id = songId,
                title = cleanTitle,
                artist = cleanArtist,
                durationMs = analyzed.durationMs,
                bpm = analyzed.estimatedBpm,
                genreTag = "$tagPrefix · ${analyzed.estimatedBpm} BPM",
                isImported = true,
                audioFilePath = persistedFile.absolutePath,
                primaryColorHex = primaryHex,
                secondaryColorHex = secondaryHex
            )

            val chartEntities = analyzed.chartsByDifficulty.map { (diff, notes) ->
                ChartEntity(
                    songId = songId,
                    difficultyLevel = diff.level,
                    serializedNotes = ChartSerializer.serialize(notes),
                    noteCount = notes.size,
                    holdCount = notes.count { it.isHold },
                    chordCount = notes.count { it.isChord } / 2
                )
            }

            dao.insertSong(songEntity)
            dao.insertCharts(chartEntities)

            val countsMap = analyzed.chartsByDifficulty.mapValues { it.value.size }
            Result.success(ImportedSongSummary(songEntity, countsMap))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Importa un archivo de audio (MP3, M4A, WAV, OGG) desde un Uri del sistema, lo copia al almacenamiento
     * privado de la app y delega en `importSongFromLocalFile(...)` para analizarlo e insertarlo en Room.
     */
    suspend fun importSongFromUri(
        uri: Uri,
        customTitle: String? = null,
        customArtist: String? = null,
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ): Result<ImportedSongSummary> = withContext(Dispatchers.IO) {
        val ctx = context ?: return@withContext Result.failure(IllegalStateException("Contexto no disponible"))
        try {
            onProgress(0.04f, "Copiando archivo de audio a la biblioteca...")
            val rawName = queryDisplayName(ctx, uri)
            val mimeType = ctx.contentResolver.getType(uri)
            val extension = resolveSupportedExtension(rawName, mimeType)
                ?: return@withContext Result.failure(
                    IllegalArgumentException(
                        "Formato no compatible. Selecciona un archivo MP3, M4A, WAV u OGG."
                    )
                )

            val cleanBaseName = (rawName ?: "Pista Importada")
                .substringBeforeLast('.')
                .replace('_', ' ')
                .replace('-', ' ')
                .trim()
                .ifBlank { "Pista Importada" }

            val importDir = File(ctx.filesDir, "imported_tracks").apply { mkdirs() }
            val songId = "imp_${UUID.randomUUID().toString().take(8)}"
            val destFile = File(importDir, "$songId.$extension")

            ctx.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output, bufferSize = 16384)
                }
            } ?: return@withContext Result.failure(IllegalArgumentException("No se pudo abrir el archivo de audio seleccionado."))

            importSongFromLocalFile(
                file = destFile,
                title = customTitle?.takeIf { it.isNotBlank() } ?: cleanBaseName,
                artist = customArtist?.takeIf { it.isNotBlank() } ?: "Importada · Audio Local (${extension.uppercase()})",
                genreTagPrefix = "Audio ${extension.uppercase()}",
                songId = songId,
                onProgress = onProgress
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Registra una canción analizada directamente desde muestras PCM (útil para pruebas e importación sintética).
     */
    suspend fun registerAnalyzedSong(
        title: String,
        artist: String,
        samples: FloatArray,
        sampleRate: Int,
        durationMs: Long
    ): ImportedSongSummary = withContext(Dispatchers.IO) {
        val analyzed = AudioChartAnalyzer.analyzePcmSamples(samples, sampleRate, durationMs)
        val songId = "custom_${UUID.randomUUID().toString().take(8)}"
        val songEntity = SongEntity(
            id = songId,
            title = title,
            artist = artist,
            durationMs = analyzed.durationMs,
            bpm = analyzed.estimatedBpm,
            genreTag = "Pista Personalizada · ${analyzed.estimatedBpm} BPM",
            isImported = true,
            audioFilePath = "",
            primaryColorHex = 0xFF06B6D4L,
            secondaryColorHex = 0xFF8B5CF6L
        )
        val chartEntities = analyzed.chartsByDifficulty.map { (diff, notes) ->
            ChartEntity(
                songId = songId,
                difficultyLevel = diff.level,
                serializedNotes = ChartSerializer.serialize(notes),
                noteCount = notes.size,
                holdCount = notes.count { it.isHold },
                chordCount = notes.count { it.isChord } / 2
            )
        }
        dao.insertSong(songEntity)
        dao.insertCharts(chartEntities)
        ImportedSongSummary(songEntity, analyzed.chartsByDifficulty.mapValues { it.value.size })
    }

    suspend fun getNotesForSongAndDifficulty(songId: String, difficulty: DifficultyLevel): List<RhythmNote> =
        withContext(Dispatchers.IO) {
            val chart = dao.getChart(songId, difficulty.level)
            if (chart != null) {
                val raw = ChartSerializer.deserialize(chart.serializedNotes)
                AudioChartAnalyzer.sanitizeChartForPhonePlayability(raw, difficulty)
            } else {
                emptyList()
            }
        }

    suspend fun deleteImportedSong(songId: String) = withContext(Dispatchers.IO) {
        val song = dao.getSongById(songId)
        if (song != null && song.isImported) {
            if (song.audioFilePath.isNotBlank()) {
                try {
                    File(song.audioFilePath).delete()
                } catch (_: Exception) {
                }
            }
            dao.deleteSongCompletely(songId)
        }
    }

    /**
     * Guarda el resultado de una partida, actualiza el récord si se superó y otorga Fragmentos de Resonancia.
     */
    suspend fun saveGameResult(
        songId: String,
        difficulty: DifficultyLevel,
        score: Int,
        accuracy: Float,
        stars: Int,
        rank: String,
        maxCombo: Int,
        earnedFragments: Int
    ): Boolean = withContext(Dispatchers.IO) {
        val existing = dao.getScoreRecord(songId, difficulty.level)
        val isNewRecord = existing == null || score > existing.highScore
        val merged = if (existing == null) {
            ScoreRecordEntity(
                songId = songId,
                difficultyLevel = difficulty.level,
                highScore = score,
                bestAccuracy = accuracy,
                stars = stars,
                bestRank = rank,
                maxCombo = maxCombo,
                playCount = 1
            )
        } else {
            ScoreRecordEntity(
                songId = songId,
                difficultyLevel = difficulty.level,
                highScore = max(existing.highScore, score),
                bestAccuracy = max(existing.bestAccuracy, accuracy),
                stars = max(existing.stars, stars),
                bestRank = pickBetterRank(existing.bestRank, rank),
                maxCombo = max(existing.maxCombo, maxCombo),
                playCount = existing.playCount + 1
            )
        }
        dao.insertScoreRecord(merged)
        prefsRepo.addFragments(earnedFragments)
        isNewRecord
    }

    /**
     * Compra o equipa un artículo de la tienda.
     * Devuelve true si la operación tuvo éxito.
     */
    suspend fun purchaseOrEquipShopItem(itemId: String): Boolean = withContext(Dispatchers.IO) {
        val item = dao.getShopItemById(itemId) ?: return@withContext false
        if (!item.isUnlocked) {
            val paid = prefsRepo.trySpendFragments(item.price)
            if (!paid) return@withContext false
            dao.unlockShopItem(itemId)
        }
        dao.equipShopItemInCategory(itemId, item.category)
        prefsRepo.setEquippedItem(item.category, itemId)
        true
    }

    private fun pickBetterRank(r1: String, r2: String): String {
        val order = listOf("S", "A", "B", "C")
        val i1 = order.indexOf(r1).let { if (it == -1) 99 else it }
        val i2 = order.indexOf(r2).let { if (it == -1) 99 else it }
        return if (i1 <= i2) r1 else r2
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx) else null
                } else null
            }
        } catch (_: Exception) {
            uri.lastPathSegment
        }
    }

    companion object {
        private val SUPPORTED_EXTENSIONS = setOf("mp3", "m4a", "aac", "mp4", "wav", "ogg", "oga")

        fun resolveSupportedExtension(displayName: String?, mimeType: String?): String? {
            val fromName = displayName
                ?.substringAfterLast('.', "")
                ?.lowercase()
                ?.takeIf { it.isNotBlank() && it != displayName.lowercase() }
            if (fromName != null) {
                return if (fromName in SUPPORTED_EXTENSIONS) fromName else null
            }

            return when (mimeType?.lowercase()?.substringBefore(';')?.trim()) {
                "audio/mpeg", "audio/mp3" -> "mp3"
                "audio/mp4", "audio/x-m4a", "audio/m4a", "audio/aac" -> "m4a"
                "audio/wav", "audio/x-wav", "audio/vnd.wave" -> "wav"
                "audio/ogg", "application/ogg" -> "ogg"
                else -> null
            }
        }
    }
}
