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
 * The candidates bar, shown in place of the clipboard strip while composing pinyin: the raw
 * pinyin typed so far on the left, then the Hanzi candidates, each tappable to commit.
 *
 * Deliberately has NO accidental-tap guard (unlike TopStripView's paste chips). Candidates change
 * on every keystroke, so a settle-delay would block exactly the tap the user is reaching for.
 */
@SuppressLint("ViewConstructor")
class CandidatesView(
    context: Context,
    private val dark: Boolean,
    private val onPick: (PinyinEngine.Candidate) -> Unit
) : LinearLayout(context) {

    private val density = resources.displayMetrics.density
    private val textColor = if (dark) Color.WHITE else Color.parseColor("#202020")
    private val hintColor = if (dark) Color.parseColor("#9E9E9E") else Color.parseColor("#757575")
    private val row: LinearLayout
    private val composingLabel: TextView

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(if (dark) Color.parseColor("#1B1B1B") else Color.parseColor("#ECEFF1"))
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, (44 * density).toInt())

        composingLabel = TextView(context).apply {
            setTextColor(hintColor)
            textSize = 13f
            gravity = Gravity.CENTER_VERTICAL
            setPadding((10 * density).toInt(), 0, (8 * density).toInt(), 0)
        }
        addView(composingLabel)

        row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        addView(HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            addView(row)
        })
    }

    /** Render [candidates] for the pinyin [composing] typed so far. */
    fun show(composing: String, candidates: List<PinyinEngine.Candidate>) {
        composingLabel.text = composing
        row.removeAllViews()
        if (candidates.isEmpty()) {
            // Distinguish "nothing matches this pinyin" from "the dictionary never loaded", so a
            // missing/!broken asset doesn't just look like a dead keyboard.
            row.addView(note(if (PinyinEngine.hasDictionary()) "No match" else "No dictionary loaded"))
            return
        }
        candidates.forEach { row.addView(candidate(it)) }
    }

    private fun note(text: String): TextView = TextView(context).apply {
        this.text = text
        setTextColor(hintColor)
        textSize = 13f
        gravity = Gravity.CENTER_VERTICAL
        setPadding((10 * density).toInt(), 0, (10 * density).toInt(), 0)
    }

    private fun candidate(c: PinyinEngine.Candidate): TextView = TextView(context).apply {
        text = c.word
        setTextColor(textColor)
        textSize = 20f
        maxLines = 1
        gravity = Gravity.CENTER
        setPadding((12 * density).toInt(), 0, (12 * density).toInt(), 0)
        layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)
        setOnClickListener { onPick(c) }
    }
}
