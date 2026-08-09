# トラブルシューティング

## 入力結果が壊れる

### 同じ音が抜ける (`かかく` → `きく`)

同キー連続の multi-tap 誤爆。`build_type_steps` の `right_arrow` 挿入が効いてない。
- `flick.py` の `prev_key == cur_key` チェックが動いているか、`build_type_steps` を `dry` で確認:
  ```bash
  python3 flick.py --dry "かかく"
  # → 中間に {"op":"click","by":"id","value":"...key_pos_right_arrow"} が入っているはず
  ```
- alpha/symbol モードには right_arrow が無いので、その場合は sleep で回避 (1200ms 以上必要)

### 記号が全部抜ける (`Hello / world` → `Hello /`)

symbol → alpha 復帰失敗。次のセグメントが変な場所を叩いてる。
- `switch_to_alpha()` が `in_symbol_mode()` を先にチェックしているか確認
- 手動で `flick.switch_to_alpha()` を呼んで戻り値 True か

### 漢字が違うのに変換される

- janome の分割位置が意図と違う: `type_text.py --dry "..."` でセグメント表示。hira 内の tokenize は verbose=True で見える
- 候補バーに希望候補が無い: 「その他の候補」展開が未実装。ひらがな入力で妥協

### 全部空になる

editor がフォーカスされていない or 起動していない。
```python
send("top", {}).get("result",{}).get("package")
# → "ru.androidtools.texteditor" になっているか
```
違えば `send("launch", {"package":"ru.androidtools.texteditor"})` + editor をタップして focus。

## モード切替が失敗

### `switch_to_hira()` が False

現在のモードを確認:
```python
print(flick.in_hira_mode(), flick.in_alpha_mode(), flick.in_symbol_mode())
```

- 3 つとも False → キーボード自体が出ていない (editor unfocus or 別 app)
- alpha=True で `key_pos_switch_hiragana_alphabet` が露出していない: Gboard 設定で「あA」キーが物理的に消えているケース (space バーが「QWERTY」ラベル、globe が表示)
  - 一時対処: long-press space か globe icon 経由の IME picker (未自動化)
  - 恒久対処: Gboard 設定 → 言語 → 日本語 → 「英字」レイアウトの有効化

### `switch_to_alpha()` が False

- symbol からなら `back_to_prime` 経由が必要 (Ph.3 の修正)
- 現状の `switch_to_alpha` は symbol チェックを含む

### `ensure_symbol_A2()` が False

- last-used が B なら B→A 遷移で canvas (325,2170) tap が要る
- そこが失敗している可能性: `symbol_state()` を追って原因特定

## autoact 通信エラー

### TCP connection refused

- autoact APK が動いていない → 起動 (a11y サービス ON)
- ポート 8765 が他プロセスに掴まれてる (稀)
- 詳細は `../autoact/docs/troubleshooting.md`

### `step failed: <op>`

autoact 側の Step 実行失敗。よくあるパターン:
- ノード not found (`click by=id value=...`): `find` で存在確認
- 座標が画面外: `send("screen", {})` で解像度確認
- ジェスチャ拒否 (dispatchGesture が false): a11y サービス権限確認

### `step failed: launchApp`

`launchApp` は Step の op として存在しない。トップレベル cmd `launch` を使う:
```python
send("launch", {"package":"..."})  # 正
# send("exec", {"scenario":{"steps":[{"op":"launchApp"...}]}})  # 誤: silent fail
```

## 座標が合わない (別解像度)

flick.py の座標は 1080×2392 決め打ち。別解像度なら:
1. `send("screen", {})` で確認
2. `probe_*.py` を再実行 → JSON 更新
3. flick.py の座標定数を書き換え

現状スケーリング機能なし。

## janome が遅い/落ちる

- 初回起動: 辞書ロードで 1-2 秒。以降は高速
- `type_text._tokenizer()` で module singleton 化済 → 2 回目以降の hira run はロードなし
- Termux で ImportError: `pip install janome` (依存無しなので普通は通る)

## 入力速度が遅い

- モード切替 (400ms each) が支配的 → セグメント数を減らす
- 候補選択は `_poll_candidate` (30ms 間隔, 500ms 上限) で最短化済み
- 各 step 間の gap は `--gap 0` (デフォルト) が最速。ゆっくり見たいときは `--gap 80` などに上げる
- 濁点は dak キーのフリック 1 発なので追加 sleep 不要 (旧 `dak_gap_ms` は撤廃)

## 実測タイミングログを取りたい

`type_arbitrary` に verbose=True で:
- 各セグメントの `[alpha]/[A1]/[A2]/[conv]` ログ
- 変換 fallback (`[conv failed]`)、mode switch 失敗 (`[!!]`) が stderr に出る

TCP レベルは autoact のログ (`Logger.currentFile()`) で `tookMs` 見える。
