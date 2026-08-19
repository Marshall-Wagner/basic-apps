package dev.montb.basickeyboard.ime

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The persistent bar across the top of the keyboard (like Gboard's suggestion/clipboard
 * strip): a clear-history button, recently-copied chips you can tap to paste, and a
 * button to open the full clipboard panel + settings.
 */
@SuppressLint("ViewConstructor")
class TopStripView(
    context: Context,
    private val dark: Boolean,
    private val onPaste: (String) -> Unit,
    private val onClearAll: () -> Unit,
    private val onOpenClipboard: () -> Unit,
    private val onOpenPassword: () -> Unit,
    private val onSettings: () -> Unit
) : LinearLayout(context) {

    private val density = resources.displayMetrics.density
    private val textColor = if (dark) Color.WHITE else Color.parseColor("#202020")
    private val chipBg = if (dark) Color.parseColor("#3A3A3A") else Color.parseColor("#FFFFFF")
    private val chipScroll: HorizontalScrollView
    private val chipRow: LinearLayout

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(if (dark) Color.parseColor("#1B1B1B") else Color.parseColor("#ECEFF1"))
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, (44 * density).toInt())

        addView(iconButton("🗑") { onClearAll() })

        chipRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        chipScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            addView(chipRow)
        }
        addView(chipScroll)

        addView(iconButton("📋") { onOpenClipboard() })
        // Opens Proton Pass so you can copy a password, then paste it back here.
        addView(iconButton("🔑") { onOpenPassword() })
        addView(iconButton("⚙") { onSettings() })

        refresh()
    }

    /** Rebuild the chips from the current clipboard history. When [hideChips] is true
     *  (a password field is focused), show nothing, never surface copied text, which
     *  could be a secret, in a login context. */
    fun refresh(hideChips: Boolean = false) {
        chipRow.removeAllViews()
        if (hideChips) {
            chipRow.addView(TextView(context).apply {
                text = "Clipboard hidden in password field"
                setTextColor(if (dark) Color.parseColor("#888888") else Color.parseColor("#9E9E9E"))
                textSize = 13f
                gravity = Gravity.CENTER_VERTICAL
                setPadding((10 * density).toInt(), 0, (10 * density).toInt(), 0)
            })
            return
        }
        val items = ClipboardStore.items(context)
        if (items.isEmpty()) {
            chipRow.addView(TextView(context).apply {
                text = "Copy text to see it here"
                setTextColor(if (dark) Color.parseColor("#888888") else Color.parseColor("#9E9E9E"))
                textSize = 13f
                gravity = Gravity.CENTER_VERTICAL
                setPadding((10 * density).toInt(), 0, (10 * density).toInt(), 0)
            })
        } else {
            items.take(8).forEach { entry ->
                chipRow.addView(chip(entry))
            }
        }
    }

    private fun chip(text: String): TextView = TextView(context).apply {
        this.text = if (text.length > 24) text.take(24) + "…" else text
        setTextColor(textColor)
        textSize = 14f
        maxLines = 1
        setBackgroundColor(chipBg)
        gravity = Gravity.CENTER_VERTICAL
        setPadding((12 * density).toInt(), (6 * density).toInt(), (12 * density).toInt(), (6 * density).toInt())
        val lp = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            setMargins((4 * density).toInt(), (6 * density).toInt(), (4 * density).toInt(), (6 * density).toInt())
        }
        layoutParams = lp
        setOnClickListener { onPaste(text) }
    }

    private fun iconButton(glyph: String, onClick: () -> Unit): TextView = TextView(context).apply {
        text = glyph
        setTextColor(textColor)
        textSize = 18f
        gravity = Gravity.CENTER
        val side = (44 * density).toInt()
        layoutParams = LayoutParams(side, ViewGroup.LayoutParams.MATCH_PARENT)
        setOnClickListener { onClick() }
    }
}
