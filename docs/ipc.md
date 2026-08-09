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

## IPC 手段の制約 (前提)

Android の SELinux / FS 権限下で、Termux (別 UID) から叩ける同期 IPC は TCP loopback がほぼ一択:

- **abstract Unix socket**: Android 9+ の SELinux `untrusted_app` ポリシーで cross-app connect 不可 (同一 UID 内のみ)
- **filesystem Unix socket**: 相互に書ける場所がない (`/sdcard/` は socket 作成不可、`/data/data/<pkg>/` は自 UID のみ)
- **Content Provider**: bash から叩ける薄いクライアントがなく重い

## TCP loopback の性質

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

## BroadcastReceiver 経由 CLI (`CliReceiver`)

TCP を使えない状況向けの補助経路 (`CliReceiver.java` + Manifest exported)。

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

レスポンスや拡張性が要る場合は TCP を使う。

参考: [api.md](api.md), [cli.md](cli.md)
