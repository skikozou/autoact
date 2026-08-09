# AutoAct

Android を自動操作するためのフレームワーク。AccessibilityService 経由で任意アプリの UI 操作
(タップ/スワイプ/文字入力/スクロール 等) をシナリオとして記述・実行できる。
TCP JSON API と CLI (`aa`) を同梱。

**テキスト入力は基本 `aa setText`** (a11y の `ACTION_SET_TEXT` 経由、IME 非依存で高速・確実)。
別途 Gboard 日本語フリック入力を実 IME 経由で叩く `flick/` サブプロジェクトも同梱しているが、
これはロマン仕様 (実機の IME 挙動を再現したい・IME 経由でしか反応しない UI を叩きたい 等の
特殊用途) で、通常用途では非推奨。詳細は [`flick/`](flick/) の README/design 参照。

## 使い方

APK ビルド → 端末インストール → **設定 > ユーザ補助** で AutoAct 有効化 → TCP `127.0.0.1:8765` で待ち受け開始。

`aa` CLI は POSIX shell + bash `/dev/tcp` で動くので Termux/Linux/macOS 等どこでも使える。接続先は
`AUTOACT_HOST` / `AUTOACT_PORT` 環境変数で上書き可能 (adb フォワード先や別端末を叩く場合)。

```sh
aa health                                  # 疎通確認
aa launch package=com.google.android.keep  # アプリ起動
aa tap x=500 y=800                         # 座標タップ
aa click by=text value=OK                  # テキストで要素タップ
aa dumpUi tag=cur                          # UI ツリーをダンプ
aa run name=self_smoke                     # samples/ のシナリオ実行
```

セットアップ、ビルド、詳細な API/シナリオ仕様は [`docs/`](docs/index.md) を参照。

flick モジュール (基本非推奨、特殊用途のみ):

```sh
python3 flick/type_text.py "こんにちは"                    # Gboard フリック入力
python3 flick/type_text.py --convert "きょうは" 今日は     # 変換候補選択
```

## 構成

```
autoact/
├── src/com/example/autoact/  # Java (a11y service 本体、29 ファイル)
├── res/                      # Android リソース
├── AndroidManifest.xml
├── samples/                  # サンプル scenario + aa CLI
├── docs/                     # ドキュメント (日本語)
└── flick/                    # Gboard フリック入力補助 (Python, 基本非推奨・特殊用途)
```

環境固有ファイル (`android.jar`, `debug.keystore`, `build.sh`, `flick/*.json` 等) は
gitignore で除外している。導入手順は [`docs/setup.md`](docs/setup.md)。

## 基本設計

- **AccessibilityService** (`AutomationService.java`) が UI ツリーとジェスチャ、キーイベントへの入口
- **TCP `127.0.0.1:8765`** で NDJSON リクエストを受け、`ApiHandler` が cmd を dispatch
- **Scenario** は JSON の steps 配列。ScenarioRunner が同期実行し、per-step レポートを返せる
- **FindSpec** で region / ancestorId / visibleOnly / clickableOnly / limit を指定し a11y walk を早期打ち切り可能
- **WaitTask** は AccessibilityEvent 駆動で出現待ちする (CPU polling ゼロ)

詳細は [`docs/architecture.md`](docs/architecture.md)。

## ライセンス

MIT LICENSE
