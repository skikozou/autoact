# APK インストール / アンインストール

## フロー概観

```
aa install path=/sdcard/x.apk
  ↓
ApiHandler → Installer.install(path, tag)
  ↓
PackageInstaller.createSession() → openWrite() → APK ストリーム → commit(sender)
  ↓  (Android が非同期で処理)
InstallStatusReceiver.onReceive()
  ├─ STATUS_PENDING_USER_ACTION: 承認 UI を Activity として起動 (a11y で自動タップ可)
  ├─ STATUS_SUCCESS: 完了ログ
  └─ 他: 失敗コード保持
  ↓
aa install_status で最新状態が取れる
```

## 必要な宣言 (AndroidManifest.xml)

```xml
<uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES"/>
<uses-permission android:name="android.permission.REQUEST_DELETE_PACKAGES"/>
<queries>
  <intent><action android:name="android.intent.action.MAIN"/></intent>
</queries>

<receiver android:name=".InstallStatusReceiver" android:exported="false">
  <intent-filter>
    <action android:name="com.example.autoact.INSTALL_STATUS"/>
  </intent-filter>
</receiver>
```

## ユーザ設定
`REQUEST_INSTALL_PACKAGES` は runtime 特別権限。**設定 > アプリ > autoact > 提供元不明のアプリのインストール** を許可する必要あり。

## 承認 UI の自動化

`STATUS_PENDING_USER_ACTION` で来た Intent (`EXTRA_INTENT`) を `startActivity` する。承認画面が出るので:
```bash
aa waitFor by=textContains value=インストール timeoutMs=5000
aa click by=text value=インストール
```
のように自動タップできる (autoact 自身の a11y サービス経由)。

## 自己書き換え

autoact 自身の APK を差し替えることも可 (`path=/sdcard/Download/autoact.apk`)。
- インストール成功で古いプロセスは終了
- a11y サービスは Android の仕様上、APK 更新で **一度無効化される** → ユーザが再度 ON にする必要あり
- 起動直後は `onServiceConnected` が呼ばれるまで API 応答しない

## 既知の落とし穴

### 承認 UI が出ない (BAL 制約)
Android 10+ の背景 Activity 起動制限。`BroadcastReceiver` から `startActivity` を呼ぶと画面前面化されないケースがある。回避策:
- a11y サービス (フォアグラウンド) 側から起動する
- ユーザ介入時のみ `startActivity` を許容する

現在の `InstallStatusReceiver` はレシーバから直接 startActivity しているので、フォアグラウンドに Termux 等がいると発火しないことがある。

### 承認 UI のロケール差
「インストール」「Install」「安装」等。汎用シナリオでは `by=textContains` かボタン位置座標で対応。

## アンインストール
```bash
aa uninstall package=com.example.foo
```
同じく `STATUS_PENDING_USER_ACTION` → 削除確認ダイアログ → 自動タップ可。

参考: [api.md](api.md), [troubleshooting.md](troubleshooting.md)
