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

参考メモリ: `project_android_abstract_socket.md`

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

参考: [api.md](api.md), [cli.md](cli.md)
