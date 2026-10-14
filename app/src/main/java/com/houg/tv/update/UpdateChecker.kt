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

    /**
     * 检查更新的候选域名（按顺序尝试，任一成功即采用其返回）。
     * 背景：有用户反馈「SSL handshake aborted ... Connection reset by peer」——
     * Cloudflare 自定义域名在部分运营商/地区网络下会被干扰重置。多列一个备用源，
     * 主域名失败时自动降级尝试，提高检查成功率。
     * 备用：Worker 原始 workers.dev 域名（同一份静态资源，只是国内多数情况下不可达，
     * 作为"某些网络反而只通它"的兜底）。
     */
    private val HOSTS = listOf(
        "https://hongguo.kdns.fr",
        "https://hongguotv.moyushen.workers.dev"
    )

    private fun updateUrl(host: String): String = host.trimEnd('/') + "/update.json"

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
     * 依次尝试 [HOSTS]，任一域名成功返回即采用；全部失败才报错。
     * @param currentVersionCode 本机安装的 versionCode
     */
    fun check(currentVersionCode: Int): CheckResult {
        var lastErr = "检查失败"
        for (host in HOSTS) {
            if (host.isBlank() || !host.startsWith("http")) continue
            try {
                val req = Request.Builder().url(updateUrl(host))
                    .header("Cache-Control", "no-cache") // 确保拿到最新 update.json 而非缓存
                    .build()
                val resp = HttpClientProvider.get().newCall(req).execute()
                try {
                    if (!resp.isSuccessful) {
                        lastErr = "检查失败：HTTP ${resp.code()}"
                        continue // 换下一个域名
                    }
                    val text = resp.body()?.string() ?: ""
                    val remote = parse(text, host)
                        ?: return CheckResult(false, null, "update.json 格式错误")
                    return if (remote.versionCode > currentVersionCode) {
                        CheckResult(true, remote, "发现新版本 v${remote.versionName}")
                    } else {
                        CheckResult(false, remote, "已是最新版本 v${remote.versionName}")
                    }
                } finally {
                    resp.close()
                }
            } catch (e: Exception) {
                // 网络被重置/SSL 中断等：记录后尝试下一个候选域名
                lastErr = "检查失败：" + (e.message ?: e.javaClass.simpleName)
            }
        }
        return CheckResult(false, null, lastErr)
    }

    private fun parse(text: String, host: String): Remote? = try {
        val o = JSONObject(text)
        val apk = o.optString("apk", "")
        if (apk.isEmpty()) null
        else Remote(
            versionCode = o.optInt("versionCode", 0),
            versionName = o.optString("versionName", "?"),
            // apk 用"命中成功的那个 host"拼绝对地址，避免主域名取到清单却从备用域名下载
            apkUrl = if (apk.startsWith("http")) apk else host.trimEnd('/') + "/" + apk.trimStart('/'),
            notes = o.optString("notes", "")
        )
    } catch (e: Exception) {
        null
    }

    /**
     * 同步下载 APK 到应用缓存目录（网络操作，调用方需切 IO 线程）。
     * 候选地址 = 清单命中的 URL + 各 HOSTS 拼出的同名文件，逐个尝试，
     * 抗"检查通了但下载域名被 reset"的情况。
     * @return 下载好的文件；失败返回 null 并写 [error]。
     */
    fun download(context: Context, remote: Remote, error: StringBuilder): File? {
        val dir = File(context.cacheDir, "update").apply { mkdirs() }
        // 先写临时文件，完成后改名，避免中断留下半截文件被误装
        val tmp = File(dir, "update.apk.part")
        val target = File(dir, "hongguo-${remote.versionName}.apk")
        val name = remote.apkUrl.substringAfterLast('/')
        val candidates = LinkedHashSet<String>().apply {
            add(remote.apkUrl)
            for (h in HOSTS) add(h.trimEnd('/') + "/" + name)
        }
        for (url in candidates) {
            error.setLength(0)
            if (downloadOne(url, tmp, target, error)) return target
        }
        if (error.length == 0) error.append("下载失败")
        return null
    }

    /** 下载单个 URL 到 tmp 并改名为 target。成功返回 true，否则写 [error] 返回 false。 */
    private fun downloadOne(url: String, tmp: File, target: File, error: StringBuilder): Boolean {
        return try {
            tmp.delete()
            target.delete()
            val req = Request.Builder().url(url).build()
            val resp = HttpClientProvider.get().newCall(req).execute()
            try {
                if (!resp.isSuccessful) {
                    error.append("下载失败：HTTP ${resp.code()}")
                    return false
                }
                val body = resp.body() ?: run {
                    error.append("下载失败：响应为空")
                    return false
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
                    return false
                }
                if (!tmp.renameTo(target)) {
                    // rename 失败（跨设备等）：直接复制
                    tmp.copyTo(target, overwrite = true)
                    tmp.delete()
                }
                true
            } finally {
                resp.close()
            }
        } catch (e: Exception) {
            error.append("下载异常：" + (e.message ?: e.javaClass.simpleName))
            false
        }
    }

    /**
     * 唤起系统安装器安装已下载的 APK。
     * @return true 表示成功唤起安装器（不代表用户点了安装）；false 表示无法唤起。
     *
     * 注意：这里**不**检查 canRequestPackageInstalls()——小米等 ROM 上该 API 常年
     * 返回 false（即使「允许安装未知应用」已开），据此拦截会让用户永远装不上。
     * 直接唤起系统安装器，授权页由安装器自行弹出；失败再由调用方给分厂商引导。
     */
    fun install(context: Context, apk: File): Boolean {
        val uri: Uri = try {
            FileProvider.getUriForFile(
                context, context.packageName + ".fileprovider", apk
            )
        } catch (e: Exception) {
            android.widget.Toast.makeText(
                context, "安装包路径异常：${e.message ?: ""}", android.widget.Toast.LENGTH_LONG
            ).show()
            return false
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            setDataAndType(uri, "application/vnd.android.package-archive")
        }
        return try {
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            // 部分盒子无系统安装器，或 ROM 直接拦截；调用方据此给引导
            android.widget.Toast.makeText(
                context, "无法启动安装器，请手动安装：$apk", android.widget.Toast.LENGTH_LONG
            ).show()
            false
        }
    }
}
