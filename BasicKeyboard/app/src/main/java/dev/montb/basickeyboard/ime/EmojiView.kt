package dev.montb.basickeyboard.ime

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Simple scrollable emoji grid + a "back to letters" bar. */
@SuppressLint("ViewConstructor")
class EmojiView(
    context: Context,
    dark: Boolean,
    onEmoji: (String) -> Unit,
    onBack: () -> Unit
) : LinearLayout(context) {

    init {
        orientation = VERTICAL
        setBackgroundColor(if (dark) Color.parseColor("#1B1B1B") else Color.parseColor("#ECEFF1"))
        val textColor = if (dark) Color.WHITE else Color.parseColor("#202020")
        val density = resources.displayMetrics.density

        val scroll = ScrollView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, (260 * density).toInt())
        }
        val grid = GridLayout(context).apply {
            columnCount = 8
            setPadding((4 * density).toInt(), (4 * density).toInt(), (4 * density).toInt(), 0)
        }
        EmojiData.emoji.forEach { e ->
            val cell = TextView(context).apply {
                text = e
                textSize = 22f
                gravity = Gravity.CENTER
                val side = (40 * density).toInt()
                layoutParams = GridLayout.LayoutParams().apply { width = side; height = side }
                setOnClickListener { onEmoji(e) }
            }
            grid.addView(cell)
        }
        scroll.addView(grid)
        addView(scroll)

        // Bottom bar: back to letters + backspace.
        val bar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, (44 * density).toInt())
        }
        val back = Button(context).apply {
            text = "ABC"
            setTextColor(textColor)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { onBack() }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        }
        bar.addView(back)
        addView(bar)
    }
}
