package app.falak.player.update

import android.content.Context
import okhttp3.OkHttpClient
import java.io.File

class UpdateInfo(
    val build: Int,
    val title: String,
    val notes: String,
    val apkUrl: String,
    val sizeBytes: Long,
)

/**
 * Google Play build: updates come from Google Play only, so there is no update code here at all
 * (Play doesn't allow apps to download or install their own updates). Same API as the GitHub
 * build's Updater so the rest of the app compiles unchanged.
 */
object Updater {
    val enabled: Boolean get() = false

    fun check(http: OkHttpClient): UpdateInfo? = null

    fun download(context: Context, http: OkHttpClient, info: UpdateInfo, onProgress: (Float) -> Unit): File =
        throw UnsupportedOperationException("Updates come from Google Play")

    fun canInstall(context: Context): Boolean = false

    fun openInstallPermission(context: Context): Boolean = false

    fun install(context: Context, apk: File) = Unit
}
