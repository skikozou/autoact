#!/usr/bin/env python3
"""Gboard の各モード (hira / alpha / symbol / etc) にあるキー一覧を dump.

打鍵はせず, モード切替 + a11y tree snapshot のみ.
出力: modes_dump.json + 見やすい表を stdout.

前提: ru.androidtools.texteditor が起動済みで EditText focus 済み.
"""
import json
import sys
import time

from probe_common import IME_PKG, send, launch_editor

SWITCH_ALPHA = f"{IME_PKG}:id/key_pos_switch_hiragana_alphabet"
SWITCH_SYMBOL = f"{IME_PKG}:id/key_pos_switch_to_symbol"


def dump_keys():
    """key_pos_* のキー全部拾う."""
    r = send("find", {"by": "idContains", "value": "key_pos_", "limit": 100})
    out = []
    for m in r.get("result", {}).get("matches", []):
        i = m.get("id") or ""
        short = i.split(":id/", 1)[-1]
        b = m.get("bounds", {})
        cx = m.get("centerX") or ((b.get("left", 0) + b.get("right", 0)) // 2)
        cy = m.get("centerY") or ((b.get("top", 0) + b.get("bottom", 0)) // 2)
        out.append({
            "id": short,
            "desc": (m.get("desc") or "")[:60],
            "text": m.get("text") or "",
            "xy": [cx, cy],
            "bounds": [b.get("left"), b.get("top"), b.get("right"), b.get("bottom")],
        })
    out.sort(key=lambda k: (k["xy"][1], k["xy"][0]))
    return out


def print_keys(keys, mode):
    print(f"\n== [{mode}] {len(keys)} keys ==")
    for k in keys:
        print(f"  {k['id']:44} xy={tuple(k['xy'])} desc={k['desc']!r}")


def main():
    print("== launch editor ==")
    if not launch_editor():
        sys.exit(1)

    all_modes = {}

    # 1. hira
    keys_hira = dump_keys()
    all_modes["hiragana"] = keys_hira
    print_keys(keys_hira, "hiragana")

    # 2. alpha
    print("\n== click switch_hiragana_alphabet -> alpha ==")
    send("click", {"by": "id", "value": SWITCH_ALPHA})
    time.sleep(0.6)
    keys_alpha = dump_keys()
    all_modes["alphabet"] = keys_alpha
    print_keys(keys_alpha, "alphabet")

    # 3. symbol (from alpha)
    if any(k["id"] == "key_pos_switch_to_symbol" for k in keys_alpha):
        print("\n== click switch_to_symbol -> symbol ==")
        send("click", {"by": "id", "value": SWITCH_SYMBOL})
        time.sleep(0.6)
        keys_symbol = dump_keys()
        all_modes["symbol"] = keys_symbol
        print_keys(keys_symbol, "symbol")
    else:
        print("\n[no switch_to_symbol in alpha mode]")

    # 4. return
    print("\n== return to hiragana ==")
    send("click", {"by": "id", "value": SWITCH_ALPHA})
    time.sleep(0.5)
    keys_back = dump_keys()
    got_hira = any(k["id"] == "key_pos_ja_12keys_1" for k in keys_back)
    print(f"  back to hira? {got_hira}")
    if not got_hira:
        send("click", {"by": "id", "value": SWITCH_ALPHA})
        time.sleep(0.5)

    with open("modes_dump.json", "w") as f:
        json.dump(all_modes, f, ensure_ascii=False, indent=2)
    print("\n== saved modes_dump.json ==")


if __name__ == "__main__":
    main()
