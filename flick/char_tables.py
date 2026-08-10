"""Gboard 日本語 12キーの文字→打鍵マップ (flick.py から分離).

CHAR_MAP[ch] = (base_key, direction, dak_dir)
  base_key: keymap.json のキー名 ("a","ka",...,"ten")
  direction: "C"=center tap / "L","U","R","D"= flick 方向
  dak_dir:  None = 濁点操作なし
            "L" (←) = 濁点   (は→ば, か→が, う→ゔ, つ→づ, ...)
            "R" (→) = 半濁点 (は→ぱ)
            "U" (↑) = 小文字化 (あ→ぁ, や→ゃ, つ→っ, う→ぅ, わ→ゎ)
"""

CHAR_MAP = {
    "あ": ("a","C",None), "い": ("a","L",None), "う": ("a","U",None), "え": ("a","R",None), "お": ("a","D",None),
    "ぁ": ("a","C","U"), "ぃ": ("a","L","U"), "ぅ": ("a","U","U"), "ぇ": ("a","R","U"), "ぉ": ("a","D","U"),
    "ゔ": ("a","U","L"),
    "か": ("ka","C",None), "き": ("ka","L",None), "く": ("ka","U",None), "け": ("ka","R",None), "こ": ("ka","D",None),
    "が": ("ka","C","L"), "ぎ": ("ka","L","L"), "ぐ": ("ka","U","L"), "げ": ("ka","R","L"), "ご": ("ka","D","L"),
    "さ": ("sa","C",None), "し": ("sa","L",None), "す": ("sa","U",None), "せ": ("sa","R",None), "そ": ("sa","D",None),
    "ざ": ("sa","C","L"), "じ": ("sa","L","L"), "ず": ("sa","U","L"), "ぜ": ("sa","R","L"), "ぞ": ("sa","D","L"),
    "た": ("ta","C",None), "ち": ("ta","L",None), "つ": ("ta","U",None), "て": ("ta","R",None), "と": ("ta","D",None),
    "だ": ("ta","C","L"), "ぢ": ("ta","L","L"), "づ": ("ta","U","L"), "で": ("ta","R","L"), "ど": ("ta","D","L"),
    "っ": ("ta","U","U"),
    "な": ("na","C",None), "に": ("na","L",None), "ぬ": ("na","U",None), "ね": ("na","R",None), "の": ("na","D",None),
    "は": ("ha","C",None), "ひ": ("ha","L",None), "ふ": ("ha","U",None), "へ": ("ha","R",None), "ほ": ("ha","D",None),
    "ば": ("ha","C","L"), "び": ("ha","L","L"), "ぶ": ("ha","U","L"), "べ": ("ha","R","L"), "ぼ": ("ha","D","L"),
    "ぱ": ("ha","C","R"), "ぴ": ("ha","L","R"), "ぷ": ("ha","U","R"), "ぺ": ("ha","R","R"), "ぽ": ("ha","D","R"),
    "ま": ("ma","C",None), "み": ("ma","L",None), "む": ("ma","U",None), "め": ("ma","R",None), "も": ("ma","D",None),
    "や": ("ya","C",None), "ゆ": ("ya","U",None), "よ": ("ya","D",None),
    "ゃ": ("ya","C","U"), "ゅ": ("ya","U","U"), "ょ": ("ya","D","U"),
    "ら": ("ra","C",None), "り": ("ra","L",None), "る": ("ra","U",None), "れ": ("ra","R",None), "ろ": ("ra","D",None),
    "わ": ("wa","C",None), "を": ("wa","L",None), "ん": ("wa","U",None), "ー": ("wa","R",None), "〜": ("wa","D",None),
    "ゎ": ("wa","C","U"),
    "、": ("ten","C",None), "。": ("ten","L",None), "？": ("ten","U",None), "！": ("ten","R",None), "…": ("ten","D",None),
    "?": ("ten","U",None), "!": ("ten","R",None),
    "（": ("ya","L",None), "）": ("ya","R",None), "(": ("ya","L",None), ")": ("ya","R",None),
}
