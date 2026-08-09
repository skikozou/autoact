# 入力パイプライン

`type_text.py type_arbitrary(text, cfg)` の内部処理。

## 段階

```
text (str)
  ↓  1. classify_char + split_by_mode
segments: [(mode, chunk), ...]
  ↓  2. run_hira / run_alpha / run_symbol (mode 毎)
  ↓
  ├─ hira:  tokenize_ja → 各 (surface, yomi) を type_hira_segment
  ├─ alpha: switch_to_alpha → type_alpha (build_alpha_steps)
  └─ sym*:  ensure_symbol_A? → type_symbol_page
  ↓
  各 build_*_steps: [Step JSON] を組み立て
  ↓  3. autoact `exec` で 1 コネクション内で一括発火
```

## 1. モード分類 (classify_char)

各文字を **優先順位** で分類:

```python
NEUTRAL_CHARS = ("\n", " ", "\u3000")

def classify_char(ch, cur_mode=None):
    if ch in NEUTRAL_CHARS: return cur_mode or "hira"  # 直前 mode に吸収 (中立)
    if ch in ALPHA_KEYS: return "alpha"                # 数字/英字/,/.
    if ch.isupper() and ch.lower() in ALPHA_KEYS: return "alpha"
    if ch in ALPHA_FLICK_UP: return "alpha"            # flick-up 記号 (@#%等)
    if ch in SYMBOL_A1_KEYS: return "sym_a1"           # &+-() 等
    if ch in SYMBOL_A2_KEYS: return "sym_a2"           # ${}等
    return "hira"
```

**優先度がキモ**: alpha を最優先にすると `@ # $ ¥` などが flick-up で打てて symbol モード切替を回避できる。

**中立文字 (`\n`, 半角/全角スペース) は現行モードに吸収する**。`key_pos_ime_action` (Enter) と `key_pos_space` は全モードで a11y click できるので、hira 文の途中の改行/スペースで alpha に切り替える必要がない (以前は毎回 `switch_to_alpha` が走って ~400ms×N の無駄が出ていた)。中立文字だけで始まるテキストは hira を既定にする。

### 実測: どの記号がどの経路で速いか

| 記号 | 経路 | 備考 |
|---|---|---|
| `@ # $ % & - + ( )` | alpha flick-up | symbol 切替不要 |
| `¥` | alpha flick-up (d↑) | |
| `~ £ € ¢ ° = { }` | symbol A2 のみ | flick-up には無い |
| `$` | ⚠ **flick-up には無い、symbol A2 のみ** | 実測: `$` は SYMBOL_A2_KEYS |
| `1234567890` | alpha 直接 | 数字は QWERTY 上段 |
| `.,` | alpha 直接 | canvas キー |

→ classify で ALPHA を最優先 → 大半は alpha 単独。symbol A2 に落ちるのは `$ ~ £` 等の少数派のみ。

## 2. セグメント結合 (split_by_mode)

隣接同 mode の文字を 1 chunk に結合。中立文字 (`\n`, ` `, `\u3000`) は直前 mode に吸収:

```python
"価格 $100 と ¥1,500"
→ [
    ("hira",   "価格 "),        # 半角スペースを hira に吸収
    ("sym_a2", "$"),
    ("alpha",  "100 "),         # 半角スペースを alpha に吸収
    ("hira",   "と "),
    ("alpha",  "¥1,500"),
]
```

5 セグメント (以前は 6)。改行を含む複数行日本語 `"一行目\n二行目"` は `[("hira","一行目\n二行目")]` の 1 セグメントで済み、`\n` のたびに alpha へ切り替わる旧挙動を回避する。

各 mode 遷移が 400ms 弱かかるので、セグメント数最小化が速度に直結。

## 3. hira 入力 (run_hira → type_hira_segment)

hira chunk はさらに janome で **形態素分割**:

```python
tokenize_ja("価格と") → [
    ("価格", "かかく"),  # (surface, yomi_hira)
    ("と",   "と"),
]
```

各 token 単位で:
1. `flick.type_text(yomi_hira, cfg)` → build_type_steps で step 列を組み立て、autoact に一括送信
2. 分岐:
   - `orig` が中立文字 (`\n`/` `/`\u3000`) のみ → `build_type_steps` 内で既に KEY_ENTER/KEY_SPACE click 済み。**commit_hiragana は呼ばない** (二重に Enter を click すると余分な改行が入るため)
   - `orig == yomi` (漢字化不要) → **即** `commit_hiragana()` (Enter クリック)。sleep 不要 (候補バーを参照しないため)
   - else → **poll**: 候補バーに `orig` で始まる候補が出るまで `find_candidate_by_prefix` を `CANDIDATE_POLL_INTERVAL=30ms` 間隔で最大 `CANDIDATE_POLL_TIMEOUT=500ms` 呼び続ける。出たら click
3. 候補が無ければ `commit_hiragana()` でひらがなのまま確定 (フォールバック)

### なぜ polling か

以前は `time.sleep(0.25)` 固定 → 全 token で 250ms 無駄待ち。実際の候補バー出現は速いときは 50-100ms、遅いときで 300ms 超と幅がある。
poll (30ms 間隔) にすると:
- 早く出た token では ~50ms で捕まえ、250ms 固定より最大 200ms 節約
- 出るのが遅い token でも上限 500ms まで自動延長 (取りこぼし率も下がる)
- a11y `find` 1 発は ~15-30ms なので poll ループ自体のコストは小さい

pure hira トークン (`orig == yomi`) は候補選択しないので sleep も poll も不要 → 即 commit で 250ms まるごと節約。

## 4. hira 12キー step 生成 (build_type_steps)

```python
for ch in text:
    key, direction, dak_dir = CHAR_MAP[ch]  # 例: "か"→("ka","C",None), "ぱ"→("ha","C","R")
    cx, cy = keys[key]["xy"]

    # ★同じ base key 連続なら right_arrow 挟む (multi-tap 回避、後述)
    if prev_key == key:
        steps.append(click key_pos_right_arrow)

    emit(cx, cy, direction)          # C=1px tap, L/U/R/D=100px flick swipe

    if dak_dir is not None:
        emit(dak_xy[0], dak_xy[1], dak_dir)  # 濁点キーもフリック 1 発
        prev_key = "dak"
    else:
        prev_key = key
```

`emit(cx, cy, dir)` は `"C"` なら 1px swipe (擬似 tap)、`"L"/"U"/"R"/"D"` なら 100px flick swipe を steps に append する共通ヘルパ。

### 濁点は dak キーのフリック 1 発 (旧: multi-tap 廃止)

`が` = `(ka,C,"L")` → `か` タップ → dak を **←フリック**。
`ぱ` = `(ha,C,"R")` → `は` タップ → dak を **→フリック**。
`ゃ` = `(ya,C,"U")` → `や` タップ → dak を **↑フリック**。
`ゔ` = `(a,"U","L")` → `う` (a↑) → dak ← フリック。
`づ` = `(ta,"U","L")` → `つ` (ta↑) → dak ← フリック。

旧実装は `dak_count` (int) で「濁点キーを N 回タップして候補循環」させる方式だったが、
1. `dak_count ≥ 2` の文字 (`ぱ/ゔ/づ` 等) で `right_arrow` ガードが誤って dak 連打を分断 → 半濁音/2 段変換が失敗し得る
2. Gboard の multi-tap タイムアウト待ちも入る (`dak_gap_ms` の必要性)

の 2 点で不安定だった。フリック方式なら 1 op で確定するのでこれらが全部消える。

### 1px swipe を tap の代わりに使う

autoact の `tap` op は dispatchGesture の point-stroke で 330ms かかる (Android 内部オーバヘッド)。
1px swipe (durMs=30) にすると 90ms 前後に短縮できる。フリックも同じ swipe API を使うので実装が統一される。

### 打鍵間インターバル (`gap_ms`)

`build_type_steps(text, cfg, gap_ms=0)` は各打鍵 op (tap / flick / space / enter / dak-flick) の直後に `{"op":"sleep","ms":gap_ms}` を挟む (0 なら挟まない)。`build_alpha_steps` / `build_symbol_steps` も同じ挙動。

`type_text.type_arbitrary(text, cfg, gap_ms=...)` および CLI の `--gap` で伝播。

- `gap_ms=0`: 最速 (デフォルト)
- `gap_ms=50-100`: 人間観察しやすい速度
- `gap_ms=300+`: 超スロー (デモ / 目視デバッグ用)

sleep step は autoact 側の `ScenarioRunner` が待つので、Python ↔ autoact の TCP 追加往復は発生しない。

**旧 `dak_gap_ms` は撤廃**: multi-tap をやめたので濁点連打の間隔調整は不要。

## 5. 同一キー連続の multi-tap 回避 (bug#2)

### 現象

`かかく` を素直に打つと `きく` になる。

### 原因

Gboard 12キーの multi-tap 判定は「同じキーを短時間内に再度叩くと候補を進める」。
`tap か → tap か` を続けると、2回目が multi-tap 判定されて `か → き` に進んでしまう。
その後の `flick↑ か` は独立して `く` に。結果 `きく` (2文字)。

**auto-commit タイムアウトは >1000ms** (実測: 600ms sleep でも駄目、1200ms で OK)。

### 修正: `key_pos_right_arrow` を挟む

hira モード限定で `key_pos_right_arrow` (右カーソル) が使える:
- 未確定文字を **即確定** して
- カーソルを 1 つ右に動かす

これを同キー連続の間に挟むと、multi-tap の連鎖が切れる:

```
tap か
click right_arrow    ← 「か」確定、カーソル右
tap か
click right_arrow    ← 「か」確定、カーソル右
flick↑ か → く
```

sleep 1200ms より圧倒的に速い (a11y click は ~50ms)。

### ケア

- 修正後の `かかく` は 1.20s で完了 (right_arrow 2 回挟む)
- 未確定文字が無くても right_arrow はカーソル右移動だけで無害
- 副作用: セグメント全体が同キー連続だと右端に飛ぶが、続く入力は問題なし

## 6. 一括発火 (autoact exec)

step 列は `send("exec", {"scenario":{"name":..., "steps":[...]}})` で 1 リクエストで送る。autoact 側は `ScenarioRunner` が別スレで順次実行。

利点:
- TCP 接続オーバヘッド (~5ms/回) が 1 回だけ
- クライアント側は 1 回の recv でブロック待機

## 7. 全体タイミング (実測)

| ケース | 文字数 | 秒 | 備考 |
|---|---|---|---|
| `こんにちは、せかい！` | 10 | 1.68 | hira 単独、変換なし |
| `かかく` | 3 | 1.20 | multi-tap 対策 |
| `Hello / world` | 13 | 5.1 | alpha 単独, 3 セグ |
| `~/home/user` | 11 | 7.2 | sym_a2 → alpha 遷移 5 回 |
| `価格 $100 と ¥1,500` | 15 | 9.3 | 6 セグ (hira+alpha+sym_a2 混在) |

律速はモード切替 (各 400ms) と符号変換 (find_candidate_by_prefix)。

### 高速化差分 (現行)

- **janome Tokenizer singleton 化** (`_tokenizer()`): 以前は `run_hira` 呼び出し毎に `Tokenizer()` を new して辞書ロード (1-2s)。今は module singleton で初回だけ。→ 2 回目以降の hira run で **1-2s 短縮**
- **pure hira トークンの sleep 廃止**: `orig == yomi` のとき以前は `time.sleep(0.25)` → 即 `commit_hiragana()`。→ 純ひらがな token 1 個あたり **250ms 短縮**
- **漢字変換の poll 化**: 以前は `time.sleep(0.25)` 固定後に 1 回 find。今は `_poll_candidate` (30ms 間隔, 500ms 上限)。→ 候補が早く出た token で **最大 200ms 短縮**、遅い token の取りこぼしも減る

上の実測表は旧実装の値なので、次回計測時に再取得すること。
