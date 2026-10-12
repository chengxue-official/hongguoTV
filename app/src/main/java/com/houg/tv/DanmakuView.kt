package com.houg.tv

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import com.houg.tv.net.NetClient

/**
 * 滚动弹幕 View：从右往左滚动，最多 4 条轨道，8 秒生命周期。
 * 位置由播放时钟驱动（基准位置 + 墙钟经过时间 × 倍速），配合播放器每 500ms 的位置校准。
 */
class DanmakuView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private class Flying(val item: NetClient.DanmakuItem, val lane: Int, val width: Float)

    private val lifetimeMs = 8000L
    private val laneCount = 4

    private val items = ArrayList<NetClient.DanmakuItem>()
    private val flying = ArrayList<Flying>()
    private var pendingIndex = 0

    private var basePositionMs = 0L
    private var baseWallTime = 0L
    private var playing = false
    private var rate = 1f

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var textSizePx = 0f
    private var lineHeightPx = 0f
    private var lastWidth = -1

    init {
        strokePaint.style = Paint.Style.STROKE
        strokePaint.strokeWidth = 3f
        strokePaint.color = 0xFF000000.toInt()
        fillPaint.color = 0xFFFFFFFF.toInt()
        updateTextSize()
    }

    fun setItems(list: List<NetClient.DanmakuItem>) {
        items.clear()
        items.addAll(list.sortedBy { it.timeMs })
        pendingIndex = 0
        flying.clear()
        invalidate()
    }

    /** 累积追加一个窗口的弹幕（按时间归并去重），不覆盖已有窗口数据。 */
    fun addItems(list: List<NetClient.DanmakuItem>) {
        for (it in list) {
            // 按 (timeMs, id) 去重，避免倒退重放后重复
            val dup = items.any { e -> e.timeMs == it.timeMs && e.id == it.id }
            if (!dup) items.add(it)
        }
        items.sortBy { it.timeMs }
        invalidate()
    }

    fun setPositionMs(pos: Long) {
        if (pos < basePositionMs) {
            // 回退（seek 往回）：重置飞行态并重新派生
            flying.clear()
            pendingIndex = 0
        }
        basePositionMs = pos
        baseWallTime = SystemClock.uptimeMillis()
        invalidate()
    }

    fun setPlaying(p: Boolean) {
        if (playing != p) baseWallTime = SystemClock.uptimeMillis()
        playing = p
        invalidate()
    }

    fun setRate(r: Float) {
        if (rate != r) baseWallTime = SystemClock.uptimeMillis()
        rate = if (r > 0f) r else 1f
        invalidate()
    }

    fun reset() {
        items.clear()
        pendingIndex = 0
        flying.clear()
        basePositionMs = 0L
        baseWallTime = SystemClock.uptimeMillis()
        invalidate()
    }

    private fun currentPositionMs(): Long {
        if (!playing) return basePositionMs
        val elapsed = (SystemClock.uptimeMillis() - baseWallTime) * rate
        return basePositionMs + elapsed.toLong()
    }

    /** 弹幕可见时长：随倍速补偿——播放时钟走得快时，弹幕按播放时钟算寿命相应缩短，保持屏幕上停留的真实时间恒定。 */
    private fun visibleLifetimeMs(): Long = (lifetimeMs / rate).toLong().coerceAtLeast(2000L)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != lastWidth) {
            lastWidth = w
            updateTextSize()
            flying.clear()
            pendingIndex = 0
        }
    }

    private fun updateTextSize() {
        val sp = if (lastWidth in 1..599) 16f else 20f
        textSizePx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, resources.displayMetrics)
        lineHeightPx = textSizePx * 1.5f
        fillPaint.textSize = textSizePx
        strokePaint.textSize = textSizePx
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width
        if (w <= 0) {
            postInvalidateOnAnimation()
            return
        }
        val pos = currentPositionMs()
        spawnDue(pos)
        removeFinished(pos)
        val life = visibleLifetimeMs()

        if (flying.isEmpty() && pendingIndex >= items.size) return

        val areaHeight = height * 0.55f
        val fm = fillPaint.fontMetrics
        for (f in flying) {
            val elapsed = pos - f.item.timeMs
            val progress = elapsed.toFloat() / life
            val x = w - (w + f.width) * progress
            val laneTop = lineHeightPx * 0.5f + f.lane * lineHeightPx
            if (laneTop > areaHeight) continue
            val baseY = laneTop + lineHeightPx / 2f + (fm.bottom - fm.top) / 2f - fm.bottom
            canvas.drawText(f.item.text, x, baseY, strokePaint)
            canvas.drawText(f.item.text, x, baseY, fillPaint)
        }
        postInvalidateOnAnimation()
    }

    private fun spawnDue(pos: Long) {
        val life = visibleLifetimeMs()
        while (pendingIndex < items.size) {
            val it = items[pendingIndex]
            if (it.timeMs > pos) break
            pendingIndex++
            if (pos >= it.timeMs + life) continue  // 已过期，跳过
            spawn(it)
        }
    }

    private fun spawn(item: NetClient.DanmakuItem) {
        val busy = BooleanArray(laneCount)
        for (f in flying) busy[f.lane] = true
        var lane = -1
        for (i in 0 until laneCount) if (!busy[i]) { lane = i; break }
        if (lane < 0) {
            var oldest: Flying? = null
            for (f in flying) if (oldest == null || f.item.timeMs < oldest.item.timeMs) oldest = f
            if (oldest != null) {
                lane = oldest.lane
                flying.remove(oldest)
            } else {
                lane = 0
            }
        }
        val width = fillPaint.measureText(item.text)
        flying.add(Flying(item, lane, width))
    }

    private fun removeFinished(pos: Long) {
        val life = visibleLifetimeMs()
        var i = flying.size - 1
        while (i >= 0) {
            if (pos >= flying[i].item.timeMs + life) flying.removeAt(i)
            i--
        }
    }
}
