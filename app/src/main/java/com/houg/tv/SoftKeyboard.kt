package com.houg.tv

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 内置软键盘（TV 用）：不依赖系统 IME，供部分无法唤起键盘的电视型号使用。
 * 布局：字母 26 键（QWERTY 三行）+ 数字一行 + 功能键一行（删除/清空/空格/搜索）。
 * 按键用 LinearLayout 等权分布（weight=1），文字明确白色 + 深灰圆角底，焦点态高亮边框。
 * 通过 setOnKey / setOnCmd 把输入回传给宿主（SearchActivity）。
 */
class SoftKeyboard @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    enum class Cmd { BACKSPACE, CLEAR, SPACE, SEARCH }

    private var onKey: ((Char) -> Unit)? = null
    private var onCmd: ((Cmd) -> Unit)? = null

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.parseColor("#101010"))
        setPadding(dp(8), dp(8), dp(8), dp(8))
        build()
    }

    fun setOnKey(listener: (Char) -> Unit) { this.onKey = listener }
    fun setOnCmd(listener: (Cmd) -> Unit) { this.onCmd = listener }

    private fun build() {
        // 字母三行（QWERTY 标准排列）
        addView(buildRow("QWERTYUIOP") { c -> onKey?.invoke(c) })
        addView(buildRow("ASDFGHJKL") { c -> onKey?.invoke(c) })
        addView(buildRow("ZXCVBNM") { c -> onKey?.invoke(c) })
        // 数字一行
        addView(buildRow("1234567890") { c -> onKey?.invoke(c) })
        // 功能键一行
        addView(buildFuncRow())
    }

    private fun buildRow(chars: String, onClick: (Char) -> Unit): LinearLayout {
        val row = LinearLayout(context)
        row.orientation = HORIZONTAL
        row.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        for (c in chars) {
            row.addView(buildKey(c.toString(), weight = 1f) { onClick(c) })
        }
        return row
    }

    private fun buildFuncRow(): LinearLayout {
        val row = LinearLayout(context)
        row.orientation = HORIZONTAL
        row.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        row.addView(buildKey("⌫", weight = 1f) { onCmd?.invoke(Cmd.BACKSPACE) })
        row.addView(buildKey("清空", weight = 1f) { onCmd?.invoke(Cmd.CLEAR) })
        row.addView(buildKey("空格", weight = 1f) { onCmd?.invoke(Cmd.SPACE) })
        row.addView(buildKey("搜索", weight = 2f) { onCmd?.invoke(Cmd.SEARCH) })
        return row
    }

    private fun buildKey(text: String, weight: Float, onClick: () -> Unit): TextView {
        val tv = TextView(context)
        tv.text = text
        tv.gravity = Gravity.CENTER
        tv.textSize = 17f
        tv.setTextColor(Color.WHITE)
        tv.isClickable = true
        tv.isFocusable = true
        tv.setPadding(dp(4), dp(13), dp(4), dp(13))

        // 明确背景：深灰圆角 + 焦点态高亮边框（避免 selector 覆盖文字）
        tv.background = makeKeyBg()
        tv.setOnClickListener { onClick() }
        tv.setOnFocusChangeListener { _, hasFocus ->
            tv.background = makeKeyBg(hasFocus)
        }

        val lp = LayoutParams(0, LayoutParams.WRAP_CONTENT, weight)
        lp.setMargins(dp(3), dp(3), dp(3), dp(3))
        tv.layoutParams = lp
        return tv
    }

    private fun makeKeyBg(focused: Boolean = false): GradientDrawable {
        val d = GradientDrawable()
        d.cornerRadius = dp(4).toFloat()
        d.setColor(if (focused) Color.parseColor("#E53935") else Color.parseColor("#2A2A2A"))
        if (focused) {
            d.setStroke(dp(2), Color.WHITE)
        }
        return d
    }

    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()
}
