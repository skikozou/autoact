# アーキテクチャ

## 全体像

```
┌── Python (Termux) ─────────────┐
│  type_text.py                  │  ← ユーザが叩く
│    │                           │
│    ├─ classify_char / split    │  ← 文字を mode に分類
│    ├─ janome tokenize          │  ← 漢字 → 読み変換
│    │                           │
│    └─ flick.py                 │
│         ├─ build_type_steps    │  ← ひら 12キー
│         ├─ build_alpha_steps   │  ← 英字 QWERTY
│         ├─ build_symbol_steps  │  ← 記号 A1/A2
│         ├─ switch_to_*         │  ← モード遷移
│         └─ send(cmd, args)     │
└────────────────────────────────┘
              │ TCP 127.0.0.1:8765 NDJSON
              ▼
┌── autoact APK ─────────────────┐
│  ApiHandler → ActionExecutor   │
│    ├─ swipe/tap (dispatchGest) │  ← フリック本体
│    └─ click (a11y ACTION_CLICK)│  ← space/enter/mode切替
└────────────────────────────────┘
              │ InputEvent
              ▼
┌── Gboard IME ──────────────────┐
│  日本語(12キー) / 英字 / 記号   │
└────────────────────────────────┘
              │
              ▼
   フォーカス中の EditText (テストは ru.androidtools.texteditor)
```

## 責務分担

| 層 | 役割 |
|---|---|
| `type_text.py` | 高レベル: 混在テキスト → セグメント → モード切替 + 各セグメント入力 |
| `flick.py` | 中レベル: 各モードでの座標ベース入力、モード判定/切替、候補選択 |
| `keymap.json` | データ: hira 12キー中心座標 + flick 割当 |
| `alpha_qwerty.json` | データ: 英字 36キー座標 (probe 実測) |
| `symbol_A.json` | データ: 記号 A1/A2 各キー座標 (probe 実測) |
| autoact | 低レベル: TCP API, a11y ノード検索, ジェスチャ実行 |

## データフロー例

入力: `"かかく"` (bug#2 のケース)

```
type_text.py
  classify_char() → 全て "hira"
  split_by_mode() → [("hira", "かかく")]
  run_hira("かかく")
    switch_to_hira()  → send click key_pos_switch_hiragana_alphabet
    tokenize_ja("かかく") → [("かかく","かかく")]
    type_hira_segment("かかく", "かかく")
      is_hira_typeable("かかく") → True
      flick.type_text("かかく", cfg)
        build_type_steps() → 5 step:
          [swipe ka(C), click right_arrow, swipe ka(C),
           click right_arrow, swipe ka(U)]
        send exec → autoact 一括実行
      orig == hira → commit_hiragana() → click key_pos_ime_action

autoact
  swipe (540,1645)→(540,1646)  → tap "か"
  click key_pos_right_arrow    → 未確定「か」を確定 + 右移動
  swipe (540,1645)→(540,1646)  → tap "か"
  click key_pos_right_arrow    → 確定
  swipe (540,1645)→(540,1545)  → flick↑ "く"
  click key_pos_ime_action     → 確定
```

結果: `かかく` (3文字) が入力される。

## 依存

- **autoact APK** — TCP loopback API, 127.0.0.1:8765
- **Gboard IME** — `com.google.android.inputmethod.latin` が active IME
- **janome** — Python 純粋実装の形態素解析 (`pip install janome`)
- **対象 EditText** — テストは `ru.androidtools.texteditor`

## 拡張ポイント

- 別解像度対応: `keymap.json` と `alpha_qwerty.json`, `symbol_A.json` を probe で作り直す
- 別 IME 対応: `flick.py` のキー ID/座標定数を差し替え (現状 Gboard 直結)
- カタカナ入力: 現状は変換候補経由 (`find_candidate_by_prefix`) で拾う
