"""Multilingual text normalisation and fuzzy token matching.

Tokenisation deliberately splits on whitespace/punctuation only (not ``\\W``)
because Devanagari and Kannada vowel signs are not "word characters" in
Python's regex engine and would otherwise cut words in half.
"""

from __future__ import annotations

import re
import unicodedata
from difflib import SequenceMatcher

_SPLIT_RE = re.compile(r"[\s,.;:!?/\\()\[\]{}'\"|+*&@#%^=<>~`_\-–—]+")

# Rewrites applied to the whole normalised string before tokenising.
_PHRASE_REWRITES: list[tuple[re.Pattern[str], str]] = [
    (re.compile(r"\btype\s*-?\s*c\b"), " type c "),
    (re.compile(r"\btypec\b"), " type c "),
    (re.compile(r"\busb\s*-?\s*c\b"), " type c "),
    (re.compile(r"\bmicro\s*-?\s*usb\b"), " micro usb "),
    (re.compile(r"\bparle\s*-?\s*g\b"), " parle g "),
    (re.compile(r"\bnote\s+book"), " notebook"),
    (re.compile(r"(\d)\s*-\s*(inch|in)\b"), r"\1 inch"),
    (re.compile(r"₹"), " rs "),
]

# Each group is a set of interchangeable words (English, Hinglish, Hindi, Kannada).
_SYNONYM_GROUPS: list[set[str]] = [
    {"atta", "aata", "ata", "flour", "आटा", "ಹಿಟ್ಟು", "hittu"},
    {"salt", "namak", "नमक", "ಉಪ್ಪು", "uppu"},
    {"sugar", "chini", "cheeni", "shakkar", "चीनी", "ಸಕ್ಕರೆ", "sakkare"},
    {"oil", "tel", "तेल", "ಎಣ್ಣೆ", "enne"},
    {"rice", "chawal", "चावल", "ಅಕ್ಕಿ", "akki"},
    {"dal", "daal", "dhal", "दाल", "ಬೇಳೆ", "bele"},
    {"milk", "doodh", "दूध", "ಹಾಲು", "haalu"},
    {"tea", "chai", "चाय", "ಟೀ"},
    {"biscuit", "biscuits", "cookies", "बिस्कुट", "बिस्किट", "ಬಿಸ್ಕತ್"},
    {"noodles", "noodle", "नूडल्स"},
    {"soap", "sabun", "साबुन", "ಸೋಪು"},
    {"charger", "adapter", "adaptor", "चार्जर", "ಚಾರ್ಜರ್"},
    {"cable", "wire", "cord", "तार", "ಕೇಬಲ್"},
    {"earphones", "earphone", "headphones", "earbuds", "handsfree"},
    {"bulb", "lamp", "बल्ब", "ಬಲ್ಬ್"},
    {"notebook", "notebooks", "copy", "कॉपी", "ನೋಟ್ಬುಕ್"},
    {"pen", "pens", "पेन", "ಪೆನ್"},
    {"pencil", "pencils", "पेंसिल"},
    {"pipe", "pipes", "पाइप", "ಪೈಪ್"},
    {"detergent", "washing", "surf"},
    {"toothpaste", "paste", "मंजन"},
    {"onion", "pyaz", "pyaaz", "प्याज", "ಈರುಳ್ಳಿ", "eerulli"},
    {"tomato", "tamatar", "टमाटर", "ಟೊಮೇಟೊ"},
    {"potato", "aloo", "आलू", "ಆಲೂಗಡ್ಡೆ"},
    {"packet", "packets", "pack", "pkt", "पैकेट", "ಪ್ಯಾಕೆಟ್"},
]

SYNONYMS: dict[str, set[str]] = {}
for _group in _SYNONYM_GROUPS:
    for _word in _group:
        SYNONYMS.setdefault(_word, set()).update(_group)


def normalize(text: str | None) -> str:
    if not text:
        return ""
    s = unicodedata.normalize("NFKC", text).lower()
    s = s.replace("‌", "").replace("‍", "")  # zero-width joiners
    for pattern, repl in _PHRASE_REWRITES:
        s = pattern.sub(repl, s)
    return " ".join(s.split())


def tokenize(text: str | None) -> list[str]:
    return [t for t in _SPLIT_RE.split(normalize(text)) if t]


def expand(token: str) -> set[str]:
    return SYNONYMS.get(token, {token})


def _is_numeric(token: str) -> bool:
    return any(ch.isdigit() for ch in token)


def token_match_score(query_token: str, candidate_tokens: set[str] | list[str]) -> float:
    """1.0 exact/synonym, 0.9 prefix, fuzzy ratio for typos, else 0."""
    options = expand(query_token)
    best = 0.0
    for cand in candidate_tokens:
        if cand in options:
            return 1.0
        if _is_numeric(query_token) or _is_numeric(cand):
            continue  # never fuzzy-match numbers ("100" vs "1000")
        for opt in options:
            if len(opt) >= 3 and cand.startswith(opt):
                best = max(best, 0.9)
            elif len(cand) >= 4 and opt.startswith(cand) and len(cand) >= len(opt) - 2:
                best = max(best, 0.85)
            elif len(opt) >= 4 and len(cand) >= 4:
                if _one_transposition(opt, cand):
                    best = max(best, 0.85)  # "slat" -> "salt"
                    continue
                ratio = SequenceMatcher(None, opt, cand).ratio()
                if ratio >= 0.8:
                    best = max(best, ratio * 0.9)
    return best


def _one_transposition(a: str, b: str) -> bool:
    if len(a) != len(b) or a == b:
        return False
    diff = [i for i in range(len(a)) if a[i] != b[i]]
    return len(diff) == 2 and diff[1] == diff[0] + 1 and a[diff[0]] == b[diff[1]] and a[diff[1]] == b[diff[0]]


def build_search_text(*parts: str | None, aliases: list[str] | None = None) -> str:
    tokens: list[str] = []
    for part in [*parts, *(aliases or [])]:
        tokens.extend(tokenize(part))
    seen: set[str] = set()
    ordered = [t for t in tokens if not (t in seen or seen.add(t))]
    return " ".join(ordered)
