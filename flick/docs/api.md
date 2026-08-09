# Python API リファレンス

## `flick.py` — 低レベル

### 定数

```python
IME_PKG = "com.google.android.inputmethod.latin"
KEY_ENTER = f"{IME_PKG}:id/key_pos_ime_action"
KEY_SPACE = f"{IME_PKG}:id/key_pos_space"
KEY_DEL   = f"{IME_PKG}:id/key_pos_del"
KEY_SWITCH_HIRA_ALPHA = f"{IME_PKG}:id/key_pos_switch_hiragana_alphabet"
KEY_SWITCH_SYMBOL     = f"{IME_PKG}:id/key_pos_switch_to_symbol"
KEY_SHIFT             = f"{IME_PKG}:id/key_pos_shift"
KEY_BACK_TO_PRIME     = f"{IME_PKG}:id/key_pos_back_to_prime"
KEY_RIGHT_ARROW       = f"{IME_PKG}:id/key_pos_right_arrow"  # hira mode 確定+右移動
```

### キーマップ辞書

- `CHAR_MAP` — ひらがな → `(base_key, direction, dak_dir)` の三つ組。`dak_dir` は `None`/`"L"`(濁点)/`"R"`(半濁点)/`"U"`(小文字化)。`direction` は `"C"`(tap) または `"L"/"U"/"R"/"D"`(フリック)
- `ALPHA_KEYS` — QWERTY キー座標 (英数字 + `,` `.`)
- `ALPHA_SHIFT_XY` — shift キー座標
- `ALPHA_FLICK_UP` — flick↑ で入る記号 → base 英字 の逆引き
- `SYMBOL_A1_KEYS` — 記号 A1 座標
- `SYMBOL_A2_KEYS` — 記号 A2 座標
- `SYMBOL_TOGGLE_A_B_XY` — A/B トグルキー座標 (canvas)

### 通信

```python
send(cmd, args=None, timeout=60) -> dict
```
autoact TCP API へ 1 リクエスト送信、レスポンス JSON dict を返す。

```python
load_keymap() -> dict
```
`keymap.json` (hira 12キー座標 + flick 設定) をロード。

### hira 入力

```python
build_type_steps(text, cfg, gap_ms=0) -> [Step]
```
ひらがな文字列を Step 列に変換。同キー連続時に `key_pos_right_arrow` click を挿入。
濁点/半濁点/小文字化は dak キー (`keys["dak"]["xy"]`) のフリック 1 発で行う (旧: N 回タップは廃止)。

```python
type_text(text, cfg, gap_ms=0) -> dict
```
build → exec で一括発火。

### alpha (QWERTY) 入力

```python
build_alpha_steps(text, gap_ms=0) -> [Step]
type_alpha(text, gap_ms=0) -> dict
```
英数字/記号 (flick↑ 含む) を入力。space/enter は a11y click、他は座標打鍵。

### symbol 入力

```python
build_symbol_steps(text, gap_ms=0) -> [Step]
type_symbol_page(text, gap_ms=0) -> dict
```
現在の symbol ページ (A1 or A2) で入力。呼び出し側で `ensure_symbol_A?` 済みが前提。

### モード判定・切替

```python
in_hira_mode()   -> bool   # key_pos_ja_12keys_* の存在で判定
in_alpha_mode()  -> bool   # key_pos_shift の y 位置で判定
in_symbol_mode() -> bool   # key_pos_back_to_prime の存在で判定
symbol_state()   -> 'A1'|'A2'|'B'|None
```

```python
switch_to_hira()  -> bool
switch_to_alpha() -> bool  # symbol の場合は back_to_prime 経由
switch_to_symbol() -> bool  # last-used variant
ensure_symbol_A1() -> bool
ensure_symbol_A2() -> bool
```

### 確定・候補選択

```python
commit_hiragana() -> dict          # click key_pos_ime_action (改行入らず)
commit_first_candidate() -> dict   # click key_pos_space (先頭候補)
find_candidate_by_prefix(target) -> node|None
click_candidate(node) -> dict
convert_to(target) -> dict         # 上二つの合成
```

### CLI

```bash
python3 flick.py "こんにちは"                        # 未確定入力
python3 flick.py --commit "こんにちは"               # 確定まで
python3 flick.py --convert-first "きょう"            # 先頭候補で確定
python3 flick.py --convert "きょうはあめ" "今日は雨"  # 特定候補
python3 flick.py --dry "text"                        # step JSON 出力
```

## `type_text.py` — 高レベル (混在テキスト対応)

### 主 API

```python
type_arbitrary(text, cfg, verbose=True,
               gap_ms=DEFAULT_GAP_MS  # 各打鍵 (tap/flick/click) の直後の sleep
) -> (ok:int, total:int)
```
任意文字列を mode 分割して各 mode で入力。返り値は成功セグ数/全セグ数。

`gap_ms` を上げると全打鍵がゆっくりになる (「タップしてから次のフリックまでの間」なども含む)。デフォルト 0 (最速)。目安: 少しゆっくり `--gap 50`、明確にゆっくり `--gap 150`、超スロー観察用 `--gap 400`。

**旧 `dak_gap_ms` は廃止**: 濁点を multi-tap ではなく dak キーのフリック 1 発でやるようになり、連打間隔の概念自体が無くなった。

### 内部

```python
DEFAULT_GAP_MS = 0  # 各打鍵の直後に入れる sleep ms

NEUTRAL_CHARS = ("\n", " ", "\u3000")  # 全モードで a11y click 可 → 現行モードに吸収
CANDIDATE_POLL_TIMEOUT  = 0.5   # 候補バー出現待ち上限 (秒)
CANDIDATE_POLL_INTERVAL = 0.03  # find 呼び直しの間隔

classify_char(ch, cur_mode=None) -> 'alpha'|'sym_a1'|'sym_a2'|'hira'
# 中立文字は cur_mode を返す (先頭が中立なら 'hira' 既定)
split_by_mode(text) -> [(mode, chunk), ...]
tokenize_ja(text) -> [(surface, yomi_hira), ...]  # janome (Tokenizer は singleton)
_tokenizer() -> Tokenizer   # module singleton, 初回だけ辞書ロード
_poll_candidate(orig, timeout=0.5, interval=0.03) -> node|None
    # 候補バーに出るまで find_candidate_by_prefix を polling
kata_to_hira(s) -> str  # U+30A1..U+30F6 - 0x60

is_hira_typeable(s) -> bool  # 全文字が CHAR_MAP か space/enter か

type_hira_segment(orig, hira, cfg, verbose=True, gap_ms=0) -> bool
run_hira(chunk, cfg, verbose=True, gap_ms=0) -> (ok, total)
run_alpha(text, verbose=True, gap_ms=0) -> bool
run_symbol(text, page, verbose=True, gap_ms=0) -> bool
```

`gap_ms` は最終的に `flick.build_type_steps` / `build_alpha_steps` / `build_symbol_steps` に届き、各打鍵 op の直後に `{"op":"sleep","ms":gap_ms}` を挿入する。autoact 側の `ScenarioRunner` が step 順次実行で待つので、Python 側から追加 sleep を送る形ではない (TCP 追加往復ゼロ)。

### CLI

```bash
python3 type_text.py "任意テキスト"                    # 通常実行 (最速)
python3 type_text.py --dry "任意テキスト"              # セグメント分割だけ表示
python3 type_text.py --gap 80 "少しゆっくり入力"       # 各打鍵の直後に 80ms sleep
python3 type_text.py --gap 300 "がっつりゆっくり"
```

## autoact 側 API (依存)

flick から使う主なもの:

| cmd | 用途 |
|---|---|
| `launch` | package を前面起動 (top-level cmd) |
| `top` | 現フォアグラウンド package 取得 |
| `find` | by=id/idContains/descContains/desc でノード検索 |
| `click` | a11y ACTION_CLICK |
| `swipe` | dispatchGesture 直線スワイプ |
| `exec` | scenario steps を一括実行 |

詳細: `../autoact/docs/api.md`, `../autoact/docs/selectors.md`, `../autoact/docs/steps.md`
