# Gboard の癖・落とし穴 まとめ

実験で得た「これは知らないと詰む」集。

## 1. multi-tap 誤爆 (最悪の罠)

**現象**: 同じ 12キーを連続で叩くと 2 回目が multi-tap 判定されて候補が進む。
- `かかく` を素直に打つと `きく` になる (か + か → き / flick↑ → く)

**原因**: Gboard 12キー は multi-tap 入力方式で、同キー連打で候補循環する仕様。フリック入力しか使わない場合でも、`tap` 連続はこの判定にひっかかる。

**timeout の実測**:
| gap | 結果 |
|---|---|
| 60ms  | 誤爆 (きく) |
| 280ms | 誤爆 |
| 600ms | 誤爆 |
| 1200ms | OK ✓ — でも遅すぎ |

**修正**: `key_pos_right_arrow` を挟む (未確定文字を即確定+カーソル右移動)。50ms 弱で終わる。

```python
# build_type_steps 内
if prev_key == current_key:
    steps.append({"op":"click","by":"id","value":KEY_RIGHT_ARROW})
```

## 2. symbol variant は last-used が開く

`key_pos_switch_to_symbol` は「symbol モードに入る」だけで、A1/A2/B のどれになるかは **前回開いていた variant**:

- 前回 A1 → A1
- 前回 A2 → A2
- 前回 B  → B

**対処**: 開いた直後に `symbol_state()` で確認 → 期待バリアントに手動遷移 (`ensure_symbol_A1/A2`)。

## 3. a11y に露出しないキーがある (canvas 描画)

Gboard の QWERTY / 記号 モードでは、文字キーの大半が Canvas で描画されており、a11y ノードとして存在しない。

`by=id` や `find` で得られるのは **shift/del/space/enter/mode切替/header 系** のみ (alpha モードで 7 個)。

→ 文字キーは全て **座標を実測して JSON に固定** する必要がある (`alpha_qwerty.json`, `symbol_A.json`)。

### probe 方法

編集器を用意 → 1キーずつ tap → 表示文字を読む → JSON に記録:

```python
for key, coord in candidates.items():
    clear_editor()
    tap(*coord)
    got = read_editor_text()
    if got == expected:
        results[key] = coord
```

## 4. alpha モードには hira 切替キーが無いことがある

**症状**: alpha モードでの a11y キー一覧に `key_pos_switch_hiragana_alphabet` が無い。

**発見経緯**:
- 元は「あa1」ボタンで hira ↔ alpha 往復できた
- 何かの Gboard 設定変更で alpha 側からその物理キーが消えた
- space バーが「QWERTY」表示に、globe が表示 → 言語切替は long-press space か globe から行う仕様

**現状**: alpha → hira の直接切替が壊れる (`switch_to_hira` が空振り)。
**回避策候補**:
- long-press space (2秒 hold) → 言語選択ポップアップ → タップ
- globe icon (971, 2317) 経由の IME picker
- Gboard 設定で「あA」キー復活

未修正。テスト時は最初に hira にしておいて alpha に行き、再度 hira 復帰は `key_pos_switch_hiragana_alphabet` で戻る (hira→alpha→hira は可能)。

## 5. space の長押しは「キーボード変更」ダイアログを出す

**症状**: space キーを長押しすると Gboard が「キーボードの変更」IME picker を出す (Android 標準挙動)。**enter や他キーはこの副作用なし** — space 限定。

**注意点**: `dispatchGesture` の point-stroke は Android 内部で最低 330ms かかる (§9)。座標打鍵で space を叩くとこの 330ms が Gboard から長押しと判定され、ダイアログが出てしまう。

**回避**: space は `click by=id` (a11y ACTION_CLICK) で叩けば副作用ゼロ。ついでにモード切替キー (`switch_hiragana_alphabet`, `switch_to_symbol`, `back_to_prime`, `shift`, `del`, `ime_action`) も a11y click 可能なら全部 click 経由が安全 (canvas 描画されたキーではないため id で引ける)。

**enter**: `key_pos_ime_action` を a11y click しているが、これは主に「改行を送る」目的で、長押し副作用の回避ではない。座標打鍵でも問題は起きない (enter は長押しに特別な挙動を持たない)。

**文字入力キー**: canvas 描画で a11y に無い → 座標打鍵必須。space 以外は 330ms tap されても副作用なし。

## 6. symbol モードの back_to_prime は「直前 mode」に戻る

`key_pos_back_to_prime` は hira とは限らず、symbol に入る前の mode (alpha or hira) に戻る。

→ symbol 抜けたら必ず `in_hira_mode()/in_alpha_mode()` で再判定して、意図と違えば追加の switch。

## 7. モード切替後の settle が必要

click 直後に次の操作を叩くと、古い座標系で発火することがある。実測で 400ms 待つと安定。

```python
send("click", ...)
time.sleep(0.4)  # settle
```

## 8. 候補バーの desc 形式

- desc は `"<候補>。<各文字の読み解説>"`
- 例: `"今日は雨。コンゲツ ノ コン，イマ。オンナ，ジョセイ。…"`
- 単字候補や最後の候補は `。` が付かないことがある → fallback で `desc == target` も見る

`by=descContains` は候補バー外の他ノードにもヒットする → **bounds y が 1400-1550 内か** で絞る。

## 9. dispatchGesture の tap は 330ms

point-stroke gesture は Android 内部で最低 330ms かかる。1px swipe (durMs=30) にすると 90ms 前後に短縮できる。

`_tap_step(cx, cy)` は 1px swipe で実装:
```python
{"op":"swipe","x1":cx,"y1":cy,"x2":cx,"y2":cy+1,"durMs":30}
```

## 10. autoact の `launchApp` op は存在しない

**症状**: scenario steps 内で `{"op":"launchApp","package":"..."}` を書いてもエラーにならないが起動もしない。

**原因**: `launchApp` は Step の op として ActionExecutor に無い。トップレベル cmd (`launch`) のみ。

**正しい書き方**:
```python
send("launch", {"package":"ru.androidtools.texteditor"})  # ← トップレベル cmd
# ではなく
send("exec", {"scenario":{"steps":[{"op":"launchApp",...}]}})  # ← 空振り
```

foreground アプリの確認は `send("top", {})` の result.package。

## 11. 濁点キーは直前文字を変換 — フリック 3 方向で 1 発

`dak_xy` は N 回タップの循環キーだが、**フリックすると 1 発で目的の変換が確定**する:

| フリック | 効果 | 例 |
|---|---|---|
| ← (L) | 濁点付与 | は→ば, か→が, う→ゔ, つ→づ |
| → (R) | 半濁点付与 | は→ぱ |
| ↑ (U) | 小文字化 | あ→ぁ, や→ゃ, つ→っ, う→ぅ, わ→ゎ |
| ↓ (D) | (未使用) | — |

本パッケージは全てフリック方式で発火する (`CHAR_MAP` の `dak_dir` に `L/R/U` を格納)。旧実装の「N 回タップで循環」は multi-tap 誤爆リスクがあり撤去済。

一部文字 (な行/ま行/ら行) は dak 対応が無い (フリックしても不変)。

## 12. `key_pos_ja_12keys_*` は hira mode 判定に使える

hira モードで存在する `key_pos_ja_12keys_1..12` を `idContains "key_pos_ja_12keys"` で検索、1 件でも出たら hira 確定。alpha/symbol では出ない。
