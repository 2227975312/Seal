package com.junkfood.seal.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import com.junkfood.seal.App
import com.junkfood.seal.App.Companion.context
import com.junkfood.seal.R
import com.junkfood.seal.util.FileUtil.getFileProvider
import com.junkfood.seal.util.PreferenceUtil.getBoolean
import com.junkfood.seal.util.PreferenceUtil.getInt
import com.junkfood.seal.util.PreferenceUtil.getString
import com.junkfood.seal.util.PreferenceUtil.updateLong
import com.yausername.youtubedl_android.YoutubeDL
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody

object UpdateUtil {

    private const val OWNER = "JunkFood02"
    private const val REPO = "Seal"
    private const val ARM64 = "arm64-v8a"
    private const val ARM32 = "armeabi-v7a"
    private const val X86 = "x86"
    private const val X64 = "x86_64"
    private const val TAG = "UpdateUtil"

    private val client = OkHttpClient()
    private val requestForLatestRelease =
        Request.Builder()
            .url("https://api.github.com/repos/${OWNER}/${REPO}/releases/latest")
            .build()

    private val requestForReleases =
        Request.Builder().url("https://api.github.com/repos/${OWNER}/${REPO}/releases").build()

    private const val YT_DLP_ASSET = "yt-dlp"
    private const val YT_DLP_SUMS_ASSET = "SHA2-256SUMS"
    private const val DEFAULT_GITHUB_MIRROR = "https://gh-proxy.com"
    private const val SHA256_HEX_LENGTH = 64
    private val WHITESPACE = Regex("\\s+")

    private const val YT_DLP_STABLE_API =
        "https://api.github.com/repos/yt-dlp/yt-dlp/releases/latest"
    private const val YT_DLP_NIGHTLY_API =
        "https://api.github.com/repos/yt-dlp/yt-dlp-nightly-builds/releases/latest"
    private const val YT_DLP_STABLE_RELEASE_URL =
        "https://github.com/yt-dlp/yt-dlp/releases/download"
    private const val YT_DLP_NIGHTLY_RELEASE_URL =
        "https://github.com/yt-dlp/yt-dlp-nightly-builds/releases/download"

    /** Preferences owned by youtubedl-android, kept in sync so that its version lookup works. */
    private const val YTDLP_PREFS = "youtubedl-android"

    private const val YTDLP_VERSION_KEY = "dlpVersion"
    private const val YTDLP_VERSION_NAME_KEY = "dlpVersionName"
    private const val MIN_YTDLP_SIZE = 1_000_000L

    private val jsonFormat = Json { ignoreUnknownKeys = true }

    /** The default timeouts are too short for downloading several MB through a mirror. */
    private val updateClient: OkHttpClient by lazy {
        client
            .newBuilder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.MINUTES)
            .build()
    }

    suspend fun updateYtDlp(): YoutubeDL.UpdateStatus? =
        withContext(Dispatchers.IO) {
            val channel = YT_DLP_UPDATE_CHANNEL.getInt()
            val mirror =
                if (GITHUB_MIRROR_ENABLED.getBoolean()) {
                    GITHUB_MIRROR.getString().toMirrorPrefix().ifEmpty { DEFAULT_GITHUB_MIRROR }
                } else {
                    ""
                }

            val status =
                if (mirror.isEmpty()) {
                    updateYtDlpWithYoutubeDL(channel)
                } else {
                    updateYtDlpWithMirror(channel, mirror)
                }

            YT_DLP_UPDATE_TIME.updateLong(System.currentTimeMillis())
            status
        }

    private fun updateYtDlpWithYoutubeDL(channel: Int): YoutubeDL.UpdateStatus? {
        val updateChannel =
            when (channel) {
                YT_DLP_NIGHTLY -> YoutubeDL.UpdateChannel.NIGHTLY
                else -> YoutubeDL.UpdateChannel.STABLE
            }
        return YoutubeDL.getInstance()
            .updateYoutubeDL(appContext = context, updateChannel = updateChannel)
            .also { status ->
                if (status == YoutubeDL.UpdateStatus.DONE) {
                    YoutubeDL.getInstance().version(context)?.let { version ->
                        PreferenceUtil.encodeString(YT_DLP_VERSION, version)
                    }
                }
            }
    }

    private fun updateYtDlpWithMirror(channel: Int, mirror: String): YoutubeDL.UpdateStatus {
        val nightly = channel == YT_DLP_NIGHTLY
        val apiUrl = if (nightly) YT_DLP_NIGHTLY_API else YT_DLP_STABLE_API
        val releaseUrl = if (nightly) YT_DLP_NIGHTLY_RELEASE_URL else YT_DLP_STABLE_RELEASE_URL

        val release = fetchRelease(apiUrl, mirror)
        val tag =
            release.tagName?.takeIf { it.isNotBlank() }
                ?: throw IOException("Missing tag name in release response")

        if (tag == readYtdlpPref(YTDLP_VERSION_KEY)) {
            return YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE
        }

        // Build both URLs from the resolved tag instead of trusting the release JSON, so a
        // tampered API response cannot point the checksum at an attacker controlled URL.
        val assetUrl = "$releaseUrl/$tag/$YT_DLP_ASSET"
        val sumsUrl = "$releaseUrl/$tag/$YT_DLP_SUMS_ASSET"

        val downloaded = downloadYtDlpAsset(assetUrl, mirror)
        try {
            verifyChecksum(downloaded, sumsUrl, mirror)
            installYtDlp(downloaded)
        } finally {
            downloaded.delete()
        }

        writeYtdlpPref(YTDLP_VERSION_KEY, tag)
        writeYtdlpPref(YTDLP_VERSION_NAME_KEY, release.name ?: tag)
        PreferenceUtil.encodeString(YT_DLP_VERSION, tag)
        Log.i(TAG, "yt-dlp updated to $tag")
        return YoutubeDL.UpdateStatus.DONE
    }

    private fun fetchRelease(apiUrl: String, mirror: String): Release {
        var lastError: Exception? = null
        for (url in apiUrl.mirroredBy(mirror)) {
            try {
                val request =
                    Request.Builder()
                        .url(url)
                        .header("Accept", "application/vnd.github+json")
                        .header("User-Agent", "Seal")
                        .build()
                updateClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code} for $url")
                    return jsonFormat.decodeFromString<Release>(response.body.string())
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to get release info from $url", e)
                lastError = e
            }
        }
        throw lastError ?: IOException("Failed to get release info")
    }

    private fun downloadYtDlpAsset(assetUrl: String, mirror: String): File {
        var lastError: Exception? = null
        for (url in assetUrl.mirroredBy(mirror)) {
            val tempFile = File.createTempFile(YT_DLP_ASSET, null, context.cacheDir)
            try {
                val request = Request.Builder().url(url).build()
                updateClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code} for $url")
                    response.body.byteStream().use { input ->
                        tempFile.outputStream().use { output -> input.copyTo(output) }
                    }
                }
                // A mirror may answer with an HTML error page or a truncated file
                if (tempFile.length() < MIN_YTDLP_SIZE || !tempFile.hasZipAppHeader()) {
                    throw IOException("Unexpected payload from $url")
                }
                return tempFile
            } catch (e: Exception) {
                Log.w(TAG, "Failed to download yt-dlp from $url", e)
                tempFile.delete()
                lastError = e
            }
        }
        throw lastError ?: IOException("Failed to download yt-dlp")
    }

    /** Returns the mirror-prefixed URL first, followed by the original URL as a fallback. */
    private fun String.mirroredBy(mirror: String) =
        if (mirror.isEmpty()) listOf(this) else listOf("$mirror/$this", this)

    /** Returns the original URL first, followed by its mirrored counterpart as a fallback. */
    private fun String.directThenMirrored(mirror: String) =
        if (mirror.isEmpty()) listOf(this) else listOf(this, "$mirror/$this")

    /**
     * Verifies the downloaded bundle against the release checksum. The checksum is fetched from
     * GitHub directly first, so a tampered mirror payload gets rejected. The mirrored checksum is
     * only used when GitHub itself is unreachable.
     */
    private fun verifyChecksum(downloaded: File, sumsUrl: String, mirror: String) {
        val expected = fetchExpectedChecksum(sumsUrl, mirror)
        val actual = downloaded.sha256()
        if (!actual.equals(expected, ignoreCase = true)) {
            throw IOException(
                "Checksum mismatch for $YT_DLP_ASSET (expected $expected, got $actual)"
            )
        }
        Log.i(TAG, "$YT_DLP_ASSET checksum verified")
    }

    private fun fetchExpectedChecksum(sumsUrl: String, mirror: String): String {
        var lastError: Exception? = null
        for (url in sumsUrl.directThenMirrored(mirror)) {
            try {
                val sums =
                    updateClient.newCall(Request.Builder().url(url).build()).execute().use {
                        response ->
                        if (!response.isSuccessful) {
                            throw IOException("HTTP ${response.code} for $url")
                        }
                        response.body.string()
                    }
                return sums
                    .lineSequence()
                    .map { it.trim().split(WHITESPACE) }
                    .firstOrNull { it.size >= 2 && it[1].trimStart('*') == YT_DLP_ASSET }
                    ?.first()
                    ?.takeIf { it.length == SHA256_HEX_LENGTH }
                    ?: throw IOException("No checksum for $YT_DLP_ASSET in $url")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to get checksum from $url", e)
                lastError = e
            }
        }
        throw lastError ?: IOException("Failed to get checksum for $YT_DLP_ASSET")
    }

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString(separator = "") { "%02x".format(it) }
    }

    private fun String.toMirrorPrefix(): String {
        val prefix = trim().trimEnd('/')
        if (prefix.isEmpty()) return ""
        return when {
            prefix.startsWith("http://") || prefix.startsWith("https://") -> prefix
            else -> "https://$prefix"
        }
    }

    private fun File.hasZipAppHeader(): Boolean {
        val header = ByteArray(4)
        val read = inputStream().use { it.read(header) }
        if (read < 2) return false
        val text = String(header, 0, read, Charsets.US_ASCII)
        return text.startsWith("#!") || text.startsWith("PK")
    }

    /** Replaces the yt-dlp bundle used by youtubedl-android, keeping the old one for rollback. */
    private fun installYtDlp(source: File) {
        val ytdlpDir =
            File(File(context.noBackupFilesDir, YoutubeDL.baseName), YoutubeDL.ytdlpDirName)
        if (!ytdlpDir.exists() && !ytdlpDir.mkdirs()) {
            throw IOException("Failed to create ${ytdlpDir.absolutePath}")
        }

        val target = File(ytdlpDir, YoutubeDL.ytdlpBin)
        val incoming = File(ytdlpDir, "${target.name}.new")
        val backup = File(ytdlpDir, "${target.name}.bak")

        source.copyTo(incoming, overwrite = true)
        try {
            if (target.exists()) target.copyTo(backup, overwrite = true)
            if (!incoming.renameTo(target)) {
                target.delete()
                if (!incoming.renameTo(target)) throw IOException("Failed to install yt-dlp")
            }
            backup.delete()
        } catch (e: Exception) {
            incoming.delete()
            if (backup.exists()) backup.copyTo(target, overwrite = true)
            throw e
        }
    }

    private fun readYtdlpPref(key: String): String? =
        context.getSharedPreferences(YTDLP_PREFS, Context.MODE_PRIVATE).getString(key, null)

    private fun writeYtdlpPref(key: String, value: String) {
        context
            .getSharedPreferences(YTDLP_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(key, value)
            .apply()
    }

    private fun getLatestRelease(): Release =
        client.newCall(requestForReleases).execute().body.use {
            val releaseList = jsonFormat.decodeFromString<List<Release>>(it.string())
            val stable = UPDATE_CHANNEL.getInt() == STABLE
            val latestRelease =
                releaseList
                    .filter { if (stable) it.name.toVersion() is Version.Stable else true }
                    .maxByOrNull { it.name.toVersion() } ?: throw Exception("null response")
            latestRelease
        }

    fun checkForUpdate(context: Context = App.context): Release? {
        val currentVersion = context.getCurrentVersion()
        val latestRelease = getLatestRelease()
        val latestVersion = latestRelease.name.toVersion()
        return if (currentVersion < latestVersion) latestRelease else null
    }

    private fun Context.getCurrentVersion(): Version =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager
                .getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
                .versionName
                .toVersion()
        } else {
            packageManager.getPackageInfo(packageName, 0).versionName.toVersion()
        }

    private fun Context.getLatestApk() = File(getExternalFilesDir("apk"), "latest.apk")

    fun installLatestApk(context: Context = App.context) =
        context.run {
            kotlin
                .runCatching {
                    val contentUri =
                        FileProvider.getUriForFile(this, getFileProvider(), getLatestApk())
                    val intent =
                        Intent(Intent.ACTION_VIEW).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            setDataAndType(contentUri, "application/vnd.android.package-archive")
                        }
                    startActivity(intent)
                }
                .onFailure { throwable: Throwable ->
                    throwable.printStackTrace()
                    ToastUtil.makeToast(R.string.app_update_failed)
                }
        }

    suspend fun deleteOutdatedApk(context: Context = App.context) =
        context.runCatching {
            val apkFile = getLatestApk()
            if (apkFile.exists()) {
                val apkVersion =
                    context.packageManager
                        .getPackageArchiveInfo(apkFile.absolutePath, 0)
                        ?.versionName
                        .toVersion()
                if (apkVersion <= context.getCurrentVersion()) {
                    apkFile.delete()
                }
            }
        }

    suspend fun downloadApk(
        context: Context = App.context,
        release: Release,
    ): Flow<DownloadStatus> =
        withContext(Dispatchers.IO) {
            val apkVersion =
                context.packageManager
                    .getPackageArchiveInfo(context.getLatestApk().absolutePath, 0)
                    ?.versionName
                    .toVersion()

            Log.d(TAG, apkVersion.toString())

            if (apkVersion >= release.name.toVersion()) {
                return@withContext flow<DownloadStatus> {
                    emit(DownloadStatus.Finished(context.getLatestApk()))
                }
            }

            val abiList = Build.SUPPORTED_ABIS
            val preferredArch = abiList.firstOrNull() ?: return@withContext emptyFlow()

            val targetUrl =
                release.assets
                    ?.find {
                        return@find it.name?.contains(preferredArch) ?: false
                    }
                    ?.browserDownloadUrl ?: return@withContext emptyFlow()
            val request = Request.Builder().url(targetUrl).build()
            try {
                val response = client.newCall(request).execute()
                val responseBody = response.body
                return@withContext responseBody.downloadFileWithProgress(context.getLatestApk())
            } catch (e: Exception) {
                e.printStackTrace()
            }
            emptyFlow()
        }

    private fun ResponseBody.downloadFileWithProgress(saveFile: File): Flow<DownloadStatus> =
        flow {
                emit(DownloadStatus.Progress(0))

                var deleteFile = true

                try {
                    byteStream().use { inputStream ->
                        saveFile.outputStream().use { outputStream ->
                            val totalBytes = contentLength()
                            val data = ByteArray(8_192)
                            var progressBytes = 0L

                            while (true) {
                                val bytes = inputStream.read(data)

                                if (bytes == -1) {
                                    break
                                }

                                outputStream.channel
                                outputStream.write(data, 0, bytes)
                                progressBytes += bytes
                                emit(
                                    DownloadStatus.Progress(
                                        percent = ((progressBytes * 100) / totalBytes).toInt()
                                    )
                                )
                            }

                            when {
                                progressBytes < totalBytes -> throw Exception("missing bytes")
                                progressBytes > totalBytes -> throw Exception("too many bytes")
                                else -> deleteFile = false
                            }
                        }
                    }

                    emit(DownloadStatus.Finished(saveFile))
                } finally {
                    if (deleteFile) {
                        saveFile.delete()
                    }
                }
            }
            .flowOn(Dispatchers.IO)
            .distinctUntilChanged()

    @Serializable
    data class Release(
        @SerialName("html_url") val htmlUrl: String? = null,
        @SerialName("tag_name") val tagName: String? = null,
        val name: String? = null,
        val draft: Boolean? = null,
        @SerialName("prerelease") val preRelease: Boolean? = null,
        @SerialName("created_at") val createdAt: String? = null,
        @SerialName("published_at") val publishedAt: String? = null,
        val assets: List<AssetsItem>? = null,
        val body: String? = null,
    )

    @Serializable
    data class AssetsItem(
        val name: String? = null,
        @SerialName("content_type") val contentType: String? = null,
        val size: Int? = null,
        @SerialName("download_count") val downloadCount: Int? = null,
        @SerialName("created_at") val createdAt: String? = null,
        @SerialName("updated_at") val updatedAt: String? = null,
        @SerialName("browser_download_url") val browserDownloadUrl: String? = null,
    )

    sealed class DownloadStatus {
        object NotYet : DownloadStatus()

        data class Progress(val percent: Int) : DownloadStatus()

        data class Finished(val file: File) : DownloadStatus()
    }

    private val pattern = Pattern.compile("""v?(\d+)\.(\d+)\.(\d+)(-(\w+)\.(\d+))?""")
    private val EMPTY_VERSION = Version.Stable()

    fun String?.toVersion(): Version =
        this?.run {
            val matcher = pattern.matcher(this)
            if (matcher.find()) {
                val major = matcher.group(1)?.toInt() ?: 0
                val minor = matcher.group(2)?.toInt() ?: 0
                val patch = matcher.group(3)?.toInt() ?: 0
                val buildNumber = matcher.group(6)?.toInt() ?: 0
                when (matcher.group(5)) {
                    "alpha" -> Version.Alpha(major, minor, patch, buildNumber)
                    "beta" -> Version.Beta(major, minor, patch, buildNumber)
                    "rc" -> Version.ReleaseCandidate(major, minor, patch, buildNumber)
                    else -> Version.Stable(major, minor, patch)
                }
            } else EMPTY_VERSION
        } ?: EMPTY_VERSION

    sealed class Version(val major: Int, val minor: Int, val patch: Int, val build: Int = 0) :
        Comparable<Version> {
        companion object {
            // private const val ABI = 1L
            private const val BUILD = 10L
            private const val VARIANT = 100L
            private const val PATCH = 10_000L
            private const val MINOR = 1_000_000L
            private const val MAJOR = 100_000_000L

            private const val STABLE = VARIANT * 4
            private const val ALPHA = VARIANT * 1
            private const val BETA = VARIANT * 2
            private const val RELEASE_CANDIDATE = VARIANT * 3
        }

        abstract fun toVersionName(): String

        abstract fun toNumber(): Long

        class Alpha(
            versionMajor: Int = 0,
            versionMinor: Int = 0,
            versionPatch: Int = 0,
            versionBuild: Int = 0,
        ) : Version(versionMajor, versionMinor, versionPatch, versionBuild) {
            override fun toVersionName(): String = "${major}.${minor}.${patch}-alpha.$build"

            override fun toNumber(): Long =
                major * MAJOR + minor * MINOR + patch * PATCH + build * BUILD + ALPHA
        }

        class Beta(versionMajor: Int, versionMinor: Int, versionPatch: Int, versionBuild: Int) :
            Version(versionMajor, versionMinor, versionPatch, versionBuild) {
            override fun toVersionName(): String = "${major}.${minor}.${patch}-beta.$build"

            override fun toNumber(): Long =
                major * MAJOR + minor * MINOR + patch * PATCH + build * BUILD + BETA
        }

        class ReleaseCandidate(
            versionMajor: Int,
            versionMinor: Int,
            versionPatch: Int,
            versionBuild: Int,
        ) : Version(versionMajor, versionMinor, versionPatch, versionBuild) {
            override fun toVersionName(): String = "${major}.${minor}.${patch}-rc.$build"

            override fun toNumber(): Long =
                major * MAJOR + minor * MINOR + patch * PATCH + build * BUILD + RELEASE_CANDIDATE
        }

        class Stable(versionMajor: Int = 0, versionMinor: Int = 0, versionPatch: Int = 0) :
            Version(versionMajor, versionMinor, versionPatch) {
            override fun toVersionName(): String = "${major}.${minor}.${patch}"

            override fun toNumber(): Long =
                major * MAJOR + minor * MINOR + patch * PATCH + build * BUILD + STABLE
            // Prioritize stable versions

        }

        override operator fun compareTo(other: Version): Int =
            this.toNumber().compareTo(other.toNumber())
    }
}
