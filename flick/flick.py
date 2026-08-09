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
import socket
import sys
import time
import argparse

HOST, PORT = "127.0.0.1", 8765
KEYMAP_PATH = __file__.rsplit("/", 1)[0] + "/keymap.json"

IME_PKG = "com.google.android.inputmethod.latin"
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

# CHAR_MAP[ch] = (base_key, direction, dak_dir)
#   direction: "C"=center tap / "L","U","R","D"= flick 方向
#   dak_dir:   None       = 濁点操作なし
#              "L" (←)    = 濁点   (は→ば, か→が, う→ゔ, つ→づ, ...)
#              "R" (→)    = 半濁点 (は→ぱ)
#              "U" (↑)    = 小文字化 (あ→ぁ, や→ゃ, つ→っ, う→ぅ, わ→ゎ)
# 旧実装は「濁点キー N 回タップ」だったが, 現行 Gboard は濁点キーもフリック 3 方向で
# 一発変換できる (dak L/R/U). N 回連打 + right_arrow ガードの複雑さを撲滅.
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

def send(cmd, args=None, timeout=60):
    req = json.dumps({"cmd": cmd, "args": args or {}}, ensure_ascii=False) + "\n"
    s = socket.socket()
    s.settimeout(timeout)
    s.connect((HOST, PORT))
    s.sendall(req.encode("utf-8"))
    buf = b""
    while not buf.endswith(b"\n"):
        chunk = s.recv(65536)
        if not chunk:
            break
        buf += chunk
    s.close()
    return json.loads(buf.decode("utf-8"))

def load_keymap():
    with open(KEYMAP_PATH) as f:
        return json.load(f)

def _tap_step(cx, cy):
    """tap の代わりに 1px swipe を使う (dispatchGesture の point-stroke は 330ms かかるが
    ミニマル swipe は 90ms — Android の隠れオーバヘッド回避)."""
    return {"op":"swipe","x1":cx,"y1":cy,"x2":cx,"y2":cy+1,"durMs":30}

FLICK_DELTA = {"L":(-1,0), "U":(0,-1), "R":(1,0), "D":(0,1)}

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
    def gap():
        if gap_ms > 0: steps.append({"op":"sleep","ms":gap_ms})
    def sep(cur_key):
        nonlocal prev_key
        if prev_key is not None and cur_key == prev_key:
            steps.append({"op":"click","by":"id","value":KEY_RIGHT_ARROW})
        prev_key = cur_key
    def emit(cx, cy, direction):
        """direction が 'C' なら 1px swipe (tap), それ以外は flick swipe."""
        if direction == "C":
            steps.append(_tap_step(cx, cy))
        else:
            dxu, dyu = FLICK_DELTA[direction]
            steps.append({"op":"swipe","x1":cx,"y1":cy,
                          "x2":cx+dxu*px,"y2":cy+dyu*px,"durMs":dur})
    for ch in text:
        if ch in (" ", "\u3000"):
            steps.append({"op":"click","by":"id","value":KEY_SPACE})  # 変換誤爆回避
            gap(); prev_key = "space"
            continue
        if ch == "\n":
            steps.append({"op":"click","by":"id","value":KEY_ENTER})
            gap(); prev_key = "enter"
            continue
        if ch not in CHAR_MAP:
            print(f"[skip] no mapping: {ch!r}", file=sys.stderr)
            continue
        key, d, dak = CHAR_MAP[ch]
        cx, cy = keys[key]["xy"]
        sep(key)
        emit(cx, cy, d)
        gap()
        if dak is not None:
            # dak は 3 方向フリック (L=濁点, R=半濁点, U=小文字化). C は使わない.
            emit(dak_xy[0], dak_xy[1], dak)
            prev_key = "dak"
            gap()
    return steps

def type_text(text, cfg, gap_ms=0):
    steps = build_type_steps(text, cfg, gap_ms)
    return send("exec", {"scenario":{"name":"flick","steps":steps}}, timeout=120)

def build_alpha_steps(text, gap_ms=0):
    """英字/数字/記号 (alpha mode) の steps を build."""
    steps = []
    def gap(ms):
        if ms > 0: steps.append({"op":"sleep","ms":ms})
    for ch in text:
        if ch == " ":
            steps.append({"op":"click","by":"id","value":KEY_SPACE})
        elif ch == "\n":
            steps.append({"op":"click","by":"id","value":KEY_ENTER})
        elif ch in ALPHA_KEYS:
            x, y = ALPHA_KEYS[ch]
            steps.append(_tap_step(x, y))
        elif ch.isupper() and ch.lower() in ALPHA_KEYS:
            sx, sy = ALPHA_SHIFT_XY
            steps.append(_tap_step(sx, sy))
            x, y = ALPHA_KEYS[ch.lower()]
            steps.append(_tap_step(x, y))
        elif ch in ALPHA_FLICK_UP:
            base = ALPHA_FLICK_UP[ch]
            x, y = ALPHA_KEYS[base]
            steps.append({"op":"swipe","x1":x,"y1":y,"x2":x,"y2":y-100,"durMs":60})
        else:
            print(f"[skip alpha] no mapping: {ch!r}", file=sys.stderr)
            continue
        gap(gap_ms)
    return steps

def type_alpha(text, gap_ms=0):
    steps = build_alpha_steps(text, gap_ms)
    return send("exec", {"scenario":{"name":"alpha","steps":steps}}, timeout=120)

def in_alpha_mode():
    """今 alpha モードか判定 (key_pos_shift が alpha 位置にあれば)."""
    r = send("find", {"by":"id","value":f"{IME_PKG}:id/key_pos_shift","limit":1})
    for m in r.get("result",{}).get("matches",[]):
        if m.get("centerY") == 2040:  # alpha row 4
            return True
    return False

def in_hira_mode():
    r = send("find", {"by":"idContains","value":"key_pos_ja_12keys","limit":1})
    return bool(r.get("result",{}).get("matches"))

def switch_to_alpha():
    if in_alpha_mode(): return True
    # symbol の場合は back_to_prime で直前 (alpha か hira) に戻る
    if in_symbol_mode():
        send("click", {"by":"id","value":KEY_BACK_TO_PRIME})
        time.sleep(0.4)
        if in_alpha_mode(): return True
    send("click", {"by":"id","value":KEY_SWITCH_HIRA_ALPHA})
    time.sleep(0.4)
    return in_alpha_mode()

def switch_to_hira():
    if in_hira_mode(): return True
    # symbol の場合は back_to_prime で alpha に戻ってから hira へ
    if in_symbol_mode():
        send("click", {"by":"id","value":KEY_BACK_TO_PRIME})
        time.sleep(0.4)
    if in_hira_mode(): return True
    send("click", {"by":"id","value":KEY_SWITCH_HIRA_ALPHA})
    time.sleep(0.4)
    return in_hira_mode()

def in_symbol_mode():
    r = send("find", {"by":"id","value":KEY_BACK_TO_PRIME,"limit":1})
    return bool(r.get("result",{}).get("matches"))

def symbol_state():
    """'A1', 'A2', 'B', or None."""
    r = send("find", {"by":"id","value":KEY_SHIFT,"limit":1})
    ms = r.get("result",{}).get("matches",[])
    if ms:
        d = (ms[0].get("desc") or "").strip()
        if "その他" in d: return "A1"
        if d == "記号": return "A2"
        return "A?"
    if in_symbol_mode(): return "B"
    return None

def switch_to_symbol():
    """symbol モードに入る (last-used variant)."""
    if in_symbol_mode(): return True
    send("click", {"by":"id","value":KEY_SWITCH_SYMBOL})
    time.sleep(0.4)
    return in_symbol_mode()

def ensure_symbol_A1():
    if not switch_to_symbol(): return False
    st = symbol_state()
    if st == "B":
        # 1234 toggle -> A
        send("swipe", {"x1":SYMBOL_TOGGLE_A_B_XY[0], "y1":SYMBOL_TOGGLE_A_B_XY[1],
                       "x2":SYMBOL_TOGGLE_A_B_XY[0], "y2":SYMBOL_TOGGLE_A_B_XY[1]+1, "durMs":30})
        time.sleep(0.4)
        st = symbol_state()
    if st == "A2":
        send("click", {"by":"id","value":KEY_SHIFT})
        time.sleep(0.4)
        st = symbol_state()
    return st == "A1"

def ensure_symbol_A2():
    if not ensure_symbol_A1(): return False
    send("click", {"by":"id","value":KEY_SHIFT})
    time.sleep(0.4)
    return symbol_state() == "A2"

def build_symbol_steps(text, gap_ms=0):
    """symbol モード内で連続文字列を打つ. A1/A2 切替は python 側で分割してから呼ぶ.
    ここでは 1 ページの chars だけ扱う (呼び出し側で保証)."""
    steps = []
    def gap(ms):
        if ms > 0: steps.append({"op":"sleep","ms":ms})
    for ch in text:
        if ch == " ":
            steps.append({"op":"click","by":"id","value":KEY_SPACE})
        elif ch == "\n":
            steps.append({"op":"click","by":"id","value":KEY_ENTER})
        elif ch in SYMBOL_A1_KEYS:
            x, y = SYMBOL_A1_KEYS[ch]
            steps.append(_tap_step(x, y))
        elif ch in SYMBOL_A2_KEYS:
            x, y = SYMBOL_A2_KEYS[ch]
            steps.append(_tap_step(x, y))
        else:
            print(f"[skip symbol] no mapping: {ch!r}", file=sys.stderr)
            continue
        gap(gap_ms)
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
    """候補バーから desc が target で始まる候補を探し, 見つかればそのノードを click.

    候補バー: RecyclerView bounds [5,1420,965,1536] 内の FrameLayout, C=1.
    desc 形式: "<候補>。<漢字読み解説>..."
    """
    # region で候補バー y=[1400,1550] のみ走査 → tree DFS を早期打ち切り
    region = {"y1": 1400, "y2": 1550}
    r = send("find", {"by":"descContains","value":target + "。",
                      "region":region, "limit":1})
    matches = r.get("result",{}).get("matches",[])
    for m in matches:
        d = m.get("desc","") or ""
        if d.startswith(target + "。"):
            return m
    # target 自体で終わる場合 (最後の候補, desc に "。" が無いパターン)
    r = send("find", {"by":"descContains","value":target,
                      "region":region, "limit":1})
    for m in r.get("result",{}).get("matches",[]):
        d = m.get("desc","") or ""
        if d == target:
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
