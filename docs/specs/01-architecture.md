---
status: confirmed
confirmed_rev: 6f8ceab
範囲: レイヤー構成・パッケージ境界・Repository/Gatewayインターフェース・DI・プロセス構成（整合性アンカー）
---

# 01. アーキテクチャ仕様（整合性アンカー）

**親**: [`00-overview.md`](./00-overview.md) ｜ **担当**: Backend ｜ **範囲**: 全機能が共有する構成・インターフェース定義

## 1. 目的

全詳細仕様が参照する構成の正本。レイヤー境界・パッケージ配置・Repository/Gatewayインターフェースの命名をここで固定し、工程間で型と依存方向が食い違わないようにする。

一次情報源: `docs/requirements.md` §10（技術アーキテクチャ）。

## 2. 入出力

- 入力: なし（他仕様が参照する定義集）
- 出力: パッケージ構成・インターフェース契約・DI構成

## 3. 処理詳細

### 3.1 レイヤーと依存方向（requirements §10.3）

Presentation → Application/Domain → （抽象） ← Data → Framework の4層。

- Presentation: Compose Screens / Overlay View / 画面単位 ViewModel（不変 `UiState` を `StateFlow` で公開）
- Application/Domain: Use Case、Capture Session Coordinator、Repository/Gateway **インターフェース**（ports）
- Data: Repository/Gateway 実装、Entity/Domain マッパー
- Framework: Room / File / MediaProjection / ML Kit / PDFBox / SAF

**禁止事項（AGENTS.md ルール4・8）**:
- Room・ML Kit・PDFBox の型を Presentation / Domain 層へ漏らさない
- UI からデータソースへ直接アクセスしない
- 再利用UI部品に ViewModel を持たせない

### 3.2 パッケージ境界（requirements §10.6）

単一 Gradle モジュール `:app`、ルートパッケージ `com.pagebinder.app`。

```text
com.pagebinder.app/
  ui/        # Compose画面・ViewModel・オーバーレイView・ナビゲーション
  domain/    # Use Case・ドメインモデル・Repository/Gatewayインターフェース
  data/      # Repository実装・Room・DataStore・マッパー
  capture/   # MediaProjection・VirtualDisplay・ImageReader・FGS・撮影パイプライン
  image/     # 画像保存・回転/切り取り演算・黒画面/重複判定（pHash）
  ocr/       # ML Kit ラッパー・OCRキュー（WorkManager）
  export/    # PDF/Markdown/TXT/ZIP 生成エンジン
  storage/   # アプリ専用領域のファイル管理・SAF出力
  legal/     # 初回同意・書き出し時確認
```

マルチモジュール化・先行抽象化はしない（AGENTS.md ルール9）。

### 3.3 Repository / Gateway インターフェース一覧

`domain/` に置く抽象。実装は `data/` または各機能パッケージに閉じる。メソッドシグネチャは実装時に確定するが、**名前と責務はここが正本**。

| インターフェース | 責務 | 実装が使うFramework | 主に使う仕様 |
|---|---|---|---|
| `BookProjectRepository` | 書籍プロジェクトCRUD・ごみ箱 | Room | [03](./03-book-project.md) |
| `PageRepository` | ページCRUD・並べ替え・品質/OCR状態更新 | Room + File | [05](./05-manual-capture.md) [08](./08-page-editing.md) |
| `OcrResultRepository` | OCR結果の保存・取得・修正版管理 | Room | [09](./09-ocr.md) |
| `ExportRecordRepository` | 書き出し履歴の管理 | Room | [11](./11-export.md) |
| `ImageStore` | 元画像・一時ファイルの原子的保存/読み出し | File | [05](./05-manual-capture.md) [07](./07-image-quality.md) |
| `CaptureGateway` | MediaProjectionセッションの開始/停止/フレーム取得 | MediaProjection | [04](./04-capture-session.md) |
| `OcrGateway` | 1画像→OCR構造化結果 | ML Kit | [09](./09-ocr.md) |
| `PdfGateway` | PDF生成（検索可能/画像） | PDFBox-Android | [10](./10-searchable-pdf.md) |
| `ExportStorageGateway` | SAF URIへのストリーム出力 | SAF | [11](./11-export.md) |
| `SettingsRepository` | アプリ設定（撮影間隔・感度等） | DataStore | [06](./06-auto-capture.md) |
| `ConsentRepository` | 初回同意履歴 | DataStore | [12](./12-legal-guardrails.md) |

PDF生成とOCRはインターフェース越しに利用し、ライブラリ差し替えを可能にする（requirements §10.6、Phase 0 スパイク不合格時の差替に備える）。

### 3.4 プロセス・実行コンポーネント

| コンポーネント | 種別 | 責務 |
|---|---|---|
| `MainActivity` | Activity | Compose画面ホスト |
| Capture Foreground Service | FGS（`mediaProjection` 種別） | 撮影セッション維持・通知・オーバーレイ制御 |
| Capture Session Coordinator | Domainサービス | 撮影状態機械・直列撮影キュー |
| OCR Worker Queue | WorkManager | OCRのバックグラウンド直列/制限付き並列実行 |
| Export Engine | Coroutine（進捗・キャンセル付き） | 成果物生成 |

### 3.5 UDFの契約

- 1画面 = 1 ViewModel。`UiState`（不変 data class）を `StateFlow` で公開
- UI → ViewModel は `UiEvent`（sealed interface）で送る
- 状態は ViewModel → UI へ一方向

## 4. 設定値・確定値

| 項目 | 値 | 変更禁止理由 |
|---|---|---|
| minSdk | 29 | requirements 確定事項 |
| targetSdk / compileSdk | 実装時点の最新安定版 | requirements §10.1 |
| Gradleモジュール | `:app` 単一 | requirements §10.6 |
| 依存追加 | `gradle/libs.versions.toml` 経由のみ | AGENTS.md ルール10 |
| `INTERNET` 権限 | 追加禁止 | requirements §15 |
| AccessibilityService | 宣言・実装禁止 | requirements §8.4・§15 |

## 5. インターフェース

本仕様がアンカー。API・型は §3.3 の表を参照。データスキーマは [`02-data-model.md`](./02-data-model.md) を参照。

## 6. エラー処理

- 各Gateway実装は Framework例外をドメインエラー型（sealed class、実装時に確定）へ変換してから上位へ返す。Framework例外を Presentation まで透過させない。
- ログへ画像・OCR全文・書籍タイトル・保存URIを出力しない（requirements §16.3、全実装共通）。

## 7. スコープ外

- マルチモジュール分割（コード量・ビルド時間・複数人開発の増大時に再検討）
- メソッドシグネチャの詳細定義（実装時に確定。名前と責務のみ本仕様で固定）

## 8. 関連仕様

- 全体: [`00-overview.md`](./00-overview.md)
- データスキーマ: [`02-data-model.md`](./02-data-model.md)
- 撮影プロセス詳細: [`04-capture-session.md`](./04-capture-session.md)

## 9. 実装単位

<!-- spec-to-beads がこの節を機械的に読んで bd の子タスクを作る -->
- [ ] [Backend] 依存追加（Hilt / Room / DataStore / Coroutines / WorkManager / Navigation Compose）を Version Catalog 経由で導入し、パッケージ骨格 `ui/ domain/ data/ capture/ image/ ocr/ export/ storage/ legal/` を作成する
  - 受け入れ基準: make verify が PASS; libs.versions.toml にのみバージョンが記載され build.gradle.kts に直書きがない; マージ後Manifestに INTERNET 権限とAccessibilityService宣言が無いことを検査するビルド時テストが存在し通過する
- [ ] [Backend] Hilt を導入し Application クラス・DIモジュール骨格（Repository/Gatewayのバインド地点）を作成する
  - 受け入れ基準: make verify が PASS; @HiltAndroidApp を持つ Application が Manifest に登録されている
- [ ] [Backend] §3.3 の Repository/Gateway インターフェースを domain/ に定義する（メソッドは主要ユースケースぶんの最小セット）
  - 受け入れ基準: make verify が PASS; domain/ が Room・ML Kit・PDFBox の型を import していないことを確認する単体テストまたはlintで検証できる
