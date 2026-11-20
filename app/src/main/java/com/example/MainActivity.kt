package com.example

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.audio.GameAudioController
import com.example.data.AppDatabase
import com.example.data.AppUpdateChecker
import com.example.data.InstallLaunchOutcome
import com.example.data.RhythmRepository
import com.example.data.UserPreferencesRepository
import com.example.ui.components.AppUpdateDialog
import com.example.ui.screens.GameplayScreen
import com.example.ui.screens.LibraryScreen
import com.example.ui.screens.ResultsScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.ShopScreen
import com.example.ui.theme.LocalAppStyleTokens
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.PrimaryViolet
import com.example.viewmodel.AppScreen
import com.example.viewmodel.MainViewModel
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val database = AppDatabase.getInstance(applicationContext)
        val prefsRepository = UserPreferencesRepository(applicationContext)
        val repository = RhythmRepository(
            dao = database.appDao(),
            prefsRepo = prefsRepository,
            context = applicationContext
        )
        val audioController = GameAudioController(applicationContext)
        val appUpdateChecker = AppUpdateChecker()

        setContent {
            val mainViewModel: MainViewModel = viewModel(
                factory = MainViewModel.Factory(
                    repository = repository,
                    prefsRepo = prefsRepository,
                    audioController = audioController,
                    appUpdateChecker = appUpdateChecker
                )
            )
            NeonResonanceApp(
                viewModel = mainViewModel,
                autoCheckUpdatesOnLaunch = true
            )
        }
    }
}

private data class NavTabItem(
    val screen: AppScreen,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String
)

private val mainNavTabs = listOf(
    NavTabItem(
        screen = AppScreen.LIBRARY,
        label = "Biblioteca",
        selectedIcon = Icons.Filled.LibraryMusic,
        unselectedIcon = Icons.Outlined.LibraryMusic,
        testTag = "nav_tab_library"
    ),
    NavTabItem(
        screen = AppScreen.SHOP,
        label = "Tienda",
        selectedIcon = Icons.Filled.Palette,
        unselectedIcon = Icons.Outlined.Palette,
        testTag = "nav_tab_shop"
    ),
    NavTabItem(
        screen = AppScreen.SETTINGS,
        label = "Ajustes",
        selectedIcon = Icons.Filled.Settings,
        unselectedIcon = Icons.Outlined.Settings,
        testTag = "nav_tab_settings"
    )
)

@Composable
fun NeonResonanceApp(
    viewModel: MainViewModel,
    autoCheckUpdatesOnLaunch: Boolean = false
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val updateState by viewModel.updateState.collectAsStateWithLifecycle()
    val activeEngine by viewModel.activeGameEngine.collectAsStateWithLifecycle()
    val view = LocalView.current
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(autoCheckUpdatesOnLaunch) {
        if (autoCheckUpdatesOnLaunch) {
            viewModel.checkForAppUpdateIfNeeded()
        }
    }

    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                viewModel.onAppPaused()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val useLightBars = !uiState.preferences.visualStyle.isDark && uiState.currentScreen != AppScreen.GAMEPLAY
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                val controller = WindowCompat.getInsetsController(window, view)
                controller.isAppearanceLightStatusBars = useLightBars
                controller.isAppearanceLightNavigationBars = useLightBars
            }
        }
    }

    MyApplicationTheme(visualStyle = uiState.preferences.visualStyle) {
        val tokens = LocalAppStyleTokens.current

        val audioPickerLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument()
        ) { uri ->
            if (uri != null) {
                viewModel.importAudioUri(uri)
            }
        }

        val launchAudioPicker = {
            audioPickerLauncher.launch(
                arrayOf(
                    "audio/mpeg",
                    "audio/mp3",
                    "audio/mp4",
                    "audio/x-m4a",
                    "audio/m4a",
                    "audio/aac",
                    "audio/wav",
                    "audio/x-wav",
                    "audio/ogg",
                    "application/ogg",
                    "audio/*"
                )
            )
        }

        if (uiState.currentScreen == AppScreen.GAMEPLAY && activeEngine != null) {
            GameplayScreen(
                engine = activeEngine!!,
                themePalette = uiState.equippedTheme,
                trailStyle = uiState.equippedTrail,
                audioOffsetMs = uiState.preferences.audioOffsetMs,
                highBloomEnabled = uiState.preferences.highBloomEnabled,
                beatReactiveTiles = uiState.preferences.beatReactiveTiles,
                showLaneGuides = uiState.preferences.showLaneGuides,
                audioController = viewModel.audioController,
                onNoteJudgment = { judgment, lane, isFever ->
                    viewModel.onGameplayNoteHitJudgment(judgment, lane, isFever)
                },
                onSessionFinished = { viewModel.finishGameplaySession() },
                onRestartClick = {
                    viewModel.startGameplay(
                        song = uiState.selectedSong,
                        difficulty = uiState.selectedDifficulty
                    )
                },
                onQuitToLibrary = { viewModel.quitGameplayToLibrary() }
            )
            return@MyApplicationTheme
        }

        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val isWideScreen = maxWidth >= 680.dp
            val showNavBars = uiState.currentScreen in setOf(
                AppScreen.LIBRARY,
                AppScreen.SHOP,
                AppScreen.SETTINGS
            )

            Scaffold(
                modifier = Modifier.fillMaxSize(),
                containerColor = tokens.canvasBackground,
                contentWindowInsets = WindowInsets.safeDrawing,
                bottomBar = {
                    if (showNavBars && !isWideScreen) {
                        NavigationBar(
                            containerColor = tokens.navSurface,
                            contentColor = tokens.textPrimary
                        ) {
                            mainNavTabs.forEach { tab ->
                                val selected = uiState.currentScreen == tab.screen
                                NavigationBarItem(
                                    selected = selected,
                                    onClick = { viewModel.navigateTo(tab.screen) },
                                    icon = {
                                        Icon(
                                            imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                            contentDescription = tab.label
                                        )
                                    },
                                    label = {
                                        Text(
                                            text = tab.label,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = tokens.accentSecondary,
                                        selectedTextColor = tokens.textPrimary,
                                        indicatorColor = PrimaryViolet.copy(alpha = 0.25f),
                                        unselectedIconColor = tokens.textSecondary,
                                        unselectedTextColor = tokens.textSecondary
                                    ),
                                    modifier = Modifier.testTag(tab.testTag)
                                )
                            }
                        }
                    }
                }
            ) { innerPadding ->
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    if (showNavBars && isWideScreen) {
                        NavigationRail(
                            containerColor = tokens.navSurface,
                            contentColor = tokens.textPrimary
                        ) {
                            mainNavTabs.forEach { tab ->
                                val selected = uiState.currentScreen == tab.screen
                                NavigationRailItem(
                                    selected = selected,
                                    onClick = { viewModel.navigateTo(tab.screen) },
                                    icon = {
                                        Icon(
                                            imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                            contentDescription = tab.label
                                        )
                                    },
                                    label = {
                                        Text(
                                            text = tab.label,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    },
                                    colors = NavigationRailItemDefaults.colors(
                                        selectedIconColor = tokens.accentSecondary,
                                        selectedTextColor = tokens.textPrimary,
                                        indicatorColor = PrimaryViolet.copy(alpha = 0.25f),
                                        unselectedIconColor = tokens.textSecondary,
                                        unselectedTextColor = tokens.textSecondary
                                    ),
                                    modifier = Modifier.testTag(tab.testTag)
                                )
                            }
                        }
                    }

                    when (uiState.currentScreen) {
                        AppScreen.LIBRARY -> {
                            LibraryScreen(
                                uiState = uiState,
                                onSelectFilter = viewModel::selectLibraryFilter,
                                onSelectSong = viewModel::selectSong,
                                onSelectDifficulty = viewModel::selectDifficulty,
                                onTogglePreview = viewModel::toggleSongPreview,
                                onStartGame = { viewModel.startGameplay() },
                                onImportAudioClick = launchAudioPicker,
                                onDeleteImportedSong = viewModel::deleteImportedSong,
                                onDismissImportDialog = viewModel::dismissImportDialog,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        AppScreen.SHOP -> {
                            ShopScreen(
                                uiState = uiState,
                                onSelectCategory = viewModel::selectShopCategory,
                                onPreviewItem = viewModel::previewShopItem,
                                onPurchaseOrEquip = viewModel::purchaseOrEquipShopItem,
                                onTestPadTap = { lane -> viewModel.playCalibrationPulse(lane) },
                                onBackToLibrary = { viewModel.navigateTo(AppScreen.LIBRARY) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        AppScreen.SETTINGS -> {
                            SettingsScreen(
                                preferences = uiState.preferences,
                                updateState = updateState,
                                onSelectVisualStyle = viewModel::updateAppVisualStyle,
                                onUpdateBeatReactiveTiles = viewModel::updateBeatReactiveTiles,
                                onUpdateShowLaneGuides = viewModel::updateShowLaneGuides,
                                onUpdateOffsetMs = viewModel::updateAudioOffsetMs,
                                onUpdateMusicVolume = viewModel::updateMusicVolume,
                                onUpdateSfxVolume = viewModel::updateSfxVolume,
                                onUpdateVibration = viewModel::updateVibration,
                                onUpdateHighBloom = viewModel::updateHighBloom,
                                onPlayMetronomeClick = viewModel::playMetronomeClick,
                                onCheckForUpdates = { viewModel.checkForAppUpdate(silent = false) },
                                onBackToLibrary = { viewModel.navigateTo(AppScreen.LIBRARY) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        AppScreen.RESULTS -> {
                            val res = uiState.lastSessionResult
                            if (res != null) {
                                ResultsScreen(
                                    result = res,
                                    onRetry = {
                                        val song = uiState.songs.firstOrNull { it.id == res.songId }
                                        viewModel.startGameplay(song, res.difficulty)
                                    },
                                    onNextDifficulty = { nextDiff ->
                                        val song = uiState.songs.firstOrNull { it.id == res.songId }
                                        viewModel.startGameplay(song, nextDiff)
                                    },
                                    onOpenShop = { viewModel.navigateTo(AppScreen.SHOP) },
                                    onBackToLibrary = { viewModel.navigateTo(AppScreen.LIBRARY) },
                                    modifier = Modifier.weight(1f)
                                )
                            } else {
                                viewModel.navigateTo(AppScreen.LIBRARY)
                            }
                        }
                        AppScreen.GAMEPLAY -> Unit
                    }
                }
            }

            if (updateState.showUpdateDialog && updateState.availableUpdate != null) {
                val launchInstaller: (File) -> Unit = { apkFile ->
                    when (AppUpdateChecker.launchInstallOrPermissionFlow(context, apkFile)) {
                        InstallLaunchOutcome.INSTALLER_OPENED -> {
                            viewModel.setUpdateStatusNotice("Instalador de Android abierto.")
                        }
                        InstallLaunchOutcome.PERMISSION_SETTINGS_OPENED -> {
                            viewModel.setUpdateStatusNotice(
                                "Activa «Permitir desde esta fuente» y vuelve a tocar Instalar APK."
                            )
                        }
                        InstallLaunchOutcome.FAILED -> {
                            viewModel.setUpdateStatusNotice(
                                "No se pudo abrir el instalador automáticamente. Toca Instalar APK."
                            )
                        }
                    }
                }

                AppUpdateDialog(
                    state = updateState,
                    onDownloadClick = {
                        viewModel.downloadAvailableUpdate(
                            cacheDir = context.cacheDir,
                            onApkReady = launchInstaller
                        )
                    },
                    onInstallClick = { path ->
                        launchInstaller(File(path))
                    },
                    onDismiss = viewModel::dismissUpdateDialog
                )
            }
        }
    }
}
