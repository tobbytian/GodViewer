package com.godviewer.app.host

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.godviewer.app.BuildConfig
import com.godviewer.app.R
import com.godviewer.app.host.prefs.HostPrefs
import com.godviewer.app.shared.GITHUB_LATEST_RELEASE_API
import com.godviewer.app.shared.GITHUB_RELEASES_PAGE_URL
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 打开宿主时在线检查 GitHub Releases 最新版；有新版本则弹窗。
 * 无第三方网络库：HttpURLConnection + Gson。
 */
object UpdateChecker {
    private const val TAG = "GodViewer.Update"
    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 8_000

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val checking = AtomicBoolean(false)
    private val gson = Gson()

    data class ReleaseInfo(
        val tagName: String,
        val versionName: String,
        val htmlUrl: String,
        val name: String?,
        val body: String?,
    )

    /**
     * 启动时静默检查：仅在自动更新开启、且未对最新 tag「不再提示」时弹窗。
     */
    fun checkOnLaunch(activity: Activity) {
        if (!HostPrefs.isAutoUpdateEnabled(activity)) {
            Log.d(TAG, "auto update off, skip launch check")
            return
        }
        check(activity, silent = true, forcePrompt = false)
    }

    /**
     * 设置页手动检查：始终反馈结果（已是最新 / 有更新 / 失败）。
     */
    fun checkManual(context: Context) {
        check(context, silent = false, forcePrompt = true)
    }

    private fun check(context: Context, silent: Boolean, forcePrompt: Boolean) {
        if (!checking.compareAndSet(false, true)) {
            if (!silent) {
                Toast.makeText(context, R.string.update_checking, Toast.LENGTH_SHORT).show()
            }
            return
        }
        if (!silent) {
            Toast.makeText(context, R.string.update_checking, Toast.LENGTH_SHORT).show()
        }
        val appCtx = context.applicationContext
        executor.execute {
            val result = runCatching { fetchLatestRelease() }
            mainHandler.post {
                checking.set(false)
                result.onSuccess { release ->
                    handleSuccess(context, appCtx, release, silent, forcePrompt)
                }.onFailure { err ->
                    Log.w(TAG, "check failed", err)
                    if (!silent) {
                        Toast.makeText(context, R.string.update_check_failed, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun handleSuccess(
        uiContext: Context,
        appCtx: Context,
        release: ReleaseInfo,
        silent: Boolean,
        forcePrompt: Boolean,
    ) {
        val newer = isNewerThanCurrent(release.versionName)
        if (!newer) {
            if (!silent) {
                Toast.makeText(uiContext, R.string.update_already_latest, Toast.LENGTH_SHORT).show()
            }
            return
        }
        val skip = HostPrefs.getUpdateSkipTag(appCtx)
        if (!forcePrompt && !skip.isNullOrBlank() && skip.equals(release.tagName, ignoreCase = true)) {
            Log.d(TAG, "skip prompt for tag=${release.tagName}")
            return
        }
        val activity = uiContext as? Activity
        if (activity == null || activity.isFinishing) {
            return
        }
        showUpdateDialog(activity, release)
    }

    private fun showUpdateDialog(activity: Activity, release: ReleaseInfo) {
        val message = activity.getString(
            R.string.update_dialog_message,
            release.versionName,
            BuildConfig.VERSION_NAME,
        )
        AlertDialog.Builder(activity)
            .setTitle(R.string.update_dialog_title)
            .setMessage(message)
            .setPositiveButton(R.string.update_dialog_download) { _, _ ->
                openUrl(activity, release.htmlUrl.ifBlank { GITHUB_RELEASES_PAGE_URL })
            }
            .setNegativeButton(R.string.update_dialog_skip) { _, _ ->
                HostPrefs.setUpdateSkipTag(activity, release.tagName)
                Toast.makeText(activity, R.string.update_dialog_skipped, Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton(R.string.cancel, null)
            .show()
    }

    private fun openUrl(context: Context, url: String) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
        }.onFailure {
            Toast.makeText(context, url, Toast.LENGTH_SHORT).show()
        }
    }

    private fun fetchLatestRelease(): ReleaseInfo {
        val conn = (URL(GITHUB_LATEST_RELEASE_API).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "GodViewer/${BuildConfig.VERSION_NAME}")
            instanceFollowRedirects = true
        }
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.use { input ->
                BufferedReader(InputStreamReader(input, Charsets.UTF_8)).readText()
            }.orEmpty()
            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code: ${body.take(200)}")
            }
            val dto = gson.fromJson(body, GithubReleaseDto::class.java)
                ?: throw IllegalStateException("empty release json")
            val tag = dto.tagName?.trim().orEmpty()
            if (tag.isEmpty()) throw IllegalStateException("missing tag_name")
            val versionName = stripVersionPrefix(tag)
            val html = dto.htmlUrl?.trim().orEmpty().ifBlank { GITHUB_RELEASES_PAGE_URL }
            return ReleaseInfo(
                tagName = tag,
                versionName = versionName,
                htmlUrl = html,
                name = dto.name,
                body = dto.body,
            )
        } finally {
            conn.disconnect()
        }
    }

    /** v3.3.2 / V3.3.2 → 3.3.2 */
    fun stripVersionPrefix(tag: String): String {
        val t = tag.trim()
        return if (t.length >= 2 && (t[0] == 'v' || t[0] == 'V') && t[1].isDigit()) {
            t.substring(1)
        } else {
            t
        }
    }

    /** remote 是否比当前 versionName 新（按点分数字比较） */
    fun isNewerThanCurrent(remoteVersion: String): Boolean {
        return compareVersion(remoteVersion, BuildConfig.VERSION_NAME) > 0
    }

    /**
     * @return >0 remote 更新；0 相同；<0 remote 更旧
     */
    fun compareVersion(a: String, b: String): Int {
        val pa = parseParts(a)
        val pb = parseParts(b)
        val n = maxOf(pa.size, pb.size)
        for (i in 0 until n) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }

    private fun parseParts(version: String): List<Int> {
        val cleaned = version.trim().removePrefix("v").removePrefix("V")
        if (cleaned.isEmpty()) return listOf(0)
        return cleaned.split('.', '-', '_')
            .map { part ->
                part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
            }
    }

    private data class GithubReleaseDto(
        @SerializedName("tag_name") val tagName: String? = null,
        @SerializedName("html_url") val htmlUrl: String? = null,
        @SerializedName("name") val name: String? = null,
        @SerializedName("body") val body: String? = null,
    )
}
