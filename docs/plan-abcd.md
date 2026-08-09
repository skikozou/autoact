# ABCD 実装計画 — 変換高速化と汎用 a11y API 拡張

## 背景

flick の bench_convert.py で「ひらがな → 漢字変換 = 平均 940ms」の内訳が判明:

| phase | wall | server | 特記 |
|---|---:|---:|---|
| type   | 527ms | 518ms | exec 1発 (n_steps 平均 5.1, 1step ≒100ms) |
| poll   | 364ms | ―     | 全語 iter=1 で即ヒット |
| click  |  49ms |  41ms | 十分速い |
| total  | 940ms | | |

find (a11y dump) 単発: wall 364ms / server 356ms / TCP overhead 8ms

**主犯**: `find(descContains, ...)` の tree 全走査 (356ms). 副犯: exec sync の awaitRunner 200ms poll 粒度.

変換専用の hack ではなく、他の a11y 自動化 (Studyplus/chatapp 等) にも効く汎用機能として実装する。

## 計画

### A: `find` に汎用フィルタ追加

- **`region: {x1,y1,x2,y2}` (or `{x,y,w,h}`)** — node bounds が枠内でなければ walk skip
- **`ancestorId: "pkg:id/foo"`** — 指定 id のノード配下だけ探索。座標変動に強い
- **`limit=1` 早期 return を明示** — 現状 walk は limit 満たすまで tree 舐め続けるので limit=1 で 1 件で break

**汎用性**: 「特定ダイアログ/バー内で label を探す」パターンに全部効く。変換 poll 356ms → 見込み 50-100ms

### B: `waitFor` / `waitClick` — cmd + step 両対応, event mode

- **cmd `waitFor`** — 全 find 引数 + `timeoutMs` + `mode: "event"|"poll"` + `intervalMs`
- **event mode**: autoact は既に AccessibilityService。`onAccessibilityEvent` を hook して predicate match で latch.countDown。CPU polling ゼロ
- **step op `waitClick`** — waitFor 成功したノードを即 click する combo (最頻パターン)
- **step op `waitFor`** は既に Step.OP_WAIT_FOR 定数だけあった → 実装は poll のみ → event mode を追加

**汎用性**: dialog 表示待ち, progress spinner 消滅待ち, any UI transition。変換以外の中核機能

### C: `exec` response に per-step report

- opt-in `withReport: true` フラグで `steps: [{op, ok, tookMs, error?, attempts}, ...]` を返す
- ScenarioRunner が per-step 記録 → response に詰める
- bench の `--single-step` mode 廃止可能

### D: `ScenarioRunner` 完了検知を CountDownLatch 化

- 現状 `awaitRunner` が 50ms 初期 sleep + 200ms poll → 最大 250ms 粒度ロス
- ScenarioRunner に CountDownLatch. run() の finally で countDown. `awaitRunner` は `latch.await(maxMs)`
- API 表面変わらず、exec sync 使う全 client が速くなる

## 変換パスへの適用イメージ (最終形)

現状 (3 TCP roundtrip, wall 940ms):
```
Python: exec(input_steps)    → 527ms
Python: find(descContains)   → 364ms
Python: click(descContains)  → 49ms
```

改善後 (1 TCP roundtrip, wall 目標 400-600ms):
```python
send("exec", {"scenario": {"steps": [
    ...input tap/swipe steps...,
    {"op":"waitClick",
     "by":"descContains","value":"今日。",
     "region":{"y1":1400,"y2":1550},
     "timeoutMs":500,"mode":"event"}
]}, "withReport": True})
```

## 実装順序 & 進捗

順序は「独立性が高く低リスクな順」+ 「A は B の前提 (find が高速化されないと event mode の恩恵薄い)」

- [x] **D** ScenarioRunner に CountDownLatch (`awaitFinished(maxMs)`, `overallOk()`, `report()`)
- [x] **共通** 新 POJO: `FindSpec`, `WaitTask`, `StepReport`
- [x] **A** NodeFinder / ApiHandler の find に region/ancestorId/limit 早期 return
- [x] **B** ActionExecutor に waitClick 追加, waitFor に event mode. AutomationService.waiters list
- [x] **C** Scenario.withReport, ScenarioRunner が List<StepReport> 蓄積, ApiHandler.exec が response に詰める
- [x] **docs 同期**: autoact/docs/{api,steps,selectors}.md, autoact/flick/docs/ を更新
- [x] **ビルド**: `./build.sh` で通ることを確認 (2026-08-09 通過)
- [x] **実機テスト**: bench_convert.py で before/after 測定 (2026-08-09)

## 実機ベンチ結果 (2026-08-09)

10 語変換 × 各種条件比較 (poll = a11y find call):

| phase | 元 | region追加 | +limit=1 | 総 Δ |
|---|---:|---:|---:|---:|
| type wall  | 445ms | 407ms |  393ms |  -52ms |
| **poll wall** | **438ms** | 340ms | **69ms** | **-369ms (85%削減)** |
| click wall |  58ms |  64ms |   63ms |   +5ms |
| **total wall** | **955ms** | 805ms | **531ms** | **-424ms (44%削減)** |

**find server (a11y tree DFS)**: 356ms → 63ms

- region 追加のみ (`accepts` post-filter): 356 → 313ms — ほぼ効かず。原因は walk が全 node visit で subtree skip してなかった
- `FindSpec.subtreeSkippable(n)` + walk 冒頭で descend skip 追加 → 313ms (region 内候補全収集)
- `limit=1` → 63ms (1 件で即 break)

**副産物**: `find_candidate_by_prefix` が Python 側で bounds filter してた冗長ロジック削除できた

**残り改善余地** (優先度低):
- type phase 393ms — exec batch 内で 5 step 順次実行 (78ms/step)。ここは a11y click 自体の同期待ちが本質、大幅短縮は難しい
- exec 全体を 1 shot にする waitClick combo は未活用 → 更に 1 TCP roundtrip 節約可能

## 実装メモ (d8/javac 制約対応)

- **匿名内部クラス禁止** (project memory): `new Runnable() {...}` 等は NPE → 全部 top-level クラスとして定義
- **implements ジェネリックinterface禁止** (project memory): `implements Comparator<T>` 等 NPE → raw type
- 変数レベルの `List<Foo>` は OK

## ファイル一覧

**新規:**
- `src/com/example/autoact/FindSpec.java` — find/wait の filter 集約
- `src/com/example/autoact/WaitTask.java` — event/poll wait 実体
- `src/com/example/autoact/StepReport.java` — per-step report POJO

**変更:**
- `src/com/example/autoact/ScenarioRunner.java` — latch + report
- `src/com/example/autoact/Scenario.java` — withReport フラグ
- `src/com/example/autoact/Step.java` — mode/intervalMs/region/ancestorId フィールド, OP_WAIT_CLICK 定数
- `src/com/example/autoact/ScenarioParser.java` — 新フィールド parse
- `src/com/example/autoact/NodeFinder.java` — findWithSpec/findAllWithSpec
- `src/com/example/autoact/ActionExecutor.java` — waitClick / event-mode waitFor
- `src/com/example/autoact/AutomationService.java` — waiters list, event dispatch
- `src/com/example/autoact/ApiHandler.java` — find の spec 化, waitFor/waitClick cmd, exec report, awaitRunner を latch 化

**docs:**
- `docs/api.md` — find の新引数, waitFor/waitClick cmd, exec withReport
- `docs/steps.md` — waitClick 追加, waitFor に mode/intervalMs/region/ancestorId
- `docs/selectors.md` — region/ancestorId 追加
- `flick/docs/api.md` — 変換パス最適化 (もし実装するなら)
