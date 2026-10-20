package app.falak.player.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import app.falak.player.BuildConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

class UpdateInfo(
    val build: Int,
    val title: String,
    val notes: String,
    val apkUrl: String,
    val sizeBytes: Long,
)

/**
 * One-tap updates for sideloaded builds: reads the latest GitHub release of this app's repo
 * (tag "build-N", N = versionCode) and installs its APK. Disabled in the Google Play build.
 */
object Updater {
    val enabled: Boolean get() = BuildConfig.SELF_UPDATE

    private val agent = "Falak/${BuildConfig.VERSION_NAME}"

    /** Returns the newer release, or null if this build is already the latest. */
    fun check(http: OkHttpClient): UpdateInfo? {
        val req = Request.Builder()
            .url("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", agent)
            .build()
        http.newCall(req).execute().use { res ->
            if (res.code == 404) return null // no releases yet
            if (!res.isSuccessful) throw IOException("Update server returned HTTP ${res.code}")
            val root = Json.parseToJsonElement(res.body?.string().orEmpty()) as? JsonObject ?: return null
            val tag = root.text("tag_name")
            val build = Regex("(\\d+)$").find(tag)?.groupValues?.get(1)?.toIntOrNull() ?: return null
            if (build <= BuildConfig.VERSION_CODE) return null
            val asset = (root["assets"] as? JsonArray)
                ?.mapNotNull { it as? JsonObject }
                ?.firstOrNull { it.text("name").endsWith(".apk") }
                ?: return null
            return UpdateInfo(
                build = build,
                title = root.text("name").ifBlank { tag },
                notes = root.text("body"),
                apkUrl = asset.text("browser_download_url"),
                sizeBytes = asset.text("size").toLongOrNull() ?: 0L,
            )
        }
    }

    /** Downloads the APK into the app's cache, reporting progress from 0 to 1. */
    fun download(context: Context, http: OkHttpClient, info: UpdateInfo, onProgress: (Float) -> Unit): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "falak-${info.build}.apk")
        val req = Request.Builder().url(info.apkUrl).header("User-Agent", agent).build()
        http.newCall(req).execute().use { res ->
            if (!res.isSuccessful) throw IOException("Download failed (HTTP ${res.code})")
            val body = res.body ?: throw IOException("Empty download")
            val total = body.contentLength().takeIf { it > 0 } ?: info.sizeBytes
            body.byteStream().use { input ->
                file.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    var lastPercent = -1
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        done += n
                        if (total > 0) {
                            val percent = (done * 100 / total).toInt()
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(percent / 100f)
                            }
                        }
                    }
                }
            }
        }
        return file
    }

    /** Android 8+ asks the user once to allow this app to install updates. */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun JsonObject.text(key: String): String {
        val p = this[key] as? JsonPrimitive ?: return ""
        return if (p.isString || p.content != "null") p.content else ""
    }
}
