# トラブルシューティング

## API が応答しない

`aa health` がタイムアウトする場合:

1. **a11y サービスが OFF** → 設定 > アクセシビリティ > autoact を ON
   - APK 更新後は必ず OFF になる
2. **サービス起動直後** → `onServiceConnected` が来ないと ApiServer は上がらない。数秒待つ
3. **ポート衝突** → 別プロセスが 8765 を掴んでる。`ss -tlnp | grep 8765` (root か termux で可視な範囲で)
4. **APK が古いバージョン** → `aa health` の `version` を確認

## `step failed: click`

- ノードが見つからない: `aa find by=<by> value=<value>` で存在確認
- ノードが `C=0` (非 clickable): `by=descContains` で親の clickable を狙う
- Compose test-tag: `by=id` ではなく `by=idContains` を使う
- タイミング: `timeoutMs=3000` を付ける

## `step failed: setText`

- 対象が EditText でない: `by=focused` で入力欄を明示 (先に click しておく)
- IME が閉じている: 先に `aa click by=focused` で開く
- ノード無しで `by=focused` を使ったのに value=null で弾かれる: 最新版は許容 (`ActionExecutor.waitForStep` の WaitTask 経由)

## `by=id` が空振り

- Compose の test-tag は `pkg:id/` プレフィックスなし → `by=idContains` へ
- 動的 id (`androidx.compose.ui:id/` 系) はダンプで正確な形を確認

## WebView がスクロールしない

`scrollDown` / `pageDown` は WebView 未対応。`scroll dir=forward` を使う。または swipe ジェスチャ。

## `install` が承認 UI を出さない

- BAL 制約 (Android 10+): `BroadcastReceiver.startActivity` が画面前面化されない
- 現状の回避策: **手動でインストール承認** → 修正案は [install.md](install.md) 末尾

## タブ・ドロワーが出ない

- 端末のジェスチャナビ設定によっては左端スワイプがシステムで奪われる
- `startSpan` を大きく (200 とか) して 2 本指を離す
- または画面内側の座標から始める (`x1=50` 等)

## 承認ダイアログの言語差

「インストール」/「Install」/「安装」等: `by=textContains` で緩くマッチ。ロケール固定なら固定文字。

## シナリオが 2 本同時に走らない

`ScenarioRunner` は 1 本のみ許容。既存を止めるには `aa stop`。

## `aa` が動かない

- `/dev/tcp/` が使えないシェルの場合 (dash/zsh 素朴設定 等): bash を明示 `bash aa ...`
- 環境変数 `AUTOACT_HOST` / `AUTOACT_PORT` の typo
- `chmod +x samples/bin/aa`
- 別端末や adb フォワード先を叩きたい: `AUTOACT_HOST=<ip> AUTOACT_PORT=<port> aa health` ([cli.md](cli.md))

## adb shell からも叩ける?

同じ端末上の別 UID から `nc 127.0.0.1 8765` で普通に叩ける (認証なし)。デバッグ時は便利、脅威モデルによっては要注意。

## dumpUi が呼び出し側のシェル画面を返す

foreground が Termux (等の呼び出し元) になっている。`aa launch package=対象` で切り替え直後に dump する。または dump 直前の `aa top` で foreground 確認。

## d8 で NPE
`build.sh` 実行時に d8 が
`NullPointerException: Cannot invoke "String.length()" because "<parameter1>" is null`
で失敗する場合、匿名/private inner クラス or ジェネリック interface 実装が入っていないか確認
(Termux の d8 3.3 が javac 21 の `InnerClasses` / `Signature` 属性をパースできない) →
[build.md](build.md)

参考: [a11y-quirks.md](a11y-quirks.md), [install.md](install.md)
