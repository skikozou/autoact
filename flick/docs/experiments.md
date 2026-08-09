# 実験ログ / 発見の経緯

「なぜ今この設計か」を再現できるように、行き詰まりと解決を時系列で記録。

## Ph.1: hira 12キー フリック単独

### 目標
`こんにちは` みたいなひらがな文字列を Gboard に流し込む。

### やったこと
- `probe.py`: 12キー × 5方向で全 55 パターンを座標→タップ→表示文字を記録
- `probe_dakuten.py`: dak 連打で濁点/半濁点/小文字の循環パターン記録
- 結果 → `CHAR_MAP` (ひらがな → (base_key, direction, dak_count))  ※ dak_count は Ph.6 で dak_dir (フリック方向) に置換

### 発見: space/enter tap が「キーボード切替ダイアログ」を出す

autoact の `tap` 実装は dispatchGesture の point-stroke で 60ms 以上かかる。Gboard の space/enter はこの長さを **長押し** と誤認して切替 UI を出す。

**回避**: 全て a11y `click by=id key_pos_space` / `key_pos_ime_action` に置換。副作用ゼロ、高速。

### 発見: dispatchGesture tap は 330ms かかる

Android 内部で point-stroke は最低 330ms 費やす仕様。実測で 1px swipe (durMs=30) にすると 90ms 前後に短縮。

`_tap_step(cx, cy)` は 1px swipe で実装するようになった。

### 実測 (初期)
- `こんにちは、せかい！` 10文字 — 1.68s (6.0 cps)
- 33文字 — 3.89s (8.5 cps)
- 律速: swipe durMs=60ms + step 間の overhead

## Ph.2: 漢字混在 (kana→kanji 変換)

### 最初のアプローチ: pykakasi (失敗)

「漢字を読みに戻して flick 入力」を pykakasi でやろうとした。

```python
kks = pykakasi.kakasi()
result = kks.convert("今日は雨")
# → [{'orig':'今日は雨', 'hira':'こんにちはあめ'}]   ← 誤変換
```

**症状**: `今日は` → `こんにちは` (慣用句読み)、分割位置が壊れる。

**原因**: pykakasi は文字テーブル + 辞書ベース、Viterbi な形態素解析ではないので慣用読みを引きずる。

### 対策: janome に切替

```python
from janome.tokenizer import Tokenizer
t = Tokenizer()
list(t.tokenize("今日は雨"))
# → [今日 名詞 キョウ, は 助詞 ハ, 雨 名詞 アメ]
```

IPADIC 内蔵、Python 純粋実装。`reading` は全てカタカナで返るので `kata_to_hira` で変換。

`reading == "*"` (未知語/記号) は `surface` にフォールバック。

**成功**: `今日は雨` → 3 tokens → 各々 hira 入力 + 候補選択で完璧。

### 候補バーからの選択

候補ノードの探索:
- Gboard 候補バー: `RecyclerView bounds=[5,1420,965,1536]`
- 各候補: `FrameLayout, C=1`
- desc 形式: `"今日は雨。<各字読み解説>..."`

`find_candidate_by_prefix(target)`:
1. `by=descContains value=target+"。"` で走査
2. bounds y が 1400-1550 内のもの
3. desc が `target+"。"` で始まる → hit
4. 見つからなければ desc == target のフォールバック

`by=id` は使えない (動的生成で ID なし)。`descContains` はウォークサーチ (DFS) なので少し遅いが十分。

## Ph.3: 英数字/記号 (alpha + symbol)

### 目標

`Hello / world` や `価格 $100 と ¥1,500` みたいな混在テキストを打つ。

### 発見: alpha/symbol モードは canvas 描画

`probe_modes.py` で hira/alpha/symbol の全 `key_pos_*` を dump した結果:

- **hira mode**: 12キー全部 + del/space/enter/矢印/mode切替、計 24 個くらい露出
- **alpha mode**: shift/del/space/enter/mode切替 + header 2 個、**7 個のみ**。QWERTY 字は全部 canvas
- **symbol mode**: 同様に少数

→ 文字キーは a11y から取れない、**全て座標を実測して JSON に固定**。

### QWERTY 座標特定

Screenshot から目視 + `probe_alpha.py` で 36 キー全 tap 確認。100% 一致で確定。

座標:
- 数字 row1 (y=1596): 10-col grid, 54..1026 (108 step)
- qwertyuiop (y=1744): 同 grid
- asdfghjkl (y=1892): 108 offset で 9-col
- zxcvbnm (y=2040): 216 offset で 7-col
- 底列 (y=2188): `,`(325), `.`(595) — ただしこれは古い Gboard 版。新版では `,`(218), `.`(860) のことも

### flick↑ 記号 (bonus feature)

QWERTY 各キーの上フリックで **各記号が 1 発で入力可能**。symbol モード切替を回避できる:

```
q↑=% w↑=\ e↑=| r↑= t↑=[ y↑=] u↑=< i↑=> o↑={ p↑=}
a↑=@ s↑=# d↑=¥ f↑=_ g↑=& h↑=- j↑=+ k↑=( l↑=)
z↑=* x↑=" c↑=' v↑=: b↑=; n↑=! m↑=?
```

24 記号 (+ 一部重複)。`probe_alpha_flick.py` で 24/26 一致 (残り 2 は目視推定ミスで実際は仕様通り)。

`classify_char` で alpha 優先 → `@#$%&+-()` 等がこの経路で入る → mode 切替不要 → 高速。

### symbol A/B 発見: **last-used variant が開く**

ユーザ指摘: 「Gboard は少し特殊な仕様があって、シンボルキーボードは数字パッドと qwerty 配列の記号キーボードの二種類があり、シンボルキーボードを開くとき、前回開いた方のモードで開かれる」

→ `switch_to_symbol` 直後にどっちが出るか分からない。

対応:
- `symbol_state()` で `A1`/`A2`/`B` を判定 (shift の desc で見分ける)
  - shift desc に `"その他"` → A1
  - shift desc が `"記号"` → A2
  - shift が無い → B (数字パッド)
- `ensure_symbol_A1()` で:
  - B → canvas (325,2170) tap で A へ
  - A2 → shift click で A1 へ
- `ensure_symbol_A2()` で:
  - A1 → shift click で A2 へ

### 発見: switch_to_alpha が symbol から失敗

初期の `switch_to_alpha` は無条件で `key_pos_switch_hiragana_alphabet` を叩いていた。symbol モードにはそのキーが無い → silent fail → 続く type_alpha が変な場所に打鍵。

**修正**:
```python
def switch_to_alpha():
    if in_alpha_mode(): return True
    if in_symbol_mode():
        send("click", {"by":"id","value":KEY_BACK_TO_PRIME})  # ← 追加
        if in_alpha_mode(): return True
    send("click", {"by":"id","value":KEY_SWITCH_HIRA_ALPHA})
    return in_alpha_mode()
```

`back_to_prime` は「symbol に入る前の mode」に戻すので、alpha→symbol→back なら alpha に戻る。

## Ph.4: multi-tap bug (最大の敵)

### 症状

`かかく` を打つと `きく` (2文字) になる。

内訳:
- tap か → 未確定「か」
- tap か → multi-tap 判定で「き」に進む (! not「かか」)
- flick↑ か → 「く」
- 確定 → `きく`

### 原因

Gboard 12キー は multi-tap 入力方式で、同キー連打で候補循環する。フリック入力しか使ってない場合でも `tap` 連続は影響を受ける。

### auto-commit timeout 実測

`sleep N ms` を挟んで同キー連打:
| sleep | 結果 |
|---|---|
| 60ms | 誤爆 |
| 280ms | 誤爆 |
| 600ms | 誤爆 |
| 1200ms | OK |

→ Gboard の multi-tap timeout は **1000ms を超える**。sleep で解決するには遅すぎる。

### 修正: right_arrow 挿入

hira モードには `key_pos_right_arrow` (右カーソル) がある。desc は `"右"`。
- 未確定文字を **即確定** して
- カーソルを 1 つ右に動かす

これを同キー連続の間に挟むと multi-tap の連鎖が切れる:

```python
# build_type_steps 内
if prev_key == current_key:
    steps.append({"op":"click","by":"id","value":KEY_RIGHT_ARROW})
```

- `a11y click` は 50ms 弱
- 未確定文字が無くても right_arrow はカーソル右移動だけで無害
- 修正後: `かかく` が 1.20s で完了 ✓

## Ph.5: 統合テスト

`type_text.type_arbitrary` で 3 ケーステスト:

| 入力 | before (バグ状態) | after (修正済) |
|---|---|---|
| `Hello / world` | `Hello /` (symbol→alpha 失敗で切れる) | `Hello / world` ✓ 5.1s |
| `~/home/user` | `~//` (同上) | `~/home/user` ✓ 7.2s |
| `価格 $100 と ¥1,500` | `きく $と ¥1,500` (multi-tap で 価格 → きく) | `価格 $100 と ¥1,500` ✓ 9.3s |

## Ph.6: 濁点をフリック方式に置換

### 問題

Ph.1 の `probe_dakuten.py` で採取した **「dak を N 回タップで循環」** 方式を長らく使っていたが、以下の 2 問題があった:

1. `dak_count ≥ 2` の文字 (`ぱぴぷぺぽ / ゔ / づ`) では 2 回目の dak タップの前に `sep("dak")` が prev="dak" と一致して `right_arrow` を誤挿入 → 「ば」で確定してしまい半濁音や 2 段変換に到達しない
2. Gboard の multi-tap タイムアウトを跨がないため `dak_gap_ms` (60ms) が必要 → 全体が遅くなる

### 発見

Gboard の 12キーは濁点キーもフリックに対応していて、方向で操作を選べる:
- ← (L) = 濁点
- → (R) = 半濁点
- ↑ (U) = 小文字化
- ↓ (D) = 未使用

これで `ぱ` は「は タップ → dak → フリック」の 1 フリックで確定。multi-tap 経路を通らないので `dak_count` も `dak_gap_ms` も要らない。

### 修正

- `CHAR_MAP` の三つ組を `(base_key, direction, dak_dir)` に。`dak_dir` は `None`/`"L"`/`"R"`/`"U"`
- `build_type_steps` の dak 処理を「for _ in range(dak_count): tap dak」から「if dak_dir: flick dak in dak_dir」へ
- `dak_gap_ms` パラメータ、`--dak-gap` CLI arg、`DEFAULT_DAK_GAP_MS` 定数を全て削除
- `keymap.json` の `dak` エントリにフリック方向 (L/U/R) の意味を明示

### 効果

- `ぱぴぷぺぽ` 系の未確定バグが消える
- 濁点入力の 1 文字あたり所要 op 数: 3 (base tap + N=2 dak tap + gap sleep) → 2 (base tap + dak flick) に減少
- `dak_gap_ms=60ms` 分の delay がゼロになる (濁点付き文字あたり 60ms 短縮)

## その他の発見・小ネタ

### autoact の `launchApp` op は存在しない

scenario 内で `{"op":"launchApp","package":"..."}` を書いても silent fail (エラーにならず起動もしない)。正しくは top-level cmd:

```python
send("launch", {"package":"..."})
```

編集器 focus が外れた時 (自分のセッション tty に切り替わって editor が背景に行ったとか) は再 `launch` するのが確実。`send("top", {})` で foreground package 確認可能。

### 混在テキストの高速化余地

- モード切替 (400ms) がドミナント。**セグメント数最小化** = 分類優先度で alpha 最優先
- flick durMs (60ms) をさらに詰められるか (Gboard の取りこぼし限界を測定)
- 濁点 tap を a11y click に置換 (ただし dak キーの id は key_pos_ja_12keys_10)

### 未解決/課題

- **候補フォールバック**: 上位 4 件に無い場合、「その他の候補」展開して再検索。未実装。
- **alpha → hira 直接切替**: 現在の Gboard 設定で `key_pos_switch_hiragana_alphabet` が alpha に露出しないケースあり。long-press space か globe 経由の切替が必要。回避策未確定。
- **カタカナ直接入力**: 現状は変換候補経由。カタカナ専用モードの探索は未着手。
- **数字パッド (symbol B)**: 座標を保持していない (A/B トグルで A に戻す運用)。

## メモ書き (再現しない・環境固有)

- 元テスト用 notepad で英字 space が入らない bug があった → notepad アプデで解決 (Gboard 側は無関係)
- termux ↔ APK は TCP loopback 一択 (SELinux が abstract Unix socket を弾く。詳細は autoact/docs/ipc.md)
- 実機テスト前に TTS で合図出して人間確認取る (memory rule)
