# セットアップ

AutoAct を clone してから APK を端末で動かすまでの手順。

## 環境固有ファイル (リポジトリに含まれない)

以下 4 種は端末・開発環境に紐付くので gitignore で除外している。各自用意する。

| ファイル | 用途 | 入手方法 |
|---|---|---|
| `android.jar` | Android SDK ヘッダ (API 34 前提) | `$ANDROID_HOME/platforms/android-34/android.jar` をコピー or symlink |
| `debug.keystore` | APK 署名鍵 | 自分で生成 (下記) |
| `build.sh` | ビルドスクリプト | 開発環境向けに書く。Termux 用テンプレートは [build.md](build.md) |
| `flick/{keymap,alpha_qwerty,alpha_flick_up,modes_dump,symbol_A}.json` | Gboard キー座標 (1080×2392 縦画面固有) | `flick/probe*.py` を実機で走らせて再生成 |

### debug.keystore 生成

```sh
keytool -genkey -v -keystore debug.keystore -storepass android \
    -alias androiddebugkey -keypass android \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Android Debug,O=Android,C=US"
```

### android.jar

Android Studio / sdkmanager で API 34 platform を入れた後:

```sh
ln -s "$ANDROID_HOME/platforms/android-34/android.jar" ./android.jar
```

別 API level を使う場合は `AndroidManifest.xml` の `targetSdkVersion` と一致させる。

## ビルド

環境ごとに手段が異なる:

- **Termux**: `build.sh` を書く ([build.md](build.md) にテンプレート)。gradle 不使用で aapt2/javac/d8/apksigner 直パイプ
- **Android Studio / gradle**: `src/` `res/` `AndroidManifest.xml` をそのまま新規プロジェクトに import して build。ジェネリック interface / 匿名内部クラスの制約は d8 3.3 特有 ([build.md](build.md#制約-d8-33-由来)) なので現行 AGP なら不要

## 端末インストール & 有効化

1. APK を端末に転送 (`adb install` or `aa install path=/sdcard/foo.apk`)
2. Android の **設定 > ユーザ補助 (Accessibility)** > AutoAct を **オン**
3. 起動後、TCP `127.0.0.1:8765` で待ち受け開始
4. `aa health` で疎通確認 — `{"ok":true, ...}` が返れば OK

`aa` CLI は Termux/Linux/macOS/`adb shell` 等どこでも動く (bash /dev/tcp + python3 のみ)。
別端末や adb フォワード先を叩く場合は `AUTOACT_HOST` / `AUTOACT_PORT` を設定 ([cli.md](cli.md))。

## flick を使う場合の追加セットアップ

Gboard 日本語12キー配列 + 1080×2392 縦画面前提。他解像度なら以下を実機で走らせて JSON を再生成:

```sh
python3 flick/probe.py            # ひらがなキー座標 → keymap.json
python3 flick/probe_alpha.py      # 英数キー → alpha_qwerty.json
python3 flick/probe_alpha_flick.py # 英数フリック → alpha_flick_up.json
python3 flick/probe_modes.py      # モード探索 → modes_dump.json
python3 flick/probe_symbol.py     # 記号ページ → symbol_A.json
```

コード内のハードコード座標 (`flick.py` の `ALPHA_KEYS` 等) も端末解像度依存なので、
`flick/design.md` を見て必要なら手で調整。

## トラブルシューティング

うまく動かない場合は [`troubleshooting.md`](troubleshooting.md) を参照。
