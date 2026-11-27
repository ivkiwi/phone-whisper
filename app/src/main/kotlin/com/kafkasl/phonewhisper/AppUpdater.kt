package com.kafkasl.phonewhisper

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** A newer CI build, published by .github/workflows/build.yml as release `build-<versionCode>`. */
data class AppUpdate(val versionCode: Long, val apkUrl: String, val sizeBytes: Long)

object AppUpdater {
    private const val LATEST_RELEASE_URL =
        "https://api.github.com/repos/ivkiwi/phone-whisper/releases/latest"
    private val client = OkHttpClient()

    fun installedVersionCode(ctx: Context): Long =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode

    /** Latest release if it is a newer `build-N` with an APK asset. Blocking. */
    fun check(currentVersionCode: Long): AppUpdate? {
        val request = Request.Builder().url(LATEST_RELEASE_URL)
            .header("Accept", "application/vnd.github+json").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            return parseLatest(response.body?.string() ?: "", currentVersionCode)
        }
    }

    internal fun parseLatest(json: String, currentVersionCode: Long): AppUpdate? {
        val release = JSONObject(json)
        val tag = release.optString("tag_name")
        if (!tag.startsWith("build-")) return null
        val code = tag.removePrefix("build-").toLongOrNull() ?: return null
        if (code <= currentVersionCode) return null
        val assets = release.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            if (asset.optString("name").endsWith(".apk")) {
                return AppUpdate(code, asset.getString("browser_download_url"), asset.optLong("size", -1))
            }
        }
        return null
    }

    /** Download the APK into cache/updates/ (replacing older ones). Blocking. */
    fun download(ctx: Context, update: AppUpdate, onProgress: (Float) -> Unit): File {
        val dir = File(ctx.cacheDir, "updates")
        dir.deleteRecursively()
        dir.mkdirs()
        val apk = File(dir, "phone-whisper-build-${update.versionCode}.apk")
        var done = 0L
        val bytes = ModelDownloader.downloadTo(update.apkUrl, apk) { delta ->
            done += delta
            if (update.sizeBytes > 0) onProgress((done.toFloat() / update.sizeBytes).coerceIn(0f, 1f))
        }
        if (update.sizeBytes > 0 && bytes != update.sizeBytes) {
            apk.delete()
            throw IOException("Incomplete update: $bytes/${update.sizeBytes} bytes")
        }
        return apk
    }

    /**
     * Hand the APK to the system installer. Android verifies it is signed with the same key
     * (app/debug-signing.p12) and asks the user to confirm. Returns false if the user first has
     * to allow installs from this app; the settings screen is opened and they retry after.
     */
    fun install(ctx: Context, apk: File): Boolean {
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
            )
            return false
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.updates", apk)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return true
    }
}
