# Phase 0 オーバーレイ写り込み防止検証

- 実施日時: 2026-08-31
- 対象: `CaptureOverlayController` と `CaptureOnePage`
- 結果: **PASS**

## 検証内容

1. `CaptureOnePage` はフレーム取得より先に `hideForCapture()` を呼び出す。
2. 非表示指示後、150ms の安定待機を経て `CaptureGateway.latestFrame()` を取得する。
3. 成功、フレーム取得失敗、画像保存/DB保存失敗のいずれでも `restoreAfterCapture()` を呼び出す。
4. `CaptureOverlayControllerTest` は WindowManager に対する `setVisible(false)` → `setVisible(true)` を検証する。
5. `CaptureOnePageTest` は保存失敗時でもオーバーレイが再表示され、保存対象だけがロールバックされることを検証する。

## 実機確認

- 環境: Android Emulator `pixel-api35`（Android 15 / API 35）
- 対象アプリ: Camera（単一アプリの MediaProjection）
- 手順: `APPLICATION_OVERLAY` の表示を WindowManager で確認後、PageBinder の撮影ボタンを押して保存された WebP を目視確認した。
- 結果: オーバーレイウィンドウは `ty=APPLICATION_OVERLAY`、`isVisible=true` で表示された。保存画像には Camera の UI は含まれるが、PageBinder のフローティング撮影ボタン・状態表示・保存件数は含まれない。

よって、MediaProjection が生成する保存画像へのオーバーレイ写り込み防止の受入基準を満たす。
