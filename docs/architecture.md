# アーキテクチャ

## 全体構成

```
┌── client (bash/py/…) ────┐        TCP $AUTOACT_HOST:$AUTOACT_PORT
│  aa <cmd> [k=v ...]      │────── NDJSON (1req = 1line) ──┐
│  (Termux, Linux, adb 等) │        default 127.0.0.1:8765 │
└──────────────────────────┘                               │
                                                            ▼
┌── autoact APK ────────────────────────────────────────────┐
│                                                            │
│  ApiServer (Thread, ServerSocket)                          │
│    └─ ApiClient (per-conn, read 1 line → dispatch)         │
│         └─ ApiHandler.handle(cmd, args)                    │
│              ├─ meta:  health/screen/top/status            │
│              ├─ node:  find + Step 系 (click/setText 等)   │
│              ├─ pkg:   install/uninstall/packages/launch   │
│              ├─ intent: openUrl/intent/key                 │
│              └─ scenario: run/run_sync/exec/exec_async     │
│                                                            │
│  AutomationService (AccessibilityService)                  │
│    ├─ onServiceConnected: ApiServer 起動                   │
│    ├─ ScenarioRunner (別スレ、Step を順次実行)             │
│    ├─ ActionExecutor.perform(svc, step) ← 実処理           │
│    │     ├─ dispatchGesture (tap/swipe/curve/pinch/multi)  │
│    │     ├─ performAction (click/setText/scroll/...)       │
│    │     └─ NodeFinder.find(svc, by, value) で対象特定     │
│    ├─ Installer (PackageInstaller.Session)                 │
│    ├─ UiDumper (a11y ツリー→ text)                         │
│    └─ Screenshot (takeScreenshot)                          │
│                                                            │
│  InstallStatusReceiver (Broadcast) — 承認 UI を起動        │
│  MainActivity — a11y 有効化ガイド + ログ表示               │
└────────────────────────────────────────────────────────────┘
```

## 主要クラス

| ファイル | 責務 |
|---|---|
| `AutomationService.java` | AccessibilityService 本体。ApiServer / Runner のライフサイクル管理 |
| `ApiServer.java` | ポート 8765 で LISTEN、接続毎に `ApiClient` を投入 |
| `ApiClient.java` | 1 リクエストを read → ApiHandler → write → close |
| `ApiHandler.java` | コマンドディスパッチ (~1200 行、全 cmd 集約) |
| `ActionExecutor.java` | Step を実際の Android API 呼び出しに変換 |
| `NodeFinder.java` | `by`/`value` → `AccessibilityNodeInfo` 解決 |
| `GestureBuilder.java` | `GestureDescription` 組み立て (tap/swipe/curve/pinch/multi) |
| `Scroller.java` | スクロール系 Step の実行 |
| `Installer.java` | PackageInstaller.Session ラッパ |
| `InstallStatusReceiver.java` | 承認 Intent を Activity として起動 |
| `Step.java` | Step の POJO + 定数 (OP\_\* / BY\_\*) |
| `Scenario.java`, `ScenarioParser.java`, `ScenarioRunner.java` | JSON シナリオの表現/解析/逐次実行 |
| `UiDumper.java` | 全ウィンドウの a11y ツリーを text ダンプ |
| `Logger.java` | ファイルログ (`/sdcard/Download/autoact/logs/`) |
| `Storage.java` | 外部ストレージパスの管理 |

## リクエストのライフサイクル

1. クライアントで `aa click by=text value=OK` を叩く
2. `aa` が `{"cmd":"click","args":{"by":"text","value":"OK"}}` を組み、`/dev/tcp/$AUTOACT_HOST/$AUTOACT_PORT` (既定 `127.0.0.1:8765`) に 1 行送信
3. `ApiServer.accept()` → `ApiClient.run()` が 1 行 `readLine()`
4. `ApiHandler.handle("click", args)`:
   - args を Step に詰め替え
   - `ActionExecutor.perform(svc, step)` を呼ぶ
   - Step 内部で `NodeFinder.find(svc, "text", "OK")` → `node.performAction(ACTION_CLICK)`
5. 結果を JSON 化して 1 行返して close

## スレッドモデル

- `ApiServer` は自前 Thread。`accept()` ブロッキング。
- 各 `ApiClient` も別 Thread (同時多重接続を許容)。
- シナリオ実行 (`ScenarioRunner`) も別 Thread。1 本のみ (2 個目は 409 相当エラー)。
- AccessibilityService 本体のコールバックはメインスレッド。Node 操作は基本メインスレッドから呼んでも問題ないが、a11y API はスレッドセーフ。

## 保存されるファイル

`/sdcard/Download/autoact/`:
- `screenshots/shot_YYYYMMDD_HHMMSS_<tag>.png`
- `dumps/dump_YYYYMMDD_HHMMSS_<tag>.txt`
- `logs/log.txt` (append)
- `scenarios/*.json` (ユーザ配置)

参考: [ipc.md](ipc.md), [api.md](api.md), [steps.md](steps.md)
