# 日本語変換フロー

漢字混じり文を Gboard で入力するには **「読み → ひらがな入力 → 候補選択」** の 3 段が要る。

## 全体

```
"今日は雨"
  ↓ janome tokenize
[("今日", "きょう"), ("は", "は"), ("雨", "あめ")]
  ↓ 各 token を
    1. type_text("きょう")        # フリック入力
    2. 候補バーから "今日" を探す
    3. click_candidate("今日")   # → 「今日」確定
    ...
  ↓
"今日は雨" が入力される
```

## 1. 形態素解析 (janome)

janome は MeCab 互換の形態素解析器 (Python 純粋実装、IPADIC 内蔵):

```python
from janome.tokenizer import Tokenizer
t = Tokenizer()
for tok in t.tokenize("今日は雨"):
    print(tok.surface, tok.reading)
# 今日  キョウ
# は    ハ
# 雨    アメ
```

- 100% Python なので Termux でも `pip install janome` だけで動く (辞書同梱)
- 辞書ロードは初回 1-2 秒、以降は高速 (`type_text._tokenizer()` で module singleton)
- 読みは全て **カタカナ** で返る (`reading` 属性)

pykakasi は慣用句読みを引きずり `今日は` → `こんにちは` と誤変換するため不採用 (経緯は [experiments.md Ph.2](experiments.md))。

### カタカナ → ひらがな

そのままだと Gboard に入力できないので変換:

```python
def kata_to_hira(s):
    return "".join(
        chr(ord(c) - 0x60) if 0x30A1 <= ord(c) <= 0x30F6 else c
        for c in s
    )
```

Unicode `U+30A1..U+30F6` (カタカナ) から `-0x60` でひらがな `U+3041..U+3096` へ。

### 記号読みの落とし穴

janome は記号を `reading="*"` で返すことがある:

```python
reading = tok.reading if tok.reading != "*" else surface  # フォールバック
```

## 2. ひらがなを Gboard に流し込む

`flick.type_text(hira, cfg)` が `build_type_steps` で step 列を作って autoact に投げる。詳細は [input-pipeline.md](input-pipeline.md)。

## 3. 候補バーから漢字を選ぶ

### 候補バー構造 (Gboard)

- 位置: `RecyclerView bounds=[5,1420,965,1536]` (y 1420-1536 の帯)
- 各候補: `FrameLayout, clickable=true`
- desc の形式: `"<候補>。<各漢字の読み解説>"`
  - 例: `"今日は雨。コンゲツ ノ コン，イマ。オンナ，ジョセイ。…"`
- 見えるのは 4 件 + 右端「その他の候補」ボタン (`key_pos_show_more_candidates`)

### 探し方 (`find_candidate_by_prefix`)

```python
def find_candidate_by_prefix(target):
    # 1) desc が "<target>。" で始まる候補を探す
    r = send("find", {"by":"descContains","value":target+"。","limit":30})
    for m in r.get("result",{}).get("matches",[]):
        b = m.get("bounds",{})
        if m["desc"].startswith(target+"。") and 1400 <= b["top"] <= 1550:
            return m
    # 2) fallback: desc == target (最後の候補で "。" が無いパターン)
    r = send("find", {"by":"descContains","value":target,"limit":30})
    for m in r.get("result",{}).get("matches",[]):
        b = m.get("bounds",{})
        if m["desc"] == target and 1400 <= b["top"] <= 1550:
            return m
    return None
```

- `descContains` で **ウォークサーチ**して bounds が候補バー帯 (y 1400-1550) にあるものだけ拾う
- `descContains` は部分一致だが、`target+"。"` を含む条件で誤爆低減
- `by=id` は使えない (候補は動的生成で ID なし)

### 出現待ちは polling (`_poll_candidate`)

`type_text._poll_candidate(orig, timeout=0.5, interval=0.03)` が `find_candidate_by_prefix` を 30ms 間隔で叩き、ヒット即返す (上限 500ms)。a11y `find` 1 発は ~15-30ms なので poll ループ自体のコストは小さい。

### click (`click_candidate`)

```python
def click_candidate(node):
    return send("click", {"by":"descContains","value":node["desc"]})
```

desc をキーに click し直す (直接ノード click 経路が autoact に無いので find→click の 2 段構え)。

## 4. 変換候補にヒットしないとき (pending 課題)

- 現状: 上位 4 件 + 「その他」トグル未展開だと拾えない → hira commit にフォールバック
- 対処案 (未実装):
  1. `key_pos_show_more_candidates` を click して展開
  2. 拡張候補一覧の RecyclerView 内で再検索
  3. 見つからなければひらがな確定 + ログ

## 5. 確定コマンド

| 目的 | コマンド | 動作 |
|---|---|---|
| ひらがな確定 (未確定→確定, 改行入らず) | `click by=id key_pos_ime_action` | Enter の a11y ACTION_CLICK |
| 最初候補で確定 (space 由来) | `click by=id key_pos_space` | space 送出で最有力候補確定 |
| 特定候補 | 上記 `find_candidate_by_prefix` + `click` | 名前指定 |

**⚠ tap で叩くと Gboard がキーボード切替ダイアログを出す**。原因: autoact の dispatchGesture が Gboard の gesture recognizer に長押し扱いされる。必ず `click by=id` (a11y ACTION_CLICK) を使う。

### 改行/スペースが hira トークンで来た場合の注意

`type_hira_segment(orig, hira, ...)` は入力後に `commit_hiragana()` (=`click key_pos_ime_action`) を呼ぶ設計だが、`orig` が中立文字 (`\n`/半角スペース/`\u3000`) のみのときは `build_type_steps` 内で既に KEY_ENTER / KEY_SPACE を click しており未確定状態が無い。ここで追加で `commit_hiragana()` を呼ぶと **もう一発 Enter が click されて余分な改行が挿入**される。よって中立文字トークンは commit をスキップする。

## 6. 前提: 「読みで打って候補選択」経路

Gboard を実際に叩いて入力を発火する設計上、`setText` (IME 非経由) 直接漢字投入は取れない。読み → hira 入力 → 候補選択が Gboard を経由する唯一の実用パス。他案の比較検討は [experiments.md](experiments.md) 参照。
