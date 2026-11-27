package com.kafkasl.phonewhisper

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** A newer CI build, published by .github/workflows/build.yml as release `build-<versionCode>`. */
data class AppUpdate(val versionCode: Long, val apkUrl: String)

object AppUpdater {
    private const val RELEASES = "https://github.com/ivkiwi/phone-whisper/releases"
    // The web redirect names the latest release without spending the 60/hour API quota.
    private val client = OkHttpClient.Builder().followRedirects(false).build()
    private val downloading = AtomicBoolean(false)

    val isDownloading: Boolean get() = downloading.get()

    fun installedVersionCode(ctx: Context): Long =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode

    /** Latest release if it is a newer `build-N`. Blocking. */
    fun check(currentVersionCode: Long): AppUpdate? {
        client.newCall(Request.Builder().url("$RELEASES/latest").build()).execute().use { response ->
            if (!response.isRedirect) throw IOException("HTTP ${response.code}")
            return parseLatest(response.header("Location"), currentVersionCode)
        }
    }

    /** `…/releases/tag/build-N` → update to N if newer; `…/releases` (no release yet) → null. */
    internal fun parseLatest(location: String?, currentVersionCode: Long): AppUpdate? {
        val tag = location?.substringAfter("/releases/tag/", "") ?: return null
        val code = tag.removePrefix("build-").takeIf { it != tag }?.toLongOrNull() ?: return null
        if (code <= currentVersionCode) return null
        return AppUpdate(code, "$RELEASES/download/$tag/phone-whisper.apk")
    }

    private fun apkFile(ctx: Context, update: AppUpdate) =
        File(ctx.cacheDir, "updates/phone-whisper-build-${update.versionCode}.apk")

    /** The APK for [update] if a previous download finished (files appear only by rename). */
    fun downloaded(ctx: Context, update: AppUpdate): File? = apkFile(ctx, update).takeIf { it.isFile }

    fun clearDownloads(ctx: Context) {
        if (!isDownloading) File(ctx.cacheDir, "updates").deleteRecursively()
    }

    /**
     * Download the APK into cache/updates/, resuming a partial of the same build and dropping
     * older ones. Process-wide single flight: returns null if a download is already running
     * (e.g. started before the activity was recreated). Blocking.
     */
    fun download(ctx: Context, update: AppUpdate, onProgress: (Long) -> Unit): File? {
        if (!downloading.compareAndSet(false, true)) return null
        try {
            downloaded(ctx, update)?.let { return it }
            val apk = apkFile(ctx, update)
            val dir = apk.parentFile!!
            val part = File(dir, "${apk.name}.part")
            dir.listFiles()?.filter { it != part }?.forEach { it.deleteRecursively() } // keep a partial of this build to resume
            dir.mkdirs()
            var done = part.length()
            ModelDownloader.downloadWithRetry(update.apkUrl, part) { delta -> done += delta; onProgress(done) }
            if (!part.renameTo(apk)) throw IOException("Failed to save ${apk.name}")
            return apk
        } finally {
            downloading.set(false)
        }
    }

    /** Installs from this app need a one-time user grant; on Android 11+ granting it restarts the app. */
    fun canInstall(ctx: Context) = ctx.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings(ctx: Context) = ctx.startActivity(
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
    )

    /**
     * Hand the APK to the system installer. Android checks it is signed with the same key
     * (app/debug-signing.p12) and asks the user to confirm.
     */
    fun install(ctx: Context, apk: File) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.updates", apk)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
