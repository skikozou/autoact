#!/usr/bin/env python3
"""Gboard 英字QWERTYモードの各キーの座標を probe.

推定 座標: shift(85,2040) / del(994,2040) は a11y確認済.
Row 4 y=2040, Row 5 y=2188 (a11y).
Row 3 asdfghjkl 9キーは行4より上に等間隔と仮定.

各キー中心 tap → editor に入った文字読取 → 対応表を出力.
"""
import json
import sys
import time

from probe_common import IME_PKG, send, tap, read_editor, clear_editor, launch_editor

SWITCH_ALPHA = f"{IME_PKG}:id/key_pos_switch_hiragana_alphabet"

# 推定 layout (Y は等間隔 148px):
# Row4 y=2040 (shift 85, del 994, zxcvbnm 中央 7キー)
# Row3 y=1892 (asdfghjkl 9キー)
# Row2 y=1744 (qwertyuiop 10キー)
# Row1 y=1596 (1234567890 10キー)

ROWS = {
    "row1": {"y": 1596, "keys": "1234567890"},                    # 10キー x=54,162,270,378,486,594,702,810,918,1026
    "row2": {"y": 1744, "keys": "qwertyuiop"},                    # 10キー 同上
    "row3": {"y": 1892, "keys": "asdfghjkl"},                     # 9キー 中央寄せ
    "row4": {"y": 2040, "keys": "zxcvbnm"},                       # 7キー 中央 (shift/del の間)
}


def x_for_row(row_name, i):
    keys = ROWS[row_name]["keys"]
    n = len(keys)
    if n == 10:
        # 全幅10分割
        return 54 + i * 108
    if n == 9:
        # asdfghjkl は少しインデントされる (画像測定: a≈108, l≈972)
        return 108 + i * 108
    if n == 7:
        # zxcvbnm: shift(85)とdel(994)の間, z≈216 m≈864
        return 216 + i * 108
    raise ValueError(n)


def probe_key(cx, cy, name):
    clear_editor()
    tap(cx, cy)
    time.sleep(0.15)
    return read_editor()


def switch_to_alpha():
    send("click", {"by": "id", "value": SWITCH_ALPHA})
    time.sleep(0.5)


def main():
    if not launch_editor():
        print("editor not found"); sys.exit(1)
    switch_to_alpha()
    time.sleep(0.4)

    layout = {}
    print("== center tap probe ==")
    for row, info in ROWS.items():
        y = info["y"]
        keys = info["keys"]
        for i, k in enumerate(keys):
            x = x_for_row(row, i)
            got = probe_key(x, y, k)
            match = "OK" if got == k else "!!"
            print(f"  {row} {k!r:3} ({x:4},{y}) -> {got!r:6}  {match}")
            layout[k] = [x, y]

    with open("alpha_qwerty.json", "w") as f:
        json.dump({"keys": layout, "shift": [85, 2040], "del": [994, 2040]}, f, ensure_ascii=False, indent=2)
    print("\n== saved alpha_qwerty.json ==")

    clear_editor()
    switch_to_alpha()  # back to hira
    time.sleep(0.4)


if __name__ == "__main__":
    main()
