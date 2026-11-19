package com.yanyan.assistant

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/**
 * M6 所有界面的基类：主题生效 + 一套「按主题色现场搭控件」的小工具。
 *
 * 为什么控件用代码搭：设置页有八个二级页、几十个条目，写 XML 会散成一堆布局文件，
 * 还要每处都写一遍颜色；这里统一从主题属性取色，深/浅两套自动跟着变。
 *
 * 尺寸规矩（M6 文档）：控件用 dp；关键可点区不小于 48dp；字号跟系统缩放走（sp）。
 */
open class BaseActivity : Activity() {

    /** 当前这套主题是不是深色（onResume 发现变了就 recreate，实现「立即生效」）。 */
    private var appliedDark = false

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        appliedDark = ThemeManager.isDark(this)
        super.onCreate(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        val now = ThemeManager.isDark(this)
        if (now != appliedDark) {
            appliedDark = now
            recreate()
        }
    }

    // ------------------------------------------------------------ 基础工具

    protected fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    protected fun sp(v: Float): Float = v

    /** 主题色：R.attr.yyXxx */
    protected fun c(attr: Int): Int = ThemeManager.color(this, attr)

    protected fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    protected fun rounded(color: Int, radiusDp: Int = 12): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
        }

    protected fun outlined(fill: Int, stroke: Int, radiusDp: Int = 12, strokeDp: Int = 1): GradientDrawable =
        rounded(fill, radiusDp).apply { setStroke(dp(strokeDp), stroke) }

    /** 顶栏右侧动作图标（48dp 可点区，图标 24dp）。 */
    protected fun addTopBarAction(iconRes: Int, desc: String, onClick: () -> Unit): ImageButton {
        val box = findViewById<LinearLayout>(R.id.ll_topbar_actions)
        val btn = ImageButton(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            setPadding(dp(12), dp(12), dp(12), dp(12))
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageResource(iconRes)
            imageTintList = ColorStateList.valueOf(c(R.attr.yyIcon))
            background = resolveSelectableItemBackground()
            contentDescription = desc
            setOnClickListener { onClick() }
        }
        box.addView(btn)
        return btn
    }

    private fun resolveSelectableItemBackground(): android.graphics.drawable.Drawable? {
        val tv = TypedValue()
        if (!theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)) return null
        return if (tv.resourceId != 0) resources.getDrawable(tv.resourceId, theme) else null
    }

    /** 无边界水波纹（图标按钮用），取系统主题里的那个。 */
    protected fun borderless(): android.graphics.drawable.Drawable? = resolveSelectableItemBackground()

    /** 顶栏标题 + 返回键（子页都调这个）。 */
    protected fun setupTopBar(title: String, showBack: Boolean = true) {
        findViewById<TextView>(R.id.tv_topbar_title).text = title
        val back = findViewById<ImageButton>(R.id.btn_topbar_back)
        if (showBack) {
            back.visibility = View.VISIBLE
            back.imageTintList = ColorStateList.valueOf(c(R.attr.yyIcon))
            back.setOnClickListener { finish() }
        } else {
            back.visibility = View.GONE
        }
    }

    /** 顶栏右侧放一小段状态文字（聊天页的状态点用）。 */
    protected fun addTopBarText(text: String, colorAttr: Int, sizeSp: Float = 13f): TextView {
        val box = findViewById<LinearLayout>(R.id.ll_topbar_actions)
        val tv = TextView(this).apply {
            this.text = text
            setTextColor(c(colorAttr))
            textSize = sizeSp
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(4), 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)
            )
        }
        box.addView(tv)
        return tv
    }

    // ------------------------------------------------------------ 卡片 / 行

    /** 一张圆角卡片：surface 底色，16dp 圆角，顶上留 12dp。 */
    protected fun newCard(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(14))
        background = rounded(c(R.attr.yySurface), 16)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12) }
    }

    protected fun cardTitle(card: LinearLayout, text: String) {
        card.addView(
            TextView(this).apply {
                this.text = text
                setTextColor(c(R.attr.yyText))
                textSize = 16f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
    }

    protected fun cardHint(card: LinearLayout, text: String, colorAttr: Int = R.attr.yyTextSecondary) {
        card.addView(
            TextView(this).apply {
                this.text = text
                setTextColor(c(colorAttr))
                textSize = 12f
                setLineSpacing(dp(2).toFloat(), 1f)
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        )
    }

    /** 卡片里的一段正文（可长可短，自动换行）。 */
    protected fun bodyText(
        card: LinearLayout,
        text: String,
        sizeSp: Float = 14f,
        colorAttr: Int = R.attr.yyText
    ): TextView {
        val tv = TextView(this).apply {
            this.text = text
            textSize = sizeSp
            setTextColor(c(colorAttr))
            setLineSpacing(dp(3).toFloat(), 1f)
        }
        card.addView(tv, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6) })
        return tv
    }

    /** 一句强调提示（固定可见，比如悬浮窗的那句降级说明）。 */
    protected fun warnBox(card: LinearLayout, text: String) {
        val tv = TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(c(R.attr.yyWarn))
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = outlined(c(R.attr.yySurfaceAlt), c(R.attr.yyWarn), 12)
        }
        card.addView(tv, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) })
    }

    protected fun cardDivider(card: LinearLayout) {
        card.addView(
            View(this).apply { setBackgroundColor(c(R.attr.yyDivider)) },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)
            ).apply { topMargin = dp(10) }
        )
    }

    /** 开关行：文字 + 开关，整行 ≥48dp。 */
    protected fun switchRow(
        card: LinearLayout,
        title: String,
        checked: Boolean,
        subtitle: String? = null,
        onChange: (Boolean) -> Unit
    ): Switch {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            minimumHeight = dp(48)
            setPadding(0, dp(6), 0, dp(6))
        }
        val sw = Switch(this).apply {
            text = title
            textSize = 15f
            setTextColor(c(R.attr.yyText))
            minimumHeight = dp(48)
            isChecked = checked
        }
        row.addView(sw, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        if (subtitle != null) {
            row.addView(
                TextView(this).apply {
                    text = subtitle
                    textSize = 12f
                    setTextColor(c(R.attr.yyTextSecondary))
                }, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(2) }
            )
        }
        sw.setOnCheckedChangeListener { _, v -> onChange(v) }
        card.addView(row, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(4) })
        return sw
    }

    /** 一行「标题 + 右侧值 + ›」，整行可点、≥48dp。 */
    protected fun actionRow(
        card: LinearLayout,
        title: String,
        value: String?,
        onClick: () -> Unit
    ): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48)
            isClickable = true
            isFocusable = true
            setPadding(0, dp(6), 0, dp(6))
            background = resolveSelectableItemBackground()
            setOnClickListener { onClick() }
        }
        row.addView(
            TextView(this).apply {
                text = title
                textSize = 15f
                setTextColor(c(R.attr.yyText))
            }, LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            )
        )
        if (!value.isNullOrEmpty()) {
            row.addView(
                TextView(this).apply {
                    text = value
                    textSize = 13f
                    setTextColor(c(R.attr.yyTextSecondary))
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    gravity = Gravity.END
                }, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        row.addView(
            TextView(this).apply {
                text = "›"
                textSize = 18f
                setTextColor(c(R.attr.yyTextSecondary))
                setPadding(dp(8), 0, 0, 0)
            }
        )
        card.addView(row, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        return row
    }

    /** 整行按钮（次操作）：48dp 高、描边样式。 */
    protected fun outlineButton(
        card: LinearLayout,
        text: String,
        onClick: () -> Unit
    ): Button {
        val btn = Button(this).apply {
            this.text = text
            textSize = 14f
            minimumHeight = dp(48)
            isAllCaps = false
            setTextColor(c(R.attr.yyAccent))
            background = outlined(c(R.attr.yySurface), c(R.attr.yyAccent), 12)
            setOnClickListener { onClick() }
        }
        card.addView(btn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })
        return btn
    }

    /** 单选组（音色、保留期、外观都用它）。 */
    protected fun radioOptions(
        card: LinearLayout,
        options: List<String>,
        selectedIndex: Int,
        onSelect: (Int) -> Unit
    ): RadioGroup {
        val group = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
        }
        options.forEachIndexed { index, label ->
            val rb = RadioButton(this).apply {
                id = View.generateViewId()
                text = label
                textSize = 14f
                setTextColor(c(R.attr.yyText))
                minimumHeight = dp(48)
                isChecked = index == selectedIndex
                buttonTintList = ColorStateList.valueOf(c(R.attr.yyAccent))
            }
            group.addView(rb, RadioGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        group.setOnCheckedChangeListener { g, checkedId ->
            val index = (0 until g.childCount).firstOrNull { g.getChildAt(it).id == checkedId } ?: -1
            if (index >= 0) onSelect(index)
        }
        card.addView(group, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(4) })
        return group
    }

    /** 输入框行（电脑地址、通知文案）。 */
    protected fun editRow(
        card: LinearLayout,
        title: String,
        initial: String,
        hint: String = "",
        inputType: Int = InputType.TYPE_CLASS_TEXT
    ): EditText {
        card.addView(
            TextView(this).apply {
                text = title
                textSize = 13f
                setTextColor(c(R.attr.yyTextSecondary))
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        )
        val et = EditText(this).apply {
            setText(initial)
            this.hint = hint
            this.inputType = inputType
            textSize = 15f
            setTextColor(c(R.attr.yyText))
            setHintTextColor(c(R.attr.yyTextSecondary))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = outlined(c(R.attr.yySurfaceAlt), c(R.attr.yyDivider), 10)
            minHeight = dp(48)
        }
        card.addView(et, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6) })
        return et
    }
}
