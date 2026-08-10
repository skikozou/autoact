#!/usr/bin/env python3
"""ひらがな文字列を Gboard日本語フリックで高速入力する.

autoact の `exec` (シナリオ一括実行) で TCP 1接続で全ステップ発火。

使い方:
  python3 flick.py "こんにちは"                # 未確定のまま (次の操作で確定)
  python3 flick.py --commit "こんにちは"       # ひらがな確定 (Enter a11y click)
  python3 flick.py --convert "きょうはあめ" 今日は雨     # 変換候補を先頭一致で選択
  python3 flick.py --convert-first "きょう"    # 最初の候補で確定 (space a11y click)

注意:
  space/Enterキーは `tap` (dispatchGesture 60ms) だと Gboard がキーボード切替と誤認する.
  必ず `click by=id` (a11y ACTION_CLICK) で叩く.
"""
import json
import sys
import time
import argparse

from probe_common import HOST, PORT, IME_PKG, send

KEYMAP_PATH = __file__.rsplit("/", 1)[0] + "/keymap.json"

KEY_ENTER = f"{IME_PKG}:id/key_pos_ime_action"
KEY_SPACE = f"{IME_PKG}:id/key_pos_space"
KEY_DEL   = f"{IME_PKG}:id/key_pos_del"
KEY_RIGHT_ARROW = f"{IME_PKG}:id/key_pos_right_arrow"  # 未確定文字を確定して右移動 (hira mode)
KEY_SWITCH_HIRA_ALPHA = f"{IME_PKG}:id/key_pos_switch_hiragana_alphabet"
KEY_SWITCH_SYMBOL = f"{IME_PKG}:id/key_pos_switch_to_symbol"

ALPHA_KEYS = {
    # row 1 digits
    "1": (54, 1596), "2": (162, 1596), "3": (270, 1596), "4": (378, 1596), "5": (486, 1596),
    "6": (594, 1596), "7": (702, 1596), "8": (810, 1596), "9": (918, 1596), "0": (1026, 1596),
    # row 2 qwertyuiop
    "q": (54, 1744), "w": (162, 1744), "e": (270, 1744), "r": (378, 1744), "t": (486, 1744),
    "y": (594, 1744), "u": (702, 1744), "i": (810, 1744), "o": (918, 1744), "p": (1026, 1744),
    # row 3 asdfghjkl
    "a": (108, 1892), "s": (216, 1892), "d": (324, 1892), "f": (432, 1892), "g": (540, 1892),
    "h": (648, 1892), "j": (756, 1892), "k": (864, 1892), "l": (972, 1892),
    # row 4 zxcvbnm
    "z": (216, 2040), "x": (324, 2040), "c": (432, 2040), "v": (540, 2040), "b": (648, 2040),
    "n": (756, 2040), "m": (864, 2040),
    # row 5 canvas
    ",": (325, 2188), ".": (595, 2188),
}
ALPHA_SHIFT_XY = (85, 2040)

# flick-up 上添字: 記号 -> alpha基底キー
ALPHA_FLICK_UP = {
    "%": "q", "\\": "w", "|": "e", "=": "r", "[": "t",
    "]": "y", "<": "u", ">": "i", "{": "o", "}": "p",
    "@": "a", "#": "s", "¥": "d", "_": "f", "&": "g",
    "-": "h", "+": "j", "(": "k", ")": "l",
    "*": "z", '"': "x", "'": "c", ":": "v", ";": "b",
    "!": "n", "?": "m",
}

# Symbol variant A の座標 (probe_symbol.py 実測)
SYMBOL_A1_KEYS = {
    "1": (54, 1622),  "2": (162, 1622), "3": (270, 1622), "4": (378, 1622), "5": (486, 1622),
    "6": (594, 1622), "7": (702, 1622), "8": (810, 1622), "9": (918, 1622), "0": (1026, 1622),
    "@": (54, 1820),  "#": (162, 1820), "¥": (270, 1820), "%": (378, 1820), "&": (486, 1820),
    "-": (594, 1820), "+": (702, 1820), "(": (810, 1820), ")": (918, 1820), "/": (1026, 1820),
    "*": (216, 2018), '"': (324, 2018), "'": (432, 2018), ":": (540, 2018), ";": (648, 2018),
    "!": (756, 2018), "?": (864, 2018),
}
SYMBOL_A2_KEYS = {
    "~": (54, 1622),  "`": (162, 1622), "|": (270, 1622), "•": (378, 1622), "√": (486, 1622),
    "π": (594, 1622), "÷": (702, 1622), "×": (810, 1622), "§": (918, 1622), "∆": (1026, 1622),
    "£": (54, 1820),  "€": (162, 1820), "$": (270, 1820), "¢": (378, 1820), "^": (486, 1820),
    "°": (594, 1820), "=": (702, 1820), "{": (810, 1820), "}": (918, 1820), "\\": (1026, 1820),
    "_": (216, 2018), "©": (324, 2018), "®": (432, 2018), "™": (540, 2018), "✓": (648, 2018),
    "[": (756, 2018), "]": (864, 2018),
}
SYMBOL_TOGGLE_A_B_XY = (325, 2170)  # 1234/!?# canvas トグル
KEY_SHIFT = f"{IME_PKG}:id/key_pos_shift"
KEY_BACK_TO_PRIME = f"{IME_PKG}:id/key_pos_back_to_prime"

from char_tables import CHAR_MAP

def load_keymap():
    with open(KEYMAP_PATH) as f:
        return json.load(f)

def _tap_step(cx, cy):
    """tap の代わりに 1px swipe を使う (dispatchGesture の point-stroke は 330ms かかるが
    ミニマル swipe は 90ms — Android の隠れオーバヘッド回避)."""
    return {"op":"swipe","x1":cx,"y1":cy,"x2":cx,"y2":cy+1,"durMs":30}

FLICK_DELTA = {"L":(-1,0), "U":(0,-1), "R":(1,0), "D":(0,1)}


def _flick_step(cx, cy, direction, px=1, dur=60):
    """direction='C' なら 1px swipe (tap), それ以外 (L/U/R/D) は px×方向の flick swipe."""
    if direction == "C":
        return _tap_step(cx, cy)
    dxu, dyu = FLICK_DELTA[direction]
    return {"op":"swipe","x1":cx,"y1":cy,
            "x2":cx+dxu*px,"y2":cy+dyu*px,"durMs":dur}


def _append_with_gap(steps, step, gap_ms):
    steps.append(step)
    if gap_ms > 0:
        steps.append({"op":"sleep","ms":gap_ms})

def build_type_steps(text, cfg, gap_ms=0):
    """hira 12キー入力の steps を build.

    同じ base key を連続で叩くと Gboard の multi-tap 判定で
    「か+か」→「き」になるため, 同キー連続時は right_arrow で確定を挟む.

    濁点/半濁点/小文字化は dak キーの 3 方向フリック (L/R/U) 一発で行う.
    gap_ms: 各打鍵の直後に挿入する sleep ms (0 で最速).
    """
    keys = cfg["keys"]
    px = cfg["flick"]["px"]
    dur = cfg["flick"]["durMs"]
    dak_xy = keys["dak"]["xy"]
    steps = []
    prev_key = None
    for ch in text:
        if ch in (" ", "\u3000"):
            _append_with_gap(steps, {"op":"click","by":"id","value":KEY_SPACE}, gap_ms)
            prev_key = "space"
            continue
        if ch == "\n":
            _append_with_gap(steps, {"op":"click","by":"id","value":KEY_ENTER}, gap_ms)
            prev_key = "enter"
            continue
        if ch not in CHAR_MAP:
            print(f"[skip] no mapping: {ch!r}", file=sys.stderr)
            continue
        key, d, dak = CHAR_MAP[ch]
        cx, cy = keys[key]["xy"]
        # 同じ base key 連続は Gboard の multi-tap 判定を避けるため right_arrow で区切る
        if prev_key is not None and key == prev_key:
            steps.append({"op":"click","by":"id","value":KEY_RIGHT_ARROW})
        prev_key = key
        _append_with_gap(steps, _flick_step(cx, cy, d, px, dur), gap_ms)
        if dak is not None:
            # dak は 3 方向フリック (L=濁点, R=半濁点, U=小文字化). C は使わない.
            _append_with_gap(steps, _flick_step(dak_xy[0], dak_xy[1], dak, px, dur), gap_ms)
            prev_key = "dak"
    return steps

def type_text(text, cfg, gap_ms=0):
    steps = build_type_steps(text, cfg, gap_ms)
    return send("exec", {"scenario":{"name":"flick","steps":steps}}, timeout=120)

def build_alpha_steps(text, gap_ms=0):
    """英字/数字/記号 (alpha mode) の steps を build."""
    steps = []
    for ch in text:
        if ch == " ":
            _append_with_gap(steps, {"op":"click","by":"id","value":KEY_SPACE}, gap_ms)
        elif ch == "\n":
            _append_with_gap(steps, {"op":"click","by":"id","value":KEY_ENTER}, gap_ms)
        elif ch in ALPHA_KEYS:
            _append_with_gap(steps, _tap_step(*ALPHA_KEYS[ch]), gap_ms)
        elif ch.isupper() and ch.lower() in ALPHA_KEYS:
            steps.append(_tap_step(*ALPHA_SHIFT_XY))
            _append_with_gap(steps, _tap_step(*ALPHA_KEYS[ch.lower()]), gap_ms)
        elif ch in ALPHA_FLICK_UP:
            x, y = ALPHA_KEYS[ALPHA_FLICK_UP[ch]]
            _append_with_gap(steps, _flick_step(x, y, "U", px=100), gap_ms)
        else:
            print(f"[skip alpha] no mapping: {ch!r}", file=sys.stderr)
    return steps

def type_alpha(text, gap_ms=0):
    steps = build_alpha_steps(text, gap_ms)
    return send("exec", {"scenario":{"name":"alpha","steps":steps}}, timeout=120)

MODES = ("hira", "alpha", "sym_A1", "sym_A2", "sym_B")

def _current_mode():
    """find 1 発で現在 mode を判定。
    'hira' | 'alpha' | 'sym_A1' | 'sym_A2' | 'sym_B' | 'unknown'."""
    r = send("find", {"by":"idContains","value":"key_pos_","limit":60})
    ids = {}
    for m in r.get("result",{}).get("matches",[]):
        short = (m.get("id") or "").rsplit(":id/",1)[-1]
        ids[short] = m
    if any(k.startswith("key_pos_ja_12keys") for k in ids):
        return "hira"
    if "key_pos_back_to_prime" in ids:
        sh = ids.get("key_pos_shift")
        if sh:
            d = (sh.get("desc") or "").strip()
            if "その他" in d: return "sym_A1"
            if d == "記号": return "sym_A2"
        return "sym_B"
    if "key_pos_shift" in ids:  # shift はあるが back_to_prime 無し = alpha
        return "alpha"
    return "unknown"

def _step_toward(cur, target):
    """cur から target に近づく 1 手を発火。発火できたら True、詰みなら False."""
    # sym_B は独自の toggle で sym_A に上げてから他所へ
    if cur == "sym_B":
        x, y = SYMBOL_TOGGLE_A_B_XY
        send("swipe", {"x1":x, "y1":y, "x2":x, "y2":y+1, "durMs":30})
        return True
    # symbol 内 A1 ↔ A2 は shift 1 発
    if cur in ("sym_A1","sym_A2") and target in ("sym_A1","sym_A2"):
        send("click", {"by":"id","value":KEY_SHIFT})
        return True
    # symbol から出る (hira/alpha 側は back_to_prime の着地を再判定)
    if cur in ("sym_A1","sym_A2") and target in ("hira","alpha"):
        send("click", {"by":"id","value":KEY_BACK_TO_PRIME})
        return True
    # hira/alpha → symbol (last-used variant に着地するので再判定)
    if cur in ("hira","alpha") and target.startswith("sym"):
        send("click", {"by":"id","value":KEY_SWITCH_SYMBOL})
        return True
    # hira ↔ alpha
    if (cur, target) in (("hira","alpha"), ("alpha","hira")):
        send("click", {"by":"id","value":KEY_SWITCH_HIRA_ALPHA})
        return True
    return False

def ensure_mode(target, max_steps=5, settle=0.4):
    """target まで 1 手ずつ遷移+再判定 (最大 max_steps 手).
    target: 'hira'|'alpha'|'sym_A1'|'sym_A2'."""
    assert target in ("hira","alpha","sym_A1","sym_A2"), f"bad target: {target}"
    for _ in range(max_steps):
        cur = _current_mode()
        if cur == target:
            return True
        if not _step_toward(cur, target):
            return False
        time.sleep(settle)
    return _current_mode() == target

# ---- 互換ラッパ (bench_convert / probe_symbol / docs 用) ----
def in_hira_mode():   return _current_mode() == "hira"
def in_alpha_mode():  return _current_mode() == "alpha"
def in_symbol_mode(): return _current_mode().startswith("sym")
def switch_to_hira():  return ensure_mode("hira")
def switch_to_alpha(): return ensure_mode("alpha")
def ensure_symbol_A1(): return ensure_mode("sym_A1")
def ensure_symbol_A2(): return ensure_mode("sym_A2")
def symbol_state():
    return {"sym_A1":"A1","sym_A2":"A2","sym_B":"B"}.get(_current_mode())

def build_symbol_steps(text, gap_ms=0):
    """symbol モード内で連続文字列を打つ. A1/A2 切替は python 側で分割してから呼ぶ.
    ここでは 1 ページの chars だけ扱う (呼び出し側で保証)."""
    steps = []
    for ch in text:
        if ch == " ":
            _append_with_gap(steps, {"op":"click","by":"id","value":KEY_SPACE}, gap_ms)
        elif ch == "\n":
            _append_with_gap(steps, {"op":"click","by":"id","value":KEY_ENTER}, gap_ms)
        elif ch in SYMBOL_A1_KEYS:
            _append_with_gap(steps, _tap_step(*SYMBOL_A1_KEYS[ch]), gap_ms)
        elif ch in SYMBOL_A2_KEYS:
            _append_with_gap(steps, _tap_step(*SYMBOL_A2_KEYS[ch]), gap_ms)
        else:
            print(f"[skip symbol] no mapping: {ch!r}", file=sys.stderr)
    return steps

def type_symbol_page(text, gap_ms=0):
    """現在の symbol ページで打つ (呼び出し側で ensure_symbol_A1/A2 済のこと)."""
    steps = build_symbol_steps(text, gap_ms)
    return send("exec", {"scenario":{"name":"sym","steps":steps}}, timeout=120)

def commit_hiragana():
    """ひらがな確定 (Enter a11y click, 改行入らず)."""
    return send("click", {"by":"id","value":KEY_ENTER})

def commit_first_candidate():
    """最初の変換候補で確定 (space a11y click)."""
    return send("click", {"by":"id","value":KEY_SPACE})

def find_candidate_by_prefix(target):
    """候補バーから desc が target で始まる候補を探し, 見つかればそのノードを返す.

    候補バー: RecyclerView bounds [5,1420,965,1536] 内の FrameLayout, C=1.
    desc 形式: "<候補>。<漢字読み解説>..."  (最後の候補は "。" が無いことも)
    """
    # region で候補バー y=[1400,1550] のみ走査 → tree DFS を早期打ち切り.
    # descContains value=target で 1 発, Python 側で startswith/== target を filter.
    # limit=5: 候補バーの 4 見えるノード + 予備 (region で他ノードは除外される想定)
    r = send("find", {"by":"descContains","value":target,
                      "region":{"y1":1400, "y2":1550}, "limit":5})
    for m in r.get("result",{}).get("matches",[]):
        d = m.get("desc","") or ""
        if d.startswith(target + "。") or d == target:
            return m
    return None

def click_candidate(node):
    """候補ノードを clickable なら直接 click."""
    d = node["desc"]
    return send("click", {"by":"descContains","value":d})

def convert_to(target):
    """現在の未確定文字列を target に変換して確定."""
    node = find_candidate_by_prefix(target)
    if not node:
        return {"ok":False,"error":f"candidate not found: {target}"}
    return click_candidate(node)

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("text")
    ap.add_argument("target", nargs="?", help="--convert 使用時の変換先")
    ap.add_argument("--commit", action="store_true", help="ひらがな確定 (Enter)")
    ap.add_argument("--convert", action="store_true", help="target 引数の候補を選ぶ")
    ap.add_argument("--convert-first", action="store_true", help="最初候補で確定 (space)")
    ap.add_argument("--gap", type=int, default=0,
                    help="各打鍵の直後に入れる sleep ms (default 0=最速)")
    ap.add_argument("--dry", action="store_true")
    args = ap.parse_args()
    cfg = load_keymap()

    if args.dry:
        steps = build_type_steps(args.text, cfg, args.gap)
        print(json.dumps({"steps":steps}, ensure_ascii=False, indent=2))
        return

    t0 = time.time()
    r = type_text(args.text, cfg, args.gap)
    ok = r.get("ok")
    n = len(args.text)
    dt = time.time() - t0
    print(f"[type] ok={ok} {n}c {dt:.2f}s ({n/dt:.1f} cps)")
    if not ok:
        print(json.dumps(r, ensure_ascii=False, indent=2))
        return

    if args.convert:
        if not args.target:
            print("--convert requires target", file=sys.stderr); sys.exit(2)
        time.sleep(0.3)  # 候補バーが出るのを待つ
        r = convert_to(args.target)
        print(f"[convert -> {args.target}] {json.dumps(r, ensure_ascii=False)}")
    elif args.convert_first:
        time.sleep(0.2)
        r = commit_first_candidate()
        print(f"[convert-first] {json.dumps(r, ensure_ascii=False)}")
    elif args.commit:
        time.sleep(0.1)
        r = commit_hiragana()
        print(f"[commit] {json.dumps(r, ensure_ascii=False)}")

if __name__ == "__main__":
    main()
