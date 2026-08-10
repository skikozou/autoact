#!/usr/bin/env python3
"""任意テキスト (漢字/カナ/ひらがな/英数字/記号) を Gboard フリック/QWERTY で入力.

char-level にモード分類 (hira / alpha / sym_a1 / sym_a2) → 隣接同モードを run に集約
→ run 単位で mode 切替 & 入力.

hira run のみ janome で形態素解析して変換.
"""
import argparse
import sys
import time

from janome.tokenizer import Tokenizer

import flick

_TOKENIZER = None

def _tokenizer():
    """janome Tokenizer の module singleton. 初回のみ辞書ロード (1-2秒)."""
    global _TOKENIZER
    if _TOKENIZER is None:
        _TOKENIZER = Tokenizer()
    return _TOKENIZER

KATAKANA_RANGE = (0x30A1, 0x30F6)

def kata_to_hira(s):
    out = []
    for ch in s:
        c = ord(ch)
        if KATAKANA_RANGE[0] <= c <= KATAKANA_RANGE[1]:
            out.append(chr(c - 0x60))
        else:
            out.append(ch)
    return "".join(out)

NEUTRAL_CHARS = ("\n", " ", "\u3000")

def classify_char(ch, cur_mode=None):
    """1文字を mode に分類 (優先: alpha > sym_a1 > sym_a2 > hira).

    space/newline は全モードで a11y click できるので中立扱いにし,
    直前 mode に吸収する (mode 切替を減らして高速化).
    """
    if ch in NEUTRAL_CHARS:
        return cur_mode or "hira"
    if ch in flick.ALPHA_KEYS: return "alpha"
    if ch.isupper() and ch.lower() in flick.ALPHA_KEYS: return "alpha"
    if ch in flick.ALPHA_FLICK_UP: return "alpha"
    if ch in flick.SYMBOL_A1_KEYS: return "sym_a1"
    if ch in flick.SYMBOL_A2_KEYS: return "sym_a2"
    return "hira"

def split_by_mode(text):
    """[(mode, chunk_text)] に分割 (隣接同モードは結合)."""
    segments = []
    cur_mode, cur_buf = None, []
    for ch in text:
        m = classify_char(ch, cur_mode)
        if m == cur_mode:
            cur_buf.append(ch)
        else:
            if cur_buf:
                segments.append((cur_mode, "".join(cur_buf)))
            cur_mode = m
            cur_buf = [ch]
    if cur_buf:
        segments.append((cur_mode, "".join(cur_buf)))
    return segments

def tokenize_ja(text):
    """(surface, yomi_hira) のリスト. Tokenizer は singleton で再利用."""
    result = []
    for tok in _tokenizer().tokenize(text):
        surface = tok.surface
        reading = tok.reading if tok.reading != "*" else surface
        hira = kata_to_hira(reading)
        result.append((surface, hira))
    return result

def is_hira_typeable(s):
    for ch in s:
        if ch in flick.CHAR_MAP: continue
        if ch in (" ", "\u3000", "\n"): continue
        return False
    return bool(s)

CANDIDATE_POLL_TIMEOUT = 0.5   # 候補バー出現待ちの上限
CANDIDATE_POLL_INTERVAL = 0.03 # a11y find 1 発 ~15-30ms

def _poll_candidate(orig, timeout=CANDIDATE_POLL_TIMEOUT, interval=CANDIDATE_POLL_INTERVAL):
    """候補バーに orig で始まる候補が出るまで短間隔で polling.
    出現時刻が変動しても最短で捕まえる. 出なければ None."""
    t0 = time.time()
    while True:
        node = flick.find_candidate_by_prefix(orig)
        if node:
            return node
        if time.time() - t0 >= timeout:
            return None
        time.sleep(interval)

# gap_ms: 各打鍵 (tap/flick/click) の直後に挟む sleep ms. 0 で最速.
# 濁点も 1 フリックで済むため, かつての dak_gap_ms は廃止.
DEFAULT_GAP_MS = 0

def type_hira_segment(orig, hira, cfg, verbose=True, gap_ms=DEFAULT_GAP_MS):
    if not is_hira_typeable(hira):
        if verbose:
            print(f"  [skip hira] {orig!r} yomi={hira!r}", file=sys.stderr)
        return False
    r = flick.type_text(hira, cfg, gap_ms=gap_ms)
    if not r.get("ok"):
        if verbose: print(f"  [type failed] {orig!r}: {r}", file=sys.stderr)
        return False
    # neutral (space/newline) は build_type_steps 内で確定済みなので commit_hiragana 不要.
    # 二重に KEY_ENTER click すると余分な改行が入る.
    if all(c in NEUTRAL_CHARS for c in orig):
        return True
    # pure hira トークン (orig == hira) は候補バーを参照しないので即 commit.
    if orig == hira:
        rr = flick.commit_hiragana()
        return rr.get("ok", False)
    # 漢字変換: 候補バー出現を polling (以前の time.sleep(0.25) 固定を置換).
    node = _poll_candidate(orig)
    if node:
        rr = flick.click_candidate(node)
        if rr.get("ok"):
            if verbose: print(f"  [conv] {hira} → {orig}")
            return True
    if verbose:
        print(f"  [conv failed] {orig!r} not in top", file=sys.stderr)
    flick.commit_hiragana()
    return False

def run_alpha(text, verbose=True, gap_ms=DEFAULT_GAP_MS):
    if not flick.ensure_mode("alpha"):
        if verbose: print("  [!!] ensure_mode(alpha) failed", file=sys.stderr)
        return False
    r = flick.type_alpha(text, gap_ms=gap_ms)
    if verbose: print(f"  [alpha] {text!r} ok={r.get('ok')}")
    return r.get("ok", False)

def run_symbol(text, page, verbose=True, gap_ms=DEFAULT_GAP_MS):
    target = "sym_A1" if page == "A1" else "sym_A2"
    if not flick.ensure_mode(target):
        if verbose: print(f"  [!!] ensure_mode({target}) failed", file=sys.stderr)
        return False
    r = flick.type_symbol_page(text, gap_ms=gap_ms)
    if verbose: print(f"  [{page}] {text!r} ok={r.get('ok')}")
    return r.get("ok", False)

def run_hira(chunk, cfg, verbose=True, gap_ms=DEFAULT_GAP_MS):
    if not flick.ensure_mode("hira"):
        if verbose: print("  [!!] ensure_mode(hira) failed", file=sys.stderr)
        return 0, 0
    ok, total = 0, 0
    for s, h in tokenize_ja(chunk):
        total += 1
        if type_hira_segment(s, h, cfg, verbose, gap_ms=gap_ms): ok += 1
    return ok, total

def type_arbitrary(text, cfg, verbose=True, gap_ms=DEFAULT_GAP_MS):
    """任意テキストを Gboard に流し込む.

    gap_ms: 各打鍵 (tap / flick / click) の直後に挿入する sleep ms.
            大きくすると全体がゆっくりになる. 0 で最速.
    """
    segments = split_by_mode(text)
    if verbose:
        print(f"[segments={len(segments)}] gap_ms={gap_ms}")
        for m, c in segments:
            print(f"  ({m}) {c!r}")
    ok, total = 0, 0
    for mode, chunk in segments:
        if mode == "hira":
            o, t = run_hira(chunk, cfg, verbose, gap_ms=gap_ms)
            ok += o; total += t
        elif mode == "alpha":
            total += 1
            if run_alpha(chunk, verbose, gap_ms=gap_ms): ok += 1
        elif mode == "sym_a1":
            total += 1
            if run_symbol(chunk, "A1", verbose, gap_ms=gap_ms): ok += 1
        elif mode == "sym_a2":
            total += 1
            if run_symbol(chunk, "A2", verbose, gap_ms=gap_ms): ok += 1
    # 終了時は hira に戻す
    flick.ensure_mode("hira")
    return ok, total

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("text")
    ap.add_argument("--dry", action="store_true", help="セグメント分割のみ表示")
    ap.add_argument("--gap", type=int, default=DEFAULT_GAP_MS,
                    help=f"各打鍵の直後に入れる sleep ms (default {DEFAULT_GAP_MS}). "
                         f"大きくするとゆっくり入力. 例: --gap 80")
    args = ap.parse_args()

    if args.dry:
        for m, c in split_by_mode(args.text):
            print(f"  ({m}) {c!r}")
        return

    cfg = flick.load_keymap()
    t0 = time.time()
    ok, total = type_arbitrary(args.text, cfg, gap_ms=args.gap)
    dt = time.time() - t0
    print(f"[done] {ok}/{total} in {dt:.2f}s")

if __name__ == "__main__":
    main()
