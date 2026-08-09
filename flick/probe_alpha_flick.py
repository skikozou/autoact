#!/usr/bin/env python3
"""Alpha QWERTY 各キーの flick 上添字を probe."""
import json
import sys
import time

import flick

EDITOR_ID = "ru.androidtools.texteditor:id/big_text_view"
DEL_KEY = "com.google.android.inputmethod.latin:id/key_pos_del"

# screenshot から観察された上添字
EXPECT = {
    "q": "%", "w": "\\", "e": "|", "r": "=", "t": "[",
    "y": "]", "u": "<", "i": ">", "o": "{", "p": "}",
    "a": "@", "s": "#", "d": "¥", "f": "-", "g": "&",
    "h": "-", "j": "+", "k": "(", "l": ")",
    "z": "*", "x": '"', "c": "'", "v": ":", "b": "'",
    "n": "!", "m": "?",
}

def read():
    r = flick.send("find", {"by": "id", "value": EDITOR_ID, "limit": 1})
    m = r.get("result", {}).get("matches", [])
    return (m[0].get("text") or "") if m else ""

def clr():
    for _ in range(3):
        t = read()
        if not t: return
        for _ in range(len(t) + 2):
            flick.send("click", {"by": "id", "value": DEL_KEY})

def main():
    with open("alpha_qwerty.json") as f:
        layout = json.load(f)["keys"]
    results = {}
    ok_count = 0
    for k, exp in EXPECT.items():
        cx, cy = layout[k]
        clr(); time.sleep(0.05)
        flick.send("swipe", {"x1": cx, "y1": cy, "x2": cx, "y2": cy - 100, "durMs": 60})
        time.sleep(0.15)
        got = read()
        ok = got == exp
        if ok: ok_count += 1
        results[k] = got
        marker = "OK" if ok else "MISS"
        print(f"  {k} up -> {got!r:6} (expect {exp!r:4}) {marker}")
    clr()
    print(f"\n== {ok_count}/{len(EXPECT)} match ==")
    with open("alpha_flick_up.json", "w") as f:
        json.dump(results, f, ensure_ascii=False, indent=2)

if __name__ == "__main__":
    main()
