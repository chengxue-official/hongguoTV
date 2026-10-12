package com.houg.tv.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.houg.tv.net.HttpClientProvider
import okhttp3.Request
import org.json.JSONObject
import java.io.File

/**
 * 热更新：检查 + 下载 + 唤起安装。
 *
 * 架构：本地代码 → GitHub 仓库 → Cloudflare Pages 自动部署 → 电视直接读 Pages 域名。
 * 电视端只接触 Cloudflare Pages 域名，不直连 GitHub（raw.githubusercontent.com 国内不可达）。
 *
 * 仓库根只需两个东西（每次发版改这俩）：
 * ```json
 * // update.json
 * {
 *   "versionCode": 2,
 *   "versionName": "1.0.1",
 *   "apk": "hongguo-1.0.1.apk",
 *   "notes": "修复xxx"
 * }
 * ```
 * 加上一个 `hongguo-1.0.1.apk`（放仓库根或 releases/ 子目录，apk 字段写相对路径）。
 *
 * 注意：apk 字段支持相对路径（同 Pages 域名）或绝对 URL。
 */
object UpdateChecker {

    /**
     * 更新托管域名。已从 *.workers.dev（国内被 DNS 污染+封锁，电视端不可达）
     * 切换为自定义域名 hongguo.kdns.fr（KataBump 免费二级域名，NS 托管到
     * Cloudflare，绑定 hongguotv Worker 静态资源）。
     * 注意：v1.0.1 及更早客户端仍指向 workers.dev，收不到本次推送，
     * 需手动安装一次 1.0.2+ 的 APK 后才能走自动更新。
     */
    const val HOST = "https://hongguo.kdns.fr"

    private fun updateUrl(): String = HOST.trimEnd('/') + "/update.json"

    /** 远端版本信息。 */
    data class Remote(
        val versionCode: Int,
        val versionName: String,
        val apkUrl: String,
        val notes: String
    )

    /** 检查结果：hasUpdate=有新版本；message=给用户的提示。 */
    data class CheckResult(val hasUpdate: Boolean, val remote: Remote?, val message: String)

    /**
     * 同步检查更新（网络操作，调用方需切 IO 线程）。
     * @param currentVersionCode 本机安装的 versionCode
     */
    fun check(currentVersionCode: Int): CheckResult {
        val host = HOST.trim()
        if (host.isEmpty() || !host.startsWith("http")) {
            return CheckResult(false, null, "未配置托管域名（见 UpdateChecker.HOST）")
        }
        return try {
            val req = Request.Builder().url(updateUrl())
                .header("Cache-Control", "no-cache") // 确保拿到最新 update.json 而非缓存
                .build()
            val resp = HttpClientProvider.get().newCall(req).execute()
            try {
                if (!resp.isSuccessful) {
                    return CheckResult(false, null, "检查失败：HTTP ${resp.code()}")
                }
                val text = resp.body()?.string() ?: ""
                val remote = parse(text) ?: return CheckResult(false, null, "update.json 格式错误")
                if (remote.versionCode > currentVersionCode) {
                    CheckResult(true, remote, "发现新版本 v${remote.versionName}")
                } else {
                    CheckResult(false, remote, "已是最新版本 v${remote.versionName}")
                }
            } finally {
                resp.close()
            }
        } catch (e: Exception) {
            CheckResult(false, null, "检查失败：" + (e.message ?: e.javaClass.simpleName))
        }
    }

    private fun parse(text: String): Remote? = try {
        val o = JSONObject(text)
        val apk = o.optString("apk", "")
        if (apk.isEmpty()) null
        else Remote(
            versionCode = o.optInt("versionCode", 0),
            versionName = o.optString("versionName", "?"),
            apkUrl = if (apk.startsWith("http")) apk else HOST.trimEnd('/') + "/" + apk.trimStart('/'),
            notes = o.optString("notes", "")
        )
    } catch (e: Exception) {
        null
    }

    /**
     * 同步下载 APK 到应用缓存目录（网络操作，调用方需切 IO 线程）。
     * @return 下载好的文件；失败返回 null 并写 [error]。
     */
    fun download(context: Context, remote: Remote, error: StringBuilder): File? {
        return try {
            val dir = File(context.cacheDir, "update").apply { mkdirs() }
            // 先写临时文件，完成后改名，避免中断留下半截文件被误装
            val tmp = File(dir, "update.apk.part")
            val target = File(dir, "hongguo-${remote.versionName}.apk")
            tmp.delete()
            target.delete()

            val req = Request.Builder().url(remote.apkUrl).build()
            val resp = HttpClientProvider.get().newCall(req).execute()
            try {
                if (!resp.isSuccessful) {
                    error.append("下载失败：HTTP ${resp.code()}")
                    return null
                }
                val body = resp.body() ?: run {
                    error.append("下载失败：响应为空")
                    return null
                }
                val total = body.contentLength()
                var written = 0L
                body.byteStream().use { input ->
                    tmp.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            written += n
                        }
                        output.flush()
                    }
                }
                if (total > 0 && written < total) {
                    error.append("下载不完整：$written/$total")
                    tmp.delete()
                    return null
                }
                if (!tmp.renameTo(target)) {
                    // rename 失败（跨设备等）：直接复制
                    tmp.copyTo(target, overwrite = true)
                    tmp.delete()
                }
                target
            } finally {
                resp.close()
            }
        } catch (e: Exception) {
            error.append("下载异常：" + (e.message ?: e.javaClass.simpleName))
            null
        }
    }

    /** 唤起系统安装器安装已下载的 APK。 */
    fun install(context: Context, apk: File) {
        val uri: Uri = FileProvider.getUriForFile(
            context, context.packageName + ".fileprovider", apk
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            setDataAndType(uri, "application/vnd.android.package-archive")
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            // 部分盒子无系统安装器，提示用户
            android.widget.Toast.makeText(
                context, "无法启动安装器，请手动安装：$apk", android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }
}
