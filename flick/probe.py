#!/usr/bin/env python3
"""Probe Gboard日本語12キー: 各キー×5方向 (center/L/U/R/D) を試して結果を記録.

前提:
  - autoactが動いている (127.0.0.1:8765)
  - ru.androidtools.texteditor が前面, big_text_view にフォーカス
  - Gboard日本語(12キー)が表示されている
  - 画面 1080x2392 (縦)
"""
import json
import socket
import time
import sys

HOST = "127.0.0.1"
PORT = 8765

# 中央12キー中心座標 (probe.md参照)
KEYS = {
    "a":   (326, 1645),  # あ
    "ka":  (540, 1645),  # か
    "sa":  (754, 1645),  # さ
    "ta":  (326, 1822),  # た
    "na":  (540, 1822),  # な
    "ha":  (754, 1822),  # は
    "ma":  (326, 1999),  # ま
    "ya":  (540, 1999),  # や
    "ra":  (754, 1999),  # ら
    "dak": (326, 2177),  # 濁点
    "wa":  (540, 2177),  # わ
    "ten": (754, 2177),  # 、
}
DEL = (968, 1645)
FLICK_PX = 100
FLICK_MS = 60

def send(cmd, args=None):
    req = json.dumps({"cmd": cmd, "args": args or {}}, ensure_ascii=False) + "\n"
    s = socket.socket()
    s.connect((HOST, PORT))
    s.sendall(req.encode("utf-8"))
    buf = b""
    while not buf.endswith(b"\n"):
        chunk = s.recv(4096)
        if not chunk:
            break
        buf += chunk
    s.close()
    return json.loads(buf.decode("utf-8"))

def tap(x, y):
    return send("tap", {"x": x, "y": y})

def swipe(x1, y1, x2, y2, ms=FLICK_MS):
    return send("swipe", {"x1": x1, "y1": y1, "x2": x2, "y2": y2, "durMs": ms})

def read_text():
    r = send("find", {"by": "id", "value": "ru.androidtools.texteditor:id/big_text_view", "limit": 1})
    matches = r.get("result", {}).get("matches", [])
    if not matches:
        return ""
    return matches[0].get("text") or ""

def clear_all():
    """全削除. 現テキスト長ぶん削除キー連打."""
    for _ in range(3):
        t = read_text()
        if not t:
            return
        for _ in range(len(t) + 2):
            tap(*DEL)
            time.sleep(0.05)
        time.sleep(0.2)

def flick(key_xy, direction):
    cx, cy = key_xy
    if direction == "C":
        tap(cx, cy)
        return
    dx, dy = {"L": (-FLICK_PX, 0), "U": (0, -FLICK_PX),
              "R": (FLICK_PX, 0), "D": (0, FLICK_PX)}[direction]
    swipe(cx, cy, cx + dx, cy + dy, FLICK_MS)

def probe_one(name, key_xy, direction, settle=0.35):
    before = read_text()
    flick(key_xy, direction)
    time.sleep(settle)
    after = read_text()
    produced = after[len(before):] if after.startswith(before) else f"?before={before!r} after={after!r}"
    return produced

def main():
    print("== Probe start ==")
    clear_all()
    result = {}
    for name, xy in KEYS.items():
        result[name] = {}
        for d in ["C", "L", "U", "R", "D"]:
            produced = probe_one(name, xy, d)
            result[name][d] = produced
            print(f"  {name} {d} -> {produced!r}")
            clear_all()
    print("\n== JSON ==")
    print(json.dumps(result, ensure_ascii=False, indent=2))

if __name__ == "__main__":
    main()
