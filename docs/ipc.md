# IPC (クライアント ↔ APK)

## 採用: TCP loopback + NDJSON

- ソケット: `ServerSocket(8765, InetAddress.getByName("127.0.0.1"))`
- プロトコル: NDJSON (1 行 = 1 メッセージ、改行区切り)
- クライアント: 1 リクエスト送信 → 1 レスポンス受信 → close

### 実装
- サーバ側: `ApiServer` (Thread) + `ApiClient` (per-conn Thread)
- APK 側の必要権限: `INTERNET`
- クライアント側: bash 組込 `/dev/tcp/<host>/<port>` (追加パッケージ不要) or 任意の TCP クライアント
- クライアントは Termux に限らず Linux/macOS/WSL/`adb shell` 等どこでも可。`adb forward tcp:8765 tcp:8765` すれば開発機から実機の autoact を叩ける

### 接続先の指定 (`aa` CLI)

`aa` は環境変数で接続先を上書きできる:

```bash
export AUTOACT_HOST=127.0.0.1   # default
export AUTOACT_PORT=8765        # default
```

### メッセージ例
```
→ {"cmd":"tap","args":{"x":500,"y":800}}\n
← {"ok":true,"result":{"op":"tap","stepOk":true,"tookMs":22},"tookMs":22}\n
```

## なぜ abstract Unix socket ではないか

Linux 拡張の abstract namespace (`\0`-prefixed sun_path) を最初試した。Android の SELinux ポリシーで **`untrusted_app` ドメインの cross-app abstract socket connect が拒否される** (Android 9+)。

再現症状:
- APK 側で `LocalServerSocket("autoact")` が bind 成功
- Termux 側から `socat ABSTRACT-CONNECT:autoact -` が `Permission denied`
- `logcat` に SELinux avc denied

抜け道 (root, LSPosed 等) はあるが root 不要な作りにしたいので TCP に倒した。

### 他の IPC 手段を採らなかった理由

- **Filesystem Unix socket**: 相互に書ける場所が事実上ない (`/sdcard/` は socket 作成不可、
  `/data/data/<pkg>/` は自 UID からしか読めない)
- **Content Provider**: bash から叩ける薄いクライアントがなく重い
- **abstract Unix socket**: 上記の通り cross-app connect が SELinux で塞がれる (同一 UID 内なら可)

結果、外部 (Termux 等別 UID) から叩ける同期 IPC は実質 TCP loopback 一択。

## なぜ TCP loopback で困らないか

- ループバックは NAT/firewall の外なのでネットワーク経由アクセスは (別デバイスからは) 到達しない
- Android の netstack 上ではあるが、実効オーバヘッドは無視できる
- 追加権限 `INTERNET` はマニフェストに `<uses-permission>` 追加のみ (ユーザ承認不要のノーマル権限)

## NDJSON の落とし穴

- **改行を含む JSON を送ると分割される** → `aa` は送信前に `tr -d '\n\r'`
- 大きな JSON も 1 行で送る (数十 KB までは実測問題なし)

## 拡張余地

- 双方向ストリーミング: 現状は 1 往復で close。長時間ストリーミング (ログ tail 等) なら keep-alive + サブスクリプション設計を追加
- 認証: 現状無し。マルチユーザ端末や BYOD では localhost port の乗っ取りに注意 (別 UID からも connect 可)
- HTTPS 化: 不要 (ループバックのみ)

## 旧仕様: BroadcastReceiver 経由 CLI (`CliReceiver`)

TCP API 導入前に使っていた通信手段。現在も動く (`CliReceiver.java` + Manifest exported)。TCP を使えない状況の fallback として残置。

**使いどころ:**
- `ApiServer` が上がっていない (Service 未接続直後など)
- 別 UID / 別アプリから直接 trigger したい (broadcast は intent 権限で通る)
- `adb shell` から一発叩きたい (`aa` CLI のセットアップ不要)

**制約:**
- fire-and-forget、戻り値なし。結果は `Logger` ファイル (`/sdcard/Download/autoact/logs/`) と `logcat` で確認
- 対応アクションは 3 種のみ

**対応アクション:**

```bash
# シナリオ実行 (name: scenarios/<name>.json を解決)
am broadcast -a com.example.autoact.RUN_SCENARIO \
  -n com.example.autoact/.CliReceiver \
  --es scenario <name>

# シナリオ実行 (絶対パス)
am broadcast -a com.example.autoact.RUN_SCENARIO \
  -n com.example.autoact/.CliReceiver \
  --es scenario_path <abs-path>

# 停止
am broadcast -a com.example.autoact.STOP_SCENARIO \
  -n com.example.autoact/.CliReceiver

# a11y ツリー dump (即時)
am broadcast -a com.example.autoact.DUMP_UI \
  -n com.example.autoact/.CliReceiver \
  --es tag <tag>

# a11y ツリー dump (遅延 ms 指定、foreground 遷移待ち等)
am broadcast -a com.example.autoact.DUMP_UI \
  -n com.example.autoact/.CliReceiver \
  --es tag <tag> \
  --el delay <ms>
```

TCP API が使える環境なら TCP を優先 (レスポンス取得可、拡張性高)。

参考: [api.md](api.md), [cli.md](cli.md)
