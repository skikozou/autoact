# モード切替

Gboard は 3 モード + 記号 3 バリアントを持ち、それぞれの遷移経路と使用可能キーが違う。

## モード一覧

| モード名 | 判定 (a11y) |
|---|---|
| `hira` (12キー) | `key_pos_ja_12keys_*` が存在 |
| `alpha` (QWERTY) | `key_pos_shift` あり / `key_pos_back_to_prime` なし |
| `sym_A1` | `key_pos_back_to_prime` + `key_pos_shift.desc` に `"その他"` |
| `sym_A2` | `key_pos_back_to_prime` + `key_pos_shift.desc == "記号"` |
| `sym_B` (数字パッド) | `key_pos_back_to_prime` あり / `key_pos_shift` なし |

`flick._current_mode()` が上記のいずれか (or `"unknown"`) を find 1 発で返す。

## 遷移マップ

```
                click key_pos_switch_hiragana_alphabet
                        ⇄
              ┌── hira ──┴─────────────────────────────┐
              │                                        │
   click key_pos_switch_to_symbol         click key_pos_switch_to_symbol
              │                                        │
              ▼                                        ▼
           symbol                                    (alpha)
              │                                        ▲
              │ click key_pos_back_to_prime            │
              └────────────────────────────────────────┘
```

**重要**:
- `key_pos_switch_hiragana_alphabet` は **hira モードでのみ存在**。alpha モードには無い。
- alpha から hira への直接切替キーは a11y 露出なし。**alpha → hira は key_pos_switch_hiragana_alphabet が使えず**、実質 hira 経由でしか戻れない。
- 現状の `switch_to_hira()` は hira ではないなら `switch_hiragana_alphabet` を叩くが、alpha 単独からは失敗する可能性あり (今後の課題)。回避策: 一度 symbol を経由するか、 setIme か、外部 IME picker。

## `ensure_mode(target)` に集約

flick.py の `ensure_mode(target)` が唯一の入口。`target` は `"hira" | "alpha" | "sym_A1" | "sym_A2"`。

内部は「1 手発火 → settle 400ms → 再判定」ループ (最大 5 手):

```python
for _ in range(max_steps):
    cur = _current_mode()
    if cur == target: return True
    _step_toward(cur, target)   # 遷移テーブルに沿って 1 click / swipe
    time.sleep(settle)
```

`_step_toward` の遷移テーブル:

| cur → | 発火する op |
|---|---|
| `sym_B` → 何処でも | canvas (325, 2170) 1px swipe (sym_A に上げる) |
| `sym_A1 ↔ sym_A2` | `click key_pos_shift` |
| `sym_A*` → `hira`/`alpha` | `click key_pos_back_to_prime` (着地は再判定) |
| `hira`/`alpha` → `sym_*` | `click key_pos_switch_to_symbol` (last-used variant に着地するので再判定) |
| `hira ↔ alpha` | `click key_pos_switch_hiragana_alphabet` |

**symbol variant の last-used 癖**は再判定ループで自動的に処理される (A1 が欲しくて A2 に着地したら次周で shift、B に着地したら次周で toggle)。

互換ラッパ (`switch_to_hira()`, `ensure_symbol_A1()`, `in_hira_mode()` 等) は `ensure_mode` / `_current_mode` の薄いエイリアス。

## 遷移でハマった罠

### 罠1: alpha→hira は switch_hiragana_alphabet じゃ戻れないことがある

- **原因**: alpha モードには `key_pos_switch_hiragana_alphabet` が a11y に露出していない
- **状況**: 底列に「あA」ボタンが物理的にない Gboard 設定になっている場合。space バーが「QWERTY」ラベル表示になっている
- **回避**: 一度 hira にたどり着ければあとは `switch_hiragana_alphabet` で往復できる。初回だけ手動 setup が必要な場合あり

### 罠2: back_to_prime は「直前 mode」に戻る、hira とは限らない

- `key_pos_back_to_prime` は symbol に入る前のモード (alpha または hira) に戻す
- hira→symbol→back なら hira に、alpha→symbol→back なら alpha に戻る
- `ensure_mode` は 1 手ずつ再判定するため、着地が想定外でも次周で修正される

### 罠4: モード切替の settle 時間

各切替後 300-400ms の sleep を入れないと、続く click/swipe が古い座標系にヒットする。
実測: 400ms で安定。

## 遷移コスト (実測)

| 遷移 | 平均時間 |
|---|---|
| hira → alpha | ~420ms (click + settle) |
| alpha → symbol A1 (last=A1) | ~420ms |
| alpha → symbol A2 (last=A1) | ~840ms (A1 経由で shift) |
| alpha → symbol A? (last=B) | ~1260ms (B→A→ さらに shift) |
| symbol → alpha (back) | ~420ms |

→ **モード切替はかなり重い**。混在テキストのセグメント数を減らすと速い。
