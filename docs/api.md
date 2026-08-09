# API リファレンス

TCP 127.0.0.1:8765 / NDJSON。詳細プロトコルは [ipc.md](ipc.md)。

リクエスト: `{"cmd":"<name>","args":{...}}\n`
レスポンス: `{"ok":true|false,"result":{...}|"error":"...","tookMs":N}\n`

## メタ / 状態

### `health`
サービス生存確認。
```json
→ {"cmd":"health"}
← {"ok":true,"result":{"ok":true,"service":"autoact","version":1,"running":false,"port":8765}}
```

### `screen`
画面サイズ、密度、回転など。

### `top`
現在のフォアグラウンドパッケージ + 全ウィンドウ情報 (bounds/layer/focused)。
```json
{"result":{"package":"com.example","windows":[{"id":..., "package":..., "bounds":{...}, "active":true, "focused":true}, ...]}}
```

### `status`
実行中シナリオの状態 (`running`, `currentStep`, `tag` 等)。

## ノード操作

### `find`
ノード検索。マッチ複数返却。
```json
{"cmd":"find","args":{"by":"idContains","value":"URL_BOX","limit":5}}
```
- `by`: 検索戦略 → [selectors.md](selectors.md)
- `value`: 検索値 (`focused` の場合は不要)
- `limit`: 返却上限 (デフォルト 20)。walk 系 by は limit 満たしたら早期 break
- `region`, `ancestorId`, `visibleOnly`, `clickableOnly`: 追加フィルタ → [selectors.md](selectors.md#追加フィルタ-findspec)

返却: `{"matches":[{text, desc, class, package, id, clickable, ..., bounds, centerX, centerY}, ...], "count":N}`

### `waitFor` / `waitClick`
ノード出現待ち (event 駆動)。`waitClick` は出現直後に click まで一括。
```json
{"cmd":"waitFor","args":{
  "by":"descContains","value":"送信",
  "region":{"y1":1400,"y2":1550},
  "timeoutMs":3000, "mode":"event"
}}
{"cmd":"waitClick","args":{ ...同上... }}
```
- `mode`: `event` (default) は `AccessibilityEvent` で起床、`poll` は `intervalMs` (default 30) 毎に再探索
- 全 `find` フィルタ (`region`/`ancestorId`/…) が使える
- タイムアウト時は `ok:false, error:"step failed: waitFor"` (or `waitClick`) を返す

### Step 転送 (単発 op 実行)
Step 系 op はそのまま cmd として叩ける (`Step.OP_*`)。実装は [steps.md](steps.md) 参照。
```json
{"cmd":"click","args":{"by":"text","value":"OK","timeoutMs":3000}}
{"cmd":"setText","args":{"by":"focused","text":"hello"}}
{"cmd":"tap","args":{"x":500,"y":800}}
{"cmd":"swipe","args":{"x1":540,"y1":1800,"x2":540,"y2":600,"durMs":400}}
{"cmd":"scroll","args":{"by":"classContains","value":"WebView","dir":"forward"}}
```

## シナリオ実行

### `run`
JSON シナリオを非同期実行。すでに走ってれば拒否。
```json
{"cmd":"run","args":{"name":"self_smoke"}}   # scenarios/self_smoke.json を読む
```

### `run_sync`
`run` の完了待ち版。ScenarioRunner の `CountDownLatch` で即座に返る (旧: 200ms poll 粒度)。

### `exec`
インライン JSON シナリオを渡す。sync 実行。
```json
{"cmd":"exec","args":{
  "scenario":{"withReport":true, "steps":[{"op":"tap","x":100,"y":200}]},
  "waitMs": 300000
}}
```
- `waitMs`: 完了待ち上限 (default 300000ms)
- `scenario.withReport=true` を指定すると response に per-step report が付く

**withReport 返却:**
```json
{"ok":true, "result":{
  "ok":true,
  "report":[
    {"index":1, "op":"tap", "ok":true, "tookMs":42, "attempts":1},
    {"index":2, "op":"waitClick", "ok":true, "tookMs":127, "attempts":1},
    ...
  ]
}}
```
- `index` は 1-based (シナリオ内の step 番号)
- 失敗 step には `error` フィールドが入る
- 中断された step は `error:"aborted:stop-requested"` or `"aborted:max-duration"`

### `exec_async`
`exec` の非同期版。report 不可 (完了待ちしないので)。

### `stop`
走行中シナリオを中断。

## パッケージ管理

### `install`
APK をインストール (要 REQUEST_INSTALL_PACKAGES + ユーザ承認)。
```json
{"cmd":"install","args":{"path":"/sdcard/Download/foo.apk","tag":"myinstall"}}
```
返却: `{"sessionId":..., "path":..., "size":..., "note":"..."}` — 承認 UI が出るので a11y で自動タップ可能。

### `uninstall`
```json
{"cmd":"uninstall","args":{"package":"com.example.foo"}}
```

### `install_status`
直近の `install`/`uninstall` 結果を取得。
```json
{"result":{"status":"success|pending_user_action|failure(N)", "code":N, "package":"...", "tag":"..."}}
```

### `packages`
インストール済みパッケージ一覧。`filter` で部分一致絞込み。
```json
{"cmd":"packages","args":{"filter":"firefox"}}
```

### `launch`
LAUNCHER intent でアプリ起動。
```json
{"cmd":"launch","args":{"package":"org.mozilla.firefox"}}
```

## Intent / URL / Key

### `openUrl`
```json
{"cmd":"openUrl","args":{"url":"https://example.com"}}
```

### `intent`
汎用 intent 発火。
```json
{"cmd":"intent","args":{"action":"android.intent.action.VIEW", "uri":"...", "package":"...", "extras":{...}}}
```

### `key`
グローバル action / key event。
```json
{"cmd":"key","args":{"name":"back|home|recents|notifications|..."}}
```

## エラー
- `ok:false, error:"..."` を返す。
- ステップ失敗は `error:"step failed: <op>"`。
- 該当ノード無しは `waitFor` タイムアウト → 同上。

参考: [cli.md](cli.md), [steps.md](steps.md), [selectors.md](selectors.md)
