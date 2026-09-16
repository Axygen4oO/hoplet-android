package com.wdtt.client

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.LinkedHashMap
import javax.net.ssl.SSLException
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

const val UPDATE_CHECK_NEVER = -1
const val DEFAULT_UPDATE_CHECK_INTERVAL_HOURS = 12
const val UPDATE_DIALOG_ACTION_POSTPONED = "postponed"
const val UPDATE_DIALOG_ACTION_UPDATE = "update"
internal const val OTA_PACKAGE_NAME = "net.qwdtt.client"
internal const val PRODUCTION_CERTIFICATE_SHA256 = "3D273C91326499C298C1B655E96640D7056ED96DE2489A094FC6AB638287F27C"
private const val UPDATE_LOG_TAG = "HopletUpdate"
private const val GITHUB_RELEASES_URL = "https://api.github.com/repos/Axygen4oO/hoplet-android/releases?per_page=30"
private const val GITHUB_API_RATE_LIMIT_FALLBACK_MS = 30L * 60L * 1000L
private const val RELEASE_NOTES_CACHE_LIMIT = 12
private val VERSION_NUMBER_REGEX = Regex("\\d+(?:\\.\\d+)*")
private val SHA256_REGEX = Regex("\\b[a-fA-F0-9]{64}\\b")
private val otaHttpClient by lazy {
    OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
}
@Volatile private var githubApiCooldownUntilMs = 0L
@Volatile private var lastUpdateRequestErrorMessage = ""
private val updateCheckMutex = Mutex()

fun updateIntervalHoursToMillis(hours: Int): Long? = hours.takeIf { it > 0 }?.times(60L * 60L * 1000L)

data class AppReleaseInfo(
    val versionTag: String,
    val releaseUrl: String,
    val source: RemoteVersionSource = RemoteVersionSource.Release,
    val downloadUrl: String? = null,
    val releaseNotes: String = "",
    val isPrerelease: Boolean = false,
    val downloadFileName: String? = null,
    val downloadSizeBytes: Long = 0L,
    val expectedSha256: String? = null,
    val sha256AssetUrl: String? = null,
    val versionName: String? = null,
    val versionCode: Long? = null,
    val packageName: String? = null,
    val updateManifestUrl: String? = null,
    val publishedAt: String? = null,
    val mandatory: Boolean = false,
    val isDraft: Boolean = false,
)

/** Compatibility marker for persisted snapshots; OTA discovery never creates Tag values. */
@Deprecated("GitHub tags are not an OTA source")
enum class RemoteVersionSource { Release, Tag }

data class ReleaseChangelogItem(val versionTag: String, val publishedAt: String, val body: String, val isPrerelease: Boolean, val releaseUrl: String)
data class UpdateCheckOutcome(val checkedAt: Long, val release: AppReleaseInfo?, val errorMessage: String)
internal data class UpdateManifest(val versionName: String, val versionCode: Long, val packageName: String, val apk: String, val sha256: String, val mandatory: Boolean, val releaseNotes: String, val publishedAt: String?, val tag: String = "", val packageNamePresent: Boolean = true)

private val releaseNotesCache = object {
    private val values = LinkedHashMap<String, String>(RELEASE_NOTES_CACHE_LIMIT, 0.75f, true)
    @Synchronized fun get(key: String): String? = values[key]
    @Synchronized fun put(key: String, notes: String) { if (key.isBlank() || notes.isBlank()) return; values[key] = notes.trim(); while (values.size > RELEASE_NOTES_CACHE_LIMIT) values.remove(values.entries.first().key) }
    @Synchronized fun clear() = values.clear()
}
private fun releaseCacheKey(release: AppReleaseInfo) = "${release.versionCode ?: -1}:${release.versionName.orEmpty()}"
fun mergeReleaseInfo(existing: AppReleaseInfo?, incoming: AppReleaseInfo): AppReleaseInfo {
    val same = existing != null && ((existing.versionCode != null && existing.versionCode == incoming.versionCode) || normalizeVersionTag(existing.versionTag) == normalizeVersionTag(incoming.versionTag))
    val key = releaseCacheKey(incoming)
    val notes = incoming.releaseNotes.trim().ifBlank { existing?.releaseNotes?.trim().takeIf { same && !it.isNullOrBlank() } ?: releaseNotesCache.get(key).orEmpty() }
    if (notes.isNotBlank()) releaseNotesCache.put(key, notes)
    val preferred = if (same && existing?.source == RemoteVersionSource.Release && incoming.source != RemoteVersionSource.Release) existing else incoming
    return preferred!!.copy(releaseNotes = notes)
}
internal fun clearReleaseNotesCacheForTests() = releaseNotesCache.clear()

internal fun parseUpdateManifest(raw: String): UpdateManifest? = runCatching {
    val json = JSONObject(raw)
    val name = (json.opt("versionName") as? String)?.trim()?.takeIf(String::isNotEmpty) ?: return@runCatching null
    val number = json.opt("versionCode") as? Number ?: return@runCatching null
    val code = number.toLong(); if (code <= 0L || number.toDouble() != code.toDouble()) return@runCatching null
    val pkgRaw = (json.opt("packageName") as? String)?.trim()
    val pkg = pkgRaw?.takeIf(String::isNotEmpty) ?: OTA_PACKAGE_NAME
    val apk = (json.opt("apk") as? String)?.trim()?.takeIf(String::isNotEmpty) ?: return@runCatching null
    if (apk.contains('/') || apk.contains('\\') || !isSafeOtaApkName(apk)) return@runCatching null
    val sha = normalizeSha256(json.optString("sha256")) ?: return@runCatching null
    if (!json.has("mandatory") || json.opt("mandatory") !is Boolean) return@runCatching null
    UpdateManifest(name, code, pkg, apk, sha, json.getBoolean("mandatory"), (json.opt("releaseNotes") as? String)?.trim().orEmpty(), (json.opt("publishedAt") as? String)?.trim()?.ifBlank { null }, normalizeVersionTag(json.optString("tag")), pkgRaw != null)
}.getOrNull()

suspend fun fetchLatestReleaseInfo(@Suppress("UNUSED_PARAMETER") localVersion: String? = null, includePrerelease: Boolean = false): AppReleaseInfo? = updateCheckMutex.withLock {
    withContext(Dispatchers.IO) {
    clearLastUpdateRequestError()
    val raw = fetchGitHubApi(GITHUB_RELEASES_URL) ?: return@withContext null
    val releases = runCatching { JSONArray(raw) }.getOrElse { setLastUpdateRequestError("Сервер обновлений вернул повреждённый список релизов"); return@withContext null }
    var best: AppReleaseInfo? = null
    var rejected = 0
    for (i in 0 until releases.length()) {
        val json = releases.optJSONObject(i) ?: continue
        if (json.optBoolean("draft") || (!includePrerelease && json.optBoolean("prerelease"))) continue
        val shell = json.toAppReleaseInfo()
        if (shell == null) { rejected++; continue }
        val candidate = enrichReleaseFromManifest(shell)
        if (candidate == null) { rejected++; continue }
        if (best == null || candidate.versionCode!! > best.versionCode!!) best = candidate
    }
    if (best == null && rejected > 0) setLastUpdateRequestError("Опубликованные релизы не прошли проверку OTA-метаданных")
    best
    }
}

internal fun selectOtaCandidate(publishedRelease: AppReleaseInfo?, informationalTag: AppReleaseInfo? = null, includePrerelease: Boolean = false): AppReleaseInfo? = publishedRelease?.takeIf { isInstallableOtaRelease(it) && (includePrerelease || !it.isPrerelease) }
internal fun isInstallableOtaRelease(release: AppReleaseInfo): Boolean {
    if (release.isDraft || release.downloadUrl.isNullOrBlank() || !isSafeOtaApkName(release.downloadFileName.orEmpty())) return false
    // Discovery always sets updateManifestUrl and therefore takes the strict path.
    // The relaxed branch keeps persisted pre-1.4 snapshots readable during migration.
    if (release.updateManifestUrl == null) return true
    return release.versionCode?.let { it > 0L } == true &&
        !release.versionName.isNullOrBlank() &&
        release.packageName == BuildConfig.APPLICATION_ID &&
        release.packageName == OTA_PACKAGE_NAME &&
        normalizeSha256(release.expectedSha256) != null
}
internal fun isProductionOtaRelease(release: AppReleaseInfo): Boolean =
    !release.isPrerelease && !release.isDraft && isInstallableOtaRelease(release)
internal fun isSafeOtaApkName(fileName: String): Boolean = fileName.trim().lowercase() in setOf("app-release.apk", "app-universal-release.apk")

internal fun resolveManifestApkUrl(release: AppReleaseInfo, apk: String?): Pair<String, String>? {
    val name = apk?.trim()?.substringAfterLast('/') ?: return null
    if (!isSafeOtaApkName(name)) return null
    val value = apk.trim()
    if (value.startsWith("https://", true)) {
        if (value.contains("/releases/tag/", true) || value.endsWith("/releases/latest", true)) return null
        return value to name
    }
    release.downloadUrl?.let { return it to name }
    val tag = normalizeVersionTag(release.versionTag)
    return "https://github.com/Axygen4oO/hoplet-android/releases/download/$tag/$name" to name
}
internal fun buildGitHubAssetDownloadUrl(versionTag: String, fileName: String): String? = null

suspend fun performAppUpdateCheck(localVersion: String? = null, includePrerelease: Boolean = false): UpdateCheckOutcome = withContext(Dispatchers.IO) {
    val checkedAt = System.currentTimeMillis(); val release = fetchLatestReleaseInfo(localVersion, includePrerelease); UpdateCheckOutcome(checkedAt, release, if (release == null) lastUpdateRequestErrorMessage else "")
}
suspend fun fetchReleaseChangelog(includePrerelease: Boolean = false, limit: Int = 12): List<ReleaseChangelogItem> = withContext(Dispatchers.IO) {
    val releases = runCatching { JSONArray(fetchGitHubApi(GITHUB_RELEASES_URL) ?: return@withContext emptyList()) }.getOrElse { return@withContext emptyList() }
    buildList { for (i in 0 until releases.length()) { val j = releases.optJSONObject(i) ?: continue; if (j.optBoolean("draft") || (!includePrerelease && j.optBoolean("prerelease"))) continue; val tag = normalizeVersionTag(j.optString("tag_name")); val url = j.optString("html_url"); if (tag.isBlank() || url.isBlank()) continue; add(ReleaseChangelogItem(tag, j.optString("published_at").substringBefore('T'), j.optString("body").trim(), j.optBoolean("prerelease"), url)); if (size >= limit) break } }
}
suspend fun fetchReleaseNotesForVersion(@Suppress("UNUSED_PARAMETER") versionTag: String): String = withContext(Dispatchers.IO) { "" }
fun bundledReleaseNotes(versionTag: String): String = if (normalizeVersionTag(versionTag) == "v1.3.2") "• Улучшен механизм получения обновлений" else ""
fun sanitizedReleaseNotes(notes: String): String = notes.replace(Regex("https?://\\S+", RegexOption.IGNORE_CASE), "").replace(Regex("[ \\t]+\\n"), "\n").trim()

fun isNewerVersion(local: String, remote: String, includePrerelease: Boolean = false): Boolean { val l = parseVersionTag(local); val r = parseVersionTag(remote); if (r.core.isEmpty()) return false; if (l.core.isEmpty()) return true; for (i in 0 until maxOf(l.core.size, r.core.size)) { val c = r.core.getOrElse(i) { 0 }.compareTo(l.core.getOrElse(i) { 0 }); if (c != 0) return c > 0 }; if (l.prerelease == null && r.prerelease == null) return false; if (l.prerelease == null) return includePrerelease; if (r.prerelease == null) return true; return includePrerelease && r.prerelease != l.prerelease }
fun isNewerRelease(@Suppress("UNUSED_PARAMETER") localVersionName: String, localVersionCode: Long, remote: AppReleaseInfo, @Suppress("UNUSED_PARAMETER") includePrerelease: Boolean = false): Boolean = remote.versionCode?.let { it > localVersionCode } == true
internal fun availableUpdateLabel(localVersionName: String, localVersionCode: Long, remote: AppReleaseInfo?, includePrerelease: Boolean = false): String? = remote?.takeIf { !it.isDraft && (includePrerelease || !it.isPrerelease) && it.source == RemoteVersionSource.Release && (if (it.versionCode != null) isNewerRelease(localVersionName, localVersionCode, it, includePrerelease) else isNewerVersion(localVersionName, it.versionName ?: it.versionTag, includePrerelease)) }?.let { (it.versionName ?: it.versionTag).removePrefix("v").let { v -> "Доступна новая версия $v" } }
private data class ParsedVersionTag(val core: List<Int>, val prerelease: String?)
private fun parseVersionTag(version: String): ParsedVersionTag { val n = normalizeVersionTag(version).removePrefix("v").removePrefix("V"); val m = VERSION_NUMBER_REGEX.find(n)?.value ?: return ParsedVersionTag(emptyList(), null); return ParsedVersionTag(m.split('.').mapNotNull(String::toIntOrNull), n.removePrefix(m).trim().trimStart('-').ifBlank { null }) }

private fun enrichReleaseFromManifest(release: AppReleaseInfo): AppReleaseInfo? {
    val raw = release.updateManifestUrl?.let { fetchHttpText(it, "update.json", "application/json", false) }
    if (raw == null) { Log.w(UPDATE_LOG_TAG, "OTA ${release.versionTag}: update.json request failed") ; return null }
    val m = parseUpdateManifest(raw)
    if (m == null) { Log.w(UPDATE_LOG_TAG, "OTA ${release.versionTag}: update.json parse failed") ; return null }
    if (!m.packageNamePresent || m.packageName != BuildConfig.APPLICATION_ID || m.packageName != OTA_PACKAGE_NAME) { Log.w(UPDATE_LOG_TAG, "OTA ${release.versionTag}: package mismatch metadata=${m.packageName} app=${BuildConfig.APPLICATION_ID}"); return null }
    if (!m.apk.equals(release.downloadFileName, false)) { Log.w(UPDATE_LOG_TAG, "OTA ${release.versionTag}: apk mismatch metadata=${m.apk} asset=${release.downloadFileName}"); return null }
    return release.copy(versionName = m.versionName, versionCode = m.versionCode, packageName = m.packageName, expectedSha256 = m.sha256, releaseNotes = release.releaseNotes.ifBlank { m.releaseNotes }, publishedAt = m.publishedAt ?: release.publishedAt, mandatory = m.mandatory).takeIf(::isInstallableOtaRelease)?.let { mergeReleaseInfo(null, it) }
}
private fun fetchGitHubApi(url: String): String? { if (System.currentTimeMillis() < githubApiCooldownUntilMs) { setLastUpdateRequestError("GitHub временно ограничил запросы, попробуйте позже"); return null }; return fetchHttpText(url, "GitHub API", "application/vnd.github+json", true) }
private fun fetchHttpText(url: String, sourceLabel: String, accept: String, isGitHubApi: Boolean): String? {
    return try {
        val request = Request.Builder().url(url).get()
            .header("Accept", accept)
            .header("User-Agent", "HopletAndroid/${BuildConfig.VERSION_NAME}")
            .apply { if (isGitHubApi) header("X-GitHub-Api-Version", "2022-11-28") }
            .build()
        otaHttpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (response.isSuccessful) {
            if (isGitHubApi) githubApiCooldownUntilMs = 0L
            body
            } else {
                if (isGitHubApi) noteGitHubApiCooldown(response.code, response.header("Retry-After"), response.header("X-RateLimit-Reset"), body)
                setLastUpdateRequestError(describeHttpError(response.code, body))
                Log.w(UPDATE_LOG_TAG, "$sourceLabel returned HTTP ${response.code}")
                null
            }
        }
    } catch (e: Exception) {
        setLastUpdateRequestError(describeRequestException(e))
        Log.w(UPDATE_LOG_TAG, "$sourceLabel request failed", e)
        null
    }
}
private fun noteGitHubApiCooldown(code: Int, retryAfter: String?, rateLimitReset: String?, body: String) { if (code != 403 && code != 429) return; val now = System.currentTimeMillis(); val retry = retryAfter?.toLongOrNull()?.let { now + it * 1000 }; val reset = rateLimitReset?.toLongOrNull()?.let { it * 1000 }; githubApiCooldownUntilMs = listOfNotNull(retry, reset).filter { it > now }.minOrNull() ?: now + if (body.contains("rate limit", true)) GITHUB_API_RATE_LIMIT_FALLBACK_MS else 300_000 }

internal fun JSONObject.toAppReleaseInfo(): AppReleaseInfo? {
    if (optBoolean("draft")) return null
    val tag = normalizeVersionTag(optString("tag_name"))
    val url = optString("html_url").trim()
    val assets = optJSONArray("assets") ?: return null
    fun find(name: String): JSONObject? = (0 until assets.length())
        .mapNotNull { assets.optJSONObject(it) }
        .firstOrNull { isUploadedAsset(it) && it.optString("name") == name }
    val apk = find("app-release.apk") ?: find("app-universal-release.apk") ?: return null
    val manifest = find("update.json")
    val apkUrl = apk.optString("browser_download_url").trim().ifBlank {
        "https://github.com/Axygen4oO/hoplet-android/releases/download/$tag/${apk.optString("name")}"
    }
    val manifestUrl = manifest?.optString("browser_download_url").orEmpty().trim()
    if (tag.isBlank() || url.isBlank() || !isHttpsUrl(apkUrl)) return null
    return AppReleaseInfo(
        versionTag = tag,
        releaseUrl = url,
        downloadUrl = apkUrl,
        releaseNotes = optString("body").trim(),
        isPrerelease = optBoolean("prerelease"),
        downloadFileName = apk.optString("name"),
        downloadSizeBytes = apk.optLong("size").coerceAtLeast(0L),
        updateManifestUrl = manifestUrl.ifBlank { null },
        publishedAt = optString("published_at").substringBefore('T').ifBlank { null },
    )
}
private fun isUploadedAsset(a: JSONObject) = a.optString("state").let { it.isBlank() || it == "uploaded" }
private fun isHttpsUrl(v: String) = runCatching { URL(v).protocol.equals("https", true) && URL(v).userInfo == null && URL(v).host.isNotBlank() }.getOrDefault(false)
internal fun normalizeVersionTag(version: String): String { val t = version.trim(); return when { t.isBlank() -> ""; t.startsWith("v", true) -> t; else -> "v$t" } }

sealed interface InstallApkResult { data object Started : InstallApkResult; data object PermissionRequired : InstallApkResult; data class Failed(val message: String) : InstallApkResult }
fun installApk(context: Context, apkFile: File): InstallApkResult { if (!apkFile.isFile || apkFile.length() <= 0L) return InstallApkResult.Failed("Файл обновления не найден"); if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) return if (openUnknownSourcesSettings(context)) InstallApkResult.PermissionRequired else InstallApkResult.Failed("Разрешите установку APK для этого приложения"); return try { val uri = FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.fileprovider", apkFile); check(uri.scheme == "content"); context.startActivity(Intent(Intent.ACTION_VIEW).apply { setDataAndType(uri, "application/vnd.android.package-archive"); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK) }); InstallApkResult.Started } catch (_: ActivityNotFoundException) { InstallApkResult.Failed("Не удалось открыть установщик APK") } catch (e: Exception) { Log.e(UPDATE_LOG_TAG, "Failed to launch package installer", e); InstallApkResult.Failed("Не удалось запустить установку обновления") } }
internal fun extractSha256FromText(text: String, downloadFileName: String? = null): String? { if (text.isBlank()) return null; val target = downloadFileName?.lowercase(); text.lineSequence().firstOrNull { target != null && it.lowercase().contains(target) }?.let { normalizeSha256(SHA256_REGEX.find(it)?.value) }?.let { return it }; return text.lineSequence().firstOrNull { it.contains("sha256", true) }?.let { normalizeSha256(SHA256_REGEX.find(it)?.value) } }
internal fun normalizeSha256(value: String?): String? = value?.trim()?.lowercase()?.takeIf(SHA256_REGEX::matches)
private fun clearLastUpdateRequestError() { lastUpdateRequestErrorMessage = "" }
private fun setLastUpdateRequestError(message: String) { lastUpdateRequestErrorMessage = message }
private fun describeHttpError(code: Int, body: String) = when (code) { 403, 429 -> "GitHub временно ограничил запросы, попробуйте позже"; 404 -> "Релиз или asset обновления не найден"; 408 -> "Сервер обновлений не ответил вовремя"; in 500..599 -> "Сервер обновлений временно недоступен (HTTP $code)"; else -> "Ошибка сервера обновлений (HTTP $code)" }
private fun describeRequestException(e: Exception) = when (e) { is UnknownHostException -> "Нет подключения к интернету"; is SocketTimeoutException -> "Истекло время ожидания ответа"; is ConnectException -> "Не удалось подключиться к серверу обновлений"; is SSLException -> "Ошибка защищённого соединения с сервером обновлений"; else -> e.message?.takeIf(String::isNotBlank) ?: "Не удалось выполнить запрос на обновление" }
private fun openUnknownSourcesSettings(context: Context) = try { context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${BuildConfig.APPLICATION_ID}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true } catch (_: Exception) { false }
