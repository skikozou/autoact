# セレクタ (`by` 戦略)

`NodeFinder.find(svc, by, value)` の `by` 一覧。

## 高速パス (Android 内部インデックス使用)

| `by` | 内部 API | マッチ |
|---|---|---|
| `id` | `findAccessibilityNodeInfosByViewId` | **完全一致** かつ `pkg:id/name` フル形式 |
| `text` | `findAccessibilityNodeInfosByText` | 部分一致 (完全一致優先) |
| `textExact` | 同上 → filter | 完全一致のみ |
| `textContains` | 同上 | 部分一致 |

## ウォークサーチ (DFS)

| `by` | マッチ対象 | 判定 |
|---|---|---|
| `desc` | `getContentDescription()` | 完全一致 |
| `descContains` | 同上 | 部分一致 |
| `classContains` | `getClassName()` | 部分一致 (`WebView`, `EditText` 等) |
| `idContains` | `getViewIdResourceName()` | 部分一致 |

## 特殊

| `by` | 説明 |
|---|---|
| `focused` | `findFocus(FOCUS_INPUT)` → 無ければ `FOCUS_ACCESSIBILITY`。`value` 不要 |

## 追加フィルタ (`FindSpec`)

`by/value` に加えて以下を任意で渡せる。`find` cmd / `waitFor`/`waitClick`/`click`/`setText` 等 step 全部で共通。

| フィールド | 型 | 説明 |
|---|---|---|
| `region` | `{x1,y1,x2,y2}` or `{x,y,w,h}` | node 中心 or bounds がこの矩形内でなければ skip。tree 全走査を打ち切る効果が大きい |
| `ancestorId` | string (`pkg:id/foo`) | 指定 id の subtree だけ探索。座標変動に強い |
| `visibleOnly` | bool | `isVisibleToUser()` のみ許可 |
| `clickableOnly` | bool | `isClickable()` のみ許可 (直接クリック用) |
| `limit` | int | `find` cmd での返却上限 (default 20)。walk が limit 満たしたら break で早期 return |

例:
```json
{"cmd":"find","args":{
  "by":"descContains","value":"今日。",
  "region":{"y1":1400,"y2":1550},
  "limit":1
}}
```

**効き所**: descContains 等の tree DFS 系は region で bounds skip すると数百 ms → 数十 ms に落ちる。詳細は [plan-abcd.md](plan-abcd.md)。

## 使い分け

- **確実な id 一致**: `by=id` (`pkg:id/name` を書ける場合のみ)
- **Compose の test-tag**: `pkg:id/` プレフィックスが付かないので `by=idContains value=SEARCH_BOX` を使う
- **リンクや DL ボタン**: `by=desc` (アクセシビリティラベル)
- **入力欄への setText**: 直前タップで focus 済みなら `by=focused` が最速
- **一覧の中の項目**: `by=textContains` (`.` や記号を避けた部分)
- **WebView の要素**: text or descContains しか使えない (id は空、class は `android.view.View` に潰される) → [a11y-quirks.md](a11y-quirks.md)

## 探索 tips

1. `aa dumpUi tag=cur` で現状のツリーを吐く
2. 検索したいテキスト/id が含まれる行を `grep` で確認
3. `bounds`, `class`, `C=1`(clickable), `F=1`(focused) を見て戦略決定

## 検索範囲

- まず全ウィンドウ (`AccessibilityService.getWindows()`) をルートから DFS
- 空なら `getRootInActiveWindow()` にフォールバック

## リサイクル
`NodeFinder.find` の戻り値 (非 null) は呼び出し側で `recycle()` 必須 (autoact 内部は正しくやっている)。

参考: [steps.md](steps.md), [a11y-quirks.md](a11y-quirks.md)
