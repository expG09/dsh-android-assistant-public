package com.yanyan.assistant

import android.app.Activity
import android.app.AlertDialog
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

/**
 * M6：选应用的小对话框（主界面快捷栏换应用、设置页槽位配置、场景映射共用）。
 *
 * 不碰任何功能逻辑：列表来自 [AppIndex]（有 LAUNCHER 入口的现扫结果），
 * 选完由调用方决定写到哪里（[QuickSlots] 或 [LocalStore] 的场景映射）。
 */
object AppPicker {

    /** 快捷栏第 [slotIndex] 格：选一个应用（或清空），选完直接落 [QuickSlots]。 */
    fun showSlot(activity: Activity, slotIndex: Int, onDone: () -> Unit) {
        val current = QuickSlots.load(activity).getOrNull(slotIndex)
        choose(activity, "第 ${slotIndex + 1} 格：选一个应用", current?.label) { app ->
            if (app == null) {
                QuickSlots.setSlot(activity, slotIndex, null)
                toast(activity, activity.getString(R.string.quick_slot_cleared, slotIndex + 1))
            } else {
                QuickSlots.setSlot(activity, slotIndex, app)
                toast(activity, activity.getString(R.string.quick_slot_replaced, slotIndex + 1, app.label))
            }
            onDone()
        }
    }

    /**
     * 通用选应用：能搜索；[currentLabel] 不为空时多一个「清空」按钮（回调 app = null）。
     */
    fun choose(
        activity: Activity,
        title: String,
        currentLabel: String?,
        onPick: (InstalledApp?) -> Unit
    ) {
        val apps = QuickSlots.installedApps(activity)
        if (apps.isEmpty()) {
            toast(activity, activity.getString(R.string.quick_slot_no_apps))
            return
        }

        val cText = ThemeManager.color(activity, R.attr.yyText)
        val cSecondary = ThemeManager.color(activity, R.attr.yyTextSecondary)
        val dp = { v: Int -> (v * activity.resources.displayMetrics.density + 0.5f).toInt() }

        val shown = ArrayList(apps)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(4))
        }
        val search = EditText(activity).apply {
            hint = activity.getString(R.string.quick_slot_search_hint)
            setSingleLine(true)
            textSize = 16f
            setTextColor(cText)
            setHintTextColor(cSecondary)
        }
        val list = ListView(activity)
        val adapter = object : BaseAdapter() {
            override fun getCount(): Int = shown.size
            override fun getItem(position: Int): Any = shown[position]
            override fun getItemId(position: Int): Long = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val v = (convertView as? LinearLayout) ?: LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(16), dp(8), dp(16), dp(8))
                    addView(TextView(activity).apply {
                        textSize = 16f
                        setTextColor(cText)
                    })
                    addView(TextView(activity).apply {
                        textSize = 11f
                        setTextColor(cSecondary)
                    })
                }
                (v.getChildAt(0) as TextView).text = shown[position].label
                (v.getChildAt(1) as TextView).text = shown[position].packageName
                return v
            }
        }
        list.adapter = adapter

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString()?.trim()?.lowercase(Locale.ROOT) ?: ""
                shown.clear()
                shown.addAll(
                    if (q.isEmpty()) apps else apps.filter {
                        it.label.lowercase(Locale.ROOT).contains(q) ||
                                it.packageName.lowercase(Locale.ROOT).contains(q)
                    }
                )
                adapter.notifyDataSetChanged()
            }
        })

        root.addView(search, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        root.addView(list, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            minOf(dp(360), (activity.resources.displayMetrics.heightPixels * 0.45f).toInt())
        ))

        val builder = AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(root)
            .setNegativeButton(R.string.quick_slot_cancel, null)
        if (!currentLabel.isNullOrBlank()) {
            builder.setNeutralButton(R.string.quick_slot_clear) { _, _ -> onPick(null) }
        }
        val dialog = builder.create()
        list.setOnItemClickListener { _, _, position, _ ->
            val app = shown.getOrNull(position) ?: return@setOnItemClickListener
            onPick(app)
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun toast(activity: Activity, msg: String) {
        Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
    }
}
