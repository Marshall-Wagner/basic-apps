#!/usr/bin/env python3
"""
Convert an open pinyin dataset into BasicKeyboard's assets/pinyin_dict.txt format:

    <pinyin> <word> <frequency>

where <pinyin> is the concatenated TONELESS syllables (nihao) and u-with-diaeresis is
written "v" (lv = 绿), the convention every Chinese IME accepts.

Input formats, auto-detected per line:

  1. RIME dict (luna_pinyin.dict.yaml and friends)   你好<TAB>ni hao<TAB>1000
     word first, space-separated pinyin, optional trailing weight. The YAML header
     before the line containing only "..." is skipped automatically.
  2. phrase-pinyin-data / pinyin-data (mozillazg)    你好: nǐ hǎo
  3. Already-converted passthrough                   nihao 你好 1000

Usage:

    python3 tools/make_pinyin_dict.py [--freq FILE] [--min-freq N] [--limit N] \
        INPUT [INPUT ...] > app/src/main/assets/pinyin_dict.txt

Duplicates are merged keeping the highest frequency. Output is sorted by pinyin, then by
descending frequency, which is also the order the app ranks candidates in.

FREQUENCIES MATTER MORE THAN VOCABULARY SIZE. A dictionary whose entries all share one
frequency ranks candidates arbitrarily, which is worse to type with than a small but
correctly ordered one: ask for "shi" and you get a rare CJK-extension character instead of
是. RIME splits the two apart, and luna_pinyin.dict.yaml carries NO usable weights (its
occasional percentages are per-reading probabilities for multi-reading characters, not word
frequencies). The companion corpus rime-essay/essay.txt holds them, as <word><TAB><count>:

    --freq essay.txt    look each word's frequency up here, overriding the input's own

Words absent from the frequency file keep frequency 1, so they stay typeable but rank last.

    --min-freq N        drop entries below N (trims the long tail of rare words)
    --limit N           keep only the N most frequent entries

LICENSING: the suite is GPL v3, so only import GPLv3-compatible data. Known-good sources:
RIME dictionaries (GPL-3), mozillazg's pinyin-data / phrase-pinyin-data (MIT),
CC-CEDICT (CC BY-SA 4.0, which Creative Commons declares one-way compatible with GPLv3).
Record whichever you used in the repo so the attribution is not lost.
"""

import re
import sys
import unicodedata

HANZI = re.compile(r"[㐀-䶿一-鿿]")
PINYIN_KEY = re.compile(r"[a-z]+")


def toneless(text: str) -> str:
    """Strip tone marks; map u-with-diaeresis to 'v'.  'nǐ hǎo' -> 'ni hao', 'lǜ' -> 'lv'.

    Decomposes first so precomposed toned vowels (ǖ ǘ ǚ ǜ) are handled, then folds each base
    character together with its combining marks: a 'u' carrying a diaeresis becomes 'v', every
    other mark is simply dropped.
    """
    d = unicodedata.normalize("NFD", text)
    out = []
    i, n = 0, len(d)
    while i < n:
        base = d[i]
        i += 1
        marks = []
        while i < n and unicodedata.combining(d[i]):
            marks.append(d[i])
            i += 1
        if base in "uU" and "̈" in marks:
            out.append("v")
        else:
            out.append(base)
    return "".join(out).lower()


def parse_line(line: str):
    """Return (pinyin_key, word, freq) or None if the line carries no entry."""
    s = line.strip()
    if not s or s.startswith("#") or s.startswith("---") or s == "...":
        return None

    # Format 2:  你好: nǐ hǎo
    if ":" in s and HANZI.search(s.split(":", 1)[0]):
        word, py = s.split(":", 1)
        return _entry(toneless(py).replace(" ", ""), word.strip(), 1)

    parts = re.split(r"\s+", s)
    if len(parts) < 2:
        return None

    # Format 3: already converted, pinyin first.
    if not HANZI.search(parts[0]):
        freq = int(parts[2]) if len(parts) > 2 and parts[2].isdigit() else 1
        return _entry(toneless(parts[0]), parts[1], freq)

    # Format 1 (RIME): word first, then the syllables, then an optional weight.
    #
    # The weight is either a plain count or a PERCENTAGE, and the two mean different things.
    # RIME writes a percentage on a character with several readings to say how often each
    # reading is the right one ("的 de 99.97%", "我 e 0%"). That is not a word frequency, so
    # it must not be used as one; it is only good for discarding readings nobody uses. Note
    # these percentages sit on the most COMMON characters, which is exactly why mistaking
    # them for part of the pinyin drops 的 and 我 while keeping obscure ones.
    word, rest = parts[0], parts[1:]
    freq = 1
    if rest and re.fullmatch(r"\d+(\.\d+)?%", rest[-1]):
        share = float(rest[-1].rstrip("%"))
        rest = rest[:-1]
        if share <= 0.0:
            return None          # a reading this word never actually has
    elif rest and re.fullmatch(r"\d+(\.\d+)?", rest[-1]):
        freq = max(1, int(float(rest[-1])))
        rest = rest[:-1]
    if not rest:
        return None
    return _entry(toneless("".join(rest)), word, freq)


def _entry(key: str, word: str, freq: int):
    if not key or not PINYIN_KEY.fullmatch(key):
        return None
    if not word or not HANZI.search(word):
        return None
    return key, word, freq


def load_frequencies(path: str) -> dict:
    """Read a <word><TAB><count> corpus (rime-essay's essay.txt) into {word: count}."""
    freqs = {}
    with open(path, encoding="utf-8", errors="replace") as fh:
        for line in fh:
            s = line.strip()
            if not s or s.startswith("#"):
                continue
            parts = re.split(r"\s+", s)
            if len(parts) < 2 or not parts[1].isdigit():
                continue
            count = int(parts[1])
            if count > freqs.get(parts[0], -1):
                freqs[parts[0]] = count
    return freqs


def main(argv):
    paths, freq_path, min_freq, limit = [], None, 1, None
    i = 0
    while i < len(argv):
        arg = argv[i]
        if arg == "--freq" and i + 1 < len(argv):
            freq_path = argv[i + 1]; i += 2
        elif arg == "--min-freq" and i + 1 < len(argv):
            min_freq = int(argv[i + 1]); i += 2
        elif arg == "--limit" and i + 1 < len(argv):
            limit = int(argv[i + 1]); i += 2
        else:
            paths.append(arg); i += 1
    if not paths:
        print(__doc__, file=sys.stderr)
        return 1

    freqs = load_frequencies(freq_path) if freq_path else {}
    best = {}
    for path in paths:
        with open(path, encoding="utf-8", errors="replace") as fh:
            for line in fh:
                parsed = parse_line(line)
                if not parsed:
                    continue
                key, word, freq = parsed
                # The corpus only FILLS GAPS, it never overrides a weight the input supplied.
                # Overriding would be actively harmful when mixing sources: essay.txt is
                # traditional-weighted and scores 时 at 0, which would bury the simplified
                # dictionary's own correct 117616.
                if freq <= 1:
                    freq = freqs.get(word, freq)
                slot = (key, word)
                if freq > best.get(slot, -1):
                    best[slot] = freq

    entries = [(k, w, f) for (k, w), f in best.items() if f >= min_freq]
    if limit is not None:
        entries.sort(key=lambda e: -e[2])
        entries = entries[:limit]
    entries.sort(key=lambda e: (e[0], -e[2], e[1]))

    print("# Generated by tools/make_pinyin_dict.py -- do not hand-edit.")
    print("# Format: <pinyin> <word> <frequency>. Pinyin is concatenated toneless syllables,")
    print("# u-with-diaeresis written as 'v'. Higher frequency ranks first.")
    print(f"# {len(entries)} entries.")
    for key, word, freq in entries:
        print(f"{key} {word} {freq}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
