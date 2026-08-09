# AutoAct

Android を自動操作するためのフレームワーク。AccessibilityService 経由で任意アプリの UI 操作
(タップ/スワイプ/文字入力/スクロール 等) をシナリオとして記述・実行できる。
TCP JSON API と CLI (`aa`) を同梱。

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

## flick (基本非推奨)

Gboard 日本語フリック入力を実 IME 経由で叩く Python サブプロジェクト。
テキスト入力は通常 `aa setText by=focused text=...` (a11y `ACTION_SET_TEXT`、IME 非依存で
高速・確実) を使えば済むので、flick は基本非推奨。実機の IME 挙動を再現したい・IME 経由で
しか反応しない UI を叩きたい等のロマン用途向け。

```sh
python3 flick/type_text.py "こんにちは"                    # Gboard フリック入力
python3 flick/type_text.py --convert "きょうは" 今日は     # 変換候補選択
```

キー座標は 1080×2392 縦画面 + Gboard 日本語12キー配列固有。詳細は [`flick/`](flick/) 配下。

## ライセンス

MIT LICENSE
