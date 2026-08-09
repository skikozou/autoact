# `aa` CLI

`samples/bin/aa` は API を叩く zero-deps bash ラッパ (`bash /dev/tcp` + `python3` のみ)。

## 対応環境

bash 2.04+ + python3 があれば動く。Termux, Linux, macOS, WSL, `adb shell` (bash が入ってる端末) など。

## セットアップ

```bash
export PATH=/path/to/autoact/samples/bin:$PATH

# 接続先は環境変数で上書き可 (デフォルト 127.0.0.1:8765)
export AUTOACT_HOST=127.0.0.1
export AUTOACT_PORT=8765
```

**別端末や adb フォワード経由で叩く例**:

```bash
# 開発機 → USB adb で端末の 8765 を localhost に転送
adb forward tcp:8765 tcp:8765
aa health   # 開発機の bash から実機の autoact を叩ける

# 別ホストの autoact を叩く
AUTOACT_HOST=192.168.1.50 aa health
```

## 呼び出し形式

3 形式:

```bash
# 1. k=v 引数 (推奨、簡易)
aa <cmd> [key=value ...]

# 2. --json で args を直接渡す
aa <cmd> --json '{"key":"value"}'

# 3. raw で cmd 込みの JSON を丸ごと送る
aa raw '{"cmd":"tap","args":{"x":500,"y":500}}'
```

## 型解釈 (k=v モード)
- 全部数字 → number: `x=500` → `{"x":500}`
- `true`/`false` → bool
- その他 → 文字列 (Python の `json.dumps` で安全にエスケープ)

## よく使う例

```bash
# 情報取得
aa health
aa top
aa screen
aa find by=text value=OK limit=5
aa dumpUi tag=cli
aa screenshot tag=cli

# ジェスチャ
aa tap x=500 y=800
aa swipe x1=500 y1=1500 x2=500 y2=500 durMs=400
aa multiSwipe x1=10 y1=1200 x2=800 y2=1200 startSpan=100 durMs=400   # 2本指

# ノード操作
aa click by=text value=設定
aa click by=idContains value=SEARCH_BOX
aa setText by=focused text=https://example.com
aa imeEnter by=focused
aa scroll by=classContains value=WebView dir=forward

# キー / グローバル
aa key name=back
aa dpad dir=down

# シナリオ
aa run name=self_smoke
aa run_sync name=self_smoke
aa stop
aa exec --json "$(cat samples/self_smoke.json | jq '{scenario:.}')"

# パッケージ
aa install path=/sdcard/Download/foo.apk
aa uninstall package=com.example.foo
aa install_status
aa packages filter=firefox
aa launch package=org.mozilla.firefox
```

## 注意

- NDJSON プロトコル → 送信前に `\n\r` を除去している
- `k=v` パースは `=` 未満のトークンをエラーにする
- 複雑な JSON (ネスト、配列) は `--json` か `raw` を使う

参考: [api.md](api.md)
