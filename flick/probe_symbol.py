#!/usr/bin/env python3
"""Symbol モード variant A (QWERTY記号) の A1/A2 両ページ座標を probe.

前提: alpha or hira モードから開始. 実行後 hira に戻す.

手順:
  1. hira -> symbol
  2. 正規化: B なら 1234 tap して A へ; A2 なら shift tap して A1 へ
  3. A1 全キー tap → 対応表
  4. shift tap で A2 へ → 全キー tap → 対応表
  5. hira 復帰
"""
import json
import socket
import sys
import time

import flick

EDITOR_PKG = "ru.androidtools.texteditor"
EDITOR_ID = f"{EDITOR_PKG}:id/big_text_view"
IME_PKG = "com.google.android.inputmethod.latin"
SHIFT_ID = f"{IME_PKG}:id/key_pos_shift"
BACK_ID = f"{IME_PKG}:id/key_pos_back_to_prime"

# 期待 layout (screenshot観察)
# 各行 y は shift(2018)基準で推定. Symbol は 3 rows of chars + 1 func row.
# Row spacing 148 と仮定: r1=1574, r2=1722, r3=2018 (r3 は a11y確認)
# → 実測: r1=1622, r2=1820, r3=2018 (delta 198). Screenshot 見直し.
# 少し違うかも → probe結果で調整.

A1_LAYOUT = [
    # (y, [chars along 10-col grid])
    # Row 1: 数字 10キー
    (1622, "1234567890"),
    # Row 2: @ # ¥ % & - + ( ) /
    (1820, "@#¥%&-+()/"),
    # Row 3: [shift] * " ' : ; ! ? [⌫]  (7キー中央)
    (2018, "*\"':;!?"),
]

A2_LAYOUT = [
    (1622, "~`|·√π÷×§∆"),   # 10キー
    (1820, "£€$¢^°={}\\"),  # 10キー
    (2018, "_©®™✓[]"),       # 7キー中央
]

def x_grid10(i):
    return 54 + i * 108

def x_grid7_center(i):
    """shift(85), del(994) の間で 7キー中央寄せ. 216,324,432,540,648,756,864"""
    return 216 + i * 108

def x_for_row(row_layout, i, is_row3):
    if is_row3:
        return x_grid7_center(i)
    return x_grid10(i)

def read():
    r = flick.send("find", {"by": "id", "value": EDITOR_ID, "limit": 1})
    m = r.get("result", {}).get("matches", [])
    return (m[0].get("text") or "") if m else ""

def clr():
    for _ in range(3):
        t = read()
        if not t: return
        for _ in range(len(t) + 2):
            flick.send("click", {"by": "id", "value": f"{IME_PKG}:id/key_pos_del"})
        time.sleep(0.1)

def tap(x, y):
    return flick.send("swipe", {"x1": x, "y1": y, "x2": x, "y2": y + 1, "durMs": 30})

def current_symbol_state():
    """Returns 'A1', 'A2', 'B', or None."""
    r = flick.send("find", {"by": "id", "value": SHIFT_ID, "limit": 1})
    ms = r.get("result", {}).get("matches", [])
    if ms:
        d = ms[0].get("desc") or ""
        if "その他" in d: return "A1"
        if "記号" == d.strip(): return "A2"
        return "A?"
    # no shift = B
    r = flick.send("find", {"by": "id", "value": BACK_ID, "limit": 1})
    if r.get("result", {}).get("matches"):
        return "B"
    return None

def enter_symbol_from_hira():
    flick.send("click", {"by": "id", "value": f"{IME_PKG}:id/key_pos_switch_to_symbol"})
    time.sleep(0.5)

def normalize_to_A1():
    st = current_symbol_state()
    print(f"  current: {st}")
    if st == "B":
        tap(325, 2170)  # 1234 canvas -> A
        time.sleep(0.5)
        st = current_symbol_state()
        print(f"  after 1234 tap: {st}")
    if st == "A2":
        flick.send("click", {"by": "id", "value": SHIFT_ID})
        time.sleep(0.5)
        st = current_symbol_state()
        print(f"  after shift tap: {st}")
    return st == "A1"

def probe_layout(layout, name):
    results = {}
    ok_count = 0
    for row_idx, (y, chars) in enumerate(layout):
        is_row3 = row_idx == 2
        for i, ch in enumerate(chars):
            x = x_for_row(chars, i, is_row3)
            clr(); time.sleep(0.05)
            tap(x, y); time.sleep(0.15)
            got = read()
            ok = got == ch
            if ok: ok_count += 1
            marker = "OK" if ok else "MISS"
            results[ch] = {"xy": [x, y], "got": got}
            print(f"  {name} {ch!r:5} ({x:4},{y}) -> {got!r:6}  {marker}")
    print(f"  ==> {ok_count}/{sum(len(c) for _,c in layout)} match")
    return results

def main():
    # ensure hira mode
    if not flick.in_hira_mode():
        flick.switch_to_hira()
    if not flick.in_hira_mode():
        print("[fatal] cannot get to hira"); sys.exit(1)
    print("=== enter symbol ===")
    enter_symbol_from_hira()

    print("=== normalize to A1 ===")
    if not normalize_to_A1():
        print("[fatal] cannot normalize to A1"); sys.exit(1)

    print("\n=== probe A1 ===")
    a1 = probe_layout(A1_LAYOUT, "A1")

    print("\n=== to A2 (click shift) ===")
    flick.send("click", {"by": "id", "value": SHIFT_ID})
    time.sleep(0.5)
    if current_symbol_state() != "A2":
        print("[fatal] shift didn't go to A2"); sys.exit(1)

    print("=== probe A2 ===")
    a2 = probe_layout(A2_LAYOUT, "A2")

    with open("symbol_A.json", "w") as f:
        json.dump({"A1": a1, "A2": a2}, f, ensure_ascii=False, indent=2)
    print("\n=== saved symbol_A.json ===")

    # cleanup
    clr()
    flick.send("click", {"by": "id", "value": BACK_ID})
    time.sleep(0.5)
    flick.switch_to_hira()

if __name__ == "__main__":
    main()
