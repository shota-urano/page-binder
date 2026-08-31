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
7. MediaProjection の開始は §11.1 の順序（許可取得 → FGS 開始 → MediaProjection 取得 → Callback 登録 → VirtualDisplay 生成）を厳守し、許可 Intent・インスタンスを再利用しない。
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

<!-- BEGIN BEADS INTEGRATION v:1 profile:minimal hash:970c3bf2 -->
## Beads Issue Tracker

This project uses **bd (beads)** for issue tracking. Run `bd prime` to see full workflow context and commands.

### Quick Reference

```bash
bd ready              # Find available work
bd show <id>          # View issue details
bd update <id> --claim  # Claim work
bd close <id>         # Complete work
```

### Rules

- Use `bd` for ALL task tracking — do NOT use TodoWrite, TaskCreate, or markdown TODO lists
- Run `bd prime` for detailed command reference and session close protocol
- Use `bd remember` for persistent knowledge — do NOT use MEMORY.md files

**Architecture in one line:** issues live in a local Dolt DB; sync uses `refs/dolt/data` on your git remote; `.beads/issues.jsonl` is a passive export. See https://github.com/gastownhall/beads/blob/main/docs/SYNC_CONCEPTS.md for details and anti-patterns.

## Agent Context Profiles

The managed Beads block is task-tracking guidance, not permission to override repository, user, or orchestrator instructions.

- **Conservative (default)**: Use `bd` for task tracking. Do not run git commits, git pushes, or Dolt remote sync unless explicitly asked. At handoff, report changed files, validation, and suggested next commands.
- **Minimal**: Keep tool instruction files as pointers to `bd prime`; use the same conservative git policy unless active instructions say otherwise.
- **Team-maintainer**: Only when the repository explicitly opts in, agents may close beads, run quality gates, commit, and push as part of session close. A current "do not commit" or "do not push" instruction still wins.

## Session Completion

This protocol applies when ending a Beads implementation workflow. It is subordinate to explicit user, repository, and orchestrator instructions.

1. **File issues for remaining work** - Create beads for anything that needs follow-up
2. **Run quality gates** (if code changed) - Tests, linters, builds
3. **Update issue status** - Close finished work, update in-progress items
4. **Handle git/sync by active profile**:
   ```bash
   # Conservative/minimal/default: report status and proposed commands; wait for approval.
   git status

   # Team-maintainer opt-in only, unless current instructions forbid it:
   git pull --rebase
   bd dolt push
   git push
   git status
   ```
5. **Hand off** - Summarize changes, validation, issue status, and any blocked sync/commit/push step

**Critical rules:**
- Explicit user or orchestrator instructions override this Beads block.
- Do not commit or push without clear authority from the active profile or the current user request.
- If a required sync or push is blocked, stop and report the exact command and error.
<!-- END BEADS INTEGRATION -->

<!-- BEGIN BEADS CODEX SETUP: generated by bd setup codex -->
## Beads Issue Tracker

Use Beads (`bd`) for durable task tracking in repositories that include it. Use the `beads` skill at `.agents/skills/beads/SKILL.md` (project install) or `~/.agents/skills/beads/SKILL.md` (global install) for Beads workflow guidance, then use the `bd` CLI for issue operations.

### Quick Reference

```bash
bd ready                # Find available work
bd show <id>            # View issue details
bd update <id> --claim  # Claim work
bd close <id>           # Complete work
bd prime                # Refresh Beads context
```

### Rules

- Use `bd` for all task tracking; do not create markdown TODO lists.
- Run `bd prime` when Beads context is missing or stale. Codex 0.129.0+ can load Beads context automatically through native hooks; use `/hooks` to inspect or toggle them.
- Keep persistent project memory in Beads via `bd remember`; do not create ad hoc memory files.

**Architecture in one line:** issues live in a local Dolt DB; sync uses `refs/dolt/data` on your git remote; `.beads/issues.jsonl` is a passive export. See https://github.com/gastownhall/beads/blob/main/docs/SYNC_CONCEPTS.md for details and anti-patterns.
<!-- END BEADS CODEX SETUP -->
