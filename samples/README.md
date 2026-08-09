# AutoAct — サンプルシナリオ

これらの JSON を端末の **`Download/autoact/scenarios/`** にコピーすると
アプリのリストから選べる。

```sh
# ADB や Termux から:
mkdir -p /sdcard/Download/autoact/scenarios
cp samples/*.json /sdcard/Download/autoact/scenarios/
```

## 中身

- `self_smoke.json` — AutoAct 自身の画面で dumpUi と assert を試す最小シナリオ。
  最初に走らせる用。
- `settings_open_apps.json` — 設定アプリを前面にした状態で「アプリ」項目を探して
  タップ → 戻る。日本語 UI 前提。合わなければ dump 出力を見て `value` を変える。

## JSON スキーマ

```
{
  "name": "…",                // 必須。ログに出る
  "targetPackage": "…",       // ここと違う app が前面に来たら自動 STOP
  "defaultTimeoutMs": 5000,   // 各 Step の timeoutMs 既定
  "maxDurationMs": 300000,    // シナリオ全体の上限
  "steps": [ … ]
}
```

### op 一覧

| op | 追加フィールド | 説明 |
|---|---|---|
| `waitFor`   | `by`, `value`, `timeoutMs` | ノード出現待ち |
| `click`     | `by`, `value`              | タップ (clickable でなければ親を辿る) |
| `longClick` | `by`, `value`              | 長押し |
| `tap`       | `x`, `y`, `durMs`          | 座標タップ |
| `swipe`     | `x1`,`y1`,`x2`,`y2`,`durMs`| 座標スワイプ |
| `setText`   | `by`, `value`, `text`      | 入力欄に文字列セット |
| `scroll`    | `by`, `value`, `dir`       | forward / backward |
| `assert`    | `by`, `value`              | 見つからなければ失敗 |
| `sleep`     | `ms`                       | スリープ |
| `back`      | -                          | 戻るキー |
| `home`      | -                          | ホーム |
| `recents`   | -                          | 履歴 |
| `dumpUi`    | `tag`                      | 現在の UI ツリーを dumps/ に出力 |

`by` は `text` / `id` / `desc` の 3 種。すべての Step で共通の `retries` (既定 0) を持てる。
