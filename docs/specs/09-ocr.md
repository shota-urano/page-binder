---
status: confirmed
confirmed_rev: 6f8ceab
範囲: ML Kit日本語OCR（bundled）・OCRキュー・状態管理・読み順・OCR編集画面・品質評価スパイク
---

# 09. OCR 仕様

**親**: [`00-overview.md`](./00-overview.md) ｜ **担当**: Backend（エンジン・キュー）+ Frontend（OCR編集画面） ｜ **範囲**: FR-OCR-001～009、requirements §12、画面 §9.7

## 1. 目的

保存ページの日本語・英数字を端末内でOCRし、構造化結果（全文・ブロック・行・要素・座標・認識順）を保存・修正可能にする。実行時ダウンロード不要（モデルAPK同梱）・完全オフラインで動作する。

## 2. 入出力

- 入力: Page（rotation/crop 適用後の表示対象領域画像。変換は [07-image-quality](./07-image-quality.md) §3.4 を使用）
- 出力: OcrResult（スキーマは [02-data-model](./02-data-model.md) §3.1・blocksJson は §3.4）、Page.ocrState の更新

## 3. 処理詳細

### 3.1 OCR入力（requirements §12.1）

- 非破壊編集後の表示対象領域を入力とする
- 画像の回転を補正し、crop を適用してから、必要な場合のみ認識用に縮小する。元画像は変更しない
- crop 対象領域の中間デコードは最大辺4096px、ML Kitへ渡す最終画像は最大辺2048pxとする

### 3.2 OCRキュー（FR-OCR-003/009、requirements §16.1）

- WorkManager による OCR Worker Queue（[01-architecture](./01-architecture.md) §3.4）
- 直列または制限付き並列で実行し、**撮影処理を優先**する（FR-OCR-009: OCR処理中も撮影操作を妨げない）
- ページ単位で ocrState を管理: `pending → running → succeeded / failed`。画像編集後は `stale`（FR-OCR-007）
- 失敗ページの再実行（FR-OCR-005）、書籍単位のOCR一括実行（§9.3 の導線）を提供
- アプリ強制終了後、未完了OCRを検出して再開できる（requirements §16.2）
- 充電・温度状態に応じた抑制を考慮する（requirements §22 発熱リスク。制御方法は実装時に確定）

### 3.3 OCR実行（FR-OCR-001/002/004）

- ML Kit Text Recognition v2 Japanese（bundled）を `OcrGateway` 実装に閉じ込める（ML Kit 型を外へ出さない）
- 保存項目（requirements §12.2）: 全文 / ブロック / 行 / 要素 / 各要素の矩形座標 / 認識順 / エンジンとバージョン / 処理日時 / 元画像ハッシュ → blocksJson へ変換（[02-data-model](./02-data-model.md) §3.4）
- sourceImageHash に OCR対象画像のハッシュを保存し、stale 判定に使う

### 3.4 読み順（requirements §12.3）

- 初版はOCRエンジンのブロック順を基礎とする
- 複数段組み・縦書きは座標から読み順を補正する
- 完全自動で確定できない場合に備え、OCR編集画面でブロック順を変更できる設計余地を残す（blocksJson の blocks 並びが読み順の正本）

### 3.5 OCR編集画面（FR-OCR-006、§9.7）

- 画像とテキストの上下または左右分割表示
- ページ内検索 / OCR再実行 / 手動修正（editedText へ保存。元のOCR結果 fullText は保持）/ 元のOCR結果へ戻す（editedText を破棄）

### 3.6 品質評価（FR-OCR-008、requirements §19.2）— Phase 0 スパイク

固定の評価資料で次を個別測定する: 日本語横書き / 縦書き / ルビ / 二段組み / 図表を含むページ / 白黒反転・セピア背景 / 小さい文字。

- 横書き本文: 文字正解率95%以上を目標
- 縦書き・ルビ・複雑な段組みの目標値はスパイク後に確定（未決事項2）

## 4. 設定値・確定値

| 項目 | 値 | 出典 |
|---|---|---|
| OCRエンジン | ML Kit Text Recognition v2 Japanese（bundled） | requirements §3 |
| 実行時ダウンロード | 不要（APK同梱） | FR-OCR-002 |
| 横書き文字正解率目標 | 95%以上 | requirements §19.2 |
| 実行方式 | 直列または制限付き並列・撮影優先 | requirements §16.1 |
| crop領域の中間デコード上限 | 最大辺4096px（前処理時のメモリ使用量を抑えつつ、最終縮小前の解像度を保持） | 実装確定値 |
| OCR入力画像の縮小閾値・上限 | 最大辺2048px（超過時のみアスペクト比を維持して縮小） | 実装確定値 |
| 縦書き等の目標値 | 実装時に確定 | requirements §23 |

## 5. インターフェース

- `OcrGateway`（[01-architecture](./01-architecture.md) §3.3）: 画像 → 構造化OCR結果
- `OcrResultRepository`: 結果保存・editedText 管理
- enqueue 元: 撮影（[05](./05-manual-capture.md) 手順9）、編集での stale 化（[08](./08-page-editing.md) §3.3）、OCR一括実行・再実行（UI）

## 6. エラー処理

- OCR失敗: 元画像を保持し、ocrState = `failed` として再試行可能にする（requirements §17）
- 書き出し時のOCR未完了・失敗は [11-export](./11-export.md) が警告する（FR-EXP-009）
- ログへOCR全文を出力しない（requirements §16.3）

## 7. スコープ外

- クラウドOCR・外部AI連携（MVP対象外）
- 段組み読み順のブロック順変更UI（設計余地のみ確保。実装は将来）
- OCR結果の翻訳・要約（仕様に無い）

## 8. 関連仕様

- 全体: [`00-overview.md`](./00-overview.md) ｜ データ: [`02-data-model.md`](./02-data-model.md)
- 前工程: [`05-manual-capture.md`](./05-manual-capture.md)、[`08-page-editing.md`](./08-page-editing.md)
- 次工程: [`10-searchable-pdf.md`](./10-searchable-pdf.md)、[`11-export.md`](./11-export.md)

## 9. 実装単位

<!-- spec-to-beads がこの節を機械的に読んで bd の子タスクを作る -->
- [ ] [Backend] Phase 0 スパイク: ML Kit 日本語OCR（bundled）の横書き・縦書き評価（§3.6 の資料区分・横書き95%目標の測定）を行う
  - 受け入れ基準: make verify が PASS; 評価資料区分ごとの文字正解率の測定記録が残る; 横書き95%未達または重大な問題があれば報告して停止する
- [ ] [Backend] OcrGateway 実装（ML Kit ラッパー・入力前処理: 回転補正/crop適用/縮小・blocksJson変換）を実装する
  - 受け入れ基準: make verify が PASS; ML Kit 型が ocr/ パッケージ外へ漏れない; フィクスチャ画像でblocksJsonが§3.4スキーマに適合する単体テストが通過する
- [ ] [Backend] OCR Worker Queue（WorkManager・状態遷移・撮影優先・再実行・一括実行・強制終了後の再開）を実装する
  - 受け入れ基準: make verify が PASS; 状態遷移（pending→running→succeeded/failed、stale→再実行）の単体テストが通過する; 未完了ジョブが再起動後に再開されるテストが通過する
- [ ] [Backend] 読み順補正（縦書き・段組みの座標ベース並べ替え）を実装する
  - 受け入れ基準: make verify が PASS; 二段組み・縦書きのフィクスチャで期待読み順になる単体テストが通過する
- [ ] [Frontend] OCR編集画面（分割表示・ページ内検索・再実行・手動修正・元へ戻す）を実装する
  - 受け入れ基準: make verify が PASS; 修正がeditedTextへ保存されfullTextが不変であること、「元へ戻す」でeditedTextが破棄されることのViewModel単体テストが通過する
