# Step オペレーション

`Step` は 1 手順の POJO。シナリオ (`{steps:[...]}`) の要素であり、単発でも `aa <op> ...` で叩ける。

## 共通フィールド

| フィールド | 型 | 用途 |
|---|---|---|
| `op` | string | Step 種別 (`Step.OP_*`) |
| `by`, `value` | string | ノード検索 → [selectors.md](selectors.md) |
| `text` | string | `setText` の書込み値 |
| `timeoutMs` | long | ノード待ち上限 (デフォルト 0=即時) |
| `retries` | int | 失敗時再試行回数 |
| `tag` | string | ログ/ファイル名に混ぜる識別子 |

各 op 特有のフィールドは下記。

## ジェスチャ (dispatchGesture)

| op | 必須フィールド | 説明 |
|---|---|---|
| `tap` | `x`, `y` | 1 点タップ |
| `swipe` | `x1,y1,x2,y2,durMs` | 直線スワイプ |
| `drag` | `x1,y1,x2,y2,durMs`, `holdMs` | 事前ホールドあり |
| `curveSwipe` | `+ cx,cy` | ベジエ制御点 |
| `pinch` | `cx,cy,startSpan,endSpan,durMs` | 2 本指ピンチ |
| `multiSwipe` | `x1,y1,x2,y2,startSpan,durMs` | 2 本指スワイプ (Termux ドロワー等) |

## ノードアクション (performAction)

| op | 追加 | 対応 Android action |
|---|---|---|
| `click` | | `ACTION_CLICK` (非 clickable なら親を辿る) |
| `longClick` | | `ACTION_LONG_CLICK` |
| `contextClick` | | `ACTION_CONTEXT_CLICK` (API 23+) |
| `setText` | `text` | `ACTION_SET_TEXT` |
| `setSelection` | `start,end` | `ACTION_SET_SELECTION` |
| `setProgress` | `progress` (0..1) | `ACTION_SET_PROGRESS` (API 24+) |
| `copy` / `paste` / `cut` | | `ACTION_COPY/PASTE/CUT` |
| `select` / `clearSelection` | | `ACTION_SELECT`/`CLEAR_SELECTION` |
| `focus` / `clearFocus` | | `ACTION_FOCUS/CLEAR_FOCUS` |
| `a11yFocus` | | `ACTION_ACCESSIBILITY_FOCUS` |
| `expand` / `collapse` / `dismiss` | | 同名 action |
| `showOnScreen` | | `ACTION_SHOW_ON_SCREEN` (API 23+) |
| `imeEnter` | | `ACTION_IME_ENTER` (API 30+) |

## スクロール

| op | 追加 | 説明 |
|---|---|---|
| `scroll` | `dir=forward|backward` | `ACTION_SCROLL_FORWARD/BACKWARD` |
| `scrollUp/Down/Left/Right` | | 方向つき (API 23+) |
| `pageUp/Down/Left/Right` | | ページ単位 (API 29+) |
| `scrollToPos` | `row,col` | 座標指定スクロール (API 23+) |

補足: WebView は多くの場合 `scroll dir=forward` のみ実装 (方向系は失敗する) → [a11y-quirks.md](a11y-quirks.md)。

## 待機 / アサート

| op | 追加 | 説明 |
|---|---|---|
| `waitFor` | `timeoutMs`, `mode`, `intervalMs` | ノード出現待ち |
| `waitClick` | 同上 + click | 出現待ち → 即 click。1 step で combo |
| `waitForGone` | `timeoutMs` | 消失待ち |
| `assert` | | 存在確認 (失敗で step 失敗) |
| `assertGone` | | 非存在確認 |
| `sleep` | `ms` | 単純待機 |

**`waitFor` / `waitClick` の追加フィールド:**

| フィールド | 型 | デフォルト | 説明 |
|---|---|---|---|
| `mode` | `"event"` \| `"poll"` | `event` | `event`: `AccessibilityEvent` で起床、CPU 0。`poll`: `intervalMs` 毎に再探索 (a11y event が飛ばない稀な UI の保険) |
| `intervalMs` | long | 30 | poll mode のみ有効。event mode でも fallback として使う |
| `region`, `ancestorId`, `visibleOnly`, `clickableOnly` | | | [selectors.md](selectors.md) の追加フィルタ全部使える |

例:
```json
{"op":"waitClick",
 "by":"descContains","value":"今日。",
 "region":{"y1":1400,"y2":1550},
 "timeoutMs":500,"mode":"event"}
```

## グローバル

| op | 追加 | 説明 |
|---|---|---|
| `back` / `home` / `recents` | | `GLOBAL_ACTION_*` |
| `notifications` / `quickSettings` / `powerDialog` | | 同上 |
| `lockScreen` / `splitScreen` / `allApps` | | 同上 (API 別) |
| `dpad` | `dpad=up/down/left/right/center` | D-pad ナビ (API 33+) |
| `a11yShortcut` / `a11yButton` | | a11y ショートカット |
| `headsetHook` | | ヘッドセットボタン |
| `screenshotSystem` | | システム SS (API 28+) |

## スクリーンショット / ダンプ

| op | 追加 | 出力先 |
|---|---|---|
| `screenshot` | `tag` | `/sdcard/Download/autoact/screenshots/shot_<ts>_<tag>.png` |
| `dumpUi` | `tag` | `/sdcard/Download/autoact/dumps/dump_<ts>_<tag>.txt` |

## シナリオ例

```json
{
  "steps": [
    {"op":"launch", "value":"org.mozilla.firefox"},
    {"op":"waitFor", "by":"idContains", "value":"URL_BOX", "timeoutMs":5000},
    {"op":"click", "by":"idContains", "value":"URL_BOX"},
    {"op":"setText", "by":"focused", "text":"https://example.com"},
    {"op":"imeEnter", "by":"focused"},
    {"op":"sleep", "ms":2000},
    {"op":"screenshot", "tag":"loaded"}
  ]
}
```

参考: [selectors.md](selectors.md), [automation-patterns.md](automation-patterns.md)
