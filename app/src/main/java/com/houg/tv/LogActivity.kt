package com.houg.tv

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.houg.tv.util.AppLog
import com.houg.tv.util.DeviceInfo
import com.houg.tv.util.LogUploader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 调试日志界面：展示设备配置 + AppLog 缓冲 + 接口信息快照 */
class LogActivity : AppCompatActivity() {

    private lateinit var logText: TextView
    private lateinit var infoText: TextView
    private lateinit var deviceInfoText: TextView
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_log)

        logText = findViewById(R.id.log_text)
        infoText = findViewById(R.id.info_text)
        deviceInfoText = findViewById(R.id.device_info_text)

        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<View>(R.id.btn_clear).setOnClickListener {
            AppLog.clear()
            logText.text = getString(R.string.log_empty)
        }
        findViewById<View>(R.id.btn_copy).setOnClickListener {
            copyToClipboard(buildLogReport())
            Toast.makeText(this, "日志已复制，请粘贴发送给开发者", Toast.LENGTH_LONG).show()
        }
        findViewById<View>(R.id.btn_share).setOnClickListener { shareLog() }
        findViewById<View>(R.id.btn_upload).setOnClickListener { uploadLog() }

        refresh()
    }

    /**
     * 一键上传全量日志到开发者（飞书群机器人）。电视上分享/传文件很麻烦，
     * 这个入口让用户按一下即可把日志送到开发者手机，无需任何操作。
     *
     * 限流：同一设备 15 分钟内只允许上传一次（本地即时拦截 + 服务端兜底）。
     */
    private fun uploadLog() {
        val btn = findViewById<TextView>(R.id.btn_upload)
        btn.isEnabled = false
        btn.text = "上传中…"
        Toast.makeText(this, "正在上传全量日志…", Toast.LENGTH_SHORT).show()
        scope.launch {
            val result = withContext(Dispatchers.IO) { LogUploader.upload(this@LogActivity) }
            btn.isEnabled = true
            btn.text = getString(R.string.log_upload)
            val msg = when {
                result.ok -> "日志已上传（${result.detail}），开发者将收到"
                result.rateLimited -> result.detail   // 限流提示本身就是完整说明，不加"上传失败"前缀
                else -> "上传失败：${result.detail}"
            }
            Toast.makeText(this@LogActivity, msg, Toast.LENGTH_LONG).show()
        }
    }

    private fun refresh() {
        // 设备配置信息（实时读取，始终显示）
        deviceInfoText.text = DeviceInfo.describe(this)
        logText.text = AppLog.dump().ifEmpty { getString(R.string.log_empty) }
        infoText.text = interfaceInfo()
    }

    private fun interfaceInfo(): String = getString(
        R.string.log_interface_info,
        AppLog.baseUrl.ifEmpty { "-" },
        AppLog.deviceId.ifEmpty { "-" },
        AppLog.iid.ifEmpty { "-" },
        AppLog.lastGorgon.ifEmpty { "-" }
    )

    /** 组装完整日志报告：设备信息 + 全量日志（文件持久化）+ 接口信息 */
    private fun buildLogReport(): String {
        val logBody = AppLog.dumpFile().ifEmpty { getString(R.string.log_empty) }
        return "===== 设备信息 =====\n" +
                DeviceInfo.describe(this) + "\n\n" +
                "===== 日志 =====\n" + logBody + "\n\n" +
                "===== 接口信息 =====\n" + interfaceInfo()
    }

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("日志", text))
    }

    private fun shareLog() {
        val report = buildLogReport()
        // ACTION_SEND 的 EXTRA_TEXT 走 Binder，超长（约 5000 字符以上）会被系统静默截断，
        // 超限时落盘为临时文件用流式附件分享，保证日志完整。
        if (report.length > 4000) {
            shareAsFile(report)
            return
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, report)
        }
        try {
            startActivity(Intent.createChooser(intent, "分享日志"))
        } catch (e: ActivityNotFoundException) {
            copyToClipboard(report)
            Toast.makeText(this, "未找到可分享的应用，日志已复制到剪贴板", Toast.LENGTH_LONG).show()
        }
    }

    /** 超长日志：写临时文件 + FileProvider 附件分享。 */
    private fun shareAsFile(report: String) {
        try {
            val dir = File(cacheDir, "logs").apply { mkdirs() }
            val file = File(dir, "hougtime-log.txt")
            file.writeText(report)
            val uri = FileProvider.getUriForFile(
                this, packageName + ".fileprovider", file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "分享日志文件"))
        } catch (e: Exception) {
            // 文件分享失败降级：直接复制到剪贴板（无长度限制）
            copyToClipboard(report)
            Toast.makeText(this, "日志较长已转为文件失败，已复制到剪贴板，请粘贴发送", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
