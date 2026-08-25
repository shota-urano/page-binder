# デザイントークン（正本）

承認: spec-review 後の design-prep フェーズ1（2026-08-25）。ロゴ（docs/logo.png）由来。
**トークン値の正本はこのファイル。** 画面別プロンプト・design-writer・実装はここを参照する。

## 色（意味ベース）

| トークン | 値 | 用途 |
|---|---|---|
| `--color-primary` | `#1E3FAE` | 主要アクション・アプリバー強調・選択状態（ロゴのロイヤルブルー） |
| `--color-primary-dark` | `#16308C` | primary の押下状態 |
| `--color-accent` | `#2DD4A8` | 進捗・成功系の強調・FAB補助・OCR完了バッジ（ロゴのミント） |
| `--color-background` | `#F8FAFC` | 画面背景 |
| `--color-surface` | `#FFFFFF` | カード・シート・ダイアログ |
| `--color-text` | `#1A2233` | 本文テキスト |
| `--color-text-secondary` | `#5B6472` | 補助テキスト（日時・件数・説明） |
| `--color-divider` | `#E2E8F0` | 区切り線・入力枠 |
| `--color-error` | `#DC2626` | エラー・OCR失敗・削除系アクション |
| `--color-warning` | `#D97706` | 重複警告・黒画面警告・OCR未完了警告 |
| `--color-success` | `#16A34A` | 保存成功・書き出し成功 |
| `--color-overlay-bg` | `#1A2233` @ 85% | フローティングUIの地色（他アプリ上での視認性確保） |
| `--color-overlay-text` | `#FFFFFF` | フローティングUI上のテキスト・アイコン |

## タイポグラフィ

- フォント: システム標準サンセリフ（Roboto / Noto Sans JP）。カスタムフォントは同梱しない
  （PDF埋め込み用 Noto Sans JP と系統を揃える）
- スケール: 見出し 22sp / 画面タイトル 18sp / 本文 16sp / 補助 14sp / キャプション 12sp
- 太さ: 見出し・ボタン Medium(500)、本文 Regular(400)

## 余白・寸法

- 基本グリッド: 8dp。画面左右マージン: 16dp
- リスト行高: 72dp（サムネイル付き）/ 56dp（テキストのみ）
- サムネイルグリッド: 3列・間隔8dp
- タップ領域最小: 48dp

## 角丸・影

- カード・ダイアログ: 12dp / ボタン・入力欄: 8dp / FAB・チップ・バッジ: pill
- 影: 控えめ（elevation 1〜2 相当）。ヒーロー的な大きい影は使わない

## テーマ

- **ライトのみ（MVP確定）**。ダークテーマは Phase 4 で再検討
- 例外: フローティングUI（オーバーレイ）のみ常時 `--color-overlay-bg` の暗色半透明
