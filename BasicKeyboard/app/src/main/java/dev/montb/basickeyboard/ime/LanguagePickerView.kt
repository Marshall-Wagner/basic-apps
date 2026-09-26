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

/**
 * Pick an input mode directly. With five modes, cycling through them on the globe key took up to
 * four long-presses, so the globe opens this list instead and any mode is one tap away. The
 * current mode is ticked and highlighted.
 *
 * internal because its constructor takes [Lang], which is internal to this module.
 */
@SuppressLint("ViewConstructor")
internal class LanguagePickerView(
    context: Context,
    dark: Boolean,
    current: Lang,
    onPick: (Lang) -> Unit,
    onBack: () -> Unit
) : LinearLayout(context) {

    init {
        orientation = VERTICAL
        setBackgroundColor(if (dark) Color.parseColor("#1B1B1B") else Color.parseColor("#ECEFF1"))
        val textColor = if (dark) Color.WHITE else Color.parseColor("#202020")
        val selectedBg = if (dark) Color.parseColor("#3A3A3A") else Color.WHITE
        val density = resources.displayMetrics.density

        val list = LinearLayout(context).apply { orientation = VERTICAL }
        Lang.entries.forEach { lang ->
            val selected = lang == current
            list.addView(TextView(context).apply {
                // The short label doubles as a sample of the script itself (中文, あ, ア).
                text = if (selected) "${lang.label}   ${lang.description}   ✓"
                else "${lang.label}   ${lang.description}"
                setTextColor(textColor)
                textSize = 17f
                gravity = Gravity.CENTER_VERTICAL
                if (selected) setBackgroundColor(selectedBg)
                setPadding((16 * density).toInt(), 0, (16 * density).toInt(), 0)
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, (48 * density).toInt())
                setOnClickListener { onPick(lang) }
            })
        }
        addView(ScrollView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, (260 * density).toInt())
            addView(list)
        })

        // Bottom bar: back to the keys without changing anything.
        addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, (44 * density).toInt())
            addView(Button(context).apply {
                text = "ABC"
                setTextColor(textColor)
                setBackgroundColor(Color.TRANSPARENT)
                setOnClickListener { onBack() }
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            })
        })
    }
}
