---
status: confirmed
confirmed_rev: 6f8ceab
範囲: Roomエンティティ・列挙型・ファイル構造・書き出し構造・blocksJson/manifest.jsonスキーマ（整合性アンカー）
---

# 02. データモデル仕様（整合性アンカー）

**親**: [`00-overview.md`](./00-overview.md) ｜ **担当**: Backend ｜ **範囲**: 全機能が共有するデータスキーマと永続化構造

## 1. 目的

エンティティ・列挙型・ファイルレイアウト・中間成果物スキーマの正本。他の詳細仕様はスキーマを再定義せず、本仕様を参照する。

一次情報源: `docs/requirements.md` §12.2（OCR出力）・§14（データ設計）。

## 2. 入出力

- 入力: なし（他仕様が参照する定義集）
- 出力: Roomスキーマ・ファイル構造・JSON スキーマ

## 3. 処理詳細

### 3.1 エンティティ（requirements §14.1）

Roomエンティティは `data/` に置き、ドメインモデルへマッパー経由で変換する（[01-architecture](./01-architecture.md) §3.1）。

#### BookProject

| 属性 | 型 | 説明 |
|---|---|---|
| id | UUID | 主キー |
| title | String | 必須、1～200文字 |
| author | String? | 任意、0～200文字 |
| note | String? | 任意、0～2,000文字 |
| createdAt | Instant | 作成日時 |
| updatedAt | Instant | 更新日時 |
| deletedAt | Instant? | ごみ箱管理（非null = ごみ箱内） |

#### Page

| 属性 | 型 | 説明 |
|---|---|---|
| id | UUID | 主キー |
| projectId | UUID | 書籍ID（外部キー） |
| sequence | Int | 表示順、**1始まり** |
| originalImagePath | String | 元画像への**相対パス**（絶対パス禁止） |
| width / height | Int | 元画像寸法 |
| rotation | Int | 0/90/180/270 のみ |
| cropLeft/Top/Right/Bottom | Float | 0～1の正規化座標 |
| capturedAt | Instant | 撮影日時 |
| contentHash | String | 完全一致判定用 |
| perceptualHash | String | 近似重複判定用 |
| qualityState | Enum | `normal / duplicate / black / error` |
| ocrState | Enum | `pending / running / succeeded / failed / stale` |

#### OcrResult

| 属性 | 型 | 説明 |
|---|---|---|
| pageId | UUID | ページID（主キー、Pageと1:1） |
| fullText | String | 全文 |
| blocksJson | String | §3.4 のスキーマに従う構造化結果 |
| editedText | String? | 手動修正版（非null時はこちらを成果物に使う） |
| engineVersion | String | OCRエンジンとバージョン |
| sourceImageHash | String | OCR対象画像のハッシュ（stale判定に使用） |
| processedAt | Instant | 処理日時 |

#### ExportRecord

| 属性 | 型 | 説明 |
|---|---|---|
| id | UUID | 主キー |
| projectId | UUID | 書籍ID |
| type | Enum | `searchable_pdf / image_pdf / markdown / text_zip / image_zip` |
| targetUri | String? | SAF URI |
| state | Enum | `queued / running / succeeded / failed` |
| createdAt | Instant | 開始日時 |
| completedAt | Instant? | 完了日時 |
| errorCode | String? | 失敗理由コード |

### 3.2 アプリ専用領域のファイル構造（requirements §14.2）

```text
files/projects/{project-id}/
  images/
    {page-id}.webp      # 元画像。WebP Lossless 基本
  temp/                 # 撮影・書き出しの一時ファイル
  exports-cache/        # 書き出し前の一時出力
```

- DBには絶対パスではなく、アプリ領域からの相対パスを保存する
- 元画像は非破壊で保持する。回転・切り取りは Page の `rotation` / `crop*` 属性のみで表現し、画像ファイルを上書き・削除しない（FR-IMG-007、AGENTS.md ルール5）

### 3.3 書き出し構造（requirements §14.3）

```text
{sanitized-title}/
  {title}.searchable.pdf
  {title}.images.pdf
  {title}.md
  pages/
    page-0001.txt        # ページ番号4桁ゼロ埋め
    page-0002.txt
  images/
    page-0001.webp
    page-0002.webp
  manifest.json          # §3.5 のスキーマ
```

単一ファイル保存しかできない保存先向けにはZIPでまとめる。

### 3.4 blocksJson スキーマ

OCR構造化結果（requirements §12.2 の保存項目を JSON 化）。座標は**元画像ピクセル・左上原点**。

```json
{
  "schemaVersion": 1,
  "blocks": [
    {
      "index": 0,
      "text": "ブロック全文",
      "rect": { "left": 0, "top": 0, "right": 0, "bottom": 0 },
      "lines": [
        {
          "index": 0,
          "text": "行テキスト",
          "rect": { "left": 0, "top": 0, "right": 0, "bottom": 0 },
          "elements": [
            { "index": 0, "text": "要素", "rect": { "left": 0, "top": 0, "right": 0, "bottom": 0 } }
          ]
        }
      ]
    }
  ]
}
```

- `index` は認識順（読み順補正後）。requirements §12.3 のブロック順変更の設計余地に対応するため、blocks の並び自体を読み順とする
- ML Kit の型をこのスキーマへ変換するのは `ocr/` の責務（[09-ocr](./09-ocr.md)）。ML Kit 型を blocksJson の外へ出さない

### 3.5 manifest.json スキーマ

ページとOCR結果を対応付けるメタデータ（requirements §1）。※フィールド詳細は実装時に確定してよいが、下記を最小セットとする。

```json
{
  "schemaVersion": 1,
  "app": { "name": "PageBinder", "version": "x.y.z" },
  "project": { "title": "", "author": null, "note": null, "createdAt": "", "exportedAt": "" },
  "ocrEngine": { "name": "mlkit-text-recognition-v2-japanese", "version": "" },
  "pages": [
    {
      "sequence": 1,
      "imageFile": "images/page-0001.webp",
      "textFile": "pages/page-0001.txt",
      "capturedAt": "",
      "ocrState": "succeeded",
      "contentHash": "",
      "edited": false
    }
  ]
}
```

### 3.6 マイグレーション

- Roomスキーマ変更時はマイグレーションを必ず書き、自動テストする（requirements §16.2・§20.1）
- スキーマJSONエクスポート（`room.schemaLocation`）を有効にし、バージョン管理する

## 4. 設定値・確定値

| 項目 | 値 | 出典 |
|---|---|---|
| 画像保存形式 | **WebP Lossless（確定）**。互換用PNG出力も可能 | requirements §10.1・spec-review 2026-08-25 確定 |
| sequence | 1始まり | requirements §14.1 |
| rotation | 0/90/180/270 のみ | requirements §14.1 |
| crop座標 | 0～1 正規化 | requirements §14.1 |
| ページ番号ファイル名 | `page-NNNN`（4桁ゼロ埋め） | requirements §14.3 |
| DB内パス | アプリ領域からの相対パス | requirements §14.2 |

※既定形式は WebP Lossless で確定済み（旧・未決事項3）。Phase 0 では容量・保存速度の実測記録のみ行い、形式の再判断はしない。

## 5. インターフェース

Repositoryインターフェース定義は [`01-architecture.md`](./01-architecture.md) §3.3 を参照。本仕様はその背後のスキーマのみ定める。

## 6. エラー処理

- 元画像保存とDB登録は不整合が残らない順序（画像の原子的保存 → DB登録）で行う（requirements §16.2）
- 途中失敗時は一時ファイルを成果物として登録しない（requirements §11.2）
- Androidバックアップ対象から作業画像とOCR本文を除外する（`android:fullBackupContent` / `dataExtractionRules` で除外設定）

## 7. スコープ外

- 章・節の管理、表紙画像、ISBN（Phase 4）
- 暗号化エクスポート（Phase 4）
- クラウド同期（MVP対象外）

## 8. 関連仕様

- 全体: [`00-overview.md`](./00-overview.md)
- 構成: [`01-architecture.md`](./01-architecture.md)
- OCR出力の生成元: [`09-ocr.md`](./09-ocr.md)
- 書き出しでの利用: [`11-export.md`](./11-export.md)

## 9. 実装単位

<!-- spec-to-beads がこの節を機械的に読んで bd の子タスクを作る -->
- [ ] [Backend] Roomエンティティ4種（BookProject/Page/OcrResult/ExportRecord）・列挙型・DAO・Database クラスを定義する
  - 受け入れ基準: make verify が PASS; スキーマJSONエクスポートが有効; 各DAOの基本CRUD単体テストが通過する
- [ ] [Backend] UUID/Instant/Enum の TypeConverter とドメインモデル・マッパーを実装する
  - 受け入れ基準: make verify が PASS; Room型がdomain/へ漏れない（01-architectureの検査に合格）; マッパーの往復変換テストが通過する
- [ ] [Backend] blocksJson スキーマ（§3.4）のシリアライズ/デシリアライズを実装する
  - 受け入れ基準: make verify が PASS; スキーマ例のJSONを往復して同値になるテストが通過する
- [ ] [Backend] アプリ専用領域のファイル構造（§3.2）を管理する ImageStore 実装（原子的保存・相対パス解決・temp掃除）を作る
  - 受け入れ基準: make verify が PASS; 保存途中の失敗で不完全ファイルが images/ に残らないことをテストで検証する
- [ ] [Backend] バックアップ除外設定（fullBackupContent / dataExtractionRules）を追加する
  - 受け入れ基準: make verify が PASS; 除外ルールXMLに images と DB が含まれる
- [ ] [Backend] Roomマイグレーションテストの足場（MigrationTestHelper）を用意する
  - 受け入れ基準: make verify が PASS; バージョン1のスキーマ検証テストが通過する
