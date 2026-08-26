# PDFBox-Android 日本語不可視テキスト層スパイク

- 対象 issue: `pagebinder-0yv.1`
- 実施日: 2026-08-26
- 実行環境: Android Emulator `Google sdk_gphone64_arm64`、API 35
- PDF エンジン: PDFBox-Android 2.0.27.0（Apache 2.0）
- テスト: `PdfBoxAndroidSpikeTest`

## 総合判定

**機械検証範囲では条件付き採用可**。PDFBox-Android を第一候補として維持し、現時点ではエンジン差し替えを推奨しない。

ただし、Android 標準 PDF ビューア、Google Drive、Adobe Acrobat Reader の実検索は未確認であり、人間確認が完了するまで最終採用とはしない。加えて PDFBox が自動生成する `ToUnicode` CMap では、一部の統合漢字が CJK 互換漢字へ変換されたため、OCR 入力コードポイントから明示的な `ToUnicode` CMap を生成する補正が必須である。

## §3.4 の検証結果

| 項目 | 判定 | 手段・結果 |
|---|---|---|
| 1. 日本語横書き1ページ生成 | **PASS** | 画像層（1080×1920 JPEG）と不可視日本語テキスト層を持つ 432×768 pt の1ページ PDF を instrumented test で生成。PDFBox 再読込で1ページ、画像 XObject、サブセットフォント名をアサート。ファイル容量 147,393 bytes。 |
| 2. 3ビューア検索 | **人間確認待ち**（機械代替は **PASS**） | Apple PDFKit の `findString("検索")` が 1 hit。Poppler `pdftotext` と Apple PDFKit の双方が全文を抽出でき、外部実装から検索可能なテキスト層であることを確認。Android 標準 PDF ビューア / Google Drive / Adobe Acrobat Reader はエミュレータに未導入のため、各アプリでの実検索は人間確認が必要。 |
| 3. コピー文字列と OCR 結果の一致 | **PASS** | PDFBox `PDFTextStripper`、Poppler `pdftotext`、Apple PDFKit の3実装で、合成 OCR 文字列 `日本語の横書き検索テスト。完全オフラインで文字列を確認します。` と完全一致。 |
| 4. 100ページ性能 | **PASS** | 100個の異なる高密度合成画像とページ別OCR文字列（120文字、全体253 unique code points）を持つ PDF を3回生成。生成時間 1,246〜4,343 ms、worst peak PSS 206,693 KB、worst peak PSS delta 152,041 KB（約148.5 MiB）、worst peak Java heap 57,314,672 bytes（約54.7 MiB）、ファイル容量 12,240,148 bytes（約11.67 MiB）。Poppler の全ページ抽出ハッシュが埋め込み元と一致。 |

## 生成・計測条件

- 100ページの各画像: 1080×1920、`RGB_565`。42行の分割された疑似文字列と、4ページごとの高密度カラー領域を決定的乱数で全ページ異なる内容にし、JPEG quality 0.82 / metadata 150 dpi で別々に埋め込んだ。PDF配置後の実効解像度は180 ppi。
- 不可視テキスト: 各ページ120文字のページ別日本語文字列。全100ページで253 unique code points を使用し、`ToUnicode` の `beginbfchar` は PDF 仕様に合わせ最大100件ずつ（100 / 100 / 53）に分割した。
- PDF ページ: 432×768 pt（9:16）。画像を全面描画し、9 pt の不可視テキストをページ内に配置した。
- 時間: `SystemClock.elapsedRealtime()`。ページ生成、PDFBox のサブセット保存、明示 `ToUnicode` CMap の適用と再保存を含む。
- メモリ: 全生成区間を専用 sampler thread で10 ms周期に計測。`Debug.MemoryInfo.totalPss` と `totalMemory - freeMemory` を、Bitmap/JPEG生成中および2回の保存中も含めて採取した。
- 生成物はアプリの external files 領域から `adb pull` し、ホストの `/private/tmp/pagebinder-pdfbox-spike/` で検証した。生成物自体はリポジトリに含めない。

## 外部実装・構造検証

- Poppler `pdftotext`: 1ページ全文一致。100ページ全文を空白正規化した SHA-256 が埋め込み元の `55e6348efb0662a91018ac05f4560af3cc3eade12227c5cc973088054820c0b0` と一致。
- Apple PDFKit: 全文一致、`検索` の検索結果 1 hit。
- Poppler `pdffonts`: `XXXXXX+NotoSansJP-Thin`、CID TrueType、Identity-H、`emb=yes`、`sub=yes`、`uni=yes`。
- Poppler `pdfimages -list`: 1ページに 1080×1920、RGB、JPEG、実効180 ppi の画像を1件確認。
- `qpdf --check`: 構文・ストリームエラーなし。
- 生成 PDF: PDF 1.4。1ページ 147,393 bytes、100ページ 12,240,148 bytes。

### 100ページ計測（独立プロセス3回）

| run | elapsed ms | baseline PSS KB | peak PSS KB | delta PSS KB | peak Java heap bytes |
|---:|---:|---:|---:|---:|---:|
| 1 | 3,947 | 54,652 | 206,693 | 152,041 | 57,314,672 |
| 2 | 4,343 | 54,583 | 181,464 | 126,881 | 56,823,152 |
| 3 | 1,246 | 54,741 | 186,019 | 131,278 | 56,909,168 |

## フォントとライセンス

- 同梱物: `app/src/main/assets/fonts/NotoSansJP-wght.ttf`
- 名称・版: Noto Sans JP 2.004-H2（variable TTF、weight 100–900）
- 配布元: Google Fonts `ofl/notosansjp/NotoSansJP[wght].ttf`
- 固定コミット: `google/fonts@295d98a7a0c17c68f1341eaeea354e7960ea70d3`
- upstream: `notofonts/noto-cjk@523d033d6cb47f4a80c58a35753646f5c3608a78`
- フォント SHA-256: `c2f3b4d463500a2ddcd3849cded1fceeb9fd6d1c32e6cbecd568453ba50fc68f`
- ライセンス: SIL Open Font License 1.1。同梱ファイル `app/src/main/assets/fonts/OFL-NotoSansJP.txt`、SHA-256 `1c05c68c34f9708415aada51f17e1b0092d2cea709bf4a94cd38114f9e73d7d9`

## 判明した制約

1. PDFBox-Android 2.0.27.0 の自動 `ToUnicode` 生成では、Noto Sans JP variable TTF の `日`（U+65E5）と `文`（U+6587）が、それぞれ `⽇`（U+2F47）と `⽂`（U+2F42）として抽出された。PDFBox の埋め込みコードと OCR 入力コードポイントから明示 CMap を生成し、保存済みサブセットフォントの `ToUnicode` を差し替えることで、PDFBox / Poppler / PDFKit の完全一致を得た。
2. Noto Sans JP 2.004 の静的 OTF は CFF アウトラインで、`PDType0Font.load` が TrueType の `loca` table を要求するため PDFBox-Android 2.0.27.0 では読み込めなかった。採用資産は variable TTF とする。
3. 不可視テキストがページ外へはみ出すと、PDFBox は抽出しても Poppler がページ外部分を除外する。実装時は OCR 座標変換後のテキスト領域を画像と同じページ境界内へ収める必要がある。
4. 同じ埋め込み glyph code に異なる OCR コードポイントが割り当たる場合、単一の `ToUnicode` 対応では完全復元できない。スパイクは衝突を検出して失敗させる。実装時も衝突検出を維持し、発生時はエンジン差し替え判断へ戻す。

## 再現コマンド

```bash
./gradlew --console=plain :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.pagebinder.app.spike.pdfbox.PdfBoxAndroidSpikeTest
```

外部検証には `pdftotext`、`pdffonts`、`pdfimages`、`qpdf --check`、macOS PDFKit を使用した。
