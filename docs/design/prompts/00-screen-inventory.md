# 画面インベントリ（確定 2026-08-25）

全画面 **A（gpt-image-2 画像）と B（Claude design コード）の両方**でプロンプトを用意する（2案比較）。
A生成は必ず 01 ホーム画面（基準画面）から順に行う。

| # | 画面名 | 対応spec | 含める状態 | A生成順 |
|---|---|---|---|---|
| 01 | ホーム画面（書籍一覧） | [03-book-project](../../specs/03-book-project.md) §3.2 | 空一覧 / データあり / 検索絞り込み | **1（基準画面）** |
| 02 | 書籍作成・編集画面 | [03-book-project](../../specs/03-book-project.md) §3.1 | 通常 / バリデーションエラー | 2 |
| 03 | 書籍詳細画面 | [03-book-project](../../specs/03-book-project.md) §3.4 | 通常（統計+導線） | 3 |
| 04 | ごみ箱画面 | [03-book-project](../../specs/03-book-project.md) §3.3 | データあり / 削除確認ダイアログ | 4 |
| 05 | 撮影準備画面 | [04-capture-session](../../specs/04-capture-session.md) §3.2 | 許可済み / オーバーレイ未許可（開始無効） | 5 |
| 06 | フローティングUI | [04-capture-session](../../specs/04-capture-session.md) §3.4 | 手動:撮影中・停止中 / 連続:一時停止・保存枚数 | 6 |
| 07 | ページ一覧画面 | [08-page-editing](../../specs/08-page-editing.md) §3.1 | グリッド/リスト / 複数選択+削除確認 / 警告バッジ | 7 |
| 08 | 回転・切り取り編集画面 | [08-page-editing](../../specs/08-page-editing.md) §3.2 | crop編集 / 一括適用 | 8 |
| 09 | 重複候補比較・黒画面候補一覧 | [08-page-editing](../../specs/08-page-editing.md) §3.2 | 比較ペア / 黒画面一覧 | 9 |
| 10 | OCR編集画面 | [09-ocr](../../specs/09-ocr.md) §3.5 | 分割表示 / 修正中 | 10 |
| 11 | 書き出し画面 | [11-export](../../specs/11-export.md) §3.2 | 形式選択 / OCR未完了警告 / 進捗 | 11 |
| 12 | 初回同意画面 | [12-legal-guardrails](../../specs/12-legal-guardrails.md) §3.1 | 4点表示・未同意 | 12 |

- 削除確認ダイアログは独立画面にせず 01/03/04/07 の状態として扱う
- specs に無い画面は作っていない（アプリ設定の単独画面は specs に存在しないため対象外）

## 採用フロー（生成後のユーザー手番）

1. A: `a-image/` を生成順どおり gpt-image-2 へ1画面ずつ / B: `b-code/brief.md` を Claude design 新規セッションへ1回貼る
2. 画面ごとに2案を比較。**迷ったらBを採用**（トークン・文言が正確で実装の移植元になる）
3. 採用A → `docs/design/mockups/<screen>.png` / 採用B → `docs/design/reference/` + `SCREENS.md` に採用画面だけ記載。
   **同一画面を両方に置かない**（design-writer が停止する）
4. 不採用案を残すなら `docs/design/candidates/<screen>/`
5. 全画面採用が済んだら `/design-writer`
