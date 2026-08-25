# PageBinder システム全体仕様書（Overview）

**一次情報源**: [`../requirements.md`](../requirements.md)（要件定義兼基本仕様書 v0.1、2026-08-25）

## 1. プロダクト概要

画面キャプチャ・端末内OCR・検索可能PDF化を行う**完全オフライン**の Android アプリ（個人利用APK、Android 10 / API 29 以降）。利用者が正当に閲覧できるコンテンツを書籍プロジェクト単位で整理し、OCR付きPDF・Markdown等へ書き出す。`INTERNET` 権限を持たず、保護機構を回避せず、自動ページ送りを実装しない。

## 2. システム全体像

処理パイプラインと対応する詳細仕様ファイル：

```text
[03 書籍管理] ──作成──→ BookProject
     │ 撮影開始
     ▼
[04 権限・撮影セッション]  MediaProjection同意 → FGS → VirtualDisplay → フローティングUI
     │ フレーム供給
     ├─→ [05 手動撮影] ──┐  ボタン1押し=1ページ・直列キュー・原子的保存
     └─→ [06 連続撮影] ──┤  画面変化検出→安定→重複判定→保存
                          ▼
                 [07 画像処理・品質判定]  WebP Lossless・黒画面/重複検出・非破壊回転/切取
                          │ Page + 画像
                          ├─→ [08 ページ編集]  一覧・並べ替え・削除・回転・切取・undo
                          ▼
                 [09 OCR]  ML Kit日本語(bundled)・WorkManagerキュー・修正UI
                          │ OcrResult (blocksJson)
                          ├─→ [10 検索可能PDF]  2層構造・座標変換・Noto Sans JP
                          ▼
                 [11 書き出し]  PDF/MD/TXT/ZIP/manifest → SAF（端末内・Google Drive）
                          
横断: [01 アーキテクチャ]（層・パッケージ・インターフェース） [02 データモデル]（スキーマ・永続化） [12 法務ガードレール]（初回同意・書き出し確認）
```

## 3. 詳細仕様書一覧（索引）

**この表が唯一の有効リスト**。下流（spec-to-beads）はこの表に載る仕様だけを使う。

| # | ファイル名 | 範囲 | 担当 |
|---|-----------|------|------|
| 01 | [`01-architecture.md`](./01-architecture.md) | レイヤー・パッケージ境界・Repository/Gatewayインターフェース・DI（アンカー） | Backend |
| 02 | [`02-data-model.md`](./02-data-model.md) | Roomエンティティ・ファイル構造・blocksJson/manifest.jsonスキーマ（アンカー） | Backend |
| 03 | [`03-book-project.md`](./03-book-project.md) | 書籍プロジェクトCRUD・ごみ箱・ホーム/作成編集/詳細画面 | Frontend + Backend |
| 04 | [`04-capture-session.md`](./04-capture-session.md) | 権限フロー・FGS・MediaProjectionセッション・フローティングUI基盤 | Backend + Frontend |
| 05 | [`05-manual-capture.md`](./05-manual-capture.md) | 手動撮影・1ページ保存手順・写り込み防止 | Backend |
| 06 | [`06-auto-capture.md`](./06-auto-capture.md) | 連続撮影・画面変化/安定判定・自動停止 | Backend |
| 07 | [`07-image-quality.md`](./07-image-quality.md) | 画像保存形式・黒画面/重複検出・非破壊回転/切り取り演算 | Backend |
| 08 | [`08-page-editing.md`](./08-page-editing.md) | ページ一覧・並べ替え・削除・回転・切り取りUI・取り消し | Frontend |
| 09 | [`09-ocr.md`](./09-ocr.md) | ML Kit日本語OCR・キュー・読み順・OCR編集画面・品質評価 | Backend + Frontend |
| 10 | [`10-searchable-pdf.md`](./10-searchable-pdf.md) | 検索可能PDF・座標変換・フォント埋め込み・PDFBoxスパイク | Backend |
| 11 | [`11-export.md`](./11-export.md) | Markdown/TXT/ZIP生成・SAF書き出し・書き出し画面・履歴 | Backend + Frontend |
| 12 | [`12-legal-guardrails.md`](./12-legal-guardrails.md) | 初回同意・書き出し時確認・技術的制限 | Frontend + Backend |

## 4. 確定済みの初期値・制約（横断・変更禁止）

| 項目 | 値 | 再掲先 |
|---|---|---|
| minSdk | 29（Android 10） | [01](./01-architecture.md) |
| `INTERNET` 権限 / AccessibilityService | 追加・宣言・実装禁止（ビルド時テストで検査） | [01](./01-architecture.md) [04](./04-capture-session.md) |
| 自動ページ送り | 実装しない（dispatchGesture 等は将来検討でも禁止） | [06](./06-auto-capture.md) |
| FLAG_SECURE・DRM回避 | 実装しない。黒画面は明示エラー・回避案内なし | [07](./07-image-quality.md) [12](./12-legal-guardrails.md) |
| 撮影開始順序 | 許可取得→FGS→MediaProjection→VirtualDisplay→Callback（再利用禁止） | [04](./04-capture-session.md) |
| OCRエンジン | ML Kit Text Recognition v2 Japanese（bundled・DL不要） | [09](./09-ocr.md) |
| PDFエンジン | PDFBox-Android 第一候補（Phase 0 スパイク合格が条件） | [10](./10-searchable-pdf.md) |
| 同梱フォント | Noto Sans JP（OFL）・サブセット埋め込み | [10](./10-searchable-pdf.md) |
| 画像保存 | WebP Lossless 基本・非破壊編集（元画像の上書き/削除禁止） | [07](./07-image-quality.md) [02](./02-data-model.md) |
| 連続撮影 最短間隔 | 初期値2秒・範囲1～30秒 | [06](./06-auto-capture.md) |
| 書籍フィールド | タイトル必須1～200 / 著者0～200 / メモ0～2,000文字 | [03](./03-book-project.md) |
| 外部保存 | SAFのみ（Drive専用API・共有ボタン禁止） | [11](./11-export.md) |
| 横書きOCR品質目標 | 文字正解率95%以上 | [09](./09-ocr.md) |
| 性能 | 撮影→保存1秒以内目標・500ページプロジェクト対応 | [05](./05-manual-capture.md) [08](./08-page-editing.md) |
| ログ禁止事項 | 画像・OCR全文・書籍タイトル・保存URIを出力しない | [01](./01-architecture.md)（全実装共通） |

## 5. スコープ外（MVP に含めない — requirements §4.2）

外部AIアップロード / クラウドOCR / クラウド同期・自動バックアップ / DRM・`FLAG_SECURE`・保護サーフェス回避 / 他アプリのログイン情報・UI階層取得 / Kindle等特定アプリ専用連携 / アクセシビリティサービスによる自動ページ送り / Play Store公開対応 / iOS版 / 章・節管理・表紙画像・ISBN・暗号化エクスポート（Phase 4）

## 6. 用語

| 用語 | 意味 |
|---|---|
| 書籍プロジェクト（BookProject） | ページ・OCR結果・書き出し履歴の親となる整理単位 |
| ページ（Page） | 1回の撮影で保存された1画面。sequence は1始まり |
| 撮影セッション | MediaProjection 同意から停止までの一連の撮影期間。許可はセッションごと |
| 連続撮影 | 画面変化検出型の補助モード。ページ送り自体は利用者が行う |
| 検索可能PDF | 表示層（画像）+ 不可視テキスト層の2層PDF |
| stale | 画像編集によりOCR結果が古くなった状態（再実行を促す） |
| SAF | Storage Access Framework。書き出しの唯一の外部保存手段 |
| Phase 0 スパイク | 実装開始時に必須の技術検証（MediaProjection取得・写り込み防止・OCR品質評価・PDFBox日本語テキスト層・SAF Drive保存） |
