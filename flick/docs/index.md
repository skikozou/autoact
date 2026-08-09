# Flick ドキュメント

Gboard 日本語(12キー) / 英字(QWERTY) / 記号キーボードを autoact 経由でフリック入力する Python パッケージ。
任意の日本語/英字/記号混在テキストを、実際のキー操作として発火して入力する。

## 目次

### 全体像
- [purpose.md](purpose.md) — 何を作ったか、なぜ setText じゃないか
- [architecture.md](architecture.md) — Python ↔ autoact ↔ Gboard ↔ 入力欄の関係
- [input-pipeline.md](input-pipeline.md) — 文字列 → mode 分割 → step 列 → 発火

### キーマップ (座標仕様)
- [keymap.md](keymap.md) — hira 12キー / alpha QWERTY / symbol A1/A2/B の実測座標
- [mode-switching.md](mode-switching.md) — hira ↔ alpha ↔ symbol の遷移経路と落とし穴

### 日本語入力
- [japanese-conversion.md](japanese-conversion.md) — janome 形態素解析 → 読み → 候補選択

### Gboard の癖
- [gboard-quirks.md](gboard-quirks.md) — multi-tap 誤爆 / last-used variant / a11y に露出しないキー

### API / ツール
- [api.md](api.md) — `flick.py` / `type_text.py` の公開関数
- [probing.md](probing.md) — `probe_*.py` の役割と使い方

### 実験ログと詰まりどころ
- [experiments.md](experiments.md) — 発見の経緯 (pykakasi→janome, symbol variant 発見, multi-tap bug 等)
- [troubleshooting.md](troubleshooting.md) — よくある詰まりと対処
