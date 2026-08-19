package dev.montb.basickeyboard.ime

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Lists recent clipboard entries; tap one to paste it into the focused field. */
@SuppressLint("ViewConstructor")
class ClipboardView(
    context: Context,
    dark: Boolean,
    private val onPaste: (String) -> Unit,
    private val onClearAll: () -> Unit,
    private val onBack: () -> Unit
) : LinearLayout(context) {

    init {
        orientation = VERTICAL
        val bg = if (dark) Color.parseColor("#1B1B1B") else Color.parseColor("#ECEFF1")
        val card = if (dark) Color.parseColor("#333333") else Color.WHITE
        val textColor = if (dark) Color.WHITE else Color.parseColor("#202020")
        setBackgroundColor(bg)
        val density = resources.displayMetrics.density

        // Top bar: back + clear all.
        val bar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, (52 * density).toInt())
        }
        bar.addView(Button(context).apply {
            text = "ABC"
            setTextColor(textColor)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { onBack() }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        })
        bar.addView(TextView(context).apply {
            text = "Clipboard"
            setTextColor(textColor)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 2f)
        })
        bar.addView(Button(context).apply {
            text = "Clear"
            setTextColor(textColor)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { onClearAll() }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        })
        addView(bar)

        val items = ClipboardStore.items(context)
        val scroll = ScrollView(context).apply {
            // Taller so entries aren't cramped at the bottom of the screen.
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, (260 * density).toInt())
        }
        val list = LinearLayout(context).apply { orientation = VERTICAL }

        if (items.isEmpty()) {
            list.addView(TextView(context).apply {
                text = "Nothing copied yet.\nCopy some text, then tap 📋 to paste it here."
                setTextColor(textColor)
                gravity = Gravity.CENTER
                setPadding(0, (24 * density).toInt(), 0, 0)
            })
        } else {
            items.forEach { entry ->
                list.addView(TextView(context).apply {
                    text = if (entry.length > 120) entry.take(120) + "…" else entry
                    setTextColor(textColor)
                    maxLines = 3
                    setBackgroundColor(card)
                    setPadding((12 * density).toInt(), (12 * density).toInt(),
                        (12 * density).toInt(), (12 * density).toInt())
                    val lp = LinearLayout.LayoutParams(
                        LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT
                    ).apply { setMargins(0, 0, 0, (4 * density).toInt()) }
                    layoutParams = lp
                    setOnClickListener { onPaste(entry) }
                })
            }
        }
        scroll.addView(list)
        addView(scroll)
    }
}
