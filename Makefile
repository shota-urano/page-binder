# harness-kit: templates/android@94dced4 (deployed 2026-08-25)
# ==== harness-kit: verify契約（Android） ====
# エージェントの検証入口は verify / verify-fast の2つだけ。
# 最終行に「VERIFY: PASS」が出ない限り、完了と見なさない（Default-FAIL）。
#
# bootstrap が確定する項目: MODULE（settings.gradle で実在確認）/
# FAST_CHECKS・CHECKS（ktlint/detekt・androidTest ソースの有無で調整）

GRADLE := ./gradlew --console=plain
MODULE := app

# 軽い検査（Stop hook 用）。ktlint/detekt どちらも未導入なら bootstrap が hooks 登録ごと見送る
FAST_CHECKS := lint
# フル検証。e2e（connectedAndroidTest）はエミュレータ起動が前提
CHECKS := $(FAST_CHECKS) test android-lint manifest-check build e2e

.PHONY: verify verify-fast lint test android-lint manifest-check build e2e release run

verify: $(CHECKS)
	@test -n "$(strip $(CHECKS))" || { echo "VERIFY: FAIL (検証項目がゼロ)"; exit 1; }
	@echo "VERIFY: PASS"

verify-fast: $(FAST_CHECKS)
	@test -n "$(strip $(FAST_CHECKS))" || { echo "VERIFY-FAST: FAIL (検証項目がゼロ)"; exit 1; }
	@echo "VERIFY-FAST: PASS"

# コードスタイル（ktlint / detekt — bootstrap が導入済みの方へ確定する。速いのでStop hook向き）
lint:
	$(GRADLE) ktlintCheck

# unit テスト（JVM 上で走る。エミュレータ不要）
test:
	$(GRADLE) :$(MODULE):testDebugUnitTest

# Android Lint（静的解析。lintOptions.abortOnError=false になっていたら bootstrap が是正を提案 —
# false のままだと検出しても exit 0 で素通りする false-pass）
android-lint:
	$(GRADLE) :$(MODULE):lintDebug

# 依存ライブラリを含むマージ後 Manifest の禁止宣言を検査
manifest-check:
	$(GRADLE) :$(MODULE):verifyDebugMergedManifest

build:
	$(GRADLE) :$(MODULE):assembleDebug

release:
	$(GRADLE) :$(MODULE):assembleRelease

# E2E（instrumented test）。エミュレータ必須 — 事前に `adb devices` で1台以上を確認
e2e:
	$(GRADLE) :$(MODULE):connectedDebugAndroidTest
# ==== 人間が「動いているところ」を見るための入口 ====
# 起動コマンドの正本。AGENTS.md には「起動は make run」とだけ書けばよくなり、
# エージェントに毎回起動方法を説明するラリーが消える（2026-07-27 追加）。
# bootstrap が LAUNCH_ACTIVITY を AndroidManifest から確定する。エミュレータは事前起動が必要。
LAUNCH_ACTIVITY := .MainActivity
# applicationId は AGP が APK と一緒に出力する output-metadata.json から解決する。
# （AGP 8 の `:app:properties` は namespace / applicationId を出力しないため、
#   そこからの awk 抽出は必ず空文字になり `/.MainActivity` という壊れた Intent を投げていた）
# ここは「いま installDebug で端末へ入った APK 自身のメタデータ」なので、
# 起動対象と実際にインストールされたパッケージが必ず一致する。
APK_METADATA := $(MODULE)/build/outputs/apk/debug/output-metadata.json
run:
	$(GRADLE) :$(MODULE):installDebug
	@app_id=$$(sed -n 's/.*"applicationId"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$(APK_METADATA)" 2>/dev/null | head -1); \
	if [ -z "$$app_id" ]; then \
		echo "RUN: FAIL (applicationId を $(APK_METADATA) から解決できませんでした)"; \
		exit 1; \
	fi; \
	echo "RUN: starting $$app_id/$(LAUNCH_ACTIVITY)"; \
	start_out=$$(adb shell am start -n "$$app_id/$(LAUNCH_ACTIVITY)" 2>&1); \
	echo "$$start_out"; \
	case "$$start_out" in \
		*Error*|*error*) echo "RUN: FAIL (起動に失敗しました)"; exit 1 ;; \
	esac; \
	echo "RUN: OK"

# artifacts（動画回収）は Android 未実装。`adb shell screenrecord` を
# connectedAndroidTest と並走させる形が必要で未較正（Phase 3）。
# 未定義のままにしておくと dev-loop の integrate-close が自動でスキップする。
