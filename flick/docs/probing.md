# probe スクリプトの使い方

キー座標や挙動を実機で確かめて JSON に書き出すツール群。**別解像度/別 Gboard バージョンに移す時は再実行**。

## 共通前提

- autoact 稼働 (`127.0.0.1:8765`)
- テスト用テキストエディタ (`ru.androidtools.texteditor`) が起動、EditText focus 済み
- Gboard が対象モードで表示されている (自動遷移するものもある)

編集器起動:
```python
flick.send("launch", {"package":"ru.androidtools.texteditor"})
```

## `probe.py` — hira 12キー × 5方向

各キー中心を中心に、C (tap)/L/U/R/D の 5 パターンを試して結果を記録:

```bash
python3 probe.py
# → keymap.json.new に書き出し
```

出力: `{key: {C:..., L:..., U:..., R:..., D:...}}` の 55 パターン。CHAR_MAP の生成元。

## `probe_dakuten.py` — 濁点キー挙動 (歴史的)

**注**: 旧実装 (dak を N 回タップで循環) 用の probe。現行 `CHAR_MAP` は dak キーのフリック 3 方向 (L=濁点/R=半濁点/U=小文字化) で 1 発変換するため、このスクリプトは参考データ用途のみ。

各文字入力後に濁点キーを N 回叩いてサイクル探索:

```bash
python3 probe_dakuten.py
```

か + dak×1..3、は + dak×1..3、つ + dak×1..3 等を試して:
- ×1 → 濁点付き
- ×2 → 半濁点 or 小文字 or 元
- ×3 → 循環パターン

を記録。旧 `dak_count` 欄の根拠データだった。フリック方向の検証には別途 dak 中心を L/R/U/D にフリックして直前文字がどう変わるかを目視で確認する probe が必要 (現状未整備)。

## `probe_modes.py` — 各モードの a11y キー一覧

hira / alpha / symbol の 3 モード全てで全 `key_pos_*` ノードを dump:

```bash
python3 probe_modes.py
# → modes_dump.json
```

各キーの id / bounds / desc がわかる。**「alpha にはどの key_pos が露出しているか」** を確認するのに必須 (canvas 描画で a11y に無いキーが多いため)。

## `probe_alpha.py` — QWERTY 座標検証

事前に推定した 36 座標を実際に tap して、期待文字が出るか確認:

```bash
python3 probe_alpha.py
# → alpha_qwerty.json (座標確定版)
```

推定は screenshot からの目視。probe で 100% 一致するまで座標を微調整。

## `probe_alpha_flick.py` — 上フリック上添字

各 QWERTY キーで上方向フリック (durMs=60, dy=-100) を試して、出た記号を記録:

```bash
python3 probe_alpha_flick.py
# → alpha_flick_up.json (24 記号のマップ)
```

期待値は screenshot 目視。実測で 24/26 一致 (2 個は screenshot 読み間違い、実際は正しい)。

## `probe_symbol.py` — 記号 A1 / A2 全座標

symbol mode に入って A1/A2 両方を全キー tap して座標確定:

```bash
python3 probe_symbol.py
# → symbol_A.json ({A1: {...}, A2: {...}})
```

内部フロー:
1. hira mode 確認 → click switch_to_symbol
2. `normalize_to_A1`:
   - B なら canvas (325,2170) tap で A へ
   - A2 なら shift tap で A1 へ
3. A1 全 27 キー probe → 27/27 期待
4. shift click で A2 へ → A2 全 27 キー probe → 26/27 (`•` は目視で `·` と誤読していた)
5. cleanup: del 連打, back_to_prime click, switch_to_hira

## 出力ファイル一覧

| ファイル | 生成元 | 使う側 |
|---|---|---|
| `keymap.json` | probe.py + probe_dakuten.py | `flick.load_keymap()` |
| `alpha_qwerty.json` | probe_alpha.py | `flick.ALPHA_KEYS` (現状はソース内定数化済み) |
| `alpha_flick_up.json` | probe_alpha_flick.py | `flick.ALPHA_FLICK_UP` (同上) |
| `symbol_A.json` | probe_symbol.py | `flick.SYMBOL_A1_KEYS`, `SYMBOL_A2_KEYS` (同上) |
| `modes_dump.json` | probe_modes.py | 参照用 (どのモードに何が出るか) |

現状の flick.py は座標を **ソース内定数** として持っている (JSON をロードしない)。probe 結果を貼り直したい時は `flick.py` の該当 dict を手動更新。

## 新解像度への移植手順

1. `screen` cmd で解像度確認: `send("screen", {})`
2. `probe_modes.py` で各モードの `key_pos_*` の bounds を得る → hira 12キー座標は自動化可能
3. `probe.py`, `probe_dakuten.py` を実行して hira 側完成
4. alpha/symbol の推定座標は screenshot 目視で用意
5. `probe_alpha.py`, `probe_alpha_flick.py`, `probe_symbol.py` で微調整
6. flick.py の座標定数を新値に置換

律速は screenshot からの目視推定。probe 自動微調整 (±5px 探索) を書けば全自動化できるが未実装。
