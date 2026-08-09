# 自動化レシピ集

実動確認済みの手順集。コマンド行のママ叩ける。

## 1. Firefox で任意 URL を開く

```bash
aa launch package=org.mozilla.firefox
sleep 2
# URL バーがすでに edit mode なら:
aa setText by=focused text=https://example.com
aa imeEnter by=focused
# edit mode でなければ先に:
#   aa click by=idContains value=ADDRESSBAR_URL_BOX
#   aa click by=idContains value=SEARCH_BOX     # 保険
```

補足:
- Compose test-tag `ADDRESSBAR_URL_BOX` / `ADDRESSBAR_SEARCH_BOX` は `by=id` では取れず `by=idContains` が必要
- IME の Enter ではなく a11y の `imeEnter` を使う (安定)

## 2. WebView のスクロール探索

```bash
aa scroll by=classContains value=WebView dir=forward     # 1画面下へ
aa scroll by=classContains value=WebView dir=backward    # 1画面上へ
# 微調整はジェスチャで:
aa swipe x1=540 y1=1800 x2=540 y2=600 durMs=400
aa curveSwipe x1=540 y1=400 cx=300 cy=1200 x2=540 y2=1900 durMs=600
```

`scrollDown`/`pageDown` は WebView が action 未実装のため失敗。`scroll dir=forward` が確実。

## 3. Web ページのテキスト読み取り

```bash
aa dumpUi tag=page
# → /sdcard/Download/autoact/dumps/dump_<ts>_page.txt
grep 'text="[A-Z]' <ダンプ> | grep -v systemui   # 本文候補
```

`text="…"` を抜き出せば `<p>` / `<h*>` の中身が全部取れる。リンクは `desc="…"` 側にラベルがある。

## 4. アプリ内のリンク・カードをタップ

Compose のカードは text ノードそのものが `C=0` のことが多い。親の `C=1` を descContains で当てるのが確実:

```bash
aa click by=descContains value="The Organization How"
```

## 5. Termux で新タブ + コマンド投入

```bash
aa launch package=com.termux
aa multiSwipe x1=10 y1=1200 x2=800 y2=1200 startSpan=100 durMs=400   # 2本指ドロワー
aa click by=text value="NEW SESSION"
# キー連打で入力 (Gboard):
for k in s l e e p; do aa click by=desc value=$k; done
aa click by=desc value=スペース
for k in 5 4 3; do aa click by=desc value=$k; done
aa click by=desc value=Enter
```

外側 bash から `pgrep -af "sleep 543"` で走行確認可。

## 6. 電卓で計算 (Android 標準)

```bash
aa launch package=com.google.android.calculator
sleep 1
aa click by=desc value=7
aa click by=desc value=multiply
aa click by=desc value=8
aa click by=desc value=plus
aa click by=desc value=3
aa click by=desc value=equals
aa find by=id value=com.google.android.calculator:id/result_final   # → text=59
```

## 7. APK インストール自動化

```bash
aa install path=/sdcard/Download/foo.apk tag=demo
aa waitFor by=textContains value=インストール timeoutMs=8000
aa click by=text value=インストール
aa waitFor by=textContains value=開く timeoutMs=30000
# ← ここで install_status が SUCCESS になる
```

## 8. 定期スクショで状態変化を追う

```bash
for i in $(seq 1 10); do
  aa screenshot tag=poll_$i
  aa find by=id value=com.woheller69.audio_analyzer_for_android:id/textview_peak
  sleep 1
done
```

グラフ本体は取れなくても、`textview_peak` のような要約 TextView は毎回更新されて取れる。

## 9. シナリオ JSON 化

上記を再利用可能にする:

```json
{
  "steps": [
    {"op":"launch","value":"org.mozilla.firefox"},
    {"op":"sleep","ms":2000},
    {"op":"setText","by":"focused","text":"https://example.com"},
    {"op":"imeEnter","by":"focused"},
    {"op":"sleep","ms":2000},
    {"op":"screenshot","tag":"example_loaded"}
  ]
}
```

保存: `/sdcard/Download/autoact/scenarios/example.json`
実行: `aa run name=example`

参考: [steps.md](steps.md), [selectors.md](selectors.md), [a11y-quirks.md](a11y-quirks.md)
