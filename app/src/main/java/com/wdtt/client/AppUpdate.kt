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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException
import java.util.LinkedHashMap
import javax.net.ssl.SSLException

const val UPDATE_CHECK_NEVER = -1
const val DEFAULT_UPDATE_CHECK_INTERVAL_HOURS = 12
const val UPDATE_DIALOG_ACTION_POSTPONED = "postponed"
const val UPDATE_DIALOG_ACTION_UPDATE = "update"

private const val UPDATE_LOG_TAG = "qWDTT"
private const val GITHUB_RELEASES_URL = "https://api.github.com/repos/Axygen4oO/hoplet-android/releases?per_page=30"
private const val GITHUB_LATEST_RELEASE_URL = "https://api.github.com/repos/Axygen4oO/hoplet-android/releases/latest"
private const val GITHUB_RELEASE_TAG_URL_PREFIX = "https://github.com/Axygen4oO/hoplet-android/releases/tag/"
private const val GITHUB_RELEASE_DOWNLOAD_URL_PREFIX = "https://github.com/Axygen4oO/hoplet-android/releases/download/"
private const val GITHUB_API_RATE_LIMIT_FALLBACK_MS = 30L * 60L * 1000L
private val VERSION_NUMBER_REGEX = Regex("\\d+(?:\\.\\d+)*")

@Volatile
private var githubApiCooldownUntilMs = 0L

fun updateIntervalHoursToMillis(hours: Int): Long? = when {
    hours <= 0 -> null
    else -> hours * 60L * 60L * 1000L
}

data class AppReleaseInfo(
    val versionTag: String,
    val releaseUrl: String,
    val source: RemoteVersionSource,
    val downloadUrl: String? = null,
    val releaseNotes: String = "",
    val isPrerelease: Boolean = false,
    val downloadFileName: String? = null,
    val downloadSizeBytes: Long = 0L,
    val expectedSha256: String? = null,
    val sha256AssetUrl: String? = null,
    /** Версия из update.json; для старых релизов может отсутствовать. */
    val versionName: String? = null,
    /** Основной критерий сравнения обновлений. */
    val versionCode: Long? = null,
    val updateManifestUrl: String? = null,
    val publishedAt: String? = null,
    val mandatory: Boolean = false,
    /** GitHub draft никогда не должен попадать в OTA-кандидаты. */
    val isDraft: Boolean = false,
)

private const val RELEASE_NOTES_CACHE_LIMIT = 12
private val releaseNotesCache = object {
    private val values = LinkedHashMap<String, String>(RELEASE_NOTES_CACHE_LIMIT, 0.75f, true)

    @Synchronized
    fun get(versionTag: String): String? = values[normalizeVersionTag(versionTag)]

    @Synchronized
    fun put(versionTag: String, notes: String) {
        val key = normalizeVersionTag(versionTag)
        val value = notes.trim()
        if (key.isBlank() || value.isBlank()) return
        values[key] = value
        while (values.size > RELEASE_NOTES_CACHE_LIMIT) values.remove(values.entries.first().key)
    }

    @Synchronized
    fun clear() = values.clear()
}

/** Объединяет метаданные релиза, никогда не заменяя непустые заметки пустыми. */
fun mergeReleaseInfo(existing: AppReleaseInfo?, incoming: AppReleaseInfo): AppReleaseInfo {
    val sameVersion = existing?.let { normalizeVersionTag(it.versionTag) == normalizeVersionTag(incoming.versionTag) } == true
    val existingNotes = existing?.releaseNotes?.trim().orEmpty().takeIf { sameVersion && it.isNotBlank() }
    val cachedNotes = releaseNotesCache.get(incoming.versionTag)
    val incomingNotes = incoming.releaseNotes.trim().takeIf { it.isNotBlank() }
    // Свежий body релиза имеет приоритет над любым кэшем; пустой ответ не затирает
    // уже сохранённые заметки той же версии.
    val notes = incomingNotes ?: existingNotes ?: cachedNotes ?: ""
    if (notes.isNotBlank()) releaseNotesCache.put(incoming.versionTag, notes)
    val preferred = if (existing == null || !sameVersion) {
        incoming
    } else {
        when {
            existing.source == RemoteVersionSource.Release && incoming.source == RemoteVersionSource.Tag -> existing
            isInstallableOtaRelease(existing) && !isInstallableOtaRelease(incoming) -> existing
            else -> incoming
        }
    }
    return if (preferred.releaseNotes == notes) preferred else preferred.copy(releaseNotes = notes)
}

internal fun clearReleaseNotesCacheForTests() {
    // Kept intentionally package-private for deterministic regression tests.
    releaseNotesCache.clear()
}

data class ReleaseChangelogItem(
    val versionTag: String,
    val publishedAt: String,
    val body: String,
    val isPrerelease: Boolean,
    val releaseUrl: String,
)

enum class RemoteVersionSource {
    Release,
    Tag
}

data class UpdateCheckOutcome(
    val checkedAt: Long,
    val release: AppReleaseInfo?,
    val errorMessage: String,
)

internal data class UpdateManifest(
    val versionName: String,
    val versionCode: Long,
    val tag: String,
    val apk: String,
    val sha256: String,
    val mandatory: Boolean,
    val size: Long?,
    val releaseNotes: String,
)

/** Строгий парсер canonical update.json. Legacy-релизы обходят его только если asset отсутствует. */
internal fun parseUpdateManifest(raw: String): UpdateManifest? {
    return runCatching {
        val json = JSONObject(raw)
        val versionName = (json.opt("versionName") as? String)
            ?.trim()?.takeIf { it.isNotBlank() } ?: return@runCatching null
        val versionCodeValue = json.opt("versionCode") as? Number ?: return@runCatching null
        val versionCode = versionCodeValue.toLong().takeIf {
            it >= 0L && versionCodeValue.toDouble() == it.toDouble()
        } ?: return@runCatching null
        val tag = (json.opt("tag") as? String)
            ?.trim()?.takeIf { it.isNotBlank() } ?: return@runCatching null
        val apk = (json.opt("apk") as? String)
            ?.trim()?.takeIf { it.isNotBlank() } ?: return@runCatching null
        val sha = normalizeSha256((json.opt("sha256") as? String)?.trim())
            ?: return@runCatching null
        if (!json.has("mandatory") || json.opt("mandatory") !is Boolean) return@runCatching null
        UpdateManifest(
            versionName = versionName,
            versionCode = versionCode,
            tag = tag,
            apk = apk,
            sha256 = sha,
            mandatory = json.optBoolean("mandatory", false),
            size = json.optLong("size", Long.MIN_VALUE).takeIf { it > 0L },
            releaseNotes = json.optString("releaseNotes").trim(),
        )
    }.getOrNull()
}

@Volatile
private var lastUpdateRequestErrorMessage: String = ""

suspend fun fetchLatestReleaseInfo(
    localVersion: String? = null,
    includePrerelease: Boolean = false,
): AppReleaseInfo? = withContext(Dispatchers.IO) {
    clearLastUpdateRequestError()
    val latestRelease = if (includePrerelease) {
        // /releases/latest намеренно скрывает prerelease, поэтому beta-режим
        // всегда использует полный список релизов и выбирает лучший по версии.
        fetchLatestReleaseFromList(true)
    } else {
        fetchReleaseFromLatestEndpoint(false)
            ?: fetchLatestReleaseFromList(false)
    }

    // OTA-кандидат выбирается исключительно среди опубликованных Releases с APK.
    // Список тегов здесь намеренно не запрашивается: tag-only версия не должна
    // участвовать в сравнении версий или попадать в installer flow.
    selectOtaCandidate(latestRelease)?.let { candidate ->
        val enriched = enrichReleaseFromManifest(candidate) ?: return@withContext null
        val merged = mergeReleaseInfo(null, enriched)
        val result = if (merged.releaseNotes.isNotBlank()) {
            merged
        } else {
            merged
        }
        Log.i(
            UPDATE_LOG_TAG,
            "Update candidate: ${result.versionTag} source=${result.source} " +
                "hasApk=${isInstallableOtaRelease(result)} downloadUrlPresent=${!result.downloadUrl.isNullOrBlank()}"
        )
        result
    }
}

/** Tag остаётся совместимым информационным типом, но никогда не становится OTA-кандидатом. */
@Suppress("UNUSED_PARAMETER")
internal fun selectOtaCandidate(
    publishedRelease: AppReleaseInfo?,
    informationalTag: AppReleaseInfo? = null,
    includePrerelease: Boolean = false,
): AppReleaseInfo? = publishedRelease?.takeIf {
    isInstallableOtaRelease(it) && (includePrerelease || !it.isPrerelease)
}

internal fun isInstallableOtaRelease(release: AppReleaseInfo): Boolean {
    if (release.source != RemoteVersionSource.Release || release.isDraft ||
        release.downloadUrl.isNullOrBlank()
    ) return false
    val fileName = release.downloadFileName?.trim().orEmpty().ifBlank {
        runCatching { URL(release.downloadUrl).path.substringAfterLast('/') }.getOrDefault("")
    }
    return isSafeOtaApkName(fileName)
}

private fun isSafeOtaApkName(fileName: String): Boolean {
    val name = fileName.trim().lowercase()
    if (!name.endsWith(".apk")) return false
    if (listOf("debug", "test", "androidtest", "split").any(name::contains)) return false
    // OTA принимает только универсальную production-сборку. ABI-specific
    // APK нельзя безопасно предложить всем пользователям.
    if (Regex("(^|[-_.])(armeabi(?:-v7a)?|arm64-v8a|x86(?:_64)?)([-_.]|$)").containsMatchIn(name)) {
        return false
    }
    return name == "app-release.apk" ||
        name == "app-universal-release.apk" ||
        name.contains("universal")
}

suspend fun performAppUpdateCheck(
    localVersion: String? = null,
    includePrerelease: Boolean = false,
): UpdateCheckOutcome = withContext(Dispatchers.IO) {
    val checkedAt = System.currentTimeMillis()
    val release = fetchLatestReleaseInfo(localVersion, includePrerelease)
    val outcome = UpdateCheckOutcome(
        checkedAt = checkedAt,
        release = release,
        errorMessage = if (release == null) {
            lastUpdateRequestErrorMessage.ifBlank { "Не удалось проверить обновления" }
        } else {
            ""
        }
    )
    if (outcome.release != null) {
        Log.i(
            UPDATE_LOG_TAG,
            "Update check completed: local=${localVersion.orEmpty().ifBlank { "unknown" }}, remote=${outcome.release.versionTag}, prerelease=$includePrerelease"
        )
    } else {
        Log.w(
            UPDATE_LOG_TAG,
            "Update check failed: local=${localVersion.orEmpty().ifBlank { "unknown" }}, prerelease=$includePrerelease, error=${outcome.errorMessage}"
        )
    }
    outcome
}

suspend fun fetchReleaseChangelog(
    includePrerelease: Boolean = false,
    limit: Int = 12,
): List<ReleaseChangelogItem> = withContext(Dispatchers.IO) {
    clearLastUpdateRequestError()
    val response = fetchGitHubApi(GITHUB_RELEASES_URL) ?: return@withContext emptyList()
    val releases = try {
        JSONArray(response)
    } catch (e: Exception) {
        Log.w(UPDATE_LOG_TAG, "[WARN] Changelog: failed to parse releases list", e)
        return@withContext emptyList()
    }

    val items = mutableListOf<ReleaseChangelogItem>()
    for (i in 0 until releases.length()) {
        val json = releases.optJSONObject(i) ?: continue
        if (json.optBoolean("draft")) continue
        if (!includePrerelease && json.optBoolean("prerelease")) continue
        val versionTag = normalizeVersionTag(json.optString("tag_name"))
        val releaseUrl = json.optString("html_url").trim()
        if (versionTag.isBlank() || releaseUrl.isBlank()) continue
        val body = json.optString("body").trim()
        if (body.isNotBlank()) releaseNotesCache.put(versionTag, body)
        items += ReleaseChangelogItem(
            versionTag = versionTag,
            publishedAt = json.optString("published_at").substringBefore("T"),
            body = body,
            isPrerelease = json.optBoolean("prerelease"),
            releaseUrl = releaseUrl,
        )
    }
    items.take(limit)
}

suspend fun fetchReleaseNotesForVersion(versionTag: String): String = withContext(Dispatchers.IO) {
    val normalized = normalizeVersionTag(versionTag)
    releaseNotesCache.get(normalized)?.takeIf { it.isNotBlank() }
        ?: bundledReleaseNotes(versionTag)
}

fun bundledReleaseNotes(versionTag: String): String {
    return when (normalizeVersionTag(versionTag)) {
        "v1.3.2" -> """
            • 🚀 Добавлена схема подключения с отображением всех этапов соединения
              🔄 Реализовано автоматическое переподключение при потере соединения
              ⚙️ Добавлен выбор источника VK Hash (SERVER / LOCAL)
              🔀 Локальные и серверные VK Hash теперь работают независимо
              📱 Переработан интерфейс настроек
              📥 Улучшен механизм получения обновлений
              🔌 Повышена стабильность подключения
              🛠 Исправлены ошибки и улучшена общая стабильность приложения
        """.trimIndent()
        else -> ""
    }
}

fun sanitizedReleaseNotes(notes: String): String {
    return notes
        .replace(Regex("https?://\\S+", RegexOption.IGNORE_CASE), "")
        .replace(Regex("[ \\t]+\\n"), "\n")
        .trim()
}

fun isNewerVersion(local: String, remote: String, includePrerelease: Boolean = false): Boolean {
    val localParsed = parseVersionTag(local)
    val remoteParsed = parseVersionTag(remote)
    if (remoteParsed.core.isEmpty()) return false
    if (localParsed.core.isEmpty()) return true

    val maxLen = maxOf(localParsed.core.size, remoteParsed.core.size)
    for (i in 0 until maxLen) {
        val localPart = localParsed.core.getOrElse(i) { 0 }
        val remotePart = remoteParsed.core.getOrElse(i) { 0 }
        if (remotePart > localPart) return true
        if (remotePart < localPart) return false
    }

    val localPre = localParsed.prerelease
    val remotePre = remoteParsed.prerelease
    if (localPre == null && remotePre == null) return false
    if (localPre == null && remotePre != null) return includePrerelease
    if (localPre != null && remotePre == null) return true
    return comparePrerelease(remotePre!!, localPre!!) > 0
}

/** Сравнение релиза по Android versionCode с совместимым fallback на versionName. */
fun isNewerRelease(
    localVersionName: String,
    localVersionCode: Long,
    remote: AppReleaseInfo,
    includePrerelease: Boolean = false,
): Boolean {
    val remoteCode = remote.versionCode
    return if (remoteCode != null) {
        remoteCode > localVersionCode
    } else {
        val remoteVersionName = remote.versionName?.takeIf { it.isNotBlank() } ?: remote.versionTag
        isNewerVersion(localVersionName, remoteVersionName, includePrerelease)
    }
}

/** Возвращает компактную подпись для header только при действительно доступном обновлении. */
internal fun availableUpdateLabel(
    localVersionName: String,
    localVersionCode: Long,
    remote: AppReleaseInfo?,
    includePrerelease: Boolean = false,
): String? {
    if (remote == null || remote.source != RemoteVersionSource.Release ||
        !isNewerRelease(localVersionName, localVersionCode, remote, includePrerelease)) {
        return null
    }
    val displayVersion = (remote.versionName?.trim().takeIf { !it.isNullOrBlank() } ?: remote.versionTag)
        .trim()
        .removePrefix("v")
        .removePrefix("V")
        .takeIf { it.isNotBlank() }
        ?: return null
    return "Доступна новая версия $displayVersion"
}

private data class ParsedVersionTag(
    val core: List<Int>,
    val prerelease: String?,
)

private fun parseVersionTag(version: String): ParsedVersionTag {
    val normalized = normalizeVersionTag(version).removePrefix("v").removePrefix("V")
    val coreMatch = VERSION_NUMBER_REGEX.find(normalized)?.value ?: return ParsedVersionTag(emptyList(), null)
    val core = coreMatch.split('.').mapNotNull { it.toIntOrNull() }
    val suffix = normalized
        .removePrefix(coreMatch)
        .trim()
        .trimStart('-')
        .takeIf { it.isNotEmpty() }
    return ParsedVersionTag(core, suffix)
}

private fun comparePrerelease(remote: String, local: String): Int {
    val remoteNums = Regex("\\d+").findAll(remote).map { it.value.toInt() }.toList()
    val localNums = Regex("\\d+").findAll(local).map { it.value.toInt() }.toList()
    val max = maxOf(remoteNums.size, localNums.size)
    for (i in 0 until max) {
        val r = remoteNums.getOrElse(i) { 0 }
        val l = localNums.getOrElse(i) { 0 }
        if (r != l) return r.compareTo(l)
    }
    return remote.compareTo(local, ignoreCase = true)
}

private fun fetchLatestReleaseFromList(includePrerelease: Boolean): AppReleaseInfo? {
    val response = fetchGitHubApi(GITHUB_RELEASES_URL) ?: return null
    val releases = try {
        JSONArray(response)
    } catch (e: Exception) {
        Log.w(UPDATE_LOG_TAG, "[WARN] Update check: failed to parse releases list", e)
        return null
    }

    for (i in 0 until releases.length()) {
        val json = releases.optJSONObject(i) ?: continue
        if (json.optBoolean("draft")) continue
        if (!includePrerelease && json.optBoolean("prerelease")) continue
        val release = json.toAppReleaseInfo()
            ?.takeIf(::isInstallableOtaRelease)
            ?.let { mergeReleaseInfo(null, it) }
            ?: continue
        // GitHub returns /releases ordered by publication time (newest first).
        // OTA follows that order; tags and semantic-name guessing cannot reorder releases.
        return release
    }
    return null
}

/** Читает официальный update.json, если он приложен к релизу.
 *  Старые релизы намеренно остаются совместимыми: при любой ошибке API-метаданные
 *  (tag, body и assets) используются без манифеста.
 */
private fun enrichReleaseFromManifest(release: AppReleaseInfo): AppReleaseInfo? {
    val manifestUrl = release.updateManifestUrl ?: return release
    val raw = fetchHttpText(
        url = manifestUrl,
        sourceLabel = "update.json",
        accept = "application/json,text/plain,*/*",
        isGitHubApi = false
    ) ?: return null
    return try {
        val manifest = parseUpdateManifest(raw) ?: return null
        val manifestTag = normalizeVersionTag(manifest.tag.orEmpty())
        if (manifestTag != normalizeVersionTag(release.versionTag)) {
            Log.w(
                UPDATE_LOG_TAG,
                "[WARN] update.json tag does not match its release"
            )
            return null
        }
        val manifestApkName = manifest.apk.let {
            runCatching { URL(it).path.substringAfterLast('/') }.getOrDefault(it.substringAfterLast('/'))
        }
        if (manifestApkName.isBlank() ||
            !manifestApkName.equals(release.downloadFileName, ignoreCase = true) ||
            !isSafeOtaApkName(manifestApkName)
        ) return null
        val tagName = parseVersionTag(release.versionTag)
        val manifestName = parseVersionTag(manifest.versionName)
        if (tagName.core.isNotEmpty() && manifestName.core.isNotEmpty() &&
            (isNewerVersion(release.versionTag, manifest.versionName) ||
                isNewerVersion(manifest.versionName, release.versionTag))
        ) return null
        release.copy(
            versionName = manifest.versionName,
            versionCode = manifest.versionCode,
            expectedSha256 = manifest.sha256,
            downloadSizeBytes = manifest.size ?: release.downloadSizeBytes,
            // APK URL always comes from the selected asset, never from release HTML
            // or an unrelated URL embedded in update.json.
            downloadUrl = release.downloadUrl,
            downloadFileName = release.downloadFileName,
            releaseNotes = release.releaseNotes.ifBlank { manifest.releaseNotes },
            mandatory = manifest.mandatory,
        )
    } catch (error: Exception) {
        Log.w(UPDATE_LOG_TAG, "[WARN] update.json validation failed", error)
        null
    }
}

/** Разрешает поле apk из update.json в прямой URL asset, не используя html_url. */
internal fun resolveManifestApkUrl(release: AppReleaseInfo, apk: String?): Pair<String, String>? {
    val value = apk?.trim().orEmpty()
    if (value.isBlank()) return null
    if (value.startsWith("https://", ignoreCase = true) || value.startsWith("http://", ignoreCase = true)) {
        val parsed = runCatching { URL(value) }.getOrNull() ?: return null
        val name = parsed.path.substringAfterLast('/')
        val hasCredentials = runCatching { parsed.toURI().userInfo != null }.getOrDefault(true)
        if (hasCredentials || name.isBlank() || !name.endsWith(".apk", ignoreCase = true) ||
            parsed.path.contains("/releases/tag/", ignoreCase = true) ||
            parsed.path.endsWith("/releases", ignoreCase = true)
        ) return null
        return value to name
    }
    val fileName = value.substringAfterLast('/').takeIf { it.endsWith(".apk", ignoreCase = true) } ?: return null
    val releaseTag = release.releaseUrl.substringAfter("/releases/tag/", "")
        .substringBefore('?').substringBefore('#').substringBefore('/')
    val tag = releaseTag.ifBlank { release.versionTag }.trim().ifBlank { return null }
    val encodedTag = URLEncoder.encode(tag, "UTF-8").replace("+", "%20")
    val encodedName = URLEncoder.encode(fileName, "UTF-8").replace("+", "%20")
    return "$GITHUB_RELEASE_DOWNLOAD_URL_PREFIX$encodedTag/$encodedName" to fileName
}

internal fun buildGitHubAssetDownloadUrl(versionTag: String, fileName: String): String? =
    resolveManifestApkUrl(
        AppReleaseInfo(
            normalizeVersionTag(versionTag),
            "$GITHUB_RELEASE_TAG_URL_PREFIX${normalizeVersionTag(versionTag)}",
            RemoteVersionSource.Release
        ),
        fileName
    )?.first

private fun fetchReleaseFromLatestEndpoint(includePrerelease: Boolean): AppReleaseInfo? {
    val response = fetchGitHubApi(GITHUB_LATEST_RELEASE_URL) ?: return null
    val json = try {
        JSONObject(response)
    } catch (e: Exception) {
        Log.w(UPDATE_LOG_TAG, "[WARN] Update check: failed to parse latest release", e)
        return null
    }
    if (!includePrerelease && json.optBoolean("prerelease")) return null
    if (json.optBoolean("draft")) return null
    return json.toAppReleaseInfo()
        ?.takeIf(::isInstallableOtaRelease)
        ?.let { mergeReleaseInfo(null, it) }
}

private fun fetchGitHubApi(url: String): String? {
    val now = System.currentTimeMillis()
    if (now < githubApiCooldownUntilMs) return null
    return fetchHttpText(
        url = url,
        sourceLabel = "GitHub API",
        accept = "application/vnd.github+json",
        isGitHubApi = true
    )
}

private fun fetchHttpText(
    url: String,
    sourceLabel: String,
    accept: String,
    isGitHubApi: Boolean = false
): String? {
    var conn: HttpURLConnection? = null
    return try {
        conn = URL(url).openConnection() as HttpURLConnection
        applyNoCacheHeaders(conn)
        conn.requestMethod = "GET"
        conn.setRequestProperty("Accept", accept)
        if (isGitHubApi) {
            conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        }
        conn.setRequestProperty("User-Agent", "qWDTTAndroid/${BuildConfig.VERSION_NAME}")
        conn.connectTimeout = 8_000
        conn.readTimeout = 8_000

        val responseCode = conn.responseCode
        val stream = if (responseCode in 200..299) conn.inputStream else conn.errorStream
        val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()

        if (responseCode in 200..299) {
            if (isGitHubApi) githubApiCooldownUntilMs = 0L
            clearLastUpdateRequestError()
            response
        } else {
            if (isGitHubApi) noteGitHubApiCooldown(conn, responseCode, response)
            setLastUpdateRequestError(describeHttpError(responseCode, response))
            Log.w(UPDATE_LOG_TAG, "[WARN] Update check: $sourceLabel returned HTTP $responseCode")
            null
        }
    } catch (e: Exception) {
        setLastUpdateRequestError(describeRequestException(e))
        Log.w(UPDATE_LOG_TAG, "[WARN] Update check: $sourceLabel request failed", e)
        null
    } finally {
        conn?.disconnect()
    }
}

private fun applyNoCacheHeaders(conn: HttpURLConnection) {
    conn.useCaches = false
    conn.setRequestProperty("Cache-Control", "no-cache, no-store, max-age=0")
    conn.setRequestProperty("Pragma", "no-cache")
    conn.setRequestProperty("Expires", "0")
}

private fun noteGitHubApiCooldown(conn: HttpURLConnection, responseCode: Int, response: String) {
    if (responseCode != HttpURLConnection.HTTP_FORBIDDEN && responseCode != 429) return
    val now = System.currentTimeMillis()
    val retryAfterUntil = conn.getHeaderField("Retry-After")?.trim()?.toLongOrNull()?.takeIf { it > 0L }?.let { now + it * 1000L }
    val rateLimitResetUntil = conn.getHeaderField("X-RateLimit-Reset")?.trim()?.toLongOrNull()?.takeIf { it > 0L }?.let { it * 1000L }
    val fallbackUntil = now + if (response.contains("rate limit", ignoreCase = true)) GITHUB_API_RATE_LIMIT_FALLBACK_MS else 5L * 60L * 1000L
    val cooldownUntil = listOfNotNull(retryAfterUntil, rateLimitResetUntil).filter { it > now }.minOrNull() ?: fallbackUntil
    if (cooldownUntil > githubApiCooldownUntilMs) {
        githubApiCooldownUntilMs = cooldownUntil
        Log.w(
            UPDATE_LOG_TAG,
            "[WARN] Update check: GitHub API cooldown ${(cooldownUntil - now) / 1000}s after HTTP $responseCode"
        )
    }
}

internal fun JSONObject.toAppReleaseInfo(): AppReleaseInfo? {
    if (optBoolean("draft")) return null
    val versionTag = normalizeVersionTag(optString("tag_name"))
    val releaseUrl = optString("html_url").trim()
    if (versionTag.isBlank() || releaseUrl.isBlank()) return null

    var selectedAsset: JSONObject? = null
    val assets = optJSONArray("assets")

    if (assets != null) {
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            if (!isUploadedAsset(asset)) continue
            if (asset.optString("name").equals("app-release.apk", ignoreCase = true)) {
                selectedAsset = asset
                break
            }
        }

        if (selectedAsset == null) {
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                if (!isUploadedAsset(asset)) continue
                val name = asset.optString("name")
                if (name.equals("app-universal-release.apk", ignoreCase = true)) {
                    selectedAsset = asset
                    break
                }
            }
        }

        if (selectedAsset == null) {
            // Неизвестные APK принимаются только если имя явно доказывает universal-сборку.
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                if (!isUploadedAsset(asset)) continue
                val name = asset.optString("name")
                if (name.endsWith(".apk", ignoreCase = true) &&
                    name.contains("universal", ignoreCase = true) &&
                    !name.contains("debug", ignoreCase = true) &&
                    !name.contains("test", ignoreCase = true) &&
                    !name.contains("split", ignoreCase = true)
                ) {
                    selectedAsset = asset
                    break
                }
            }
        }
    }

    val downloadFileName = selectedAsset?.optString("name")?.trim().orEmpty().ifBlank { null }
    val browserDownloadUrl = selectedAsset?.optString("browser_download_url")?.trim().orEmpty()
        .takeIf { it.startsWith("https://", ignoreCase = true) && !it.contains("/releases/tag/") }
    val downloadUrl = browserDownloadUrl ?: run {
        downloadFileName?.let {
            resolveManifestApkUrl(
                AppReleaseInfo(versionTag, releaseUrl, RemoteVersionSource.Release), it
            )?.first
        }
    }
    val releaseNotes = optString("body").trim()
    val expectedSha256 = selectedAsset?.let(::extractSha256FromAssetDigest)
        ?: extractSha256FromText(releaseNotes, downloadFileName)
    val manifestUrl = assets?.findAssetUrl("update.json", versionTag)

    return AppReleaseInfo(
        versionTag,
        releaseUrl,
        RemoteVersionSource.Release,
        downloadUrl,
        releaseNotes = releaseNotes,
        isPrerelease = optBoolean("prerelease"),
        isDraft = optBoolean("draft"),
        downloadFileName = downloadFileName,
        downloadSizeBytes = selectedAsset?.optLong("size")?.takeIf { it > 0L } ?: 0L,
        expectedSha256 = expectedSha256,
        sha256AssetUrl = assets?.findSha256AssetUrl(downloadFileName),
        updateManifestUrl = manifestUrl,
        publishedAt = optString("published_at").substringBefore('T').ifBlank { null },
    )
}

private fun isUploadedAsset(asset: JSONObject): Boolean =
    asset.optString("state").trim().let { it.isBlank() || it.equals("uploaded", ignoreCase = true) }

internal fun normalizeVersionTag(version: String): String {
    val trimmed = version.trim()
    if (trimmed.isBlank()) return ""
    return if (trimmed.startsWith("v", ignoreCase = true)) trimmed else "v$trimmed"
}

sealed interface InstallApkResult {
    object Started : InstallApkResult
    object PermissionRequired : InstallApkResult
    data class Failed(val message: String) : InstallApkResult
}

fun installApk(context: Context, apkFile: File): InstallApkResult {
    if (!apkFile.exists()) {
        return InstallApkResult.Failed("Файл обновления не найден")
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
        !context.packageManager.canRequestPackageInstalls()
    ) {
        Log.i(UPDATE_LOG_TAG, "APK install requires unknown-sources permission for ${apkFile.name}")
        return if (openUnknownSourcesSettings(context)) {
            InstallApkResult.PermissionRequired
        } else {
            InstallApkResult.Failed("Разрешите установку APK для этого приложения")
        }
    }

    try {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(installIntent)
        Log.i(UPDATE_LOG_TAG, "Started APK installer for ${apkFile.name}")
        return InstallApkResult.Started
    } catch (_: ActivityNotFoundException) {
        return InstallApkResult.Failed("Не удалось открыть установщик APK")
    } catch (e: Exception) {
        Log.e(UPDATE_LOG_TAG, "Failed to install APK", e)
        return InstallApkResult.Failed("Не удалось запустить установку обновления")
    }
}

private fun JSONArray.findSha256AssetUrl(downloadFileName: String?): String? {
    val normalizedFileName = downloadFileName?.lowercase().orEmpty()
    val baseName = normalizedFileName.removeSuffix(".apk")
    for (i in 0 until length()) {
        val asset = optJSONObject(i) ?: continue
        if (!isUploadedAsset(asset)) continue
        val name = asset.optString("name").trim().lowercase()
        if (name.isBlank()) continue
        val looksLikeShaAsset = name.endsWith(".sha256") ||
            name.endsWith(".sha256sum") ||
            name.endsWith(".sha256.txt") ||
            (name.contains("sha256") && baseName.isNotBlank() && name.contains(baseName))
        if (looksLikeShaAsset) {
            return asset.optString("browser_download_url").trim().ifBlank { null }
        }
    }
    return null
}

private fun JSONArray.findAssetUrl(assetName: String, versionTag: String): String? {
    for (i in 0 until length()) {
        val asset = optJSONObject(i) ?: continue
        if (!isUploadedAsset(asset)) continue
        if (asset.optString("name").equals(assetName, ignoreCase = true)) {
            return asset.optString("browser_download_url").trim().ifBlank {
                buildGitHubAssetDownloadUrl(versionTag, assetName)
            }
        }
    }
    return null
}

private fun extractSha256FromAssetDigest(asset: JSONObject): String? {
    val digest = asset.optString("digest").trim()
    if (digest.isBlank()) return null
    return normalizeSha256(digest.substringAfter("sha256:", digest))
}

internal fun extractSha256FromText(text: String, downloadFileName: String? = null): String? {
    if (text.isBlank()) return null
    val fileName = downloadFileName?.lowercase()
    val fileStem = fileName?.removeSuffix(".apk")
    val lines = text.lineSequence().toList()

    for (line in lines) {
        val normalizedLine = line.lowercase()
        val mentionsTargetFile = fileName != null && normalizedLine.contains(fileName)
        val mentionsTargetStem = fileStem != null && fileStem.isNotBlank() && normalizedLine.contains(fileStem)
        if (mentionsTargetFile || mentionsTargetStem) {
            normalizeSha256(SHA256_REGEX.find(line)?.value)?.let { return it }
        }
    }

    for (line in lines) {
        if (line.contains("sha256", ignoreCase = true)) {
            normalizeSha256(SHA256_REGEX.find(line)?.value)?.let { return it }
        }
    }

    return normalizeSha256(SHA256_REGEX.find(text)?.value)
}

private fun normalizeSha256(value: String?): String? {
    val cleaned = value?.trim()?.lowercase() ?: return null
    return cleaned.takeIf { SHA256_REGEX.matches(it) }
}

private fun clearLastUpdateRequestError() {
    lastUpdateRequestErrorMessage = ""
}

private fun setLastUpdateRequestError(message: String) {
    lastUpdateRequestErrorMessage = message
}

private fun describeHttpError(code: Int, responseBody: String): String = when (code) {
    401, 403 -> {
        if (responseBody.contains("rate limit", ignoreCase = true)) {
            "GitHub временно ограничил запросы, попробуйте позже"
        } else {
            "Доступ к серверу обновлений временно ограничен"
        }
    }
    404 -> "Файл обновления или релиз не найден"
    408 -> "Сервер обновлений не ответил вовремя"
    429 -> "Слишком много запросов к серверу обновлений"
    in 500..599 -> "Сервер обновлений временно недоступен (HTTP $code)"
    else -> "Ошибка сервера обновлений (HTTP $code)"
}

private fun describeRequestException(error: Exception): String = when (error) {
    is UnknownHostException -> "Нет подключения к интернету"
    is SocketTimeoutException -> "Истекло время ожидания ответа"
    is ConnectException -> "Не удалось подключиться к серверу обновлений"
    is SSLException -> "Ошибка защищенного соединения с сервером обновлений"
    else -> error.message?.takeIf { it.isNotBlank() } ?: "Не удалось выполнить запрос на обновление"
}

private fun openUnknownSourcesSettings(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
    return try {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        true
    } catch (e: Exception) {
        Log.e(UPDATE_LOG_TAG, "Failed to open unknown-sources settings", e)
        false
    }
}

private val SHA256_REGEX = Regex("\\b[a-fA-F0-9]{64}\\b")
