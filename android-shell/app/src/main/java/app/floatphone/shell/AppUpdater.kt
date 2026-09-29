package app.floatphone.shell

import android.app.AlertDialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** 从 GitHub Releases 检查并安装壳更新。网页内容仍由线上站点即时更新。 */
class AppUpdater(private val activity: MainActivity) {
    private val client = OkHttpClient()
    private var downloadId = -1L
    private var downloadedApk: File? = null
    private var checking = false

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE ||
                intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) != downloadId
            ) return
            downloadedApk?.let(::installApk)
        }
    }

    fun register() {
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= 33) {
            activity.registerReceiver(downloadReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            activity.registerReceiver(downloadReceiver, filter)
        }
    }

    fun unregister() = runCatching { activity.unregisterReceiver(downloadReceiver) }.let { Unit }

    fun check(silent: Boolean = false) {
        if (checking) return
        checking = true
        val request = Request.Builder()
            .url(BuildConfig.UPDATE_API_URL)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "FloatShell/${BuildConfig.VERSION_NAME}")
            .build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = finishCheck {
                if (!silent) toast("检查更新失败，请稍后重试")
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) {
                        finishCheck { if (!silent) toast("检查更新失败（${it.code}）") }
                        return
                    }
                    val release = runCatching { JSONObject(it.body?.string().orEmpty()) }.getOrNull()
                    val tag = release?.optString("tag_name").orEmpty().removePrefix("v")
                    val notes = release?.optString("body").orEmpty()
                    val apkUrl = release?.optJSONArray("assets")?.let { assets ->
                        (0 until assets.length()).asSequence()
                            .map { assets.getJSONObject(it) }
                            .firstOrNull { asset -> asset.optString("name").endsWith(".apk", true) }
                            ?.optString("browser_download_url")
                    }
                    finishCheck {
                        when {
                            tag.isBlank() || apkUrl.isNullOrBlank() -> if (!silent) toast("最新版本没有可安装的 APK")
                            isNewer(tag, BuildConfig.VERSION_NAME) -> showUpdate(tag, notes, apkUrl)
                            !silent -> toast("当前已是最新版本 ${BuildConfig.VERSION_NAME}")
                        }
                    }
                }
            }
        })
    }

    private fun finishCheck(block: () -> Unit) = activity.runOnUiThread {
        checking = false
        block()
    }

    private fun showUpdate(version: String, notes: String, apkUrl: String) {
        if (activity.isFinishing) return
        val message = notes.trim().take(1200).ifBlank { "发现新版本 $version，是否下载并安装？" }
        AlertDialog.Builder(activity)
            .setTitle("发现新版本 $version")
            .setMessage(message)
            .setNegativeButton("稍后") { _, _ -> }
            .setPositiveButton("下载更新") { _, _ -> download(version, apkUrl) }
            .show()
    }

    private fun download(version: String, apkUrl: String) {
        val dir = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: activity.cacheDir
        val apk = File(dir, "float-phone-$version.apk")
        if (apk.exists()) apk.delete()
        val request = DownloadManager.Request(Uri.parse(apkUrl))
            .setTitle("小手机 $version")
            .setDescription("正在下载应用更新")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationUri(Uri.fromFile(apk))
        downloadedApk = apk
        downloadId = (activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
        toast("更新开始下载，完成后将打开安装界面")
    }

    private fun installApk(apk: File) {
        if (!apk.exists()) {
            toast("更新文件下载失败")
            return
        }
        if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) {
            toast("请允许“小手机”安装未知应用，然后重新检查更新")
            activity.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
            )
            return
        }
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", apk)
        activity.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }

    private fun toast(message: String) = Toast.makeText(activity, message, Toast.LENGTH_LONG).show()

    private fun isNewer(candidate: String, current: String): Boolean {
        fun parts(value: String) = value.removePrefix("v").substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val left = parts(candidate)
        val right = parts(current)
        repeat(maxOf(left.size, right.size)) { index ->
            val diff = (left.getOrElse(index) { 0 }).compareTo(right.getOrElse(index) { 0 })
            if (diff != 0) return diff > 0
        }
        return false
    }
}
