#!/usr/bin/env python3
"""濁点キー挙動を探る.

か + 濁点N回, は + 濁点N回, つ + 濁点N回, や + 濁点N回, あ + 濁点N回
の遷移を記録して, 各文字の入力手順を推定する.
"""
import time

from probe_common import tap, swipe, read_editor, clear_editor

DAK = (326, 2177)
KEYS = {
    "あ": (326, 1645, "C"),
    "か": (540, 1645, "C"),
    "き": (540, 1645, "L"),
    "く": (540, 1645, "U"),
    "け": (540, 1645, "R"),
    "こ": (540, 1645, "D"),
    "さ": (754, 1645, "C"),
    "し": (754, 1645, "L"),
    "す": (754, 1645, "U"),
    "せ": (754, 1645, "R"),
    "そ": (754, 1645, "D"),
    "た": (326, 1822, "C"),
    "ち": (326, 1822, "L"),
    "つ": (326, 1822, "U"),
    "て": (326, 1822, "R"),
    "と": (326, 1822, "D"),
    "は": (754, 1822, "C"),
    "ひ": (754, 1822, "L"),
    "ふ": (754, 1822, "U"),
    "へ": (754, 1822, "R"),
    "ほ": (754, 1822, "D"),
    "や": (540, 1999, "C"),
    "ゆ": (540, 1999, "U"),
    "よ": (540, 1999, "D"),
    "つ_": (326, 1822, "U"),  # for testing っ
    "わ": (540, 2177, "C"),
    "う": (326, 1645, "U"),
}
FLICK_PX = 100
FLICK_MS = 60


def input_char(cx, cy, d):
    if d == "C":
        tap(cx, cy)
        return
    dx, dy = {"L": (-FLICK_PX, 0), "U": (0, -FLICK_PX),
              "R": (FLICK_PX, 0), "D": (0, FLICK_PX)}[d]
    swipe(cx, cy, cx + dx, cy + dy, FLICK_MS)


def cycle_dakuten(base_name, taps=5):
    """base文字を入れて濁点キーを1〜taps回タップした結果を記録."""
    cx, cy, d = KEYS[base_name]
    results = []
    for n in range(taps + 1):  # 0=元字 のみ
        clear_editor()
        input_char(cx, cy, d)
        time.sleep(0.25)
        for _ in range(n):
            tap(*DAK)
            time.sleep(0.15)
        time.sleep(0.25)
        t = read_editor()
        results.append({"taps": n, "text": t})
    return results


def main():
    for base in ["あ", "う", "か", "さ", "た", "つ", "は", "や", "ゆ", "よ", "わ"]:
        print(f"\n== base={base} ==")
        for r in cycle_dakuten(base, taps=4):
            print(f"  +dak x{r['taps']} -> {r['text']!r}")


if __name__ == "__main__":
    main()
