package dev.montb.basickeyboard.ime

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import kotlin.math.max

/**
 * Custom view that draws the key grid and reports taps / long-presses. Plain Canvas
 * drawing keeps it fast and dependency-free (no per-key child views to recycle).
 */
@SuppressLint("ViewConstructor")
class KeyboardView(
    context: Context,
    private val listener: Listener
) : View(context) {

    interface Listener {
        fun onKey(action: KeyAction)
        fun onKeyText(text: String)   // from a long-press popup selection
        /** Held backspace: delete one whole word at a time (fast bulk delete). */
        fun onBackspaceWord()
    }

    var layout: Layout = Layouts.english()
        set(value) { field = value; requestLayout(); invalidate() }

    var shifted: Boolean = false
        set(value) { field = value; invalidate() }

    var dark: Boolean = true
        set(value) { field = value; invalidate() }

    // --- drawing ---
    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val rect = RectF()
    private val density = resources.displayMetrics.density
    // Row height is user-adjustable (setup screen size control); defaults to 62dp.
    // Text scales with height so taller keys don't look empty / shorter ones cramped.
    private val rowHeightDp = KeyboardPrefs.rowHeightDp(context)
    private val rowHeight = rowHeightDp * density
    private val textScale = rowHeightDp / KeyboardPrefs.DEFAULT_ROW_HEIGHT.toFloat()
    // HTC-style "square keys" mode: smaller corner radius and a slightly wider gap so the
    // keys look squarer and a touch smaller. Default (false) keeps the rounded, bigger keys.
    private val squareKeys = KeyboardPrefs.squareKeys(context)
    // Simple Keyboard (F-Droid) style: hairline gap + a shared column grid (row centering
    // happens in onDraw). Takes precedence over the gap/radius of the other modes.
    private val gridMode = KeyboardPrefs.compactGrid(context)
    private val gap = density * when {
        gridMode -> 1.5f    // hairline, removes the blank space between keys
        squareKeys -> 6f
        else -> 4f
    }
    private val radius = density * when {
        gridMode -> 4f      // flat-ish grid keys
        squareKeys -> 3f
        else -> 8f
    }

    // Hit-test map rebuilt each layout pass: key bounds -> Key.
    private val hitboxes = ArrayList<Triple<RectF, Key, String>>()

    private val handler = Handler(Looper.getMainLooper())

    // Per-pointer touch state, so fast typing with overlapping presses (key rollover) and
    // multi-finger input each register, instead of the second press being dropped by a
    // single-finger model. Keyed by pointer id.
    private class Pointer(val key: Key) {
        var popupShown = false
        var longPress: Runnable? = null
    }
    private val pointers = HashMap<Int, Pointer>()

    // --- held-backspace auto-repeat ---
    // After an initial pause, delete repeatedly; the interval shrinks (speeds up) and
    // after enough repeats we switch to deleting a whole word per tick so a long hold
    // clears far more than one char at a time.
    private var backspaceRepeats = 0
    private val backspaceRunnable = object : Runnable {
        override fun run() {
            backspaceRepeats++
            // Backstop: if the finger-up was lost entirely with no lifecycle change to catch
            // (cancelPending() handles the normal cases), don't auto-repeat forever. The cap is
            // generous, far past any deliberate hold-to-clear, so it never cuts a real bulk
            // delete short; it just bounds a genuinely stuck timer. Lift and press again to
            // continue past it.
            if (backspaceRepeats > MAX_AUTO_REPEATS) { stopBackspaceRepeat(); return }
            if (backspaceRepeats >= WORD_DELETE_AFTER) {
                listener.onBackspaceWord()
            } else {
                listener.onKey(KeyAction.Backspace)
            }
            // Accelerate: 90ms early, ramping down to a 30ms floor.
            val interval = (REPEAT_START_MS - backspaceRepeats * 6L).coerceAtLeast(REPEAT_MIN_MS)
            handler.postDelayed(this, interval)
        }
    }

    private fun startBackspaceRepeat() {
        backspaceRepeats = 0
        handler.postDelayed(backspaceRunnable, FIRST_REPEAT_DELAY_MS)
    }

    private fun stopBackspaceRepeat() {
        handler.removeCallbacks(backspaceRunnable)
        backspaceRepeats = 0
    }

    /**
     * Drop every pending touch timer and tracked pointer. Called when the keyboard is
     * hidden, detached, or moved to a new field. Without this, a press whose ACTION_UP the
     * host app swallowed, notably QQ hiding/re-showing the keyboard mid-press, leaves the
     * held-backspace auto-repeat running: it keeps firing and escalates to deleting whole
     * words of text the user never meant to erase. A stuck long-press would likewise fire
     * into the next field. Cancelling on every lifecycle change stops both.
     */
    fun cancelPending() {
        stopBackspaceRepeat()
        pointers.values.forEach { it.longPress?.let { r -> handler.removeCallbacks(r) } }
        pointers.clear()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cancelPending()
    }

    private companion object {
        const val FIRST_REPEAT_DELAY_MS = 400L  // pause before auto-repeat kicks in
        const val REPEAT_START_MS = 90L
        const val REPEAT_MIN_MS = 30L
        const val WORD_DELETE_AFTER = 12        // after ~12 char-deletes, delete whole words
        const val MAX_AUTO_REPEATS = 120        // safety cap: stop a stuck repeat (~108 word-deletes)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = (layout.rows.size * rowHeight + gap).toInt()
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        val bg = if (dark) Color.parseColor("#1B1B1B") else Color.parseColor("#ECEFF1")
        val keyBg = if (dark) Color.parseColor("#333333") else Color.WHITE
        val keyText = if (dark) Color.WHITE else Color.parseColor("#202020")
        canvas.drawColor(bg)
        hitboxes.clear()

        val width = width.toFloat()
        // Grid mode: one shared column-unit width, taken from the top row (the canonical
        // 10-column reference). Rows no wider than that are centered (so asdf… sits under
        // qwerty…); wider rows, e.g. the space-bar row, still stretch to fill.
        val reference = layout.rows.firstOrNull()
            ?.sumOf { it.weight.toDouble() }?.toFloat() ?: 10f
        val unit = if (reference > 0f) (width - gap * (reference + 1f)) / reference else 0f
        var y = gap
        for (row in layout.rows) {
            val totalWeight = row.sumOf { it.weight.toDouble() }.toFloat()
            val gridCentered = gridMode && totalWeight <= reference
            val usable = width - gap * (row.size + 1)
            // Centre short rows; the offset is the empty half-key margin on each side.
            val rowWidth = if (gridCentered) totalWeight * unit + gap * (row.size + 1) else width
            var x = if (gridCentered) (width - rowWidth) / 2f + gap else gap
            for (key in row) {
                val kw = if (gridCentered) key.weight * unit else usable * (key.weight / totalWeight)
                rect.set(x, y, x + kw, y + rowHeight - gap)
                keyPaint.color = keyBg
                canvas.drawRoundRect(rect, radius, radius, keyPaint)

                val label = displayLabel(key)
                textPaint.color = keyText
                textPaint.textSize = (if (label.length > 2) 17f else 24f) * density * textScale
                val ty = rect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2
                canvas.drawText(label, rect.centerX(), ty, textPaint)

                // Tiny corner hint (e.g. the number above q-w-e-…).
                key.hint?.let { h ->
                    hintPaint.color = if (dark) Color.parseColor("#9E9E9E") else Color.parseColor("#757575")
                    hintPaint.textSize = 11f * density * textScale
                    canvas.drawText(h, rect.centerX(), rect.top + 13f * density * textScale, hintPaint)
                }

                hitboxes.add(Triple(RectF(rect), key, label))
                x += kw + gap
            }
            y += rowHeight
        }
    }

    private fun displayLabel(key: Key): String {
        key.label?.let { return it }
        val a = key.action
        return if (a is KeyAction.Char) {
            if (shifted) a.text.uppercase() else a.text
        } else ""
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            // A finger goes down, register it on its own so overlapping presses (fast
            // typing / rollover) and multi-finger input each fire, not just the first finger.
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = event.actionIndex
                val hit = findKey(event.getX(idx), event.getY(idx)) ?: return true
                val key = hit.second
                val p = Pointer(key)
                pointers[event.getPointerId(idx)] = p
                val lpAction = key.longPressAction
                val action = key.action
                when {
                    // Backspace: tap deletes one char (on UP); holding auto-repeats and then
                    // deletes whole words. The repeat is stopped when THIS finger lifts.
                    action is KeyAction.Backspace -> startBackspaceRepeat()
                    // Explicit long-press action (e.g. , -> clipboard, emoji -> language).
                    lpAction != null ->
                        Runnable { p.popupShown = true; listener.onKey(lpAction) }
                            .also { p.longPress = it; handler.postDelayed(it, 350) }
                    // Otherwise long-press commits the first text alternative (number/accent).
                    action is KeyAction.Char && action.popup.isNotEmpty() ->
                        Runnable { p.popupShown = true; listener.onKeyText(action.popup.first()) }
                            .also { p.longPress = it; handler.postDelayed(it, 350) }
                }
            }
            // A finger lifts, fire just that finger's key (if it was a tap still on the key).
            // Using actionIndex means a finger lifting mid-rollover (ACTION_POINTER_UP) is
            // handled exactly like a normal release, so no presses are lost.
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val idx = event.actionIndex
                val p = pointers.remove(event.getPointerId(idx)) ?: return true
                p.longPress?.let { handler.removeCallbacks(it) }
                val stillOnKey = findKey(event.getX(idx), event.getY(idx))?.second === p.key
                if (p.key.action is KeyAction.Backspace) {
                    // If backspace auto-repeat already ran, the deletes are done, don't also
                    // fire a tap-delete on release.
                    val repeated = backspaceRepeats > 0
                    stopBackspaceRepeat()
                    if (!p.popupShown && !repeated && stillOnKey) fireKey(p.key)
                } else if (!p.popupShown && stillOnKey) {
                    fireKey(p.key)
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                pointers.values.forEach { it.longPress?.let { r -> handler.removeCallbacks(r) } }
                pointers.clear()
                stopBackspaceRepeat()
            }
        }
        return true
    }

    private fun fireKey(key: Key) {
        val action = key.action
        if (action is KeyAction.Char && shifted) {
            listener.onKeyText(action.text.uppercase())
        } else {
            listener.onKey(action)
        }
    }

    private fun findKey(x: Float, y: Float): Triple<RectF, Key, String>? =
        hitboxes.firstOrNull { it.first.contains(x, max(0f, y)) }
}
