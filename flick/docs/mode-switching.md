# モード切替

Gboard は 3 モード + 記号 3 バリアントを持ち、それぞれの遷移経路と使用可能キーが違う。

## モード一覧

| モード | 判定 (a11y) | flick.py 判定関数 |
|---|---|---|
| hira (12キー) | `key_pos_ja_12keys_*` が存在 | `in_hira_mode()` |
| alpha (QWERTY) | `key_pos_shift` が y=2040 に | `in_alpha_mode()` |
| symbol | `key_pos_back_to_prime` が存在 | `in_symbol_mode()` |

symbol はさらに 3 バリアント:

| バリアント | 内容 | 判定 (shift の desc) |
|---|---|---|
| A1 | QWERTY 記号 1ページ目 | `"その他"` を含む |
| A2 | QWERTY 記号 2ページ目 | `"記号"` exact |
| B  | 数字パッド | shift 自体が無い |

`flick.symbol_state()` が `'A1'/'A2'/'B'/None` を返す。

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

## symbol variant の癖: **last-used が開く**

`key_pos_switch_to_symbol` をクリックすると、Gboard は **直前に開いていた symbol variant を再表示** する:

- 直前が A1 → A1 が開く
- 直前が A2 → A2 が開く
- 直前が B (数字パッド) → B が開く

つまり最初に何が来るか予測不能。`ensure_symbol_A1() / ensure_symbol_A2()` で正規化する:

```python
def ensure_symbol_A1():
    switch_to_symbol()
    st = symbol_state()
    if st == "B":
        # B → A: canvas (325, 2170) を tap (「1234」/「!?#」トグル)
        send("swipe", {x1:325,y1:2170,x2:325,y2:2171,durMs:30})
        st = symbol_state()  # 再判定
    if st == "A2":
        # A2 → A1: shift クリック
        send("click", {"by":"id","value":KEY_SHIFT})
        st = symbol_state()
    return st == "A1"

def ensure_symbol_A2():
    ensure_symbol_A1()  # まず A1 に正規化
    send("click", {"by":"id","value":KEY_SHIFT})  # A1 → A2
    return symbol_state() == "A2"
```

## 遷移でハマった罠

### 罠1: alpha→hira は switch_hiragana_alphabet じゃ戻れないことがある

- **原因**: alpha モードには `key_pos_switch_hiragana_alphabet` が a11y に露出していない
- **状況**: 底列に「あA」ボタンが物理的にない Gboard 設定になっている場合。space バーが「QWERTY」ラベル表示になっている
- **回避**: 一度 hira にたどり着ければあとは `switch_hiragana_alphabet` で往復できる。初回だけ手動 setup が必要な場合あり

### 罠2: symbol→alpha を key_pos_switch_hiragana_alphabet で試みる

- **症状**: `switch_to_alpha` が symbol モードから呼ばれると、`key_pos_switch_hiragana_alphabet` が symbol にも無いので silent fail
- **修正**: `switch_to_alpha()` は symbol モードなら先に `key_pos_back_to_prime` を叩いて alpha に戻る
  ```python
  def switch_to_alpha():
      if in_alpha_mode(): return True
      if in_symbol_mode():
          send("click", {"by":"id","value":KEY_BACK_TO_PRIME})
          if in_alpha_mode(): return True
      send("click", {"by":"id","value":KEY_SWITCH_HIRA_ALPHA})
      return in_alpha_mode()
  ```

### 罠3: back_to_prime は「直前 mode」に戻る、hira とは限らない

- `key_pos_back_to_prime` は symbol に入る前のモード (alpha または hira) に戻す
- hira→symbol→back なら hira に、alpha→symbol→back なら alpha に戻る
- 意図と違うモードに戻ることがあるので、戻った後に必ず `in_*_mode()` で再判定

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
