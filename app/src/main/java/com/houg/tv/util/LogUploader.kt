package com.houg.tv.util

import android.content.Context
import com.houg.tv.net.HttpClientProvider
import okhttp3.MediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 日志远程上报：把全量日志 + 设备信息打包，POST 到飞书群机器人 webhook。
 *
 * 用户诉求：电视上分享/传文件太麻烦，希望「后台直接上传到我这」。
 *
 * 三件套（全量、持久、分片）：
 * 1. **全量**：日志来自 [AppLog.dumpFile]（文件持久化，跨启动、含轮转历史），不做单条截断；
 * 2. **持久**：`AppLog.attach()` 已把每行日志实时落盘，App 重启/崩溃后日志仍在；
 * 3. **分片**：飞书 markdown 卡片正文有长度上限，超长时按行切分成多片连续发送，
 *    标题带 `(i/N)` 序号，接收端按序拼接即得完整日志。
 *
 * 协议：飞书自定义机器人 incoming webhook（POST JSON，application/json）。
 */
object LogUploader {

    /** 飞书群机器人 webhook（用户建好机器人后填这里）。空 = 未配置，点击时提示。 */
    const val LOG_WEBHOOK_URL =
        "https://open.feishu.cn/open-apis/bot/v2/hook/7a8c2ca0-be89-418e-9407-0548ca423792"

    /** 机器人安全设置「自定义关键词」——务必保证该词出现在消息中，否则被飞书拦截。 */
    private const val KEYWORD = "日志"

    /**
     * 单张卡片正文字节上限。飞书实测上限为整卡 100KB，报错原文
     * `card content size is over limit, the max size is 100kb`；
     * 取 48KB 留足转义膨胀（`"`→`\"`）与 card 结构开销的余量。
     */
    private const val CHUNK_BYTES = 48 * 1024

    /** 分片数量上限，防止日志异常膨胀时刷屏（20 片 ≈ 960KB）。 */
    private const val MAX_CHUNKS = 20

    /** 分片之间间隔（ms），规避机器人 5 次/秒的限流。 */
    private const val SEND_INTERVAL_MS = 220L

    private val JSON = MediaType.parse("application/json; charset=utf-8")

    /** 上报结果。 */
    data class Result(val ok: Boolean, val detail: String)

    /**
     * 同步上报全量日志（网络耗时操作，调用方需先切 IO 线程）。
     * @return [Result.ok]=true 表示全部发出；失败时 [Result.detail] 含已发数量与具体原因。
     */
    fun upload(context: Context): Result {
        val url = LOG_WEBHOOK_URL.trim()
        if (url.isEmpty()) {
            return Result(false, "未配置 LOG_WEBHOOK_URL（见 LogUploader 顶部）")
        }
        if (!url.startsWith("https://open.feishu.cn/open-apis/bot/v2/hook/")) {
            return Result(false, "URL 需为飞书机器人地址（https://open.feishu.cn/open-apis/bot/v2/hook/...）")
        }

        val report = buildReport(context)
        val chunks = splitChunks(report)
        val total = chunks.size
        val model = android.os.Build.MODEL.replace("\"", "'").replace("\n", " ")
        val time = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date())

        for (i in chunks.indices) {
            val payload = buildPayload(chunks[i], model, time, i + 1, total)
            val error = post(url, payload)
            if (error != null) {
                return if (i == 0) Result(false, error)
                else Result(false, "第 ${i + 1}/$total 片失败（前 $i 片已送达）：$error")
            }
            if (i < chunks.size - 1) {
                try {
                    Thread.sleep(SEND_INTERVAL_MS)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }
        return Result(true, if (total > 1) "共 $total 片，${report.length} 字符" else "${report.length} 字符")
    }

    /** 发一张卡片；返回 null 表示成功，否则为失败原因。 */
    private fun post(url: String, payload: String): String? {
        return try {
            val req = Request.Builder().url(url)
                .header("Content-Type", "application/json; charset=utf-8")
                .post(RequestBody.create(JSON, payload))
                .build()
            var result: String? = null
            val resp: Response = HttpClientProvider.get().newCall(req).execute()
            try {
                val text = if (resp.body() != null) resp.body()!!.string() else ""
                if (!resp.isSuccessful) {
                    result = "HTTP " + resp.code()
                } else if (!isFeishuOk(text)) {
                    // 业务码非 0 视为失败（如关键词不匹配返回 code 190021）
                    result = "飞书返回异常: " + text.take(200)
                }
            } finally {
                resp.close()
            }
            result
        } catch (e: Exception) {
            "网络异常: " + (e.message ?: e.javaClass.simpleName)
        }
    }

    /** 飞书成功响应形如 {"code":0,...} 或 {"StatusCode":0,...}；容忍空格。 */
    private fun isFeishuOk(text: String): Boolean {
        if (text.isEmpty()) return false
        val compact = text.replace(" ", "")
        return compact.contains("\"code\":0") || compact.contains("\"StatusCode\":0")
    }

    /** 组装上报内容：设备信息 + 全量日志 + 接口信息（与 LogActivity 展示格式一致）。 */
    private fun buildReport(context: Context): String {
        val logBody = AppLog.dumpFile().ifEmpty { "（暂无日志）" }
        return "===== 设备信息 =====\n" +
                DeviceInfo.describe(context) + "\n\n" +
                "===== 日志 =====\n" + logBody + "\n\n" +
                "===== 接口信息 =====\n" +
                "Base URL: " + AppLog.baseUrl.ifEmpty { "-" } + "\n" +
                "device_id: " + AppLog.deviceId.ifEmpty { "-" } + "\n" +
                "iid: " + AppLog.iid.ifEmpty { "-" } + "\n" +
                "X-Gorgon: " + AppLog.lastGorgon.ifEmpty { "-" }
    }

    /**
     * 按行切分为不超过 [CHUNK_BYTES] 字节的分片；超长单行按字节硬切。
     * 逐行扫描并保留换行符，保证分片拼回与原文字节完全一致（不吞不加）。
     * 超过 [MAX_CHUNKS] 片时截断并追加提示（宁丢尾部也不刷屏）。
     */
    private fun splitChunks(report: String): List<String> {
        if (byteLen(report) <= CHUNK_BYTES) return listOf(report)
        val chunks = ArrayList<String>()
        val sb = StringBuilder()
        var sbBytes = 0
        var i = 0
        while (i < report.length) {
            // 取出一整行（含其换行符；末行可能无换行）
            val nl = report.indexOf('\n', i)
            val end = if (nl < 0) report.length else nl + 1
            val line = report.substring(i, end)
            i = end
            val lineBytes = byteLen(line)
            if (lineBytes > CHUNK_BYTES) {
                // 超长单行：先落盘已缓存内容，再按字节硬切成整片
                if (sb.isNotEmpty()) {
                    chunks.add(sb.toString())
                    sb.setLength(0)
                    sbBytes = 0
                }
                chunks.addAll(hardSplitByBytes(line))
                continue
            }
            if (sbBytes + lineBytes > CHUNK_BYTES) {
                chunks.add(sb.toString())
                sb.setLength(0)
                sbBytes = 0
            }
            sb.append(line)
            sbBytes += lineBytes
        }
        if (sb.isNotEmpty()) chunks.add(sb.toString())

        if (chunks.size > MAX_CHUNKS) {
            val kept = ArrayList(chunks.subList(0, MAX_CHUNKS))
            kept[MAX_CHUNKS - 1] = kept[MAX_CHUNKS - 1] +
                    "\n……（日志过长，已截断至前 $MAX_CHUNKS 片，共 ${chunks.size} 片）……"
            return kept
        }
        return chunks
    }

    /** 把一段长文本按 UTF-8 字节上限硬切成多片（不在多字节字符中间切断）。 */
    private fun hardSplitByBytes(text: String): List<String> {
        val out = ArrayList<String>()
        var start = 0
        var bytes = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val w = utf8Width(cp)
            if (bytes + w > CHUNK_BYTES) {
                out.add(text.substring(start, i))
                start = i
                bytes = 0
            }
            bytes += w
            i += Character.charCount(cp)
        }
        if (start < text.length) out.add(text.substring(start))
        return out
    }

    /** 单个码点的 UTF-8 字节宽度。 */
    private fun utf8Width(cp: Int): Int = when {
        cp < 0x80 -> 1
        cp < 0x800 -> 2
        cp < 0x10000 -> 3
        else -> 4
    }

    private fun byteLen(s: String): Int = s.toByteArray(Charsets.UTF_8).size

    /** 组装飞书 markdown 卡片 payload；多片时标题带 (i/N)。 */
    private fun buildPayload(chunk: String, model: String, time: String, index: Int, total: Int): String {
        // 标题里带 [KEYWORD]，满足机器人「自定义关键词」安全校验
        val seq = if (total > 1) " ($index/$total)" else ""
        val title = "[$KEYWORD] HougTime 上报 · $model · $time$seq"

        val sb = StringBuilder()
        sb.append('{')
        sb.append("\"msg_type\":\"interactive\",")
        sb.append("\"card\":{")
        sb.append("\"header\":{\"title\":{\"tag\":\"plain_text\",\"content\":\"")
        sb.append(escapeJson(title))
        sb.append("\"},\"template\":\"blue\"},")
        sb.append("\"elements\":[{\"tag\":\"markdown\",\"content\":\"")
        sb.append(escapeJson(chunk))
        sb.append("\"}]}}")
        return sb.toString()
    }

    /** JSON 字符串转义：\" \\ \n \r \t 与控制字符。 */
    private fun escapeJson(s: String): String {
        val sb = StringBuilder(s.length + 16)
        for (element in s) {
            when (element) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (element < ' ') {
                    sb.append(String.format("\\u%04x", element.code))
                } else {
                    sb.append(element)
                }
            }
        }
        return sb.toString()
    }
}
