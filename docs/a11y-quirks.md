# AccessibilityService の癖・落とし穴

## WebView (Firefox/Chrome/WebView-based)

### 見えるもの
- テキストコンテンツ (`<p>`, `<h1>`, `<a>` 等) は `android.view.View` として露出
- text = 表示文字列、desc = ラベル (リンクは desc="Learn more" 等)
- リンク・ボタンは親を辿ると `C=1` (clickable) が付く View にヒットすることが多い

### 見えないもの
- Canvas 描画 (`<canvas>` 内の中身)
- CSS 背景画像、SVG の中身
- 動画/音声内部

### 座標系
- WebView 内のノードの `bounds` は **画面座標** (スクロール反映後)
- WebView 自体は `S=1` (scrollable)、`scroll dir=forward|backward` のみ動作。`scrollUp/Down/pageDown` は失敗する

### id が空
`getViewIdResourceName()` は空文字。→ **text/desc/classContains で探すしかない**

## Jetpack Compose

### test-tag の落とし穴
- Compose の `Modifier.testTag("FOO")` は `getViewIdResourceName()` に **`pkg:id/` プレフィックスなしで返る** (`"FOO"` そのまま)
- そのため `findAccessibilityNodeInfosByViewId("FOO")` は **絶対にヒットしない**
- 解決: `by=idContains value=FOO` (ウォークサーチ)

例: Firefox の URL バーは `ADDRESSBAR_SEARCH_BOX` / `ADDRESSBAR_URL_BOX` (edit mode 切替で入れ替わる)

### wheel picker / snap
- Compose の wheel `LazyList` は `|delta|=1` の swipe を snap 境界で **無視することがある**
- 対策: `durMs` を短く (100ms 程度)、`|delta|>=2` にする

## Canvas / SurfaceView

- グラフや波形描画 (Audio Analyzer 等) は **a11y ツリーに現れない**
- 読み取りたいなら screenshot → 画像処理
- 数値サマリ (peak, RMS) は別途 `TextView` に出ていれば取れる

## IME (Gboard)

- 各キーが独立ノード (`desc="a"`, `desc="スペース"`, `desc="Enter"`, `desc="Shift"` 等)
- **記号は別レイアウト** → `desc="記号キーボード"` タップで切り替え
- **オートコレクトが暴発する** → 長文入力は setText を使う (`by=focused`) 方が安全
- IME のポップアップ候補も別ノード

## Termux ターミナル

- `id=com.termux:id/terminal_view` の **`desc` に画面全体のテキストが 1 発で入っている**
- スクロールで見える範囲のみ、履歴はスクロール操作が必要
- タブ・ドロワーの UI 部品も普通に a11y 露出

## システム UI

- ステータスバー / ナビバーは `com.android.systemui` として window に出る
- 通知シェードは `aa key name=notifications` で展開できるが、`getWindows()` の順序変化に注意

## ダイアログ / ポップアップ

- 別 window として来ることが多い → `NodeFinder` は `getWindows()` を全走査するので取れる
- IME 上のオーバーレイは順序に注意 (layer)

## bounds のズレ

- 一部 Compose ノードで `bounds` が古い値のことがある (再レイアウト前)
- `waitFor` + 少し `sleep` を挟むと安定

## `text` vs `contentDescription`

- 同一ノードに両方入ることも、片方だけのことも
- 検索が空振りしたら **もう片方も試す** のが定石

参考: [selectors.md](selectors.md), [automation-patterns.md](automation-patterns.md)
