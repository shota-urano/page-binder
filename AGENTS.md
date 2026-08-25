# プロジェクト概要

PageBinder — 画面キャプチャ・端末内OCR・検索可能PDF化を行う完全オフラインの Android アプリ。
仕様の正本は `docs/requirements.md`（要件定義兼基本仕様書）。エージェントはこの仕様の MVP 範囲を実装する。

## 技術スタック / 構成

- Kotlin / Jetpack Compose + Material 3 / 単一 Gradle モジュール `:app`
- minSdk 29 / Gradle Kotlin DSL + Version Catalog（`gradle/libs.versions.toml`）
- アーキテクチャ: レイヤード + UDF。パッケージ境界は `ui/ domain/ data/ capture/ image/ ocr/ export/ storage/ legal/`
- DB: Room / 設定: DataStore / 非同期: Coroutines・Flow / DI: Hilt
- 画面取得: MediaProjection / OCR: ML Kit Text Recognition v2 Japanese（bundled）/ PDF: PDFBox-Android
- ビルド・検証: `make verify-fast`（lint）/ `make verify`（lint + test + Android Lint + build + e2e）
- 起動は `make run`（エミュレータまたは USB 実機へ debug APK をインストールして起動）

## 用語・前提

- 「書籍プロジェクト」= BookProject。ページ・OCR結果・書き出し履歴の親。
- 完全オフライン: `INTERNET` 権限を持たない。外部AI・クラウドOCR・アナリティクスは仕様外。
- 個人利用APK配布。Play Store 公開・iOS は MVP 対象外。

## ルール

1. `AndroidManifest.xml` に `INTERNET` 権限・AccessibilityService 宣言を追加しない。アナリティクス・広告・クラッシュ送信 SDK も導入しない（マージ後 Manifest の検査がビルド時テストの受入条件）。
2. `FLAG_SECURE`・DRM・撮影禁止画面の回避処理を実装しない。保護画面が黒く写る場合は明示エラーにする（回避案内も書かない）。
3. 自動ページ送りを実装しない。`dispatchGesture()` 等アクセシビリティサービスの目的外利用は将来検討でも禁止（docs/requirements.md §8.4）。
4. Room / ML Kit / PDFBox の型を Presentation・Domain 層へ漏らさない。Framework 固有実装は Repository/Gateway インターフェースの実装に閉じ込める。
5. 画像編集（回転・切り取り）は非破壊で行う。元画像ファイルを上書き・削除する実装を書かない（FR-IMG-007）。
6. ログへ画像・OCR全文・書籍タイトル・保存URIを出力しない（§16.3）。
7. MediaProjection の開始は §11.1 の順序（許可取得 → FGS 開始 → MediaProjection 取得 → VirtualDisplay 生成 → Callback 登録）を厳守し、許可 Intent・インスタンスを再利用しない。
8. ViewModel は画面単位で、不変 `UiState` を `StateFlow` で公開する。UI からデータソースへ直接アクセスしない。再利用 UI 部品に ViewModel を持たせない。
9. マルチモジュール化・先行抽象化をしない。初版は単一 `:app` + パッケージ境界で分離する（§10.6）。
10. 依存追加は `gradle/libs.versions.toml` 経由のみ。バージョンをビルドファイルへ直書きしない。
11. MVP 範囲外（クラウド同期・外部AI連携・章管理・暗号化エクスポート等 §4.2）を実装しない。仕様に無い機能は提案に留める。

## 検証

- 完了と言う前に `make verify` を実行し、`VERIFY: PASS` の出力を貼る。
  PASS の証拠が無い報告は未完了として扱う（Default-FAIL）。

## 実装担当者

- Frontend: Claude Code（Opus） / Backend: codex
- 検証: codex（fresh-context。コードレビュー＋ユーザー操作タスクは実機UI検証——dev-loop 手順5）
  （spec-writer の「## 実装単位」の担当表記と dev-loop のルーティングがこの節を読む。
  プロジェクトで分担を変えるならここを書き換える）

## 振る舞い

- 範囲外の変更をしない。隣接コードに触れない（外科的変更）
- 破壊的操作・依存の追加削除・共有状態への影響の前に明示承認を取る
- 推測で進めず、不明点は質問する
- 失敗（テスト落ち・OCR/PDF スパイク不合格）を黙ってスキップせず必ず報告する
- 作業後に変更点を要約する

## 記憶 / 保守

- 決定と却下案は MEMORY.md、失敗→成功手順は ERRORS.md に記録
- 同じミスをしたら、このファイルに再発防止ルールを追記する

## 役割分担（このプロジェクトの場合）

- Hooks: 編集後 `make lint`（ktlint）。build を含む verify は hook に載せない
- Skills 化するもの: spec-writer / spec-to-beads / dev-loop（既存共通スキルを使用）
- Agents に委譲するもの: OCR・PDF スパイク（Phase 0）の独立検証は fresh-context 評価者へ
