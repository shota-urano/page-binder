---
status: confirmed
confirmed_rev: 6f8ceab
範囲: 書籍プロジェクトの作成・編集・削除（ごみ箱）・一覧・検索、ホーム/作成編集/詳細画面
---

# 03. 書籍プロジェクト管理 仕様

**親**: [`00-overview.md`](./00-overview.md) ｜ **担当**: Frontend + Backend ｜ **範囲**: FR-PRJ-001～006、画面 §9.1～9.3

## 1. 目的

書籍・資料単位の整理の親となる BookProject の CRUD とごみ箱、ホーム画面・作成編集画面・書籍詳細画面を提供する。

## 2. 入出力

- 入力: 利用者操作（タイトル・著者・メモ、並べ替え・検索・削除操作）
- 出力: BookProject レコード（スキーマは [`02-data-model.md`](./02-data-model.md) §3.1）、プロジェクト用ファイル領域 `files/projects/{project-id}/`

## 3. 処理詳細

### 3.1 作成（UC-01、FR-PRJ-001/002/003）

1. ホーム画面「新しい書籍」→ 作成画面
2. タイトル必須（1～200文字）。著者（0～200文字）・メモ（0～2,000文字）は任意
3. 保存時に BookProject 登録と `files/projects/{project-id}/` の作成を行い、書籍詳細画面へ遷移
4. createdAt / updatedAt / ページ数は自動管理。ページ数は Page テーブルから導出し、BookProject に重複保持しない

### 3.2 一覧・検索（FR-PRJ-004、§9.1）

- 既定は更新日時降順。並べ替え: 更新日時 / 作成日時 / タイトル
- 各行: タイトル、著者、ページ数、更新日時、表紙代替サムネイル（先頭ページ画像。無ければプレースホルダ）
- 書籍検索（**確定**: タイトル・著者に対する部分一致。メモは対象外。大文字小文字・全角半角は正規化して同一視する — spec-review 2026-08-25）
- ごみ箱内（deletedAt 非null）は通常一覧に表示しない

### 3.3 削除・ごみ箱（FR-PRJ-005/006）

1. 削除前に対象タイトル・ページ数・使用容量を表示して確認する（requirements §16.4 破壊操作の確認）
2. 削除はごみ箱経由（deletedAt を設定）。一定期間は復元可能
3. 保持日数は**30日で確定**（spec-review 2026-08-25。旧・未決事項5）
4. 期限超過または「完全に削除」で、DBレコードとファイル領域を物理削除する

### 3.4 書籍詳細画面（§9.3）

表示: 書籍情報 / ページ数・OCR完了数・エラー数・使用容量
導線: 手動撮影開始・連続撮影開始（→ [04](./04-capture-session.md)）、ページ一覧（→ [08](./08-page-editing.md)）、OCR一括実行（→ [09](./09-ocr.md)）、書き出し（→ [11](./11-export.md)）、書籍設定（編集画面）

## 4. 設定値・確定値

| 項目 | 値 | 出典 |
|---|---|---|
| タイトル | 必須、1～200文字 | requirements §9.2 |
| 著者 | 任意、0～200文字 | requirements §9.2 |
| メモ | 任意、0～2,000文字 | requirements §9.2 |
| 既定並べ替え | 更新日時順 | FR-PRJ-004 |
| ごみ箱保持日数 | **30日（確定）**。期限超過で物理削除 | spec-review 2026-08-25 確定 |
| 検索対象 | **タイトル+著者の部分一致（確定）**。メモ対象外・大文字小文字/全角半角を正規化 | spec-review 2026-08-25 確定 |

## 5. インターフェース

- `BookProjectRepository`（[01-architecture](./01-architecture.md) §3.3）
- ViewModel: `HomeViewModel` / `BookEditViewModel` / `BookDetailViewModel`（UDF契約は [01](./01-architecture.md) §3.5）

## 6. エラー処理

- タイトル空・文字数超過: 保存不可＋インラインエラー表示
- ファイル領域作成失敗: BookProject 登録をロールバックし、エラー表示（DB・ファイル不整合を残さない）
- ストレージ不足: 必要容量と整理導線を表示（requirements §17）

## 7. スコープ外

- 章・節管理、表紙画像、ISBN（Phase 4）
- クラウド同期・バックアップ（MVP対象外）

## 8. 関連仕様

- 全体: [`00-overview.md`](./00-overview.md) ｜ データ: [`02-data-model.md`](./02-data-model.md)
- 次工程: [`04-capture-session.md`](./04-capture-session.md)（撮影開始）、[`08-page-editing.md`](./08-page-editing.md)（ページ一覧）

## 9. 実装単位

<!-- spec-to-beads がこの節を機械的に読んで bd の子タスクを作る -->
- [ ] [Backend] BookProjectRepository 実装（CRUD・ごみ箱・並べ替え・検索・ページ数/容量集計）
  - 受け入れ基準: make verify が PASS; 作成/更新/論理削除/復元/物理削除/検索の単体テストが通過する; ファイル領域作成失敗時にDBレコードが残らないテストが通過する
- [ ] [Frontend] ホーム画面（一覧・並べ替え・検索・新しい書籍ボタン）を実装する
  - 受け入れ基準: make verify が PASS; HomeViewModel の UiState 遷移（空一覧/データあり/検索絞り込み）の単体テストが通過する
- [ ] [Frontend] 書籍作成・編集画面（バリデーション付きフォーム）を実装する
  - 受け入れ基準: make verify が PASS; タイトル空・200超・メモ2000超で保存不可となるViewModel単体テストが通過する
- [ ] [Frontend] 書籍詳細画面（統計表示・各機能への導線）を実装する
  - 受け入れ基準: make verify が PASS; ページ数/OCR完了数/エラー数の集計表示のViewModel単体テストが通過する
- [ ] [Frontend] 削除確認ダイアログ（対象・ページ数・容量表示）とごみ箱画面（復元・完全削除）を実装する
  - 受け入れ基準: make verify が PASS; 削除確認に件数と容量が含まれることをViewModel単体テストで検証する
