---
status: confirmed
confirmed_rev: 6f8ceab
範囲: Markdown/TXT/ZIP/manifest生成・SAF書き出しフロー・書き出し画面・履歴管理
---

# 11. 書き出し 仕様

**親**: [`00-overview.md`](./00-overview.md) ｜ **担当**: Backend（Export Engine・SAF）+ Frontend（書き出し画面） ｜ **範囲**: FR-EXP-001～009、UC-05、画面 §9.8

## 1. 目的

書籍プロジェクトの成果物（検索可能PDF・画像PDF・Markdown・ページ単位TXT・元画像ZIP・manifest.json）を生成し、SAF経由で利用者指定先（端末内・Google Drive等）へ書き出す。

## 2. 入出力

- 入力: BookProject + Page 一覧 + OcrResult（[02-data-model](./02-data-model.md)）、出力形式・ファイル名・保存先URI（利用者選択）
- 出力: [02-data-model](./02-data-model.md) §3.3 の書き出し構造、ExportRecord（履歴）

## 3. 処理詳細

### 3.1 出力形式（FR-EXP-001～004）

| 形式 | 内容 | 依存 |
|---|---|---|
| searchable_pdf | OCRテキスト層付きPDF | [10-searchable-pdf](./10-searchable-pdf.md) |
| image_pdf | 画像のみPDF（フォールバック） | 同上（テキスト層の成否に依存させない） |
| markdown | 全ページ統合Markdown。ページ境界とOCRテキストを出力（受入基準9） | OcrResult |
| text_zip | ページ単位TXT一式（`page-NNNN.txt`） | OcrResult |
| image_zip | 元画像 + manifest.json のZIP | ImageStore |

- テキスト系出力は `editedText` が非nullならそれを、無ければ `fullText` を使う（[02-data-model](./02-data-model.md) §3.1）
- Markdownのページ境界表現（見出し・区切り記法）は実装時に確定。ページ番号参照を必ず含める
- 単一ファイル保存しかできない保存先向けにはZIPでまとめる（§3.3 構造ごと）

### 3.2 書き出しフロー（UC-05）

1. 書籍詳細画面「書き出し」→ 書き出し画面（§9.8: 出力形式選択・ファイル名・ページ範囲・PDF画質・OCR未完了警告・利用上の注意・保存先選択）
2. OCR未処理・失敗ページがあれば警告し、続行または中止を選べる（FR-EXP-009）
3. 書き出し前の利用上の注意と権限確認を表示する（[12-legal-guardrails](./12-legal-guardrails.md) §3.2）
4. SAFの保存画面を開く。端末内・Google Drive等のDocument Providerを利用者が選ぶ（FR-EXP-005/006。Drive専用APIは使わない）
5. `exports-cache/` へ一時出力を完成させてから `content://` URIへストリーム出力し、完了で確定する（FR-EXP-007: 不完全ファイルを成功扱いしない）
6. ExportRecord に `queued → running → succeeded / failed` を記録し、成功・失敗を表示する
7. 同名ファイルはOS標準の確認に従う（FR-EXP-008）

- ページ範囲指定は**MVPに含める（確定）**（spec-review 2026-08-25。旧・未決事項6）
- アプリ強制終了後、未完了の書き出しを検出して再試行できる（requirements §16.2）

### 3.3 制約

- アプリ内に共有ボタンを設けず、SAFによる保存のみ提供する（requirements §18.2）
- ログへ保存URIを出力しない（requirements §16.3）

## 4. 設定値・確定値

| 項目 | 値 | 出典 |
|---|---|---|
| 保存手段 | SAFのみ（Drive専用API禁止・共有ボタン禁止） | requirements §3・§18.2 |
| ファイル/フォルダ構造 | [02-data-model](./02-data-model.md) §3.3 | requirements §14.3 |
| 確定手順 | 一時出力完了後に確定 | requirements §16.2 |
| ページ範囲指定 | **MVPに含める（確定）** | requirements §23・spec-review 2026-08-25 確定 |

## 5. インターフェース

- Export Engine（`export/`）: 形式別ジェネレータの調停・進捗・キャンセル
- `PdfGateway`（[10](./10-searchable-pdf.md)）/ `ExportStorageGateway` / `ExportRecordRepository`（[01-architecture](./01-architecture.md) §3.3）
- ViewModel: `ExportViewModel`（書き出し画面）

## 6. エラー処理

| 状況 | 動作 |
|---|---|
| 書き出し途中の失敗 | 検知し、不完全ファイルを成功扱いしない（FR-EXP-007）。ExportRecord = failed + errorCode |
| PDF生成失敗 | 画像PDFまたはMarkdownへのフォールバックを案内（requirements §17） |
| Google Drive書き込み失敗 | ローカル保存への切替を案内（requirements §17） |
| OCR未完了ページあり | 警告し続行/中止を選択（FR-EXP-009） |
| ストレージ不足 | 必要容量と整理導線を表示 |

## 7. スコープ外

- PDF生成の内部（[10-searchable-pdf](./10-searchable-pdf.md)）
- 外部AIへの自動アップロード・共有機能（実装禁止）
- 暗号化エクスポート（Phase 4）

## 8. 関連仕様

- 全体: [`00-overview.md`](./00-overview.md) ｜ データ: [`02-data-model.md`](./02-data-model.md)
- 前工程: [`09-ocr.md`](./09-ocr.md)、[`10-searchable-pdf.md`](./10-searchable-pdf.md)
- 法務表示: [`12-legal-guardrails.md`](./12-legal-guardrails.md)

## 9. 実装単位

<!-- spec-to-beads がこの節を機械的に読んで bd の子タスクを作る -->
- [ ] [Backend] Phase 0 スパイク: SAF経由のGoogle Drive保存（Document Provider選択→ストリーム出力→完了確認）を検証する
  - 受け入れ基準: make verify が PASS; Drive Provider構成済み端末での保存成功とエラー時挙動の検証記録が残る; 失敗時は報告して停止する
- [ ] [Backend] Markdown / ページ単位TXT / manifest.json ジェネレータ（editedText優先・ページ境界出力）を実装する
  - 受け入れ基準: make verify が PASS; OCR結果→Markdown変換の単体テスト（ページ境界・ページ番号参照・editedText優先）が通過する; manifest.jsonが02-data-model §3.5スキーマに適合する
- [ ] [Backend] ZIP梱包（image_zip / text_zip / 単一ファイル保存先向け全体ZIP）を実装する
  - 受け入れ基準: make verify が PASS; ZIP内構造が02-data-model §3.3と一致する単体テストが通過する
- [ ] [Backend] ExportStorageGateway（SAFストリーム出力・一時出力→確定・同名確認委譲）と ExportRecord 管理を実装する
  - 受け入れ基準: make verify が PASS; 途中失敗で不完全ファイルが成功扱いされない単体テストが通過する; 状態遷移（queued→running→succeeded/failed）のテストが通過する
- [ ] [Backend] Export Engine（形式別調停・進捗・キャンセル・強制終了後の再試行検出）を実装する
  - 受け入れ基準: make verify が PASS; キャンセルでExportRecordがfailedになり一時ファイルが掃除されるテストが通過する
- [ ] [Frontend] 書き出し画面（形式選択・ファイル名・ページ範囲・PDF画質・OCR未完了警告・利用上の注意・SAF起動・進捗/結果表示）を実装する
  - 受け入れ基準: make verify が PASS; OCR未完了時に警告と続行/中止の選択が出るViewModel単体テストが通過する; 続行選択なしで書き出しが始まらない
