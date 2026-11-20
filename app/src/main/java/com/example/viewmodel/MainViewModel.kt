package com.example.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.BuildConfig
import com.example.audio.GameAudioController
import com.example.data.AppUpdateChecker
import com.example.data.AppUpdateInfo
import com.example.data.ChartEntity
import com.example.data.PlayerPreferences
import com.example.data.RhythmRepository
import com.example.data.ScoreRecordEntity
import com.example.data.ShopItemEntity
import com.example.data.SongEntity
import com.example.data.UpdateCheckResult
import java.io.File
import com.example.domain.AppVisualStyle
import com.example.domain.BuiltInCatalog
import com.example.domain.DifficultyLevel
import com.example.domain.HitJudgment
import com.example.domain.HitTrailStyle
import com.example.domain.SfxProfile
import com.example.domain.ShopCategory
import com.example.domain.TileThemePalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class AppScreen {
    LIBRARY,
    GAMEPLAY,
    RESULTS,
    SHOP,
    SETTINGS
}

enum class LibraryFilter(val id: String, val label: String) {
    ALL("ALL", "Todas"),
    BUILTIN("BUILTIN", "Base"),
    IMPORTED("IMPORTED", "Importadas")
}

data class ImportDialogState(
    val isAnalyzing: Boolean = false,
    val progress: Float = 0f,
    val statusText: String = "",
    val errorMessage: String? = null,
    val completedSongId: String? = null,
    val completedSongTitle: String? = null,
    val noteCountsByDifficulty: Map<DifficultyLevel, Int> = emptyMap()
)

data class AppUpdateUiState(
    val currentVersion: String = BuildConfig.VERSION_NAME,
    val isChecking: Boolean = false,
    val availableUpdate: AppUpdateInfo? = null,
    val showUpdateDialog: Boolean = false,
    val isDownloading: Boolean = false,
    val downloadProgress: Float = 0f,
    val downloadedApkPath: String? = null,
    val statusMessage: String? = null,
    val errorMessage: String? = null
)

data class MainUiState(
    val isLoading: Boolean = true,
    val currentScreen: AppScreen = AppScreen.LIBRARY,
    val songs: List<SongEntity> = emptyList(),
    val libraryFilter: LibraryFilter = LibraryFilter.ALL,
    val chartsBySongAndDiff: Map<Pair<String, Int>, ChartEntity> = emptyMap(),
    val scoresBySongAndDiff: Map<Pair<String, Int>, ScoreRecordEntity> = emptyMap(),
    val shopItems: List<ShopItemEntity> = emptyList(),
    val preferences: PlayerPreferences = PlayerPreferences(),
    val selectedSongId: String = "",
    val selectedDifficulty: DifficultyLevel = DifficultyLevel.FLUIDO,
    val selectedShopCategory: ShopCategory = ShopCategory.TILE_THEME,
    val previewThemeId: String? = null,
    val previewTrailId: String? = null,
    val shopPreviewNonce: Int = 0,
    val isPreviewPlayingSongId: String? = null,
    val importDialog: ImportDialogState? = null,
    val shopFeedbackMessage: String? = null,
    val lastSessionResult: GameSessionResult? = null
) {
    val filteredSongs: List<SongEntity>
        get() = when (libraryFilter) {
            LibraryFilter.ALL -> songs
            LibraryFilter.BUILTIN -> songs.filter { !it.isImported }
            LibraryFilter.IMPORTED -> songs.filter { it.isImported }
        }

    val selectedSong: SongEntity?
        get() = songs.firstOrNull { it.id == selectedSongId } ?: filteredSongs.firstOrNull() ?: songs.firstOrNull()

    val equippedTheme: TileThemePalette
        get() = BuiltInCatalog.findTheme(previewThemeId ?: preferences.equippedThemeId)

    val equippedSfx: SfxProfile
        get() = SfxProfile.fromId(preferences.equippedSfxId)

    val equippedTrail: HitTrailStyle
        get() = HitTrailStyle.fromId(previewTrailId ?: preferences.equippedTrailId)

    val totalStarsEarned: Int
        get() = scoresBySongAndDiff.values.sumOf { it.stars }
}

class MainViewModel(
    private val repository: RhythmRepository,
    private val prefsRepo: UserPreferencesRepositoryWrapper,
    val audioController: GameAudioController,
    private val appUpdateChecker: AppUpdateChecker = AppUpdateChecker()
) : ViewModel() {

    class UserPreferencesRepositoryWrapper(
        private val inner: com.example.data.UserPreferencesRepository
    ) {
        suspend fun updateVisualStyle(style: AppVisualStyle) = inner.updateAppVisualStyle(style)
        suspend fun updateBeatReactiveTiles(enabled: Boolean) = inner.updateBeatReactiveTiles(enabled)
        suspend fun updateShowLaneGuides(enabled: Boolean) = inner.updateShowLaneGuides(enabled)
        suspend fun updateOffset(ms: Long) = inner.updateAudioOffsetMs(ms)
        suspend fun updateMusicVolume(vol: Float) = inner.updateMusicVolume(vol)
        suspend fun updateSfxVolume(vol: Float) = inner.updateSfxVolume(vol)
        suspend fun updateVibration(enabled: Boolean) = inner.updateVibration(enabled)
        suspend fun updateHighBloom(enabled: Boolean) = inner.updateHighBloom(enabled)
    }

    private val _currentScreen = MutableStateFlow(AppScreen.LIBRARY)
    private val _selectedSongId = MutableStateFlow("")
    private val _selectedDifficulty = MutableStateFlow(DifficultyLevel.FLUIDO)
    private val _libraryFilter = MutableStateFlow(LibraryFilter.ALL)
    private val _selectedShopCategory = MutableStateFlow(ShopCategory.TILE_THEME)
    private val _previewThemeId = MutableStateFlow<String?>(null)
    private val _previewTrailId = MutableStateFlow<String?>(null)
    private val _shopPreviewNonce = MutableStateFlow(0)
    private val _isPreviewPlayingSongId = MutableStateFlow<String?>(null)
    private val _importDialog = MutableStateFlow<ImportDialogState?>(null)
    private val _shopFeedbackMessage = MutableStateFlow<String?>(null)
    private val _lastSessionResult = MutableStateFlow<GameSessionResult?>(null)
    private val _updateState = MutableStateFlow(AppUpdateUiState())
    val updateState: StateFlow<AppUpdateUiState> = _updateState.asStateFlow()
    private var hasCheckedStartupUpdate = false

    private val _activeGameEngine = MutableStateFlow<GameEngine?>(null)
    val activeGameEngine: StateFlow<GameEngine?> = _activeGameEngine.asStateFlow()

    init {
        viewModelScope.launch {
            repository.ensureSeeded()
        }
    }

    private val dataBundleFlow = combine(
        repository.songsFlow,
        repository.allChartsFlow,
        repository.allScoresFlow,
        repository.shopItemsFlow,
        repository.preferencesFlow
    ) { songs, charts, scores, shopItems, prefs ->
        audioController.updateSettings(
            musicVol = prefs.musicVolume,
            sfxVol = prefs.sfxVolume,
            vibrate = prefs.vibrationEnabled
        )
        val chartsMap = charts.associateBy { it.songId to it.difficultyLevel }
        val scoresMap = scores.associateBy { it.songId to it.difficultyLevel }
        DataBundle(songs, chartsMap, scoresMap, shopItems, prefs)
    }

    @Suppress("UNCHECKED_CAST")
    private val uiControlFlow = combine<Any?, UiControlBundle>(
        _currentScreen,
        _selectedSongId,
        _selectedDifficulty,
        _libraryFilter,
        _selectedShopCategory,
        _previewThemeId,
        _previewTrailId,
        _shopPreviewNonce
    ) { values ->
        UiControlBundle(
            screen = values[0] as AppScreen,
            selectedSongId = values[1] as String,
            difficulty = values[2] as DifficultyLevel,
            libraryFilter = values[3] as LibraryFilter,
            shopCategory = values[4] as ShopCategory,
            previewThemeId = values[5] as String?,
            previewTrailId = values[6] as String?,
            shopPreviewNonce = values[7] as Int
        )
    }

    private val dialogAndResultFlow = combine(
        _isPreviewPlayingSongId,
        _importDialog,
        _shopFeedbackMessage,
        _lastSessionResult
    ) { previewSongId, importDlg, shopMsg, lastResult ->
        DialogBundle(previewSongId, importDlg, shopMsg, lastResult)
    }

    val uiState: StateFlow<MainUiState> = combine(
        dataBundleFlow,
        uiControlFlow,
        dialogAndResultFlow
    ) { data, ctrl, dlg ->
        val effectiveSongId = if (ctrl.selectedSongId.isNotBlank() && data.songs.any { it.id == ctrl.selectedSongId }) {
            ctrl.selectedSongId
        } else {
            data.songs.firstOrNull()?.id ?: ""
        }
        MainUiState(
            isLoading = data.songs.isEmpty(),
            currentScreen = ctrl.screen,
            songs = data.songs,
            libraryFilter = ctrl.libraryFilter,
            chartsBySongAndDiff = data.chartsMap,
            scoresBySongAndDiff = data.scoresMap,
            shopItems = data.shopItems,
            preferences = data.prefs,
            selectedSongId = effectiveSongId,
            selectedDifficulty = ctrl.difficulty,
            selectedShopCategory = ctrl.shopCategory,
            previewThemeId = ctrl.previewThemeId,
            previewTrailId = ctrl.previewTrailId,
            shopPreviewNonce = ctrl.shopPreviewNonce,
            isPreviewPlayingSongId = dlg.previewSongId,
            importDialog = dlg.importDialog,
            shopFeedbackMessage = dlg.shopMsg,
            lastSessionResult = dlg.lastResult
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MainUiState()
    )

    fun navigateTo(screen: AppScreen) {
        if (screen != AppScreen.LIBRARY) {
            stopAudioPreview()
        }
        if (screen != AppScreen.SHOP) {
            _previewThemeId.value = null
            _previewTrailId.value = null
            _shopFeedbackMessage.value = null
        }
        _currentScreen.value = screen
    }

    fun selectLibraryFilter(filter: LibraryFilter) {
        _libraryFilter.value = filter
    }

    fun selectSong(songId: String) {
        if (_selectedSongId.value != songId) {
            stopAudioPreview()
            _selectedSongId.value = songId
        }
    }

    fun selectDifficulty(difficulty: DifficultyLevel) {
        _selectedDifficulty.value = difficulty
        audioController.playLaneHitSfx(
            profile = uiState.value.equippedSfx,
            lane = (difficulty.level - 1).coerceIn(0, 3)
        )
    }

    fun toggleSongPreview(song: SongEntity) {
        if (_isPreviewPlayingSongId.value == song.id) {
            stopAudioPreview()
        } else {
            viewModelScope.launch {
                val started = withContext(Dispatchers.IO) {
                    audioController.prepareAndStartSong(song.audioFilePath, startPaused = false)
                }
                _isPreviewPlayingSongId.value = if (started) song.id else null
            }
        }
    }

    fun stopAudioPreview() {
        if (_isPreviewPlayingSongId.value != null) {
            audioController.stopSong()
            _isPreviewPlayingSongId.value = null
        }
    }

    fun startGameplay(
        song: SongEntity? = uiState.value.selectedSong,
        difficulty: DifficultyLevel = uiState.value.selectedDifficulty
    ) {
        val targetSong = song ?: return
        stopAudioPreview()
        _selectedSongId.value = targetSong.id
        _selectedDifficulty.value = difficulty

        viewModelScope.launch {
            val notes = repository.getNotesForSongAndDifficulty(targetSong.id, difficulty)
            val engine = GameEngine(
                songId = targetSong.id,
                songTitle = targetSong.title,
                artist = targetSong.artist,
                durationMs = targetSong.durationMs,
                bpm = targetSong.bpm,
                primaryStyleColorHex = targetSong.primaryColorHex,
                secondaryStyleColorHex = targetSong.secondaryColorHex,
                difficulty = difficulty,
                notes = notes,
                initialPreRollMs = 950L
            )
            withContext(Dispatchers.IO) {
                audioController.prepareAndStartSong(targetSong.audioFilePath, startPaused = true)
            }
            _activeGameEngine.value = engine
            _currentScreen.value = AppScreen.GAMEPLAY
        }
    }

    fun finishGameplaySession() {
        val engine = _activeGameEngine.value ?: return
        audioController.stopSong()
        viewModelScope.launch {
            val preliminary = engine.buildSessionResult()
            val isNewRecord = repository.saveGameResult(
                songId = preliminary.songId,
                difficulty = preliminary.difficulty,
                score = preliminary.finalScore,
                accuracy = preliminary.accuracyPercent,
                stars = preliminary.stars,
                rank = preliminary.rank,
                maxCombo = preliminary.maxCombo,
                earnedFragments = preliminary.earnedFragments
            )
            _lastSessionResult.value = preliminary.copy(isNewRecord = isNewRecord)
            _currentScreen.value = AppScreen.RESULTS
        }
    }

    fun quitGameplayToLibrary() {
        audioController.stopSong()
        _activeGameEngine.value = null
        _currentScreen.value = AppScreen.LIBRARY
    }

    fun onGameplayNoteHitJudgment(judgment: HitJudgment, lane: Int, isFever: Boolean) {
        if (judgment == HitJudgment.MISS) {
            audioController.playMissFeedback()
        } else {
            audioController.playLaneHitSfx(uiState.value.equippedSfx, lane, isFever)
        }
    }

    fun importAudioUri(uri: Uri) {
        stopAudioPreview()
        _importDialog.value = ImportDialogState(
            isAnalyzing = true,
            progress = 0.02f,
            statusText = "Preparando importación de canción..."
        )
        viewModelScope.launch {
            val result = repository.importSongFromUri(
                uri = uri,
                onProgress = { progress, status ->
                    _importDialog.value = ImportDialogState(
                        isAnalyzing = true,
                        progress = progress,
                        statusText = status
                    )
                }
            )
            result.fold(
                onSuccess = { summary ->
                    _selectedSongId.value = summary.song.id
                    _libraryFilter.value = LibraryFilter.ALL
                    _importDialog.value = ImportDialogState(
                        isAnalyzing = false,
                        progress = 1f,
                        statusText = "¡Canción importada y lista para jugar!",
                        completedSongId = summary.song.id,
                        completedSongTitle = summary.song.title,
                        noteCountsByDifficulty = summary.noteCountsByDifficulty
                    )
                },
                onFailure = { error ->
                    _importDialog.value = ImportDialogState(
                        isAnalyzing = false,
                        progress = 0f,
                        statusText = "No se pudo importar el archivo",
                        errorMessage = error.message ?: "Error al procesar el archivo de audio."
                    )
                }
            )
        }
    }

    fun dismissImportDialog() {
        _importDialog.value = null
    }

    fun deleteImportedSong(songId: String) {
        stopAudioPreview()
        viewModelScope.launch {
            repository.deleteImportedSong(songId)
        }
    }

    fun selectShopCategory(category: ShopCategory) {
        _selectedShopCategory.value = category
        _shopFeedbackMessage.value = null
    }

    fun previewShopItem(item: ShopItemEntity) {
        when (ShopCategory.fromId(item.category)) {
            ShopCategory.TILE_THEME -> {
                _previewThemeId.value = item.id
                audioController.playLaneHitSfx(uiState.value.equippedSfx, 1)
            }
            ShopCategory.SFX_PACK -> {
                val profile = SfxProfile.fromId(item.id)
                audioController.playLaneHitSfx(profile, 2, isFever = true)
            }
            ShopCategory.HIT_TRAIL -> {
                _previewTrailId.value = item.id
                audioController.playLaneHitSfx(uiState.value.equippedSfx, 3)
            }
        }
        _shopPreviewNonce.value += 1
    }

    fun purchaseOrEquipShopItem(item: ShopItemEntity) {
        viewModelScope.launch {
            val wasUnlocked = item.isUnlocked
            val ok = repository.purchaseOrEquipShopItem(item.id)
            if (ok) {
                _previewThemeId.value = null
                _previewTrailId.value = null
                _shopPreviewNonce.value += 1
                _shopFeedbackMessage.value = if (wasUnlocked) {
                    "«${item.title}» equipado"
                } else {
                    "¡«${item.title}» desbloqueado!"
                }
                val sfxToPlay = if (item.category == ShopCategory.SFX_PACK.id) {
                    SfxProfile.fromId(item.id)
                } else {
                    uiState.value.equippedSfx
                }
                audioController.playLaneHitSfx(sfxToPlay, 3, isFever = true)
            } else {
                _shopFeedbackMessage.value = "Te faltan fragmentos (${item.price}) para «${item.title}»."
                audioController.playMissFeedback()
            }
        }
    }

    fun updateAppVisualStyle(style: AppVisualStyle) {
        viewModelScope.launch {
            prefsRepo.updateVisualStyle(style)
        }
    }

    fun updateBeatReactiveTiles(enabled: Boolean) {
        viewModelScope.launch {
            prefsRepo.updateBeatReactiveTiles(enabled)
        }
    }

    fun updateShowLaneGuides(enabled: Boolean) {
        viewModelScope.launch {
            prefsRepo.updateShowLaneGuides(enabled)
        }
    }

    fun updateAudioOffsetMs(offsetMs: Long) {
        viewModelScope.launch {
            prefsRepo.updateOffset(offsetMs)
        }
    }

    fun updateMusicVolume(volume: Float) {
        viewModelScope.launch {
            prefsRepo.updateMusicVolume(volume)
        }
    }

    fun updateSfxVolume(volume: Float) {
        viewModelScope.launch {
            prefsRepo.updateSfxVolume(volume)
        }
    }

    fun updateVibration(enabled: Boolean) {
        viewModelScope.launch {
            prefsRepo.updateVibration(enabled)
        }
    }

    fun updateHighBloom(enabled: Boolean) {
        viewModelScope.launch {
            prefsRepo.updateHighBloom(enabled)
        }
    }

    fun playCalibrationPulse(lane: Int = 2) {
        audioController.playLaneHitSfx(uiState.value.equippedSfx, lane)
    }

    fun playMetronomeClick(isAccent: Boolean) {
        audioController.playMetronomeClick(isAccent)
    }

    fun checkForAppUpdateIfNeeded() {
        if (hasCheckedStartupUpdate) return
        hasCheckedStartupUpdate = true
        checkForAppUpdate(silent = true)
    }

    fun checkForAppUpdate(silent: Boolean = false) {
        if (_updateState.value.isChecking || _updateState.value.isDownloading) return
        viewModelScope.launch {
            _updateState.value = _updateState.value.copy(
                isChecking = true,
                errorMessage = null,
                statusMessage = if (silent) _updateState.value.statusMessage else "Buscando actualizaciones..."
            )
            when (val result = appUpdateChecker.checkForUpdate(_updateState.value.currentVersion)) {
                is UpdateCheckResult.UpdateAvailable -> {
                    _updateState.value = _updateState.value.copy(
                        isChecking = false,
                        availableUpdate = result.info,
                        showUpdateDialog = true,
                        downloadedApkPath = null,
                        downloadProgress = 0f,
                        statusMessage = "Disponible versión ${result.info.tagName}",
                        errorMessage = null
                    )
                }
                is UpdateCheckResult.UpToDate -> {
                    _updateState.value = _updateState.value.copy(
                        isChecking = false,
                        availableUpdate = null,
                        showUpdateDialog = false,
                        statusMessage = if (silent) null else "Ya tienes la última versión (v${result.currentVersion}).",
                        errorMessage = null
                    )
                }
                is UpdateCheckResult.Error -> {
                    _updateState.value = _updateState.value.copy(
                        isChecking = false,
                        statusMessage = null,
                        errorMessage = if (silent) null else result.message
                    )
                }
            }
        }
    }

    fun downloadAvailableUpdate(cacheDir: File, onApkReady: (File) -> Unit = {}) {
        val info = _updateState.value.availableUpdate ?: return
        if (_updateState.value.isDownloading) return

        viewModelScope.launch {
            _updateState.value = _updateState.value.copy(
                isDownloading = true,
                downloadProgress = 0f,
                errorMessage = null,
                statusMessage = "Descargando ${info.apkFileName}..."
            )

            val updatesDir = File(cacheDir, "updates").apply { mkdirs() }
            val safeFileName = info.apkFileName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                .ifBlank { "OpenTiles-update.apk" }
            val destFile = File(updatesDir, safeFileName)

            val downloadRes = appUpdateChecker.downloadApk(
                downloadUrl = info.apkDownloadUrl,
                destFile = destFile,
                onProgress = { p ->
                    _updateState.value = _updateState.value.copy(downloadProgress = p)
                }
            )

            if (downloadRes.isSuccess) {
                val apkFile = downloadRes.getOrThrow()
                _updateState.value = _updateState.value.copy(
                    isDownloading = false,
                    downloadProgress = 1f,
                    downloadedApkPath = apkFile.absolutePath,
                    statusMessage = "APK descargado. Listo para instalar.",
                    errorMessage = null
                )
                onApkReady(apkFile)
            } else {
                _updateState.value = _updateState.value.copy(
                    isDownloading = false,
                    downloadProgress = 0f,
                    errorMessage = downloadRes.exceptionOrNull()?.message
                        ?: "No se pudo descargar la actualización."
                )
            }
        }
    }

    fun setUpdateStatusNotice(message: String) {
        _updateState.value = _updateState.value.copy(
            statusMessage = message,
            errorMessage = null
        )
    }

    fun dismissUpdateDialog() {
        if (_updateState.value.isDownloading) return
        _updateState.value = _updateState.value.copy(showUpdateDialog = false)
    }

    fun onAppPaused() {
        stopAudioPreview()
        audioController.pauseSong()
    }

    override fun onCleared() {
        super.onCleared()
        audioController.release()
    }

    private data class DataBundle(
        val songs: List<SongEntity>,
        val chartsMap: Map<Pair<String, Int>, ChartEntity>,
        val scoresMap: Map<Pair<String, Int>, ScoreRecordEntity>,
        val shopItems: List<ShopItemEntity>,
        val prefs: PlayerPreferences
    )

    private data class UiControlBundle(
        val screen: AppScreen,
        val selectedSongId: String,
        val difficulty: DifficultyLevel,
        val libraryFilter: LibraryFilter,
        val shopCategory: ShopCategory,
        val previewThemeId: String?,
        val previewTrailId: String?,
        val shopPreviewNonce: Int
    )

    private data class DialogBundle(
        val previewSongId: String?,
        val importDialog: ImportDialogState?,
        val shopMsg: String?,
        val lastResult: GameSessionResult?
    )

    class Factory(
        private val repository: RhythmRepository,
        private val prefsRepo: com.example.data.UserPreferencesRepository,
        private val audioController: GameAudioController,
        private val appUpdateChecker: AppUpdateChecker = AppUpdateChecker()
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return MainViewModel(
                repository = repository,
                prefsRepo = UserPreferencesRepositoryWrapper(prefsRepo),
                audioController = audioController,
                appUpdateChecker = appUpdateChecker
            ) as T
        }
    }
}
