# Release APK signing

PageBinder の配布用APKは、リポジトリ外で管理する keystore で署名する。keystore と
`keystore.properties` は Git に追加しない。

## Keystore を作成する

keystore の保存先はリポジトリ外にする。次は macOS/Linux の例で、別名とパスワードは
自分で安全に管理する。

```sh
mkdir -p ~/.pagebinder
keytool -genkeypair -v \
  -keystore ~/.pagebinder/pagebinder-release.jks \
  -alias pagebinder \
  -keyalg RSA \
  -keysize 4096 \
  -validity 10000
```

## 署名設定を渡す

リポジトリ直下に、次の形式で `keystore.properties` を作成する。`storeFile` は絶対パスを
推奨する。

```properties
storeFile=/absolute/path/to/pagebinder-release.jks
storePassword=your-keystore-password
keyAlias=pagebinder
keyPassword=your-key-password
```

または、CI や一時的な実行では次の環境変数を設定できる。各値は
`keystore.properties` に同名のキーがある場合はそちらを優先する。

```sh
export PAGEBINDER_RELEASE_STORE_FILE=/absolute/path/to/pagebinder-release.jks
export PAGEBINDER_RELEASE_STORE_PASSWORD=your-keystore-password
export PAGEBINDER_RELEASE_KEY_ALIAS=pagebinder
export PAGEBINDER_RELEASE_KEY_PASSWORD=your-key-password
```

## Release APK を作成する

```sh
make release
```

署名設定が不足している場合、release タスクは明示エラーで停止する。debug 署名への
フォールバックは行わない。出力先は
`app/build/outputs/apk/release/app-release.apk`。必要に応じて次で署名を確認できる。

```sh
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
```
