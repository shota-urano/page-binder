---
status: confirmed
confirmed_rev: 6f8ceab
範囲: 権限取得（オーバーレイ/通知/MediaProjection同意）・FGS・撮影セッション状態管理・撮影準備画面・フローティングUI基盤
---

# 04. 権限・撮影セッション 仕様

**親**: [`00-overview.md`](./00-overview.md) ｜ **担当**: Backend（サービス・状態機械）+ Frontend（準備画面・オーバーレイUI） ｜ **範囲**: FR-SES-001～007、requirements §11.1、画面 §9.4・§9.5

## 1. 目的

MediaProjection による画面取得セッションの安全な開始・維持・終了と、その前提となる権限フロー・フォアグラウンドサービス・フローティングUI基盤を提供する。

## 2. 入出力

- 入力: 撮影開始要求（書籍詳細画面から。方式=手動/連続、書籍ID）、OSイベント（許可結果・投影停止・回転）
- 出力: 撮影セッション状態（Flow）、フレーム供給（`CaptureGateway` 経由で [05](./05-manual-capture.md)/[06](./06-auto-capture.md) へ）

## 3. 処理詳細

### 3.1 撮影開始順序（requirements §11.1、**厳守**）

Android 14以降の制約に従い、次の順序を厳守する（AGENTS.md ルール7）。

1. OSの画面共有許可を取得する（`MediaProjectionManager.createScreenCaptureIntent()`）
2. `mediaProjection` 種別のフォアグラウンドサービスを開始する
3. `MediaProjection` を取得する
4. `MediaProjection.Callback` を登録する
5. `VirtualDisplay` を**1回**生成する

Android 14以降は `createVirtualDisplay()` 前に Callback を登録する。**許可Intent・`MediaProjection` インスタンスを再利用しない**。セッションごとにOS標準画面で許可を取り直す（FR-SES-001）。

### 3.2 権限フロー（撮影準備画面 §9.4）

撮影開始前に撮影準備画面で以下を確認・案内する。要求前に必要理由と許可後の動作を説明する（requirements §16.4）。

| 権限 | 状態確認 | 未許可時 |
|---|---|---|
| `SYSTEM_ALERT_WINDOW`（オーバーレイ） | Settings.canDrawOverlays | 設定画面へ案内（FR-SES-006）。拒否時はアプリ画面内撮影以外は開始しない（FR-SES-007） |
| `POST_NOTIFICATIONS` | 対応OSで要求 | 通知の必要性を説明 |
| MediaProjection同意 | セッションごと | 拒否時は撮影を開始せず通常画面へ戻る（requirements §17） |

撮影準備画面の表示項目: 保存先書籍名 / 撮影方式 / 連続撮影時の最短間隔・最大ページ数・最大時間 / オーバーレイ権限状態 / 画面共有許可の説明 / 撮影開始。

### 3.3 セッション状態機械

```text
Idle → Preparing(権限取得中) → Active(手動 or 連続) → Stopping → Idle
                └ 許可拒否 → Idle
Active → (画面ロック / OS停止 / 別投影開始 / エラー) → Stopping → Idle
```

- 撮影中は常時通知を表示し（FR-SES-002）、通知から停止できる（FR-SES-003）
- 画面ロック・OSによる停止・別投影開始を `MediaProjection.Callback` 等で検知し、安全に終了する（FR-SES-004）: フレーム取得停止 → VirtualDisplay/ImageReader解放 → オーバーレイ除去 → FGS停止 → 利用者へ通知
- 端末回転・共有領域サイズ変更に追従する（FR-SES-005）: 既存セッションを安全にリサイズし、次ページから向きを反映（requirements §17）

### 3.4 フローティングUI基盤（§9.5）

- `WindowManager TYPE_APPLICATION_OVERLAY` で表示
- ドラッグ移動可能・画面端へ吸着（FR-CAP-006）
- 撮影直前から保存完了まで一時非表示（FR-CAP-002。撮影フロー側 [05](./05-manual-capture.md) が制御）
- 手動時: 撮影・停止ボタン。連続時: 状態表示・一時停止・停止・保存枚数（[06](./06-auto-capture.md)）
- 撮影中・停止中・連続撮影中を色だけでなく文字とアイコンで区別する（requirements §16.4）

### 3.5 フレーム取得

- `VirtualDisplay` + `ImageReader` で最新フレームを取得できる状態を維持する
- フレーム→Bitmap変換・回転/領域補正は `capture/` 内に閉じ、`CaptureGateway` 越しに提供する

## 4. 設定値・確定値

| 項目 | 値 | 出典 |
|---|---|---|
| FGS種別 | `mediaProjection` | requirements §11.1・§15 |
| 許可取得 | セッションごと・OS標準画面 | FR-SES-001 |
| 許可Intent/インスタンス再利用 | 禁止 | requirements §11.1 |
| オーバーレイ拒否時 | アプリ画面内撮影以外は開始しない | FR-SES-007 |
| `INTERNET` / AccessibilityService | 使用禁止 | requirements §15 |

## 5. インターフェース

- `CaptureGateway`（[01-architecture](./01-architecture.md) §3.3）: セッション開始/停止・状態Flow・フレーム取得
- Capture Session Coordinator（domain）: 状態機械の正本。FGS・オーバーレイ・撮影フローを調停
- Capture Foreground Service: Android コンポーネント。Coordinator へ委譲しロジックを持たない

## 6. エラー処理

| 状況 | 動作（requirements §17） |
|---|---|
| 画面共有を拒否 | 撮影を開始せず、通常画面へ戻る |
| オーバーレイ拒否 | 必要性を説明し、設定または中止を選択させる |
| 投影がOSに停止された | 撮影を停止し、リソースを解放して通知する |
| Android 14以降の単一アプリ共有で、共有対象が不可視になった | 撮影を停止してリソースを解放し、「画面全体（Entire screen）を選び直して撮影を開始してください」と通知する。`onCapturedContentVisibilityChanged(false)` で検出する |
| 端末回転 | 既存セッションを安全にリサイズし、次ページから向きを反映する |
| 保護画面（FLAG_SECURE等）で黒画面 | 明示エラーとし、回避案内をしない（AGENTS.md ルール2、[07](./07-image-quality.md) が検出） |

## 7. スコープ外

- 自動ページ送り（`dispatchGesture()` 等。将来検討でも禁止、requirements §8.4）
- FLAG_SECURE・DRM回避（実装禁止）
- 撮影フレームの保存判断（手動: [05](./05-manual-capture.md)、連続: [06](./06-auto-capture.md)）

## 8. 関連仕様

- 全体: [`00-overview.md`](./00-overview.md) ｜ 構成: [`01-architecture.md`](./01-architecture.md)
- 前工程: [`03-book-project.md`](./03-book-project.md)（書籍詳細から開始）
- 次工程: [`05-manual-capture.md`](./05-manual-capture.md)、[`06-auto-capture.md`](./06-auto-capture.md)

## 9. 実装単位

<!-- spec-to-beads がこの節を機械的に読んで bd の子タスクを作る -->
- [ ] [Backend] Phase 0 スパイク: MediaProjection から静止画を安定取得できることを検証する（§3.1 の開始順序、Android 14 制約下）
  - 受け入れ基準: make verify が PASS; 実機またはエミュレータで §3.1 の順序どおり開始し1フレームをBitmap保存できるスパイクコードと検証記録が残る; 失敗時は報告して停止する
- [ ] [Backend] Capture Foreground Service と通知（常時表示・停止アクション）を実装する
  - 受け入れ基準: make verify が PASS; Manifest に FOREGROUND_SERVICE / FOREGROUND_SERVICE_MEDIA_PROJECTION が宣言され mediaProjection 種別でFGSが起動する; 通知の停止アクションでセッションが終了するテストが通過する
- [ ] [Backend] Capture Session Coordinator（状態機械）と CaptureGateway 実装（開始順序・Callback・安全終了・回転リサイズ）を実装する
  - 受け入れ基準: make verify が PASS; 状態遷移（開始/拒否/OS停止/回転/停止）の単体テストが通過する; 許可Intent・MediaProjectionインスタンスを再利用しないことをコードレビューで確認できる構造になっている
- [ ] [Frontend] 撮影準備画面（権限状態表示・説明・撮影方式/連続設定・開始）を実装する
  - 受け入れ基準: make verify が PASS; オーバーレイ未許可時に撮影開始が無効化されるViewModel単体テストが通過する
- [ ] [Frontend] フローティングUI基盤（オーバーレイView・ドラッグ移動・端吸着・状態表示・一時非表示API）を実装する
  - 受け入れ基準: make verify が PASS; 非表示→再表示のAPIが撮影フローから呼べる; 状態（撮影中/停止中/連続中）が文字とアイコンで区別される
