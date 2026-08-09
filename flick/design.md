# AutoAct Flick — Gboard日本語フリック高速入力

## 目的
文字列を渡すと Gboard日本語(12キー) 上でフリック操作を機械的に発火し、完璧に入力する。
`setText` (IME非経由) の代替でなく、フリックを **見せる/検証する** 用途にも。

## 前提
- Android 端末 (実測: 1080×2392 縦、Gboardが日本語12キー配列)
- Gboard: `com.google.android.inputmethod.latin`
- autoact APK 稼働 (`127.0.0.1:8765`)
- 対象アプリのEditTextにフォーカス済み

## キー配置 (a11y `key_pos_ja_12keys_1..12` から自動発見)

中央12キー (幅214×高177):
```
[あ] [か] [さ]      [削除]
[た] [な] [は]      [右]
[ま] [や] [ら]      [空白]
[濁点][わ] [、]      [Enter]
```
中心座標は `keymap.json` に固定 (probe済み)。

## フリック割当 (probe.py で全探索)

| キー | C | L | U | R | D |
|---|---|---|---|---|---|
| あ | あ | い | う | え | お |
| か | か | き | く | け | こ |
| さ | さ | し | す | せ | そ |
| た | た | ち | つ | て | と |
| な | な | に | ぬ | ね | の |
| は | は | ひ | ふ | へ | ほ |
| ま | ま | み | む | め | も |
| や | や | （ | ゆ | ） | よ |
| ら | ら | り | る | れ | ろ |
| わ | わ | を | ん | ー | 〜 |
| 、 | 、 | 。 | ？ | ！ | … |

## 濁点キー サイクル (probe_dakuten.py)

直前文字に **N回タップ** で変換される:

| 元字 | ×1 | ×2 | ×3+ |
|---|---|---|---|
| あ/い/え/お | ぁ/ぃ/ぇ/ぉ | (元) | 循環 |
| う | ぅ | ゔ | (元) |
| か行 | 濁点 (が…) | (元) | 循環 |
| さ行 | 濁点 (ざ…) | (元) | 循環 |
| た行 | 濁点 (だ…) | (元) | 循環 |
| つ | っ | づ | (元) |
| は行 | 濁点 (ば…) | 半濁点 (ぱ…) | (元) |
| や/ゆ/よ | ゃ/ゅ/ょ | (元) | 循環 |
| わ | ゎ | (元) | 循環 |
| その他 (な,ま,ら 等) | 変換なし |

→ 各文字は **(base_key, direction, dakuten_taps)** で一意に指定できる。
CHAR_MAP (flick.py) が完全表。

## 入力パイプライン (flick.py)

```
文字列 → 各文字 → (key,d,dak)
  ├ d="C": tap(cx, cy)
  ├ d≠"C": swipe(cx,cy → cx+dx,cy+dy) 距離100px, 60ms
  └ dak N回: tap(濁点キー) を N回
gap: 40ms / dakuten内40ms→90ms (IMEが処理する余裕)
```

## 確定・変換フロー

⚠ **重要な罠**: space/Enterキーを `tap` (dispatchGesture 60ms) で叩くと **Gboardがキーボード切替ダイアログを出す**。原因は autoact の tap 最小 duration が Gboard の gesture recognizer に長押し扱いされること。回避: **`click by=id`** (a11y ACTION_CLICK) を使う。副作用なし。

| 動作 | コマンド | 結果 |
|---|---|---|
| ひらがな確定 | `click by=id key_pos_ime_action` | 改行入らず, 未確定→確定 |
| 最初候補で変換確定 | `click by=id key_pos_space` | 例 きょう→今日 |
| 特定候補 | `click by=desc <候補>` | 候補バー内 FrameLayout を叩く |

### 候補バー構造 (Gboard)
- 位置: `bounds=[5,1420,965,1536]` の RecyclerView
- 各候補: FrameLayout, C=1 (clickable)
- `desc = "<候補>。<各漢字の読み解説>"` 形式 (例 "今日は雨。コンゲツ ノ コン，イマ。…")
- 4件見える + 右端に「その他の候補」 (`key_pos_show_more_candidates`)

## flick.py 使い方

```bash
# 1. ひらがなだけ
python3 flick.py --commit "こんにちは"        # → こんにちは

# 2. 最初候補で変換
python3 flick.py --convert-first "きょう"    # → 今日

# 3. 特定候補
python3 flick.py --convert "きょうはあめ" "今日は雨"   # → 今日は雨
```

## 実測

- `こんにちは、せかい！` 10文字 — 1.68s, 6.0 cps (exec一発)
- 33文字 — 3.89s, 8.5 cps
- 律速: swipe durMs=60ms が支配的 (各stepの overhead)

## 今後

- **英数モード**: `key_pos_switch_hiragana_alphabet` (「あa」キー) で切替
- **記号キーボード**: `key_pos_switch_to_symbol` で切替
- **カタカナ**: 候補バーに常に存在するので `--convert "あめ" アメ` でOK
- **候補が4件超**: 「その他の候補」展開 → 追加スクロール/検索
- **高速化余地**:
  - swipe durMs をさらに詰める (Gboardの取りこぼし限界を測定)
  - dispatchGesture の `willContinue` チェインで複数フリックを1発
  - 濁点キーを a11y click に変更 (tap誤爆防止)

## ファイル

- `keymap.json` — キー座標 + フリック割当
- `probe.py` — キー×方向 全探索
- `probe_dakuten.py` — 濁点サイクル探索
- `flick.py` — 文字列 → フリック実行 (メイン)
- `design.md` — この文書
