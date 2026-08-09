# AutoAct ドキュメント

Android AccessibilityService ベースの GUI 自動化ツール。TCP 経由でクライアント (Termux/Linux/macOS/adb shell 等) から任意アプリを操作する。

## 目次

### 全体像
- [setup.md](setup.md) — 環境固有ファイル (android.jar/keystore/build.sh/keymap.json) の導入手順
- [architecture.md](architecture.md) — サービス / API サーバ / アクション実行の内部構造
- [build.md](build.md) — ビルドパイプラインと d8 由来の制約

### API リファレンス
- [api.md](api.md) — TCP API 全コマンド (health, run, install, packages, click, setText ...)
- [cli.md](cli.md) — `aa` シェルラッパの使い方と例
- [steps.md](steps.md) — Step オペレーション一覧 (tap/swipe/click/setText/scroll/waitFor 等)
- [selectors.md](selectors.md) — ノード検索の `by` 戦略 (text/id/desc/focused ...)

### IPC / 通信
- [ipc.md](ipc.md) — TCP loopback + NDJSON プロトコル、abstract socket が使えなかった理由

### アプリインストール
- [install.md](install.md) — PackageInstaller フロー、自己書き換え、権限、BAL 制約

### a11y の癖と実践
- [a11y-quirks.md](a11y-quirks.md) — WebView / Compose / Canvas / IME での落とし穴
- [automation-patterns.md](automation-patterns.md) — Firefox / Termux / 電卓 の実動レシピ

### 運用
- [troubleshooting.md](troubleshooting.md) — よくある詰まりと対処
