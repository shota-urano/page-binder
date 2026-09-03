---
status: confirmed
confirmed_rev: 6f8ceab
範囲: OCRテキスト層付き検索可能PDFの生成（2層構造・座標変換・フォント埋め込み・PDFBoxスパイク）
---

# 10. 検索可能PDF 仕様

**親**: [`00-overview.md`](./00-overview.md) ｜ **担当**: Backend ｜ **範囲**: FR-EXP-001/002、requirements §13

## 1. 目的

各ページに「表示層＝撮影画像」「テキスト層＝OCR結果の不可視文字」の2層を持つ検索可能PDFと、テキスト層なしの画像PDFを生成する。

## 2. 入出力

- 入力: Page 一覧（rotation/crop 適用後の画像）+ OcrResult（blocksJson。editedText 優先の扱いは [11-export](./11-export.md) と共通）
- 出力: PDFファイル（ストリーム。SAF出力は [11-export](./11-export.md) の責務）

## 3. 処理詳細

### 3.1 基本構造（requirements §13.1）

1. 表示層: 撮影画像（rotation/crop 適用後）
2. テキスト層: OCR結果を座標に合わせて**不可視描画**した文字

利用者には元画像が見え、PDFビューアは不可視テキストを検索・選択できる。

### 3.2 座標変換（requirements §13.2）

- OCR座標は画像左上原点（blocksJson、[02-data-model](./02-data-model.md) §3.4）
- PDF座標は左下原点へ変換する
- 切り取り・回転・PDFページへの拡大縮小を**同一変換行列**で画像とテキストへ適用する（回転/crop の変換実装は [07-image-quality](./07-image-quality.md) §3.4 と共通）
- 文字列単位ではなく、可能な範囲で**行または要素単位**に配置する

### 3.3 フォント（requirements §13.3）

- 日本語グリフを含む再配布可能なフォントを同梱する。第一候補は SIL Open Font License の **Noto Sans JP**
- PDFには必要なグリフを**サブセット埋め込み**する

### 3.4 PDFエンジンと必須スパイク（requirements §13.4）— Phase 0

PDFBox-Android を第一候補とする（Apache 2.0）。Android移植版の更新頻度と日本語フォント処理が技術リスクのため、実装開始時に次のスパイクを**必須**とする。

1. 日本語横書き1ページを生成する
2. Android標準PDFビューア・Google Drive・Adobe Acrobat Reader で検索できることを確認する
3. コピーした文字列がOCR結果と一致することを確認する
4. 100ページ生成時の時間・メモリ・ファイル容量を測定する

**スパイク不合格時はPDF生成エンジンを差し替える**（`PdfGateway` 抽象で差替可能にしておく）。画像PDFとMarkdown出力はPDFテキスト層の成否に依存させない。

### 3.5 生成実行

- PDF生成はバックグラウンドで行い、進捗とキャンセルを提供する（requirements §16.1）
- 100ページの検索可能PDF生成を実機テスト対象とする（requirements §20.2）
- PDFページサイズ: **画面比率維持で確定**（spec-review 2026-08-25。旧・未決事項7。A4固定はMVP対象外）

### 3.6 PDF画質（表示層の解像度・圧縮）— 2026-09-03 確定

書き出し画面の「PDF画質」3段階（`ExportPdfQuality` = HIGH / STANDARD / COMPACT、既定は STANDARD。[11-export](./11-export.md) §3.2）は、**表示層の画像にのみ**適用する。

| 段階 | UI文言 | 長辺上限 | 符号化 |
|---|---|---|---|
| `HIGH` | 高画質 | 原寸（安全上限 3840px） | 可逆（Flate。PDFBox の `LosslessFactory`） |
| `STANDARD` | 標準 | 2048px | JPEG 品質 85（`JPEGFactory`） |
| `COMPACT` | 軽量 | 1280px | JPEG 品質 65（`JPEGFactory`） |

- **長辺上限**は rotation/crop 適用後の派生画像の長辺に対する上限。上限を下回る画像は拡大しない（縮小のみ）。縮小時はアスペクト比を保つ
- 本アプリは画面キャプチャが入力で PDF ページサイズは画面比率維持（§3.5）のため、紙を前提とした DPI ではなく**長辺ピクセル上限**で解像度を規定する
- **PDFページサイズは画質段階によって変わらない**。ページ矩形は常に原寸（rotation/crop 適用後のピクセル寸法）を基準に決め、縮小した表示層画像は同じページ矩形いっぱいに描画する
- したがって**テキスト層の座標・検索・コピー結果は3段階で同一**になる（画質は表示層の鮮明さとファイル容量だけに効く）。§3.2 の座標変換は画質段階の影響を受けない
- 元画像は WebP Lossless（[07-image-quality](./07-image-quality.md) §4）で、内容は文字主体の画面である。HIGH を可逆にするのは JPEG のリンギングで文字の輪郭を劣化させないため
- 画像PDF（`PdfMode.IMAGE_ONLY`）にも同じ規則を適用する

## 4. 設定値・確定値

| 項目 | 値 | 出典 |
|---|---|---|
| PDFエンジン第一候補 | PDFBox-Android（スパイク合格が採用条件） | requirements §13.4 |
| 同梱フォント第一候補 | Noto Sans JP（OFL） | requirements §13.3 |
| フォント埋め込み | サブセット | requirements §13.3 |
| テキスト層 | 不可視・行/要素単位配置 | requirements §13.1/13.2 |
| ページサイズ | **画面比率維持（確定）** | requirements §23-7・spec-review 2026-08-25 確定 |
| PDF画質 HIGH | 長辺 原寸（上限3840px）／可逆（Flate） | §3.6・2026-09-03 確定 |
| PDF画質 STANDARD（既定） | 長辺 2048px／JPEG 品質85 | §3.6・2026-09-03 確定 |
| PDF画質 COMPACT | 長辺 1280px／JPEG 品質65 | §3.6・2026-09-03 確定 |
| 画質段階とページサイズ | ページ矩形は原寸基準で段階非依存（テキスト層座標は不変） | §3.6・2026-09-03 確定 |

## 5. インターフェース

- `PdfGateway`（[01-architecture](./01-architecture.md) §3.3）: ページ列 → PDF ストリーム（検索可能/画像の2モード）。PDFBox の型を外へ出さない
- 呼び出し元: Export Engine（[11-export](./11-export.md) §3.1）
- 進捗・キャンセル: Flow ベース（形式は実装時に確定）

## 6. エラー処理

- PDF生成失敗: 不完全ファイルを成功扱いしない（FR-EXP-007）。画像PDFまたはMarkdownへのフォールバックを案内する（requirements §17）
- テキスト層生成失敗: 画像PDF生成には影響させない（§3.4）

## 7. スコープ外

- SAFへの出力・書き出しフロー全体（[11-export](./11-export.md)）
- PDF暗号化（Phase 4）
- OCR未完了ページの扱いの判断（[11-export](./11-export.md) FR-EXP-009）

## 8. 関連仕様

- 全体: [`00-overview.md`](./00-overview.md) ｜ データ: [`02-data-model.md`](./02-data-model.md)
- 前工程: [`09-ocr.md`](./09-ocr.md)、座標変換共通: [`07-image-quality.md`](./07-image-quality.md)
- 次工程: [`11-export.md`](./11-export.md)

## 9. 実装単位

<!-- spec-to-beads がこの節を機械的に読んで bd の子タスクを作る -->
- [ ] [Backend] Phase 0 スパイク: PDFBox-Android で日本語不可視テキスト層1ページを生成し、§3.4 の4項目（3ビューア検索・コピー一致・100ページ性能）を検証する
  - 受け入れ基準: make verify が PASS; 4項目の検証記録（合否・測定値）が残る; 不合格時はエンジン差し替えを報告して停止する
- [ ] [Backend] Noto Sans JP の同梱とサブセット埋め込みを実装する
  - 受け入れ基準: make verify が PASS; 生成PDFのフォントがサブセットであることを確認するテストまたは検証記録がある; ライセンス表記（OFL）が同梱される
- [ ] [Backend] OCR座標→PDF座標の変換（左上→左下原点・回転/crop/拡縮の同一行列適用・行/要素単位配置）を実装する
  - 受け入れ基準: make verify が PASS; 座標変換の単体テスト（回転90/180/270・crop・拡縮の組み合わせ）が通過する
- [ ] [Backend] PdfGateway 実装（検索可能PDF・画像PDFの2モード、進捗・キャンセル付きバックグラウンド生成）を実装する
  - 受け入れ基準: make verify が PASS; PDFBoxの型がexport/パッケージ外へ漏れない; キャンセルで不完全ファイルが残らない単体テストが通過する; テキスト層失敗時も画像PDFが生成できるテストが通過する
