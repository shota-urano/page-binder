---
status: confirmed
confirmed_rev: 6f8ceab
範囲: フローティングボタンによる手動撮影と1ページ保存手順（直列キュー・写り込み防止・原子的保存）
---

# 05. 手動撮影 仕様

**親**: [`00-overview.md`](./00-overview.md) ｜ **担当**: Backend ｜ **範囲**: FR-CAP-001～007、UC-02、requirements §11.2

## 1. 目的

フローティングボタン1回押しで、ボタンが写り込まない安定した1ページを原子的に保存し、OCRキューへ登録する。

## 2. 入出力

- 入力: 撮影ボタン押下イベント、アクティブな撮影セッション（[04](./04-capture-session.md)）のフレーム
- 出力: 元画像ファイル（`files/projects/{project-id}/images/{page-id}.webp`）+ Page レコード（[02-data-model](./02-data-model.md) §3.1）+ OCRキュー登録（[09](./09-ocr.md)）

## 3. 処理詳細

### 3.1 1ページの保存手順（requirements §11.2、順序厳守）

1. 撮影要求を**直列キュー**へ投入する（二重タップ・連打の重複保存抑止、FR-CAP-003）
2. フローティングUIを非表示にする（FR-CAP-002）
3. 画面描画が安定するまで待機する（初期値150ms。§4 参照）
4. `ImageReader` から最新フレームを取得する
5. 端末回転と取得領域を補正する
6. 元画像をアプリ専用領域へ**原子的に**保存する（temp書き込み→rename）
7. DBへページ情報を登録する（sequence 採番・寸法・capturedAt・contentHash・perceptualHash）
8. 黒画面・重複判定を行う（判定ロジックは [07-image-quality](./07-image-quality.md)）
9. OCRキューへ登録する
10. フローティングUIを再表示する

途中失敗時は一時ファイルを成果物として登録しない。

### 3.2 フィードバック

- 保存成功: 短い振動 + ページ番号表示（FR-CAP-004、UC-02 手順9）
- 保存失敗: 理由と再試行手段を表示（FR-CAP-005）
- 撮影音: 初期状態で無効、任意で有効化できる（FR-CAP-007、Could）

### 3.3 性能目標

撮影ボタン押下から保存完了通知まで、通常端末で1秒以内を目標とする（requirements §16.1）。

## 4. 設定値・確定値

| 項目 | 値 | 出典 |
|---|---|---|
| 撮影キュー | 直列 | requirements §11.2 |
| 撮影音初期値 | 無効 | FR-CAP-007 |
| 保存レイテンシ目標 | 1秒以内 | requirements §16.1 |
| 安定待機時間 | **初期値150ms（確定）**。Phase 0 実測で 100～500ms の範囲内で調整可（範囲外への変更は仕様改訂） | requirements §11.2 手順3・spec-review 2026-08-25 確定 |

## 5. インターフェース

- 入力: フローティングUI（[04](./04-capture-session.md) §3.4）→ Capture Session Coordinator → CaptureOnePage Use Case
- 依存: `CaptureGateway` / `ImageStore` / `PageRepository`（[01-architecture](./01-architecture.md) §3.3）、品質判定関数（[07](./07-image-quality.md)）
- 出力: OCRキュー（[09-ocr](./09-ocr.md) §3.2）への enqueue

## 6. エラー処理

- フレーム取得失敗・保存失敗: 一時ファイルを破棄し、失敗理由と再試行手段を表示（FR-CAP-005）
- ストレージ不足: 撮影を停止し、必要容量と整理導線を表示（requirements §17）
- 黒画面検出: 保存候補として隔離し、通常ページ数へ自動算入しない（詳細は [07](./07-image-quality.md)）

## 7. スコープ外

- 画面変化検出による自動保存（[06-auto-capture](./06-auto-capture.md)）
- 黒画面・重複の判定アルゴリズム自体（[07-image-quality](./07-image-quality.md)）

## 8. 関連仕様

- 全体: [`00-overview.md`](./00-overview.md) ｜ データ: [`02-data-model.md`](./02-data-model.md)
- 前工程: [`04-capture-session.md`](./04-capture-session.md)
- 次工程: [`07-image-quality.md`](./07-image-quality.md)（品質判定）、[`09-ocr.md`](./09-ocr.md)（OCRキュー）

## 9. 実装単位

<!-- spec-to-beads がこの節を機械的に読んで bd の子タスクを作る -->
- [ ] [Backend] Phase 0 スパイク: オーバーレイの写り込み防止（非表示→フレーム取得→再表示）を検証する
  - 受け入れ基準: make verify が PASS; 非表示指示後に取得したフレームへボタンが写り込まないことの検証記録が残る; 失敗時は報告して停止する
- [ ] [Backend] CaptureOnePage Use Case（§3.1 の10手順・直列キュー・原子的保存・失敗時ロールバック）を実装する
  - 受け入れ基準: make verify が PASS; 連打時に1件しか保存されない単体テスト、保存途中失敗で画像もDBレコードも残らない単体テストが通過する
- [ ] [Backend] 撮影フィードバック（振動・ページ番号表示・失敗理由表示・撮影音設定）を実装する
  - 受け入れ基準: make verify が PASS; 撮影音の初期値が無効であることを設定の単体テストで検証する
