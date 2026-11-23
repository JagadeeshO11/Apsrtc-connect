package `in`.apsrtc.connect

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

data class AppUpdate(val version: String, val downloadUrl: String, val notes: String, val sizeBytes: Long)

object UpdateManager {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun checkLatest(): AppUpdate? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://api.github.com/repos/JagadeeshO11/Apsrtc-connect/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("GitHub update check failed (HTTP ${response.code})")
            val json = JSONObject(response.body?.string().orEmpty())
            val tag = json.optString("tag_name").removePrefix("v")
            val assetList = json.optJSONArray("assets") ?: throw IllegalStateException("No APK attached to the latest release")
            var apkUrl = ""
            var apkSize = 0L
            for (i in 0 until assetList.length()) {
                val asset = assetList.getJSONObject(i)
                if (asset.optString("name").endsWith(".apk", true)) {
                    apkUrl = asset.optString("browser_download_url")
                    apkSize = asset.optLong("size", 0L)
                    break
                }
            }
            if (apkUrl.isBlank()) throw IllegalStateException("Latest release does not contain an APK yet")
            if (compareVersions(tag, BuildConfig.VERSION_NAME) <= 0) null
            else AppUpdate(tag, apkUrl, json.optString("body"), apkSize)
        }
    }

    private fun compareVersions(a: String, b: String): Int {
        val left = a.split(".").map { it.toIntOrNull() ?: 0 }
        val right = b.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(left.size, right.size)) {
            val diff = (left.getOrElse(i) { 0 }).compareTo(right.getOrElse(i) { 0 })
            if (diff != 0) return diff
        }
        return 0
    }

    private fun apkFile(context: Context, version: String): File = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "apsrtc-connect-$version.apk")

    fun cachedApk(context: Context, update: AppUpdate): File? {
        val file = apkFile(context, update.version)
        return file.takeIf { it.exists() && it.length() > 0L && (update.sizeBytes <= 0L || it.length() == update.sizeBytes) }
    }

    fun download(context: Context, update: AppUpdate): Long {
        val request = DownloadManager.Request(Uri.parse(update.downloadUrl))
            .setTitle("APSRTC Connect update")
            .setDescription("Downloading the latest APSRTC Connect APK")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, "apsrtc-connect-${update.version}.apk")
        return (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
    }

    suspend fun waitForDownload(context: Context, id: Long, version: String): File = withContext(Dispatchers.IO) {
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        repeat(300) {
            val query = DownloadManager.Query().setFilterById(id)
            manager.query(query).use { cursor ->
                if (cursor.moveToFirst()) {
                    when (cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                        DownloadManager.STATUS_SUCCESSFUL -> {
                            val file = apkFile(context, version)
                            if (file.exists() && file.length() > 0L && (file.length() == 0L || file.length() > 0L)) return@withContext file
                            throw IllegalStateException("Downloaded APK could not be found")
                        }
                        DownloadManager.STATUS_FAILED -> {
                            val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                            throw IllegalStateException("APK download failed (code $reason)")
                        }
                    }
                } else throw IllegalStateException("APK download was cancelled")
            }
            delay(1000)
        }
        throw IllegalStateException("APK download timed out. Please try again.")
    }

    fun install(context: Context, apk: File) {
        if (!context.packageManager.canRequestPackageInstalls()) {
            val settings = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(settings)
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(intent)
    }
}
