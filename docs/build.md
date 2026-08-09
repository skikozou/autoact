# ビルドパイプライン

Gradle 不使用の Termux ビルド構成を記載。他の環境 (Android Studio, gradle) で組む場合は `src/` `res/` `AndroidManifest.xml` をそのまま import すればよい。d8 3.3 特有の制約 (下記) は現行 AGP なら不要。

`build.sh` と `debug.keystore` は環境固有なのでリポジトリには含めていない。生成方法は [setup.md](setup.md) 参照。

## 使用ツール

| ツール | 役割 |
|---|---|
| `aapt2 compile` | XML リソース (layout, values, xml) → `.flat` |
| `aapt2 link` | `.flat` + `AndroidManifest.xml` + `android.jar` → 生 apk + `R.java` |
| `javac` (JDK 21) | Java → `.class` (target 8) |
| `d8` | `.class` → `classes.dex` (D8 3.3) |
| `aapt` (v1) | `.dex` を APK に注入 |
| `zipalign` | ZIP アラインメント |
| `apksigner` | 署名 (debug.keystore) |

## 実行

```bash
cd autoact && ./build.sh
# → build/app-debug.apk
```

出力: `build/app-debug.apk` (45KB 程度)。
配布: `/sdcard/Download/autoact.apk` にコピーして `aa install path=...`。

### Termux 用 build.sh テンプレート

```sh
#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
BUILD="$ROOT/build"; GEN="$BUILD/gen"; OBJ="$BUILD/obj"; COMPILED="$BUILD/compiled"
ANDROID_JAR="$ROOT/android.jar"; KEYSTORE="$ROOT/debug.keystore"
UNALIGNED="$BUILD/app-unaligned.apk"; ALIGNED="$BUILD/app-aligned.apk"; SIGNED="$BUILD/app-debug.apk"

rm -rf "$BUILD"; mkdir -p "$GEN" "$OBJ" "$COMPILED"
aapt2 compile --dir "$ROOT/res" -o "$COMPILED"
aapt2 link -I "$ANDROID_JAR" --manifest "$ROOT/AndroidManifest.xml" \
    --java "$GEN" -o "$UNALIGNED" $(ls "$COMPILED"/*.flat)
javac -source 1.8 -target 1.8 -bootclasspath "$ANDROID_JAR" -classpath "$ANDROID_JAR" \
    -d "$OBJ" $(find "$ROOT/src" "$GEN" -name '*.java')
d8 --lib "$ANDROID_JAR" --output "$BUILD" $(find "$OBJ" -name '*.class')
( cd "$BUILD" && aapt add "$UNALIGNED" classes.dex )
zipalign -f 4 "$UNALIGNED" "$ALIGNED"
apksigner sign --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android \
    --out "$SIGNED" "$ALIGNED"
```

依存: `pkg install aapt2 openjdk-21 apksigner zipalign` + d8 (`android-tools` / `dx`)。

## 制約 (d8 3.3 由来)

Termux の d8 は `d8/stable 33.0.1-1` (D8 3.3 系、2022) が最新でアップデート手段がない。
このバージョンは javac 21 が出力する `InnerClasses` / `Signature` 属性を正しくパースできず、
以下のパターンで `NullPointerException: Cannot invoke "String.length()" because "<parameter1>" is null`
を投げて dex 変換に失敗する。つまり以下は**書けない**:

### 匿名内部クラス / private inner class 禁止
```java
// NG: NPE in D8 during dex
setOnClickListener(new View.OnClickListener() { ... });

// OK: トップレベル or public static ネスト
class MyClickListener implements View.OnClickListener { ... }
```

### ジェネリック interface 実装禁止
```java
// NG: Comparator<Foo> の raw 型化で d8 NPE
class MyCmp implements Comparator<File> { ... }

// OK: raw 型で書く
class MyCmp implements Comparator { public int compare(Object a, Object b) {...} }
```

これらは autoact 内でも徹底されており、`FileNameComparator.java` は raw 型実装、コールバック系は全部トップレベルクラス (`GestureCallback`, `ScreenshotCallback`, `RefreshRunnable`, `DumpRunnable`, `VolStopRunnable`, `RunnerBroadcastReceiver` 等)。

## Java target
- source/target = 8 (obsolete 警告出るが無視)
- API level は android.jar のバージョンで決まる (現状 API 34 相当)

## リソース最小化
- `res/layout/` — 1画面 `activity_main.xml`
- `res/values/` — 文字列と style
- `res/xml/` — `accessibility_service_config.xml`

## 署名鍵
`debug.keystore` はリポジトリに含めていない ([setup.md](setup.md#debug-keystore-生成) で自前生成)。プロダクション配布時は当然リリース鍵に差し替え。

## 検証
```bash
aapt dump badging build/app-debug.apk | head -20   # パッケージ情報
apksigner verify build/app-debug.apk               # 署名検証
```
