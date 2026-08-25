---
status: confirmed
confirmed_rev: 6f8ceab
範囲: サムネイル一覧・複数選択・削除・並べ替え・回転・切り取りUI・重複/黒画面候補の確認・取り消し
---

# 08. ページ編集 仕様

**親**: [`00-overview.md`](./00-overview.md) ｜ **担当**: Frontend ｜ **範囲**: FR-EDT-001～007、UC-04、画面 §9.6

## 1. 目的

撮影済みページの整理（削除・並べ替え・回転・切り取り・品質警告の確認）を行うページ一覧・編集画面を提供する。

## 2. 入出力

- 入力: Page 一覧（[02-data-model](./02-data-model.md) §3.1）、利用者の編集操作
- 出力: Page 属性の更新（sequence / rotation / crop* / qualityState）、OCR再実行トリガ（[09](./09-ocr.md)）

## 3. 処理詳細

### 3.1 一覧表示（FR-EDT-001、§9.6）

- サムネイルをページ順（sequence）に表示。グリッド／リスト切替
- 各ページに OCR状態アイコン（pending/running/succeeded/failed/stale）と重複・黒画面警告を表示
- 500ページのプロジェクトで一覧操作できることを最低基準とする（requirements §16.1。Paging / LazyGrid 等の手段は実装時に確定）

### 3.2 編集操作

| 操作 | 要件 | 備考 |
|---|---|---|
| 並べ替え | FR-EDT-002 | ドラッグ操作。sequence を振り直す |
| 削除 | FR-EDT-003 | 単一・複数選択。破壊操作は対象件数を表示して確認（requirements §16.4） |
| 回転 | FR-EDT-004 | 90度単位。非破壊（[07](./07-image-quality.md) §3.4） |
| 切り取り | FR-EDT-005 | 範囲編集UI。ページごと変更＋同一書籍への一括適用（FR-IMG-005/006） |
| 重複確認 | FR-EDT-006 | 重複候補を比較して残すページを選べる |
| 黒画面確認 | FR-EDT-007（Should） | 黒画面候補をまとめて確認できる |

### 3.3 編集とOCRの整合（UC-04）

- 画像変更（回転・切り取り）時は該当ページの ocrState を `stale` にし、OCR再実行を促す（FR-OCR-007）
- 再実行のトリガと実行は [09-ocr](./09-ocr.md) の責務

### 3.4 取り消し（UC-04 手順5、設計原則6）

- 変更履歴を保持し、直前操作を取り消せるようにする
- 対象操作: 削除・並べ替え・回転・切り取り
- 履歴の深さは**直前1操作で確定**（spec-review 2026-08-25。多段undoはMVP対象外、Phase 4以降で再検討）

## 4. 設定値・確定値

| 項目 | 値 | 出典 |
|---|---|---|
| 回転単位 | 90度 | FR-EDT-004 |
| 編集方式 | 非破壊（元画像を変更しない） | FR-IMG-007 |
| 取り消し | **直前1操作（確定）**。多段undoはMVP対象外 | UC-04・spec-review 2026-08-25 確定 |
| 一覧性能 | 500ページで操作可能 | requirements §16.1 |

## 5. インターフェース

- `PageRepository`（[01-architecture](./01-architecture.md) §3.3）: 属性更新・並べ替え・削除
- 画像変換・警告判定: [07-image-quality](./07-image-quality.md) の関数を利用
- ViewModel: `PageListViewModel`（一覧・選択・並べ替え・削除）、`PageEditViewModel`（回転・切り取り）

## 6. エラー処理

- 削除確認で件数を必ず表示。確認なしの複数削除を行わない
- 並べ替え・属性更新の失敗時は UI 状態を元に戻し、エラー表示
- 派生画像生成失敗（メモリ不足）: サムネイルをプレースホルダ表示し再試行可能にする

## 7. スコープ外

- ページ画像の画質補正・注釈（仕様に無い）
- OCRテキストの編集（[09-ocr](./09-ocr.md) の OCR編集画面）
- ごみ箱（書籍単位。[03-book-project](./03-book-project.md)）※ページ削除の復元は取り消し（§3.4）で担保する

## 8. 関連仕様

- 全体: [`00-overview.md`](./00-overview.md) ｜ データ: [`02-data-model.md`](./02-data-model.md)
- 判定・変換: [`07-image-quality.md`](./07-image-quality.md) ｜ 次工程: [`09-ocr.md`](./09-ocr.md)（stale→再実行）

## 9. 実装単位

<!-- spec-to-beads がこの節を機械的に読んで bd の子タスクを作る -->
- [ ] [Backend] PageRepository のページ編集系操作（並べ替えsequence振り直し・複数削除・rotation/crop更新・stale化）を実装する
  - 受け入れ基準: make verify が PASS; 並べ替え後のsequence連番性、削除後の詰め直し、画像変更でocrStateがstaleになる単体テストが通過する
- [ ] [Backend] 直前1操作の取り消し（削除・並べ替え・回転・切り取り）を実装する
  - 受け入れ基準: make verify が PASS; 各操作→undoで元の状態に完全復元される単体テストが通過する
- [ ] [Frontend] ページ一覧画面（グリッド/リスト切替・サムネイル・OCR状態アイコン・警告表示・複数選択）を実装する
  - 受け入れ基準: make verify が PASS; PageListViewModel の UiState（選択モード・警告フィルタ）単体テストが通過する; 500ページ相当のダミーデータで一覧がスクロール可能
- [ ] [Frontend] ドラッグ並べ替えと削除確認（件数表示）UIを実装する
  - 受け入れ基準: make verify が PASS; 削除確認ダイアログに選択件数が表示されるViewModel単体テストが通過する
- [ ] [Frontend] 回転・切り取り編集画面（90度回転・crop範囲編集・書籍単位の一括適用）を実装する
  - 受け入れ基準: make verify が PASS; 編集結果が正規化crop座標としてViewModelから保存される単体テストが通過する
- [ ] [Frontend] 重複候補比較UIと黒画面候補一覧を実装する
  - 受け入れ基準: make verify が PASS; 重複ペアから残すページを選ぶと他方が削除候補になるViewModel単体テストが通過する
