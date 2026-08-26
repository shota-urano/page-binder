# SAF 経由 Google Drive 保存スパイク

- 対象 issue: `pagebinder-gph.1`
- 実施日: 2026-08-26
- 実行環境: Android Emulator `Google sdk_gphone64_arm64`、API 35（AVD `pixel-api35` / `emulator-5554`）
- Document Provider: Google Drive `com.google.android.apps.docs`（Google アカウント構成済み）
- テスト: `SafGoogleDriveSpikeTest`

## 総合判定

**採用可**。Drive 専用 API や `INTERNET` 権限を使用せず、SAF の `ACTION_CREATE_DOCUMENT` で Google Drive Provider を選択し、完成済み一時ファイルを `content://` URI へストリーム出力できた。出力後に同じ URI から全量を読み戻し、バイト数と SHA-256 の一致を確認した。

本スパイクはストレージ経路の成立性と、Provider 出力の成功・失敗を判定できる境界までを検証する。`ExportRecord` を `failed + errorCode` へ遷移させる処理と、Drive 書き込み失敗時にローカル保存への切替を案内する UI は後続の本実装スコープであり、スパイク対象外とする。

## 検証結果

| 項目 | 判定 | 手段・結果 |
|---|---|---|
| Google Drive Provider の選択 | **PASS** | Instrumentation の `UiAutomation` で SAF のルート一覧から `Drive` → `My Drive` を選択。返却 URI の authority が `com.google.android.apps.docs.storage` と一致。保存 URI 自体は記録・ログ出力していない。 |
| 完成済み一時出力からのストリーム保存 | **PASS** | アプリ cache に 262,144 bytes の一時ファイルを完成させ、長さを確認してから `ContentResolver.openOutputStream(uri, "w")` へ全量出力。正常 close まで完了した場合だけ `Completed` と判定した。実測 7 ms。 |
| 保存完了確認 | **PASS** | 同じ URI を Drive Provider から読み戻し、262,144 bytes、SHA-256 `8f2144a274ead978258dbbcc0b62c5b0ee7335eb2bf729fbb5ca4026af0f0a14` が入力と完全一致。読み戻し完了まで 1,223 ms。 |
| ピッカー取消 | **PASS** | Back 操作で `Activity.RESULT_CANCELED`（0）かつ URI なしを確認。Drive 内の階層にいる場合は、上位階層への移動後にもう一度 Back が必要だった。 |
| 未許可 Drive URI の拒否 | **PASS** | Drive authority のアクセス権限を持たない捏造 document URI への `openOutputStream` が `java.lang.SecurityException` を送出することを `assertThrows(SecurityException::class.java)` で型まで確認した。 |
| 選択済み Drive URI の途中書き込み失敗 | **PASS** | SAF が返した Drive URI の実ストリームをラップし、アプリと Provider のストリーム境界で 8,192 bytes 書いた時点に制御された I/O 障害を発生させた。`java.io.IOException` を捕捉して `Failed` と判定し、読み戻しも 8,192 bytes のみで入力全体と不一致になることを確認した。全量 write → close が正常完了しない、または全量一致しない出力は完了扱いしない。実際の Drive 通信障害の再現ではなく、選択済み Provider ストリームの途中失敗を決定的に検証する fault injection である。 |
| 失敗後の状態遷移・切替案内 | **スパイク対象外** | `ExportRecord = failed + errorCode` とローカル保存への切替案内は `ExportStorageGateway`・書き出し画面の本実装スコープ。本スパイクは、その入力となる Provider 書き込み失敗と未完了判定を機械検証するところまでを対象とする。 |
| 同名ファイル確認 | **人間確認待ち** | 本スパイクでは一意なファイル名を使用。同名時は仕様どおり OS 標準 UI に委譲するため、自動検証していない。 |
| Drive サーバーへの同期完了 | **人間確認待ち** | Provider 返却 URI からの読み戻し一致までは機械確認済み。別端末・Web から見たクラウド同期完了は確認していない。 |

## 実測値

| 項目 | 値 |
|---|---:|
| SAF 起動から URI 返却 | 2,463 ms |
| 入力ファイル | 262,144 bytes |
| ストリーム出力 | 7 ms |
| Drive Provider からの読み戻し | 1,223 ms |
| 読み戻しサイズ | 262,144 bytes |
| 入出力 SHA-256 | `8f2144a274ead978258dbbcc0b62c5b0ee7335eb2bf729fbb5ca4026af0f0a14` |
| 選択済み URI の失敗時例外 | `java.io.IOException` |
| 失敗時の読み戻しサイズ | 8,192 bytes（完了判定 false） |

値は `gph-1-saf-drive-metrics.txt` を端末のアプリ external files 領域から `adb pull` して取得した。測定ファイル自体はリポジトリに含めない。

## 判明した制約

1. 完了判定には全量 write → 出力ストリームの正常 close が必要である。途中の `IOException` は `Failed` とし、スパイクではさらに読み戻しサイズとハッシュの全量一致を確認した。不完全な 8,192 bytes は完了扱いにならなかった。
2. SAF ピッカーは前回選択した Drive 内の場所を保持する。取消操作の Back は現在位置によって「上位階層へ移動」と「ピッカー取消」の2段階になる。
3. Provider URI からの読み戻し成功は確認できたが、Drive サーバーへの同期完了時刻を SAF API から確定することはできない。

## 再現コマンド

```bash
./gradlew --console=plain :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.pagebinder.app.spike.saf.SafGoogleDriveSpikeTest
```

実測ファイルを保持する場合は debug APK と androidTest APK を手動インストール後、次を実行する。

```bash
adb shell am instrument -w \
  -e class com.pagebinder.app.spike.saf.SafGoogleDriveSpikeTest \
  com.pagebinder.app.test/androidx.test.runner.AndroidJUnitRunner
```
