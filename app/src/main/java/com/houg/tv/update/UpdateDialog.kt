package com.houg.tv.update

import android.app.Activity
import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import com.houg.tv.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 更新流程 UI：检查中 → 有新版本（展示更新说明）→ 下载中 → 唤起安装。
 * 用 AlertDialog 实现，遥控器确定键/方向键可直接操作。
 */
object UpdateDialog {

    /** 入口：检查更新并走完整流程。 */
    fun start(activity: Activity, scope: CoroutineScope) {
        val current = currentVersionCode(activity)
        val checking = AlertDialog.Builder(activity)
            .setTitle("检查更新")
            .setMessage("正在检查…")
            .setCancelable(true)
            .create()
        checking.show()

        scope.launch {
            val result = withContext(Dispatchers.IO) { UpdateChecker.check(current) }
            checking.dismiss()

            if (!result.hasUpdate) {
                AlertDialog.Builder(activity)
                    .setTitle("检查更新")
                    .setMessage(result.message)
                    .setPositiveButton("知道了", null)
                    .show()
                return@launch
            }

            val remote = result.remote ?: return@launch
            val notes = remote.notes.ifEmpty { "（无更新说明）" }
            AlertDialog.Builder(activity)
                .setTitle("发现新版本 v${remote.versionName}")
                .setMessage("当前版本：${currentVersionName(activity)}\n" +
                        "最新版本：v${remote.versionName}\n\n" +
                        "更新内容：\n$notes")
                .setPositiveButton("立即更新") { _, _ -> downloadAndInstall(activity, scope, remote) }
                .setNegativeButton("稍后", null)
                .show()
        }
    }

    private fun downloadAndInstall(activity: Activity, scope: CoroutineScope, remote: UpdateChecker.Remote) {
        // Android 8.0+ 安装未知来源应用需要用户授权
        if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(activity)
                .setTitle("需要授权")
                .setMessage("首次安装更新需要开启「允许安装未知应用」权限，请在设置中允许后重试。")
                .setPositiveButton("去设置") { _, _ ->
                    try {
                        activity.startActivity(
                            android.content.Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                                .setData(android.net.Uri.parse("package:" + activity.packageName))
                        )
                    } catch (e: Exception) {
                        android.widget.Toast.makeText(
                            activity, "请手动到系统设置开启安装权限", android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                }
                .setNegativeButton("取消", null)
                .show()
            return
        }

        val progress = AlertDialog.Builder(activity)
            .setTitle("正在下载更新")
            .setMessage("v${remote.versionName} 下载中，请勿关闭电视…")
            .setCancelable(false)
            .create()
        progress.show()

        scope.launch {
            val error = StringBuilder()
            val apk = withContext(Dispatchers.IO) { UpdateChecker.download(activity, remote, error) }
            progress.dismiss()
            if (apk == null) {
                AlertDialog.Builder(activity)
                    .setTitle("更新失败")
                    .setMessage(error.toString())
                    .setPositiveButton("知道了", null)
                    .show()
            } else {
                UpdateChecker.install(activity, apk)
            }
        }
    }

    private fun currentVersionCode(context: Context): Int = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionCode
    } catch (e: Exception) {
        -1
    }

    private fun currentVersionName(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    } catch (e: Exception) {
        "?"
    }
}
