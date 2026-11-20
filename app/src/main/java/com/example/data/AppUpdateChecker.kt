package com.example.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import kotlin.math.max

data class AppUpdateInfo(
    val tagName: String,
    val versionName: String,
    val releaseName: String,
    val releaseNotes: String,
    val apkDownloadUrl: String,
    val apkFileName: String,
    val apkSizeBytes: Long,
    val htmlUrl: String
)

sealed class UpdateCheckResult {
    data class UpdateAvailable(val info: AppUpdateInfo) : UpdateCheckResult()
    data class UpToDate(val currentVersion: String, val latestVersion: String?) : UpdateCheckResult()
    data class Error(val message: String) : UpdateCheckResult()
}

enum class InstallLaunchOutcome {
    INSTALLER_OPENED,
    PERMISSION_SETTINGS_OPENED,
    FAILED
}

/**
 * Consulta GitHub Releases para `abel12345emilianoesc/OpenTiles` mediante OkHttp,
 * compara la versión remota contra `BuildConfig.VERSION_NAME`, descarga el `.apk`
 * con reporte de progreso y abre el instalador del sistema usando `FileProvider`.
 */
open class AppUpdateChecker(
    private val githubRepo: String = DEFAULT_GITHUB_REPO,
    private val httpClient: OkHttpClient = defaultHttpClient()
) {

    open suspend fun checkForUpdate(
        currentVersion: String = BuildConfig.VERSION_NAME
    ): UpdateCheckResult = withContext(Dispatchers.IO) {
        val url = "https://api.github.com/repos/$githubRepo/releases/latest"
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", USER_AGENT)
            .header("X-GitHub-Api-Version", "2022-11-28")
            .get()
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                if (response.code == 404) {
                    return@withContext UpdateCheckResult.UpToDate(
                        currentVersion = normalizeVersion(currentVersion),
                        latestVersion = null
                    )
                }
                if (!response.isSuccessful) {
                    return@withContext UpdateCheckResult.Error(
                        "No se pudo consultar GitHub (HTTP ${response.code})."
                    )
                }

                val body = response.body?.string().orEmpty()
                if (body.isBlank()) {
                    return@withContext UpdateCheckResult.Error("Respuesta vacía del servidor de actualizaciones.")
                }

                val releaseInfo = parseLatestReleaseJson(body)
                    ?: return@withContext UpdateCheckResult.UpToDate(
                        currentVersion = normalizeVersion(currentVersion),
                        latestVersion = null
                    )

                if (isRemoteVersionNewer(releaseInfo.tagName, currentVersion)) {
                    UpdateCheckResult.UpdateAvailable(releaseInfo)
                } else {
                    UpdateCheckResult.UpToDate(
                        currentVersion = normalizeVersion(currentVersion),
                        latestVersion = releaseInfo.versionName
                    )
                }
            }
        } catch (_: Exception) {
            UpdateCheckResult.Error("No se pudo conectar con GitHub. Verifica tu conexión a internet.")
        }
    }

    open suspend fun downloadApk(
        downloadUrl: String,
        destFile: File,
        onProgress: (Float) -> Unit = {}
    ): Result<File> = withContext(Dispatchers.IO) {
        val tempFile = File(destFile.parentFile, "${destFile.name}.part")
        try {
            destFile.parentFile?.mkdirs()
            if (tempFile.exists()) tempFile.delete()

            val request = Request.Builder()
                .url(downloadUrl)
                .header("Accept", "application/octet-stream")
                .header("User-Agent", USER_AGENT)
                .get()
                .build()

            onProgress(0.02f)
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        IllegalStateException("Error HTTP ${response.code} al descargar el APK.")
                    )
                }
                val body = response.body
                    ?: return@withContext Result.failure(IllegalStateException("El APK llegó vacío."))
                val contentLength = body.contentLength()

                body.byteStream().use { input ->
                    FileOutputStream(tempFile).use { output ->
                        val buffer = ByteArray(16384)
                        var downloaded = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            if (contentLength > 0L) {
                                val frac = (downloaded.toFloat() / contentLength.toFloat()).coerceIn(0f, 1f)
                                onProgress(0.02f + frac * 0.98f)
                            }
                        }
                        output.flush()
                    }
                }
            }

            if (!tempFile.exists() || tempFile.length() < 1024L) {
                tempFile.delete()
                return@withContext Result.failure(
                    IllegalStateException("La descarga del APK está incompleta.")
                )
            }

            if (destFile.exists()) destFile.delete()
            if (!tempFile.renameTo(destFile)) {
                tempFile.copyTo(destFile, overwrite = true)
                tempFile.delete()
            }

            onProgress(1f)
            Result.success(destFile)
        } catch (e: Exception) {
            try {
                if (tempFile.exists()) tempFile.delete()
            } catch (_: Exception) {
            }
            Result.failure(
                IllegalStateException(e.message ?: "Error al descargar la actualización.")
            )
        }
    }

    companion object {
        const val DEFAULT_GITHUB_REPO = "abel12345emilianoesc/OpenTiles"
        private const val USER_AGENT = "OpenTiles-Android-Updater"

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

        fun normalizeVersion(rawVersion: String): String {
            return rawVersion
                .trim()
                .removePrefix("v")
                .removePrefix("V")
                .substringBefore("-")
                .substringBefore("+")
                .trim()
        }

        /**
         * Compara semánticamente `remoteTag` (ej. `v1.0.12`) contra `currentVersion` (ej. `1.0` o `1.0.2`).
         * Devuelve `true` solo si la versión remota es estrictamente mayor.
         */
        fun isRemoteVersionNewer(remoteTag: String, currentVersion: String): Boolean {
            val remoteParts = parseNumericVersionParts(remoteTag)
            val currentParts = parseNumericVersionParts(currentVersion)
            if (remoteParts.isEmpty()) return false
            val maxLen = max(remoteParts.size, currentParts.size)
            for (i in 0 until maxLen) {
                val r = remoteParts.getOrElse(i) { 0 }
                val c = currentParts.getOrElse(i) { 0 }
                if (r != c) return r > c
            }
            return false
        }

        private fun parseNumericVersionParts(version: String): List<Int> {
            val normalized = normalizeVersion(version)
            if (normalized.isBlank()) return emptyList()
            return normalized.split(".").mapNotNull { segment ->
                segment.filter { it.isDigit() }.toIntOrNull()
            }
        }

        /**
         * Parsea la respuesta JSON de `/releases/latest` de GitHub y selecciona el asset `.apk`
         * (priorizando un APK release si existen varios).
         */
        fun parseLatestReleaseJson(jsonBody: String): AppUpdateInfo? {
            return try {
                val root = JSONObject(jsonBody)
                val tagName = root.optString("tag_name", "").trim()
                if (tagName.isBlank()) return null

                val releaseName = root.optString("name", "").trim().ifBlank { tagName }
                val releaseNotes = root.optString("body", "").trim()
                val htmlUrl = root.optString("html_url", "").trim()

                val assets = root.optJSONArray("assets") ?: return null
                var selectedApk: JSONObject? = null
                for (i in 0 until assets.length()) {
                    val asset = assets.optJSONObject(i) ?: continue
                    val name = asset.optString("name", "").trim()
                    val downloadUrl = asset.optString("browser_download_url", "").trim()
                    if (name.endsWith(".apk", ignoreCase = true) && downloadUrl.isNotBlank()) {
                        if (selectedApk == null || name.contains("release", ignoreCase = true)) {
                            selectedApk = asset
                            if (name.contains("release", ignoreCase = true)) break
                        }
                    }
                }

                val apkAsset = selectedApk ?: return null
                val apkFileName = apkAsset.optString("name", "OpenTiles-update.apk").trim()
                    .ifBlank { "OpenTiles-update.apk" }
                val apkUrl = apkAsset.optString("browser_download_url", "").trim()
                val apkSize = apkAsset.optLong("size", 0L).coerceAtLeast(0L)

                AppUpdateInfo(
                    tagName = tagName,
                    versionName = normalizeVersion(tagName),
                    releaseName = releaseName,
                    releaseNotes = releaseNotes,
                    apkDownloadUrl = apkUrl,
                    apkFileName = apkFileName,
                    apkSizeBytes = apkSize,
                    htmlUrl = htmlUrl
                )
            } catch (_: Exception) {
                null
            }
        }

        fun buildInstallApkIntent(context: Context, apkFile: File): Intent {
            val apkUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )
            return Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }

        fun launchInstallOrPermissionFlow(context: Context, apkFile: File): InstallLaunchOutcome {
            if (!apkFile.exists() || apkFile.length() < 1024L) {
                return InstallLaunchOutcome.FAILED
            }
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                    !context.packageManager.canRequestPackageInstalls()
                ) {
                    val permIntent = Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${context.packageName}")
                    ).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(permIntent)
                    InstallLaunchOutcome.PERMISSION_SETTINGS_OPENED
                } else {
                    val installIntent = buildInstallApkIntent(context, apkFile)
                    context.startActivity(installIntent)
                    InstallLaunchOutcome.INSTALLER_OPENED
                }
            } catch (_: Exception) {
                InstallLaunchOutcome.FAILED
            }
        }
    }
}
