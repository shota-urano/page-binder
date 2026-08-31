# Phase 0 MediaProjection 1フレーム検証

- 実施日時: 2026-08-31 14:24 JST
- 対象: `emulator-5554`
- 端末: `sdk_gphone64_arm64`
- Android API: 35
- APK: debug
- 結果: **PASS（Bitmap保存成功）**

## 実施手順

1. `MediaProjectionManager.createScreenCaptureIntent()` で新しい許可 Intent を生成した。
2. OS 標準の同意画面で「A single app」を選び、「Files」を共有対象にして許可した。
3. 許可結果を1回だけ debug 用 Foreground Service へ渡した。
4. Service を `mediaProjection` 種別で foreground 化した。
5. `MediaProjection` を取得した。
6. `MediaProjection.Callback` を登録した。
7. `VirtualDisplay` を1回生成した。
8. 最初の有効フレームをPNG形式のBitmapとして保存した。

実行コマンド:

```text
./gradlew --console=plain :app:assembleDebug
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 shell am start \
  -n com.pagebinder.app/.spike.capture.MediaProjectionSpikeActivity
```

## 端末上の検証記録

```text
PageBinder Phase 0 MediaProjection verification
timestamp=2026-08-31T05:24:48.702391Z
deviceSdk=35
CONSENT_GRANTED
FOREGROUND_SERVICE_STARTED
MEDIA_PROJECTION_ACQUIRED
MEDIA_PROJECTION_CALLBACK_REGISTERED
VIRTUAL_DISPLAY_CREATED
FRAME_BITMAP_SAVED=1080x2400
RESULT=PASS
MEDIA_PROJECTION_CALLBACK_STOPPED
```

端末のアプリ専用領域に次の2ファイルが生成された。

- `files/phase0/media-projection-spike.txt`: 304 bytes
- `files/phase0/media-projection-spike.png`: 59,816 bytes、1080×2400
- Bitmap SHA-256: `60890a9124ee57e3d28e4ddb7c50583d8d5c50aeb145dc751325269d26b54315`

## 判定

改訂後の `docs/specs/04-capture-session.md` §3.1
（許可取得 → FGS開始 → MediaProjection取得 → Callback登録 → VirtualDisplay生成）の順序で、
Android API 35上の1フレームBitmap保存に成功した。許可IntentとMediaProjectionインスタンスは
この1回の検証でのみ使用し、停止後に再利用していない。
