package dev.montb.basickeyboard.ime

import android.content.Context

/**
 * Minimal, fully offline pinyin -> Hanzi conversion.
 *
 * Pinyin input is (unlike Japanese kana-to-kanji) essentially dictionary lookup: the typed
 * letters ARE the key, so we store the dictionary keyed by the concatenated toneless syllables
 * ("nihao" -> 你好) and look the raw input up directly. No grammar or morphology needed.
 *
 * The dictionary is a plain asset, `assets/pinyin_dict.txt`, one entry per line:
 *
 *     pinyin<TAB>word<TAB>frequency
 *
 * with `#` comments and blank lines ignored. Higher frequency ranks first. The shipped file is
 * a small seed so the whole path works; swapping in a full open dataset (RIME/luna_pinyin,
 * pinyin-data, CC-CEDICT) is just replacing that one file, see tools/make_pinyin_dict.py.
 * By convention ü is written "v" (as every Chinese IME accepts), e.g. 女 = "nv".
 *
 * The parse/lookup functions are pure and `internal` so they can be unit-tested off-device;
 * only [ensureLoaded] touches Android.
 */
object PinyinEngine {

    const val ASSET = "pinyin_dict.txt"

    /** One hit: [word] is the Hanzi, [keyLen] how many typed letters it consumed (so the
     *  caller can commit it and keep the leftover pinyin composing). */
    data class Candidate(val word: String, val keyLen: Int)

    internal data class Entry(val word: String, val freq: Int)

    /** pinyin key -> entries, most frequent first. */
    private var dict: Map<String, List<Entry>> = emptyMap()
    /** Keys in sorted order, so prefix prediction is a binary-search range, not a full scan. */
    private var keys: List<String> = emptyList()
    private var loaded = false

    /**
     * Read the dictionary asset once. Called the first time Chinese is selected rather than at
     * service start, so an English-only session never pays for it. A failure leaves an empty
     * dictionary (no candidates) rather than crashing the keyboard.
     *
     * NOTE: this parses on the calling thread. That is fine for the seed file; if you swap in a
     * multi-megabyte dictionary, move this onto a background thread to avoid a first-switch hitch.
     */
    fun ensureLoaded(context: Context) {
        if (loaded) return
        loaded = true
        runCatching {
            context.assets.open(ASSET).bufferedReader().useLines { dict = parse(it) }
        }
        keys = dict.keys.sorted()
    }

    /** True when a dictionary actually loaded, so the UI can say so instead of looking broken. */
    fun hasDictionary(): Boolean = dict.isNotEmpty()

    /** Candidates for the pinyin typed so far, best first. */
    fun candidates(input: String, limit: Int = 40): List<Candidate> =
        lookup(dict, keys, input, limit)

    internal fun parse(lines: Sequence<String>): Map<String, List<Entry>> {
        val acc = HashMap<String, MutableList<Entry>>()
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            // Split on any whitespace run: neither pinyin nor Hanzi contain spaces, so this
            // accepts tab- or space-separated files without the format being fragile.
            val parts = line.split(Regex("\\s+"))
            if (parts.size < 2) continue
            val key = parts[0].trim().lowercase()
            val word = parts[1].trim()
            if (key.isEmpty() || word.isEmpty()) continue
            val freq = parts.getOrNull(2)?.trim()?.toIntOrNull() ?: 1
            acc.getOrPut(key) { ArrayList() }.add(Entry(word, freq))
        }
        return acc.mapValues { (_, v) -> v.sortedByDescending { it.freq } }
    }

    /**
     * Three tiers, in order:
     *  1. an exact match on everything typed (the best answer),
     *  2. predictions, dictionary keys that START with what's typed ("ni" -> 你好, 你们), ranked
     *     by frequency so common words surface early,
     *  3. if nothing matched the whole input, the longest prefix that IS in the dictionary, so
     *     the user can commit part of it and keep typing the rest.
     */
    internal fun lookup(
        dict: Map<String, List<Entry>>,
        keys: List<String>,
        input: String,
        limit: Int
    ): List<Candidate> {
        if (input.isEmpty() || limit <= 0) return emptyList()
        // Dedupe by word, keeping the first (best) hit for each.
        val out = LinkedHashMap<String, Candidate>()

        fun add(key: String) {
            val entries = dict[key] ?: return
            for (e in entries) {
                if (out.size >= limit) return
                if (!out.containsKey(e.word)) out[e.word] = Candidate(e.word, key.length)
            }
        }

        add(input)

        if (out.size < limit) {
            val preds = ArrayList<Pair<Entry, Int>>()
            for (k in prefixKeys(keys, input)) {
                if (k == input) continue
                dict[k]?.forEach { e -> preds += e to k.length }
            }
            preds.sortByDescending { it.first.freq }
            for ((e, keyLen) in preds) {
                if (out.size >= limit) break
                if (!out.containsKey(e.word)) out[e.word] = Candidate(e.word, keyLen)
            }
        }

        if (out.isEmpty()) {
            for (len in input.length - 1 downTo 1) {
                val p = input.substring(0, len)
                if (dict.containsKey(p)) { add(p); break }
            }
        }
        return out.values.toList()
    }

    /** Dictionary keys beginning with [prefix], via the sorted-key range. */
    internal fun prefixKeys(keys: List<String>, prefix: String): List<String> {
        val out = ArrayList<String>()
        var i = lowerBound(keys, prefix)
        while (i < keys.size && keys[i].startsWith(prefix)) { out += keys[i]; i++ }
        return out
    }

    private fun lowerBound(keys: List<String>, prefix: String): Int {
        var lo = 0
        var hi = keys.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (keys[mid] < prefix) lo = mid + 1 else hi = mid
        }
        return lo
    }
}
