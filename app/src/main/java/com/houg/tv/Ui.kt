package com.houg.tv

import android.content.Context

/**
 * 根据屏幕宽度自适应网格列数（QA P3-10：720p 老设备选集/卡片不拥挤）。
 * @param minItemWidthDp 每个格子的最小宽度（dp），列数 = 屏幕宽 / 最小宽，并夹在 [min, max]。
 */
fun adaptiveGridColumns(context: Context, minItemWidthDp: Int, min: Int = 2, max: Int = 12): Int {
    val dm = context.resources.displayMetrics
    val widthDp = (dm.widthPixels / dm.density).toInt()
    if (widthDp <= 0) return min
    return (widthDp / minItemWidthDp).coerceIn(min, max)
}
