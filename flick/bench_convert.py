#!/usr/bin/env python3
"""ひらがな→漢字変換のフェーズ&オペ別 精密計測ベンチマーク.

計測粒度:
  - 個別 TCP call: wall_ms (Python perf_counter) / server_ms (autoact `tookMs`)
    * tcp_overhead_ms = wall_ms - server_ms
  - phase 1 type   : exec 1 発 (steps 一括) の wall/server, ステップ数, 1step あたり
      `--single-step` 時は steps を 1 個ずつ送って個別 op 別に集計
  - phase 2 poll   : 各 iter で {find 呼び回数 (1 or 2), 各 find の wall/server, iter wall,
      直後 sleep 実測} を記録
  - phase 3 click  : 候補ノード a11y click の wall/server

autoact 側 `exec` (sync) は awaitRunner が 50ms 初期 sleep + 最大 200ms poll 粒度で
scenario 完了を待つ. → exec の server_ms は「actual scenario 時間 + <200ms」の上限.
scenario 中身の細かい breakdown が要るなら `--single-step` で個別送信 mode を使う.

Usage:
  python3 bench_convert.py                 # 既定 10 語, batched exec
  python3 bench_convert.py --single-step   # type phase を step 個別送信で計測
  python3 bench_convert.py -v              # 各 poll iter の内訳も表示
  python3 bench_convert.py --words 今日:きょう 明日:あした
"""
import argparse
import statistics
import sys
import time

import flick
import type_text as tt


_send_orig = flick.send
# 直近 send log: (cmd, wall_ms, server_ms|None). bench_one が phase 境界で clear.
_send_log = []


def _timed_send(cmd, args=None, timeout=60):
    t0 = time.perf_counter()
    r = _send_orig(cmd, args, timeout)
    wall = (time.perf_counter() - t0) * 1000.0
    server = r.get("tookMs") if isinstance(r, dict) else None
    _send_log.append((cmd, wall, server))
    return r


flick.send = _timed_send


def clear_editor(n=40):
    """del を N 回 a11y click. bench 対象外."""
    for _ in range(n):
        _send_orig("click", {"by": "id", "value": flick.KEY_DEL})


def _fmt_ov(wall, server):
    """wall/server/overhead 3 値の 1 行表示."""
    if server is None:
        return f"wall={wall:6.1f}ms server=??      ov=??"
    return f"wall={wall:6.1f}ms server={server:5}ms ov={wall-server:5.1f}ms"


def bench_type_batched(hira, cfg):
    """type_text を 1 発の exec で送る (実運用と同じパス)."""
    _send_log.clear()
    t0 = time.perf_counter()
    r = flick.type_text(hira, cfg, gap_ms=0)
    wall = (time.perf_counter() - t0) * 1000.0
    # 直前の send は exec 1 発のはず
    execs = [x for x in _send_log if x[0] == "exec"]
    server = execs[-1][2] if execs else None
    return {
        "ok": r.get("ok", False),
        "mode": "batched",
        "wall_ms": wall,
        "server_ms": server,
        "n_steps": len(flick.build_type_steps(hira, cfg, gap_ms=0)),
        "per_step_calls": None,
    }


def bench_type_single_step(hira, cfg):
    """type steps を 1 個ずつ exec で送る (op ごとの実時間を測る)."""
    steps = flick.build_type_steps(hira, cfg, gap_ms=0)
    per = []  # [(op, wall_ms, server_ms)]
    t0 = time.perf_counter()
    ok_all = True
    for st in steps:
        s0 = time.perf_counter()
        rr = _send_orig("exec", {"scenario": {"name": "one", "steps": [st]}}, 60)
        s_wall = (time.perf_counter() - s0) * 1000.0
        s_srv = rr.get("tookMs") if isinstance(rr, dict) else None
        per.append((st.get("op"), s_wall, s_srv))
        if not rr.get("ok"):
            ok_all = False
            break
    total_wall = (time.perf_counter() - t0) * 1000.0
    return {
        "ok": ok_all,
        "mode": "single",
        "wall_ms": total_wall,
        "server_ms": sum(x[2] for x in per if x[2] is not None) or None,
        "n_steps": len(steps),
        "per_step_calls": per,
    }


def bench_one(surface, hira, cfg, single_step=False):
    if not tt.is_hira_typeable(hira):
        return None

    # --- phase 1: type ---
    type_result = (bench_type_single_step if single_step else bench_type_batched)(hira, cfg)
    if not type_result["ok"]:
        return {"ok": False, "surface": surface, "hira": hira, "type": type_result}

    # --- phase 2: poll ---
    iters = []  # dict per iter: {finds: [(wall,server)], iter_wall, sleep_wall, hit}
    t_poll_start = time.perf_counter()
    node = None
    while True:
        _send_log.clear()
        it0 = time.perf_counter()
        n = flick.find_candidate_by_prefix(surface)
        it_wall = (time.perf_counter() - it0) * 1000.0
        finds = [(w, s) for c, w, s in _send_log if c == "find"]
        iter_rec = {"finds": finds, "iter_wall_ms": it_wall,
                    "sleep_wall_ms": 0.0, "hit": n is not None}
        iters.append(iter_rec)
        if n is not None:
            node = n
            break
        if time.perf_counter() - t_poll_start >= 0.5:
            break
        sp0 = time.perf_counter()
        time.sleep(0.03)
        iter_rec["sleep_wall_ms"] = (time.perf_counter() - sp0) * 1000.0
    t_poll = (time.perf_counter() - t_poll_start) * 1000.0

    # --- phase 3: click ---
    click_wall = 0.0
    click_server = None
    if node is not None:
        _send_log.clear()
        c0 = time.perf_counter()
        flick.click_candidate(node)
        click_wall = (time.perf_counter() - c0) * 1000.0
        clicks = [x for x in _send_log if x[0] == "click"]
        click_server = clicks[-1][2] if clicks else None
    else:
        _send_orig("click", {"by": "id", "value": flick.KEY_ENTER})

    # --- cleanup ---
    clear_editor(len(surface) + len(hira) + 6)

    total_wall = type_result["wall_ms"] + t_poll + click_wall

    return {
        "ok": node is not None,
        "surface": surface,
        "hira": hira,
        "type": type_result,
        "poll": {
            "wall_ms": t_poll,
            "iters": iters,
        },
        "click": {"wall_ms": click_wall, "server_ms": click_server},
        "total_wall_ms": total_wall,
    }


DEFAULT_WORDS = [
    ("今日", "きょう"),
    ("明日", "あした"),
    ("学校", "がっこう"),
    ("会社", "かいしゃ"),
    ("時間", "じかん"),
    ("問題", "もんだい"),
    ("大丈夫", "だいじょうぶ"),
    ("東京", "とうきょう"),
    ("日本", "にほん"),
    ("電話", "でんわ"),
]


def _stat(vals, unit="ms"):
    if not vals:
        return "(none)"
    return (f"med={statistics.median(vals):6.1f}  avg={statistics.mean(vals):6.1f}  "
            f"min={min(vals):6.1f}  max={max(vals):6.1f}  n={len(vals)} {unit}")


def print_word_detail(r, verbose):
    if not r.get("ok") and "type" not in r:
        print(f"  {r.get('surface')}: SKIP")
        return
    surface = r["surface"]; hira = r["hira"]
    t = r["type"]
    print(f"\n[{surface} / {hira}]  total_wall={r.get('total_wall_ms', 0):.1f}ms  {'OK' if r['ok'] else 'FAIL'}")
    n_steps = t["n_steps"]
    per_step_server = (t["server_ms"] / n_steps) if (t["server_ms"] and n_steps) else None
    ps = f"  per_step_server={per_step_server:.1f}ms" if per_step_server else ""
    print(f"  type[{t['mode']}]  {_fmt_ov(t['wall_ms'], t['server_ms'])}  n_steps={n_steps}{ps}")
    if verbose and t.get("per_step_calls"):
        for i, (op, w, s) in enumerate(t["per_step_calls"]):
            svr = f"{s:5}" if s is not None else "  ?  "
            print(f"    step[{i:2}] {op:<6}  wall={w:6.1f}ms  server={svr}ms  ov={(w-(s or 0)):5.1f}ms")

    poll = r.get("poll") or {}
    print(f"  poll         wall={poll.get('wall_ms', 0):6.1f}ms  n_iter={len(poll.get('iters', []))}")
    if verbose:
        for i, it in enumerate(poll.get("iters", [])):
            finds_desc = ", ".join(f"({w:.1f}/{s if s is not None else '?'})" for w, s in it["finds"])
            mark = " <- HIT" if it["hit"] else ""
            print(f"    iter[{i}] iter_wall={it['iter_wall_ms']:6.1f}ms  "
                  f"finds(wall/srv)={finds_desc}  sleep={it['sleep_wall_ms']:5.1f}ms{mark}")

    c = r.get("click") or {}
    print(f"  click        {_fmt_ov(c.get('wall_ms', 0), c.get('server_ms'))}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("-v", "--verbose", action="store_true",
                    help="poll iter/step 個別ログも表示")
    ap.add_argument("--single-step", action="store_true",
                    help="type phase を step 個別送信で計測 (per-op 内訳が取れる)")
    ap.add_argument("--words", nargs="*", help="surface:yomi ペア")
    ap.add_argument("--pre-clear", type=int, default=30)
    args = ap.parse_args()

    if args.words:
        words = [tuple(w.split(":", 1)) for w in args.words]
    else:
        words = DEFAULT_WORDS

    cfg = flick.load_keymap()

    if not flick.ensure_mode("hira"):
        print("[fatal] hira mode に入れない", file=sys.stderr)
        return 1

    print(f"== pre-clear {args.pre_clear} × del ==", file=sys.stderr)
    clear_editor(args.pre_clear)

    results = []
    hdr = (f"{'word':<8} {'hira':<10} "
           f"{'type_w':>8} {'type_s':>8} {'poll_w':>8}({'#':>2}) "
           f"{'click_w':>8} {'click_s':>8} {'total':>8} ok")
    print(hdr); print("-" * len(hdr))
    for surface, hira in words:
        r = bench_one(surface, hira, cfg, single_step=args.single_step)
        if r is None:
            print(f"{surface}: skip (not hira typeable)", file=sys.stderr)
            continue
        t = r["type"]; c = r.get("click", {}); p = r.get("poll", {})
        srv = (f"{t['server_ms']:>6}" if t['server_ms'] is not None else "     ?")
        csv = (f"{c.get('server_ms'):>6}" if c.get('server_ms') is not None else "     ?")
        print(f"{surface:<8} {hira:<10} "
              f"{t['wall_ms']:>7.1f}ms {srv}ms "
              f"{p.get('wall_ms', 0):>6.1f}ms({len(p.get('iters', [])):>2}) "
              f"{c.get('wall_ms', 0):>6.1f}ms {csv}ms "
              f"{r.get('total_wall_ms', 0):>6.1f}ms {'OK' if r['ok'] else 'FAIL'}")
        results.append(r)
        time.sleep(0.15)

    ok_r = [r for r in results if r.get("ok")]

    if args.verbose:
        for r in results:
            print_word_detail(r, verbose=True)

    if not ok_r:
        print("\n(no success — summary skip)", file=sys.stderr)
        return 1

    print("\n== phase 統計 (ok only) ==")
    print(f"  type wall  : {_stat([r['type']['wall_ms'] for r in ok_r])}")
    print(f"  type srv   : {_stat([r['type']['server_ms'] for r in ok_r if r['type']['server_ms'] is not None])}")
    print(f"  type n_stp : {_stat([r['type']['n_steps'] for r in ok_r], unit='steps')}")
    print(f"  poll wall  : {_stat([r['poll']['wall_ms'] for r in ok_r])}")
    print(f"  poll iters : {_stat([len(r['poll']['iters']) for r in ok_r], unit='iters')}")
    print(f"  click wall : {_stat([r['click']['wall_ms'] for r in ok_r])}")
    print(f"  click srv  : {_stat([r['click']['server_ms'] for r in ok_r if r['click']['server_ms'] is not None])}")
    print(f"  total wall : {_stat([r['total_wall_ms'] for r in ok_r])}")

    # find 単体
    all_finds_wall = []
    all_finds_srv = []
    for r in ok_r:
        for it in r["poll"]["iters"]:
            for w, s in it["finds"]:
                all_finds_wall.append(w)
                if s is not None:
                    all_finds_srv.append(s)
    if all_finds_wall:
        print(f"\n  find wall  : {_stat(all_finds_wall)}  <- 単一 find TCP roundtrip 実測")
        if all_finds_srv:
            print(f"  find srv   : {_stat(all_finds_srv)}  <- a11y dump 実処理")
            ov = [w - s for r in ok_r for it in r["poll"]["iters"]
                  for (w, s) in it["finds"] if s is not None]
            print(f"  find ov    : {_stat(ov)}  <- TCP+encode/decode オーバーヘッド")

    # single-step 詳細
    if args.single_step and ok_r:
        by_op = {}
        for r in ok_r:
            for op, w, s in r["type"]["per_step_calls"] or []:
                by_op.setdefault(op, []).append((w, s))
        print("\n  == type step 別 (single-step mode) ==")
        for op, entries in sorted(by_op.items()):
            wal = [e[0] for e in entries]
            srv = [e[1] for e in entries if e[1] is not None]
            print(f"    {op:<8} wall  {_stat(wal)}")
            if srv:
                print(f"    {op:<8} srv   {_stat(srv)}")

    return 0


if __name__ == "__main__":
    sys.exit(main())
