package com.example

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import android.content.Intent
import com.example.audio.GameAudioController
import com.example.audio.SynthesizedAudioEngine
import com.example.data.AppDatabase
import com.example.data.AppUpdateChecker
import com.example.data.RhythmRepository
import com.example.data.UpdateCheckResult
import com.example.data.UserPreferencesRepository
import java.io.File
import com.example.domain.AppVisualStyle
import com.example.domain.DifficultyLevel
import com.example.domain.ShopCategory
import com.example.ui.theme.MyApplicationTheme
import com.example.viewmodel.AppScreen
import com.example.viewmodel.MainViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
class ExampleRobolectricTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var prefsRepository: UserPreferencesRepository
    private lateinit var repository: RhythmRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        prefsRepository = UserPreferencesRepository(context = null)
        repository = RhythmRepository(
            dao = database.appDao(),
            prefsRepo = prefsRepository,
            context = null
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `app name library shop and settings labels are in Spanish`() {
        assertEquals("Custom Tile", context.getString(R.string.app_name))
        assertEquals("Biblioteca", context.getString(R.string.nav_library))
        assertEquals("Tienda", context.getString(R.string.nav_shop))
        assertEquals("Configuración", context.getString(R.string.nav_settings))
    }

    @Test
    fun `repository seeds 4 built-in songs 25 shop items and preserves purchases on incremental sync`(): Unit = runBlocking {
        repository.ensureSeeded()

        val songs = repository.songsFlow.first()
        val charts = repository.allChartsFlow.first()
        val shopItems = repository.shopItemsFlow.first()

        assertEquals(4, songs.size)
        assertEquals(20, charts.size)
        assertEquals(25, shopItems.size)
        assertTrue("Las canciones base deben incluir teclas de mantener", charts.any { it.holdCount > 0 })

        val testSamples = SynthesizedAudioEngine.generateSongSamples(SynthesizedAudioEngine.builtInSongs.first())
        val summary = repository.registerAnalyzedSong(
            title = "Mi Canción Importada",
            artist = "Artista Local",
            samples = testSamples,
            sampleRate = 22050,
            durationMs = 15_000L
        )
        assertEquals(5, summary.noteCountsByDifficulty.size)
        val updatedSongs = repository.songsFlow.first()
        assertEquals(5, updatedSongs.size)

        val crimsonTheme = shopItems.first { it.id == "theme_crimson_pulse" }
        assertFalse(crimsonTheme.isUnlocked)
        val purchaseOk = repository.purchaseOrEquipShopItem(crimsonTheme.id)
        assertTrue("La compra de Pulso Carmesí debe ser exitosa", purchaseOk)

        // Volver a ejecutar ensureSeeded() debe conservar compras y estado equipado sin sobrescribir
        repository.ensureSeeded()
        val itemsAfterResync = repository.shopItemsFlow.first()
        assertEquals(25, itemsAfterResync.size)
        assertTrue(itemsAfterResync.first { it.id == "theme_crimson_pulse" }.isUnlocked)
        assertTrue(itemsAfterResync.first { it.id == "theme_crimson_pulse" }.isEquipped)

        val prefsAfterBuy = repository.preferencesFlow.first()
        assertEquals(200, prefsAfterBuy.resonanceFragments)
        assertEquals("theme_crimson_pulse", prefsAfterBuy.equippedThemeId)

        val expensiveTheme = shopItems.first { it.id == "theme_chromatic_spectrum" }
        val failedBuy = repository.purchaseOrEquipShopItem(expensiveTheme.id)
        assertFalse("No debe permitir comprar sin fragmentos suficientes", failedBuy)
    }

    private fun waitUntilIdleAndCondition(condition: () -> Boolean) {
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            condition()
        }
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        composeTestRule.waitForIdle()
    }

    @Test
    fun `CUJ navigate across Library Shop Configuracion OLED and Gameplay with local-only import`() {
        runBlocking {
            repository.ensureSeeded()
        }

        val viewModel = MainViewModel(
            repository = repository,
            prefsRepo = MainViewModel.UserPreferencesRepositoryWrapper(prefsRepository),
            audioController = GameAudioController(context = null)
        )

        composeTestRule.setContent {
            MyApplicationTheme {
                NeonResonanceApp(viewModel = viewModel)
            }
        }
        waitUntilIdleAndCondition {
            viewModel.uiState.value.songs.isNotEmpty() && viewModel.uiState.value.shopItems.size == 25
        }

        // 1. Biblioteca: sin pestaña/botón de búsqueda online, solo importación local + filtros + 5 dificultades
        composeTestRule.onNodeWithText("CUSTOM TILE").assertIsDisplayed()
        composeTestRule.onNodeWithTag("nav_tab_explore").assertDoesNotExist()
        composeTestRule.onNodeWithTag("open_online_search_button").assertDoesNotExist()
        composeTestRule.onNodeWithTag("import_song_button").assertIsDisplayed()

        composeTestRule.onNodeWithTag("library_filter_IMPORTED").performClick()
        waitUntilIdleAndCondition {
            viewModel.uiState.value.filteredSongs.isEmpty()
        }
        composeTestRule.onNodeWithText("Aún no tienes canciones importadas").assertIsDisplayed()
        composeTestRule.onNodeWithTag("library_filter_ALL").performClick()
        waitUntilIdleAndCondition {
            viewModel.uiState.value.filteredSongs.size == 4
        }

        composeTestRule.onNodeWithTag("difficulty_chip_5").performClick()
        waitUntilIdleAndCondition {
            viewModel.uiState.value.selectedDifficulty == DifficultyLevel.RESONANCIA_MAESTRA
        }
        composeTestRule.onNodeWithText("Transcripción Total (100% fidelidad)").assertIsDisplayed()

        // 2. Tienda: probar pad interactivo, comprar/equipar tonalidad, cambiar a SFX y previsualizar nueva estela
        composeTestRule.onNodeWithTag("nav_tab_shop").performClick()
        waitUntilIdleAndCondition {
            viewModel.uiState.value.currentScreen == AppScreen.SHOP
        }
        composeTestRule.onNodeWithText("TIENDA").assertIsDisplayed()
        composeTestRule.onNodeWithTag("shop_preview_pad").performClick()
        composeTestRule.onNodeWithTag("shop_items_list").performScrollToIndex(1)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("action_shop_item_theme_crimson_pulse").performClick()
        waitUntilIdleAndCondition {
            viewModel.uiState.value.preferences.equippedThemeId == "theme_crimson_pulse"
        }

        composeTestRule.onNodeWithTag("shop_tab_SFX").performClick()
        waitUntilIdleAndCondition {
            viewModel.uiState.value.selectedShopCategory == ShopCategory.SFX_PACK
        }
        composeTestRule.onNodeWithText("Cristal Etéreo").assertIsDisplayed()

        composeTestRule.onNodeWithTag("shop_tab_TRAIL").performClick()
        waitUntilIdleAndCondition {
            viewModel.uiState.value.selectedShopCategory == ShopCategory.HIT_TRAIL
        }
        composeTestRule.onNodeWithTag("preview_shop_item_trail_starburst").performClick()
        waitUntilIdleAndCondition {
            viewModel.uiState.value.previewTrailId == "trail_starburst"
        }

        // 4. Ajustes: seleccionar estilo OLED Puro, Claro Cristal y completar prueba de metrónomo de 8 toques
        composeTestRule.onNodeWithTag("nav_tab_settings").performClick()
        waitUntilIdleAndCondition {
            viewModel.uiState.value.currentScreen == AppScreen.SETTINGS &&
                viewModel.uiState.value.previewTrailId == null
        }
        composeTestRule.onNodeWithText("AJUSTES").assertIsDisplayed()
        composeTestRule.onNodeWithTag("visual_style_style_oled_pure").performClick()
        waitUntilIdleAndCondition {
            viewModel.uiState.value.preferences.visualStyle == AppVisualStyle.OLED_PURE
        }
        composeTestRule.onNodeWithTag("visual_style_style_frost_light").performClick()
        waitUntilIdleAndCondition {
            viewModel.uiState.value.preferences.visualStyle == AppVisualStyle.FROST_LIGHT
        }

        composeTestRule.onNodeWithTag("start_calibration_test_button")
            .performScrollTo()
            .performClick()
        composeTestRule.waitForIdle()
        repeat(8) {
            composeTestRule.onNodeWithTag("calibration_tap_button")
                .performScrollTo()
                .performClick()
            composeTestRule.waitForIdle()
        }
        composeTestRule.onNodeWithTag("save_calibration_button")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        composeTestRule.waitForIdle()

        // 5. Volver a Biblioteca e iniciar partida: pre-roll negativo y SIN combo central en la pista
        composeTestRule.onNodeWithTag("nav_tab_library").performClick()
        waitUntilIdleAndCondition {
            viewModel.uiState.value.currentScreen == AppScreen.LIBRARY
        }
        composeTestRule.onNodeWithTag("play_song_button").performClick()
        waitUntilIdleAndCondition {
            viewModel.activeGameEngine.value != null &&
                viewModel.uiState.value.currentScreen == AppScreen.GAMEPLAY
        }

        assertTrue(
            "El motor debe iniciar con pre-roll negativo (< 0 ms)",
            viewModel.activeGameEngine.value!!.currentTimeMs < 0L
        )
        composeTestRule.onNodeWithTag("gameplay_root").assertIsDisplayed()
        composeTestRule.onNodeWithTag("gameplay_score_text").assertIsDisplayed()
        composeTestRule.onNodeWithTag("center_combo_counter").assertDoesNotExist()
    }

    @Test
    fun `CUJ SettingsScreen Buscar actualizaciones fetches GitHub latest release downloads APK and builds FileProvider intent`() {
        runBlocking {
            repository.ensureSeeded()
        }

        var requestedReleaseUrl = ""
        val mockApkBytes = ByteArray(2048) { (it % 127).toByte() }
        val mockUpdaterClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val url = chain.request().url.toString()
                if (url.contains("/releases/latest")) {
                    requestedReleaseUrl = url
                    val releaseJson = """
                        {
                          "tag_name": "v1.0.42",
                          "name": "OpenTiles v1.0.42",
                          "body": "Mejoras de ritmo y corrección de errores.",
                          "html_url": "https://github.com/abel12345emilianoesc/OpenTiles/releases/tag/v1.0.42",
                          "assets": [
                            {
                              "name": "OpenTiles-v1.0.42-debug.apk",
                              "size": 2048,
                              "browser_download_url": "https://github.com/abel12345emilianoesc/OpenTiles/releases/download/v1.0.42/OpenTiles-v1.0.42-debug.apk"
                            },
                            {
                              "name": "OpenTiles-v1.0.42-release.apk",
                              "size": 2048,
                              "browser_download_url": "https://github.com/abel12345emilianoesc/OpenTiles/releases/download/v1.0.42/OpenTiles-v1.0.42-release.apk"
                            }
                          ]
                        }
                    """.trimIndent()
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(releaseJson.toResponseBody("application/json".toMediaType()))
                        .build()
                } else {
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .header("Content-Length", mockApkBytes.size.toString())
                        .body(mockApkBytes.toResponseBody("application/vnd.android.package-archive".toMediaType()))
                        .build()
                }
            }
            .build()

        val updateChecker = AppUpdateChecker(httpClient = mockUpdaterClient)

        // Verificar también caso UpToDate directamente
        val upToDateCheck = runBlocking { updateChecker.checkForUpdate(currentVersion = "1.0.42") }
        assertTrue(upToDateCheck is UpdateCheckResult.UpToDate)

        val viewModel = MainViewModel(
            repository = repository,
            prefsRepo = MainViewModel.UserPreferencesRepositoryWrapper(prefsRepository),
            audioController = GameAudioController(context = null),
            appUpdateChecker = updateChecker
        )

        composeTestRule.setContent {
            MyApplicationTheme {
                NeonResonanceApp(viewModel = viewModel)
            }
        }
        waitUntilIdleAndCondition {
            viewModel.uiState.value.songs.size == 4
        }

        // Navegar a Ajustes y verificar versión actual + botón "Buscar actualizaciones"
        composeTestRule.onNodeWithTag("nav_tab_settings").performClick()
        waitUntilIdleAndCondition {
            viewModel.uiState.value.currentScreen == AppScreen.SETTINGS
        }
        composeTestRule.onNodeWithTag("settings_version_text").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("check_updates_button").performScrollTo().performClick()

        // Debe consultar el endpoint correcto y mostrar el diálogo de actualización con el APK release preferido
        waitUntilIdleAndCondition {
            viewModel.updateState.value.showUpdateDialog &&
                viewModel.updateState.value.availableUpdate?.tagName == "v1.0.42"
        }
        assertEquals(
            "https://api.github.com/repos/abel12345emilianoesc/OpenTiles/releases/latest",
            requestedReleaseUrl
        )
        assertEquals(
            "OpenTiles-v1.0.42-release.apk",
            viewModel.updateState.value.availableUpdate?.apkFileName
        )
        composeTestRule.onNodeWithTag("app_update_dialog").assertIsDisplayed()
        composeTestRule.onNodeWithText("Nueva actualización").assertIsDisplayed()

        // Descargar APK y verificar FileProvider Intent válido
        composeTestRule.onNodeWithTag("confirm_update_action_button").performClick()
        waitUntilIdleAndCondition {
            !viewModel.updateState.value.isDownloading &&
                viewModel.updateState.value.downloadedApkPath != null
        }

        val apkFile = File(viewModel.updateState.value.downloadedApkPath!!)
        assertTrue("El APK descargado debe existir en caché/updates", apkFile.exists() && apkFile.length() == 2048L)

        val installIntent = AppUpdateChecker.buildInstallApkIntent(context, apkFile)
        assertEquals(Intent.ACTION_VIEW, installIntent.action)
        assertEquals("application/vnd.android.package-archive", installIntent.type)
        assertTrue((installIntent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0)
        assertTrue(installIntent.data.toString().startsWith("content://${context.packageName}.fileprovider/"))
    }
}
