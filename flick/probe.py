#!/usr/bin/env python3
"""Probe Gboard日本語12キー: 各キー×5方向 (center/L/U/R/D) を試して結果を記録.

前提:
  - autoactが動いている (127.0.0.1:8765)
  - ru.androidtools.texteditor が前面, big_text_view にフォーカス
  - Gboard日本語(12キー)が表示されている
  - 画面 1080x2392 (縦)
"""
import json
import time

from probe_common import tap, swipe, read_editor, clear_editor

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
FLICK_PX = 100
FLICK_MS = 60


def flick(key_xy, direction):
    cx, cy = key_xy
    if direction == "C":
        tap(cx, cy)
        return
    dx, dy = {"L": (-FLICK_PX, 0), "U": (0, -FLICK_PX),
              "R": (FLICK_PX, 0), "D": (0, FLICK_PX)}[direction]
    swipe(cx, cy, cx + dx, cy + dy, FLICK_MS)


def probe_one(name, key_xy, direction, settle=0.35):
    before = read_editor()
    flick(key_xy, direction)
    time.sleep(settle)
    after = read_editor()
    produced = after[len(before):] if after.startswith(before) else f"?before={before!r} after={after!r}"
    return produced


def main():
    print("== Probe start ==")
    clear_editor()
    result = {}
    for name, xy in KEYS.items():
        result[name] = {}
        for d in ["C", "L", "U", "R", "D"]:
            produced = probe_one(name, xy, d)
            result[name][d] = produced
            print(f"  {name} {d} -> {produced!r}")
            clear_editor()
    print("\n== JSON ==")
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
