package com.houg.tv.util

import android.content.Context
import android.os.Build
import java.io.File
import java.util.Locale

/**
 * 收集当前设备配置信息（供日志展示）。
 * 兼容 API 14：仅用 Build + 反射 + 文件系统，无 java.time。
 */
object DeviceInfo {

    /** 拼接设备配置摘要（多行）。 */
    fun describe(context: Context): String {
        val dm = context.resources.displayMetrics
        val density = dm.density
        val widthPx = dm.widthPixels
        val heightPx = dm.heightPixels
        val widthDp = (widthPx / density).toInt()
        val heightDp = (heightPx / density).toInt()
        val abis = Build.SUPPORTED_ABIS.joinToString(", ")

        val sb = StringBuilder()
        sb.append("设备型号: ").append(Build.MODEL).append('\n')
        sb.append("品牌厂商: ").append(Build.BRAND).append('/').append(Build.MANUFACTURER).append('\n')
        sb.append("系统版本: Android ").append(Build.VERSION.RELEASE)
            .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
        sb.append("屏幕分辨率: ").append(widthPx).append('x').append(heightPx)
            .append(" px (").append(widthDp).append('x').append(heightDp).append(" dp, density=")
            .append(density).append(")\n")
        sb.append("CPU ABI: ").append(abis).append('\n')
        sb.append("总内存: ").append(totalMemoryMb()).append(" MB")
        return sb.toString()
    }

    /** 总内存（MB），读 /proc/meminfo，失败返回 -1。 */
    private fun totalMemoryMb(): Long {
        return try {
            val br = File("/proc/meminfo").bufferedReader()
            br.useLines { lines ->
                for (line in lines) {
                    if (line.startsWith("MemTotal:")) {
                        val kb = line.trim().split(Regex("\\s+")).getOrNull(1)?.toLongOrNull() ?: -1
                        return@useLines if (kb > 0) kb / 1024 else -1
                    }
                }
                -1
            }
        } catch (e: Exception) {
            -1
        }
    }
}
