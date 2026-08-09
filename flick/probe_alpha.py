#!/usr/bin/env python3
"""Gboard 英字QWERTYモードの各キーの座標を probe.

推定 座標: shift(85,2040) / del(994,2040) は a11y確認済.
Row 4 y=2040, Row 5 y=2188 (a11y).
Row 3 asdfghjkl 9キーは行4より上に等間隔と仮定.

各キー中心 tap → editor に入った文字読取 → 対応表を出力.
"""
import json
import socket
import sys
import time

HOST, PORT = "127.0.0.1", 8765
IME_PKG = "com.google.android.inputmethod.latin"
EDITOR_PKG = "ru.androidtools.texteditor"
EDITOR_ID = f"{EDITOR_PKG}:id/big_text_view"
DEL_KEY = f"{IME_PKG}:id/key_pos_del"
SWITCH_ALPHA = f"{IME_PKG}:id/key_pos_switch_hiragana_alphabet"

def send(cmd, args=None):
    req = json.dumps({"cmd": cmd, "args": args or {}}, ensure_ascii=False) + "\n"
    s = socket.socket()
    s.settimeout(60)
    s.connect((HOST, PORT))
    s.sendall(req.encode("utf-8"))
    buf = b""
    while not buf.endswith(b"\n"):
        c = s.recv(65536)
        if not c: break
        buf += c
    s.close()
    return json.loads(buf.decode("utf-8"))

def launch_editor():
    send("launch", {"package": EDITOR_PKG})
    time.sleep(1.5)
    if not send("find", {"by": "id", "value": EDITOR_ID, "limit": 1}).get("result",{}).get("matches"):
        return False
    send("click", {"by": "id", "value": EDITOR_ID})
    time.sleep(0.8)
    return True

def read_editor():
    r = send("find", {"by": "id", "value": EDITOR_ID, "limit": 1})
    m = r.get("result", {}).get("matches", [])
    return (m[0].get("text") or "") if m else ""

def clear_editor():
    for _ in range(3):
        t = read_editor()
        if not t: return
        for _ in range(len(t) + 3):
            send("click", {"by": "id", "value": DEL_KEY})
        time.sleep(0.1)

def tap(x, y):
    return send("swipe", {"x1": x, "y1": y, "x2": x, "y2": y + 1, "durMs": 30})

def swipe(x, y, dx, dy, ms=60):
    return send("swipe", {"x1": x, "y1": y, "x2": x + dx, "y2": y + dy, "durMs": ms})

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
    t = read_editor()
    return t

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

    # save
    with open("alpha_qwerty.json", "w") as f:
        json.dump({"keys": layout, "shift": [85, 2040], "del": [994, 2040]}, f, ensure_ascii=False, indent=2)
    print("\n== saved alpha_qwerty.json ==")

    # cleanup
    clear_editor()
    switch_to_alpha()  # back to hira
    time.sleep(0.4)

if __name__ == "__main__":
    main()
