# 基本コンポーネント（正本）

トークン値は [`01-tokens.md`](./01-tokens.md) を参照（ここでは名前で参照する）。

## ボタン

| 種別 | 見た目 | 状態 |
|---|---|---|
| Primary | `--color-primary` 塗り・白文字・角丸8dp・高さ48dp | default / pressed(`--color-primary-dark`) / disabled(38%不透明) |
| Secondary | 枠線 `--color-primary`・文字 `--color-primary`・透明地 | 同上 |
| Destructive | `--color-error` 塗りまたは文字 | 削除・完全削除に限定 |
| FAB | pill・`--color-primary` 塗り・アイコン+ラベル | ホーム「新しい書籍」等 |

## 入力欄

- 枠線 `--color-divider`・角丸8dp・フォーカスで `--color-primary` 枠
- ラベル上置き・補助文14spを下に（例: 文字数上限）
- error 状態: 枠と補助文を `--color-error`（例: タイトル空・文字数超過のインラインエラー）

## カード・リスト行

- surface 白・角丸12dp・elevation 1
- 書籍行（72dp）: 左サムネイル56dp角丸8dp → タイトル16sp+著者14sp → 右にページ数・更新日時12sp
- サムネイル無し時はプレースホルダ（`--color-divider` 地に本アイコン）

## 状態バッジ・アイコン（OCR状態）

pill バッジ。**色だけで区別せず、必ずアイコン+文字を併記**（requirements §16.4）。

| 状態 | 表示 |
|---|---|
| pending | 時計アイコン+「待機」・`--color-text-secondary` |
| running | 回転アイコン+「実行中」・`--color-primary` |
| succeeded | チェック+「完了」・`--color-accent` |
| failed | ×+「失敗」・`--color-error` |
| stale | 更新+「再実行が必要」・`--color-warning` |

警告バッジ: 「重複」「黒画面」を `--color-warning` の pill で表示。

## ダイアログ

- surface・角丸12dp・タイトル18sp・本文16sp
- 破壊操作の確認は**対象情報（件数・容量）を必ず本文に含める**。実行ボタンは Destructive、キャンセルは Secondary

## フローティングUI（オーバーレイ専用部品）

- 地色 `--color-overlay-bg`・文字/アイコン `--color-overlay-text`・pill 形状
- 状態は**文字+アイコン**で表示（「撮影中」「停止中」「連続撮影中」）
- ドラッグ移動可・画面端吸着。撮影ボタンは56dp円形

## 進捗

- 線形プログレスバー: `--color-accent`。パーセント+処理中の内容を14spで併記
- キャンセルボタンを常に併置（書き出し・PDF生成）
