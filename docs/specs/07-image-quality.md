---
status: confirmed
confirmed_rev: 6f8ceab
範囲: 画像保存形式・黒画面/単色検出・perceptual hashによる近似重複検出・非破壊の回転/切り取り演算
---

# 07. 画像処理・品質判定 仕様

**親**: [`00-overview.md`](./00-overview.md) ｜ **担当**: Backend ｜ **範囲**: FR-IMG-001～007、requirements §8.3（ハッシュ）

## 1. 目的

保存画像の品質判定（黒画面・重複）と、非破壊の回転・切り取り演算を `image/` パッケージに集約して提供する。撮影（[05](./05-manual-capture.md)/[06](./06-auto-capture.md)）・編集（[08](./08-page-editing.md)）・OCR入力（[09](./09-ocr.md)）・PDF出力（[10](./10-searchable-pdf.md)）が共有する。

## 2. 入出力

- 入力: 撮影フレーム（Bitmap）、Page の rotation / crop 属性
- 出力: 保存画像ファイル（WebP Lossless）、contentHash / perceptualHash、qualityState 判定、表示・OCR・PDF用の変換済み画像

## 3. 処理詳細

### 3.1 保存形式（FR-IMG-001/002）

- **WebP Lossless で確定**（[02-data-model](./02-data-model.md) §4 で確定済み）。互換用PNG出力も可能にする（requirements §10.1）
- 画像の向き・寸法・撮影日時を Page レコードに保存する（[02-data-model](./02-data-model.md) §3.1）
- Phase 0 では容量・保存速度の実測記録のみ行う（形式の再判断はしない。旧・未決事項3）

### 3.2 黒画面・単色画面検出（FR-IMG-003）

- 平均輝度と画素分散から黒画面・単色画面を検出する
- 検出時は qualityState = `black` として保存候補に隔離し、通常ページ数へ自動算入しない（requirements §17）
- 保護画面が黒く写った場合も同じ経路で明示エラー表示する。**回避処理・回避案内を実装しない**（AGENTS.md ルール2）
- 閾値は実装時に実測で確定する

### 3.3 近似重複検出（FR-IMG-004、requirements §8.3）

- perceptual hash（dHash または pHash）で近似重複を検出する
- contentHash（完全一致）と perceptualHash（近似）を保存時に計算し Page に格納
- 直前保存ページとのハッシュ距離が閾値以下なら qualityState = `duplicate` として警告（重複ページ警告）
- 同じハッシュ実装を連続撮影の変化判定（[06](./06-auto-capture.md) §3.2）と共有する
- 距離閾値は実装時に確定（未決事項4と併せて実測）

### 3.4 非破壊の回転・切り取り（FR-IMG-005/006/007）

- **元画像ファイルを上書き・削除しない**。回転（0/90/180/270）と切り取り（0～1正規化座標）は Page 属性のみで表現する
- 表示・OCR・PDF出力時に属性を適用した派生画像を都度生成する（キャッシュは temp/ に置いてよい）
- 切り取り範囲はページごとに変更でき（FR-IMG-005）、同一書籍への一括適用もできる（FR-IMG-006、Should）
- 座標変換（回転→切り取りの適用順）は本仕様の実装を唯一の正とし、OCR入力（[09](./09-ocr.md) §3.1）と PDF座標変換（[10](./10-searchable-pdf.md) §3.2）が同じ変換を使う

## 4. 設定値・確定値

| 項目 | 値 | 出典 |
|---|---|---|
| 保存形式 | **WebP Lossless（確定）** + 互換用PNG | requirements §10.1・[02-data-model](./02-data-model.md) §4 |
| rotation | 0/90/180/270 のみ | [02-data-model](./02-data-model.md) |
| crop | 0～1 正規化座標 | [02-data-model](./02-data-model.md) |
| 元画像 | 上書き・削除禁止（非破壊） | FR-IMG-007 |
| 輝度/分散/ハッシュ距離の閾値 | 実装時に確定（実測） | requirements §23 |

## 5. インターフェース

- `ImageStore`（[01-architecture](./01-architecture.md) §3.3）: 原子的保存・読み出し
- `image/` の純粋関数群: 黒画面判定・ハッシュ計算・距離計算・回転/切り取り変換（Bitmap→Bitmap、座標→座標）
- qualityState の書き込みは `PageRepository` 経由

## 6. エラー処理

- 変換失敗（メモリ不足等）: 元画像は無傷のまま、操作を失敗として報告
- 黒画面: 隔離して利用者に確認を促す（[08](./08-page-editing.md) の黒画面候補一覧）

## 7. スコープ外

- 判定結果を使うUI（[08-page-editing](./08-page-editing.md)）
- 連続撮影の状態機械（[06-auto-capture](./06-auto-capture.md)）
- 画質補正・二値化等の画像強調（仕様に無いため実装しない）

## 8. 関連仕様

- 全体: [`00-overview.md`](./00-overview.md) ｜ データ: [`02-data-model.md`](./02-data-model.md)
- 利用元: [`05-manual-capture.md`](./05-manual-capture.md)、[`06-auto-capture.md`](./06-auto-capture.md)、[`08-page-editing.md`](./08-page-editing.md)、[`09-ocr.md`](./09-ocr.md)、[`10-searchable-pdf.md`](./10-searchable-pdf.md)

## 9. 実装単位

<!-- spec-to-beads がこの節を機械的に読んで bd の子タスクを作る -->
- [ ] [Backend] WebP Lossless 保存・PNG互換出力・contentHash 計算を実装する
  - 受け入れ基準: make verify が PASS; 保存→読み出しでピクセル一致（ロスレス）の単体テストが通過する
- [ ] [Backend] 黒画面・単色画面検出（平均輝度＋画素分散）を実装する
  - 受け入れ基準: make verify が PASS; 黒画像/単色画像/通常画像のフィクスチャで判定が正しい単体テストが通過する
- [ ] [Backend] perceptual hash（dHash/pHash）と距離計算を実装する
  - 受け入れ基準: make verify が PASS; 同一画像で距離0・微差画像で小距離・別画像で大距離となる単体テストが通過する
- [ ] [Backend] 非破壊の回転・切り取り変換（画像変換＋座標変換の共通実装、書籍単位の一括crop適用）を実装する
  - 受け入れ基準: make verify が PASS; 回転90/180/270と切り取りの座標往復テストが通過する; 変換後も元画像ファイルが変更されないことをテストで検証する
