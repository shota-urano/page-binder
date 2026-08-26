# デザイン仕様 全体（画面一覧・トークン参照）

生成: design-writer 2026-08-25。素材はすべて**系統A（gpt-image-2 モック画像）**。B案（コード）は不採用（ユーザー決定）。

- **素材の正本**: `docs/design/mockups/`（画像そのもの。仕様書は画像の翻訳であり、最終判定は画像と突き合わせる）
- **トークンの正本**: [`system/01-tokens.md`](./system/01-tokens.md)（design-prep 成果物）。本書・画面仕様は色コード・寸法を再定義しない
- **コンポーネントの正本**: [`system/02-components.md`](./system/02-components.md)
- **原則の正本**: [`system/03-principles.md`](./system/03-principles.md)

## 実装者への共通注意（全画面共通・厳守）

[`system/03-principles.md`](./system/03-principles.md)「モック画像の読み方」の再掲。モックは**端末フレーム込み**で描かれている。

1. **OS領域は実装対象外**: 端末外枠・ベゼル・側面ボタン / ステータスバー（時刻「14:32」・電池・電波）/ 下部ナビゲーションバー。OSが描画する。アプリ内に自前で描かない
2. **サンプルデータをハードコードしない**: 書籍名「実践Kotlinプログラミング」・著者「山田太郎」・ページ数・日時等はすべて例示
3. **数値はトークンが正**: 余白・角丸・文字サイズは画像からの目測でなく [`system/01-tokens.md`](./system/01-tokens.md) に従う
4. 実装するのは**アプリバーから下、ナビゲーションバーより上のコンテンツ領域だけ**（06 フローティングUIのみ例外: オーバーレイ部品と保存トーストだけを実装し、背面に写る読書アプリの内容は撮影対象の例示）

## 画面一覧（唯一の有効リスト — 下流はこの表に載る画面だけを使う）

| # | 画面名 | 詳細仕様 | ソース種別 | 素材パス | 対応spec | 未定の状態 |
|---|---|---|---|---|---|---|
| 01 | ホーム画面（書籍一覧） | [01-home.md](./01-home.md) | 画像 | docs/design/mockups/01-home.png | [specs/03-book-project.md](../specs/03-book-project.md) §3.2 | 空一覧 / 検索絞り込み中 / 並べ替えメニュー展開 |
| 02 | 書籍作成・編集画面 | [02-book-edit.md](./02-book-edit.md) | 画像 | docs/design/mockups/02-book-edit.png | [specs/03-book-project.md](../specs/03-book-project.md) §3.1 | 編集モード（既存書籍） / 文字数超過エラー |
| 03 | 書籍詳細画面 | [03-book-detail.md](./03-book-detail.md) | 画像 | docs/design/mockups/03-book-detail.png | [specs/03-book-project.md](../specs/03-book-project.md) §3.4 | ページ0件時 / 削除導線 |
| 04 | ごみ箱画面 | [04-trash.md](./04-trash.md) | 画像 | docs/design/mockups/04-trash.png | [specs/03-book-project.md](../specs/03-book-project.md) §3.3 | 空のごみ箱 / 復元確認 |
| 05 | 撮影準備画面 | [05-capture-prep.md](./05-capture-prep.md) | 画像 | docs/design/mockups/05-capture-prep.png | [specs/04-capture-session.md](../specs/04-capture-session.md) §3.2 | 許可済み（開始有効） / 通知権限の扱い |
| 06 | フローティングUI | [06-floating-ui.md](./06-floating-ui.md) | 画像 | docs/design/mockups/06-floating-ui.png | [specs/04-capture-session.md](../specs/04-capture-session.md) §3.4 | 停止中 / 連続の一時停止中 / ドラッグ・吸着の見た目 |
| 07 | ページ一覧画面 | [07-page-list.md](./07-page-list.md) | 画像 | docs/design/mockups/07-page-list.png | [specs/08-page-editing.md](../specs/08-page-editing.md) §3.1 | リスト表示 / 非選択時アプリバー / 削除確認 / 待機バッジ / 並べ替え中 |
| 08 | 回転・切り取り編集画面 | [08-page-edit.md](./08-page-edit.md) | 画像 | docs/design/mockups/08-page-edit.png | [specs/08-page-editing.md](../specs/08-page-editing.md) §3.2 | 回転後表示 / 一括適用チェック時の確認 |
| 09 | 重複候補比較・黒画面候補一覧 | [09-duplicate-review.md](./09-duplicate-review.md) | 画像 | docs/design/mockups/09-duplicate-review.png | [specs/08-page-editing.md](../specs/08-page-editing.md) §3.2 | 候補0件 / 削除実行時の確認 |
| 10 | OCR編集画面 | [10-ocr-edit.md](./10-ocr-edit.md) | 画像 | docs/design/mockups/10-ocr-edit.png | [specs/09-ocr.md](../specs/09-ocr.md) §3.5 | 未修正時 / ページ内検索UI / OCR実行中・失敗 |
| 11 | 書き出し画面 | [11-export.md](./11-export.md) | 画像 | docs/design/mockups/11-export.png | [specs/11-export.md](../specs/11-export.md) §3.2 | 進捗・キャンセル / 成功・失敗表示 / ページ範囲指定UI |
| 12 | 初回同意画面 | [12-consent.md](./12-consent.md) | 画像 | docs/design/mockups/12-consent.png | [specs/12-legal-guardrails.md](../specs/12-legal-guardrails.md) §3.1 | 「同意しない」後の挙動表示 |

- 削除確認ダイアログは独立画面ではなく 04 の状態として素材化済み（01/03/07 の削除確認は未定＝素材に無い。04 のダイアログ様式を踏襲するのが推測）
- `mockups/` と `reference/` の重複なし（reference/ 自体が無い）。各 png と対になる `*.prompt.md` は生成時プロンプトの控えであり、正本は png

## デザイントークン（参照）

**値の正本は [`system/01-tokens.md`](./system/01-tokens.md)。ここには再掲しない。** 画面仕様はトークン名（`--color-primary` 等）で参照する。

モック全12枚と system/ トークンの明確な食い違いは検出されなかった（画像からの目測で値の一致は断定できないため、実装は常に system/ の値を使うこと）。モックで確認できるトークン運用:

- アプリバー: 通常は背景 `--color-background` に黒文字（01〜05, 08〜12）。**複数選択モードのみ** `--color-primary` 塗り+白文字（07）
- 撮影系の強調（クロップ枠・フローティングUIのアイコンリング・保存数）に `--color-accent`
- 破壊操作（完全に削除・削除・失敗）に `--color-error`、警告バッジ（重複・黒画面・OCR未完了）に `--color-warning`
- フローティングUIのみ `--color-overlay-bg` + `--color-overlay-text`（06）
