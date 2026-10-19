package com.houg.tv.net

import android.os.Handler
import android.os.Looper
import com.houg.tv.util.AppLog
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 局域网跨屏输入服务（TV 端）。
 *
 * 电视/盒子上用遥控器打字极其痛苦，系统输入法在很多机型上根本唤不出来。
 * 这里在 TV 上起一个极简 HTTP 服务，手机连同一 Wi-Fi 打开提示的网址，
 * 在网页输入框里打字 → 提交后回传给 TV 直接搜索。
 *
 * 只用 `ServerSocket` 手写最小 HTTP，不引入任何依赖（老设备/minSdk 21 友好）。
 * 安全：仅监听本机、随机端口；页面与接口都不落盘、不做持久化，服务随搜索页销毁而关闭。
 */
class LanInputServer(
    /** 收到关键词时的回调（已 trim，非空） */
    private val onKeyword: (String) -> Unit,
) {

    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var thread: Thread? = null
    private val main = Handler(Looper.getMainLooper())

    /** 本机局域网 IPv4（取第一个非回环地址），失败返回 null。 */
    fun localAddress(): String? {
        return try {
            for (nic in Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nic.isUp || nic.isLoopback) continue
                for (addr in Collections.list(nic.inetAddresses)) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val host = addr.hostAddress ?: continue
                        if (host.startsWith("169.254.")) continue // 无 DHCP 的自动地址，连不通
                        return host
                    }
                }
            }
            null
        } catch (e: Throwable) {
            AppLog.e("LanInputServer", "取本机地址失败: " + (e.message ?: ""), e)
            null
        }
    }

    /** 启动服务，返回监听端口；失败返回 0。 */
    fun start(): Int {
        if (!running.compareAndSet(false, true)) return port
        return try {
            val socket = ServerSocket(0)
            serverSocket = socket
            thread = Thread({ serveLoop(socket) }, "lan-input").apply {
                isDaemon = true
                start()
            }
            AppLog.d("LanInputServer", "局域网输入已启动，端口=" + socket.localPort)
            socket.localPort
        } catch (e: Throwable) {
            running.set(false)
            AppLog.e("LanInputServer", "启动失败: " + (e.message ?: ""), e)
            0
        }
    }

    val port: Int get() = serverSocket?.localPort ?: 0

    fun stop() {
        running.set(false)
        try { serverSocket?.close() } catch (_: Throwable) {}
        serverSocket = null
        thread = null
        AppLog.d("LanInputServer", "局域网输入已停止")
    }

    private fun serveLoop(socket: ServerSocket) {
        while (running.get() && !socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (e: Throwable) {
                break // close() 时 accept 抛异常，正常退出
            }
            try {
                handleClient(client)
            } catch (e: Throwable) {
                AppLog.e("LanInputServer", "处理请求失败: " + (e.message ?: ""), e)
            } finally {
                try { client.close() } catch (_: Throwable) {}
            }
        }
    }

    private fun handleClient(client: Socket) {
        client.soTimeout = 5000
        val reader = BufferedReader(InputStreamReader(client.getInputStream(), "UTF-8"))
        val requestLine = reader.readLine() ?: return
        val parts = requestLine.split(" ")
        if (parts.size < 2) return
        val rawPath = parts[1]

        // 读掉剩余请求头
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
        }

        val path = rawPath.substringBefore('?')
        val query = rawPath.substringAfter('?', "")
        val out = BufferedOutputStream(client.getOutputStream())

        if (path == "/submit") {
            val kw = param(query, "kw")
            if (kw.isNotEmpty()) {
                main.post { onKeyword(kw) }
            }
            reply(out, 200, "text/plain; charset=utf-8", "ok")
            return
        }

        reply(out, 200, "text/html; charset=utf-8", PAGE_HTML)
    }

    private fun param(query: String, key: String): String {
        if (query.isEmpty()) return ""
        for (pair in query.split('&')) {
            val eq = pair.indexOf('=')
            if (eq <= 0) continue
            if (pair.substring(0, eq) == key) {
                return try {
                    URLDecoder.decode(pair.substring(eq + 1), "UTF-8").trim()
                } catch (e: Throwable) {
                    ""
                }
            }
        }
        return ""
    }

    private fun reply(out: OutputStream, code: Int, contentType: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val header = StringBuilder()
            .append("HTTP/1.1 ").append(code).append(" OK\r\n")
            .append("Content-Type: ").append(contentType).append("\r\n")
            .append("Content-Length: ").append(bytes.size).append("\r\n")
            .append("Cache-Control: no-store\r\n")
            .append("Connection: close\r\n")
            .append("\r\n")
            .toString()
        out.write(header.toByteArray(Charsets.US_ASCII))
        out.write(bytes)
        out.flush()
    }

    private companion object {
        /** 手机端页面：一个输入框 + 提交按钮，提交后回显并清空，可反复输入。 */
        const val PAGE_HTML = """<!DOCTYPE html>
<html lang="zh-CN"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
<title>红果TV 远程输入</title>
<style>
 body{margin:0;font-family:-apple-system,"PingFang SC","Microsoft YaHei",sans-serif;
      background:#141414;color:#eee;padding:22px;box-sizing:border-box}
 h1{font-size:19px;margin:0 0 6px}
 p{color:#9a9a9a;font-size:13px;margin:0 0 20px;line-height:1.5}
 input{width:100%;box-sizing:border-box;font-size:19px;padding:15px 14px;border-radius:10px;
       border:1px solid #3a3a3a;background:#222;color:#fff;outline:none}
 button{width:100%;box-sizing:border-box;margin-top:14px;font-size:17px;padding:15px;
        border:0;border-radius:10px;background:#e53935;color:#fff;font-weight:600}
 button:active{background:#c62828}
 #tip{margin-top:16px;font-size:14px;color:#5ac85a;min-height:20px;text-align:center}
</style></head><body>
<h1>红果TV 远程输入</h1>
<p>在下面输入要搜索的剧名，点「发送到电视」。电视上会立即开始搜索。</p>
<input id="kw" type="search" placeholder="输入剧名…" autocomplete="off" autofocus>
<button onclick="send()">发送到电视</button>
<div id="tip"></div>
<script>
 function send(){
   var v=document.getElementById('kw').value.trim();
   if(!v){document.getElementById('tip').textContent='请先输入剧名';return;}
   fetch('/submit?kw='+encodeURIComponent(v)).then(function(){
     document.getElementById('tip').textContent='已发送：'+v;
     document.getElementById('kw').value='';
     document.getElementById('kw').focus();
   }).catch(function(){
     document.getElementById('tip').textContent='发送失败，请确认与电视在同一 Wi-Fi';
   });
 }
 document.getElementById('kw').addEventListener('keydown',function(e){
   if(e.key==='Enter'){e.preventDefault();send();}
 });
</script></body></html>"""
    }
}
