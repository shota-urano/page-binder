package com.pagebinder.app.spike.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class MlKitJapaneseOcrSpikeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val typeface by lazy { Typeface.createFromAsset(context.assets, FONT_ASSET) }

    @Test
    fun measuresAccuracyForEveryRequiredMaterialCategory() {
        val recognizer = TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
        val results =
            try {
                fixtures().map { fixture ->
                    val recognized = recognize(recognizer, fixture.bitmap)
                    fixture.bitmap.recycle()
                    val expected = normalize(fixture.expected)
                    val actual = normalize(recognized)
                    Measurement(
                        category = fixture.category,
                        expectedCodePoints = expected.codePointCount(0, expected.length),
                        recognizedCodePoints = actual.codePointCount(0, actual.length),
                        editDistance = levenshtein(codePoints(expected), codePoints(actual)),
                    )
                }
            } finally {
                recognizer.close()
            }

        val grouped =
            results.groupBy(Measurement::category).mapValues { (_, measurements) ->
                val expected = measurements.sumOf(Measurement::expectedCodePoints)
                val recognized = measurements.sumOf(Measurement::recognizedCodePoints)
                val distance = measurements.sumOf(Measurement::editDistance)
                CategoryMeasurement(expected, recognized, distance)
            }
        writeMetrics(grouped)

        assertTrue(
            "Every specification category must be measured",
            grouped.keys == REQUIRED_CATEGORIES,
        )
        val horizontalAccuracy = requireNotNull(grouped[CATEGORY_HORIZONTAL]).accuracy
        assertTrue(
            "Horizontal Japanese OCR accuracy was ${formatPercent(horizontalAccuracy)}, below 95.00%",
            horizontalAccuracy >= HORIZONTAL_TARGET,
        )
    }

    private fun fixtures(): List<Fixture> =
        listOf(
            horizontalFixture(),
            verticalFixture(),
            rubyFixture(),
            twoColumnFixture(),
            figureFixture(),
            themedFixture(inverted = true),
            themedFixture(inverted = false),
            smallTextFixture(),
        )

    private fun horizontalFixture(): Fixture {
        val lines =
            listOf(
                "日本語の横書き本文を認識します。",
                "画面を保存して文字を検索できます。",
                "端末内で安全に処理を完了します。",
                "英数字も確認します。PageBinder2026",
            )
        return textPage(CATEGORY_HORIZONTAL, lines, textSize = 54f)
    }

    private fun verticalFixture(): Fixture {
        val columns =
            listOf(
                "縦書きの文章を認識します",
                "日本語の文字を順に並べます",
                "端末内で処理を完了します",
            )
        val bitmap = newBitmap(Color.WHITE)
        val canvas = Canvas(bitmap)
        val paint = textPaint(52f, Color.BLACK)
        columns.forEachIndexed { columnIndex, column ->
            val x = WIDTH - 150f - columnIndex * 150f
            column.forEachIndexed { rowIndex, character ->
                canvas.drawText(character.toString(), x, 150f + rowIndex * 68f, paint)
            }
        }
        return Fixture(CATEGORY_VERTICAL, columns.joinToString(""), bitmap)
    }

    private fun rubyFixture(): Fixture {
        val main = listOf("日本語認識の精度を測定します", "書籍資料を端末内で処理します")
        val ruby = listOf("にほんごにんしき", "しょせきしりょう")
        val bitmap = newBitmap(Color.WHITE)
        val canvas = Canvas(bitmap)
        val mainPaint = textPaint(64f, Color.BLACK)
        val rubyPaint = textPaint(26f, Color.BLACK)
        main.forEachIndexed { index, line ->
            val baseline = 410f + index * 280f
            canvas.drawText(ruby[index], 120f, baseline - 82f, rubyPaint)
            canvas.drawText(line, 120f, baseline, mainPaint)
        }
        return Fixture(CATEGORY_RUBY, ruby.zip(main).joinToString("") { it.first + it.second }, bitmap)
    }

    private fun twoColumnFixture(): Fixture {
        val left = listOf("第一段の文章です。", "横書き本文を読みます。", "検索用文字を保存します。")
        val right = listOf("第二段の文章です。", "認識結果を確認します。", "安全に処理を終えます。")
        val bitmap = newBitmap(Color.WHITE)
        val canvas = Canvas(bitmap)
        val paint = textPaint(38f, Color.BLACK)
        drawLines(canvas, left, 70f, 220f, 76f, paint)
        drawLines(canvas, right, 640f, 220f, 76f, paint)
        return Fixture(CATEGORY_TWO_COLUMN, (left + right).joinToString(""), bitmap)
    }

    private fun figureFixture(): Fixture {
        val lines = listOf("図表を含む資料の評価", "月別の処理件数を示します。")
        val bitmap = newBitmap(Color.WHITE)
        val canvas = Canvas(bitmap)
        val paint = textPaint(48f, Color.BLACK)
        drawLines(canvas, lines, 100f, 170f, 82f, paint)
        val axisPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.DKGRAY
                strokeWidth = 5f
            }
        canvas.drawLine(180f, 1300f, 1050f, 1300f, axisPaint)
        canvas.drawLine(180f, 650f, 180f, 1300f, axisPaint)
        val labels = listOf("一月", "二月", "三月", "四月")
        val values = listOf(180f, 310f, 450f, 560f)
        val labelPaint = textPaint(34f, Color.BLACK)
        labels.forEachIndexed { index, label ->
            val left = 250f + index * 190f
            canvas.drawRect(left, 1300f - values[index], left + 95f, 1300f, axisPaint)
            canvas.drawText(label, left, 1360f, labelPaint)
        }
        return Fixture(CATEGORY_FIGURE, lines.joinToString("") + labels.joinToString(""), bitmap)
    }

    private fun themedFixture(inverted: Boolean): Fixture {
        val lines =
            if (inverted) {
                listOf("白黒反転の画面を認識します。", "明るい文字を正確に読み取ります。")
            } else {
                listOf("セピア背景の資料を認識します。", "落ち着いた色でも文字を読みます。")
            }
        val background = if (inverted) Color.rgb(25, 27, 30) else Color.rgb(239, 224, 187)
        val foreground = if (inverted) Color.WHITE else Color.rgb(63, 48, 32)
        return textPage(CATEGORY_THEMED, lines, 48f, background, foreground)
    }

    private fun smallTextFixture(): Fixture {
        val lines =
            listOf(
                "小さい文字の日本語資料を認識します。端末内で画像を解析します。",
                "文字検索と書き出しのために正確な結果を保存します。",
                "細かな注記や英数字PageBinder2026も対象として測定します。",
            )
        return textPage(CATEGORY_SMALL, lines, textSize = 28f, lineHeight = 52f)
    }

    private fun textPage(
        category: String,
        lines: List<String>,
        textSize: Float,
        background: Int = Color.WHITE,
        foreground: Int = Color.BLACK,
        lineHeight: Float = textSize * 1.8f,
    ): Fixture {
        val bitmap = newBitmap(background)
        drawLines(Canvas(bitmap), lines, 80f, 220f, lineHeight, textPaint(textSize, foreground))
        return Fixture(category, lines.joinToString(""), bitmap)
    }

    private fun newBitmap(background: Int): Bitmap =
        Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888).also {
            Canvas(it).drawColor(background)
        }

    private fun textPaint(
        textSize: Float,
        color: Int,
    ) = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        this.textSize = textSize
        this.color = color
        typeface = this@MlKitJapaneseOcrSpikeTest.typeface
    }

    private fun drawLines(
        canvas: Canvas,
        lines: List<String>,
        x: Float,
        firstBaseline: Float,
        lineHeight: Float,
        paint: Paint,
    ) {
        lines.forEachIndexed { index, line ->
            canvas.drawText(line, x, firstBaseline + index * lineHeight, paint)
        }
    }

    private fun recognize(
        recognizer: com.google.mlkit.vision.text.TextRecognizer,
        bitmap: Bitmap,
    ): String {
        val result = AtomicReference<String>()
        val error = AtomicReference<Exception>()
        val latch = CountDownLatch(1)
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { text -> result.set(text.text) }
            .addOnFailureListener(error::set)
            .addOnCompleteListener { latch.countDown() }
        check(latch.await(OCR_TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "OCR timed out" }
        error.get()?.let { throw AssertionError("ML Kit OCR failed", it) }
        return requireNotNull(result.get())
    }

    private fun normalize(text: String): String =
        buildString {
            text.codePoints().forEach { codePoint ->
                if (!Character.isWhitespace(codePoint)) {
                    appendCodePoint(codePoint)
                }
            }
        }

    private fun codePoints(text: String): IntArray = text.codePoints().toArray()

    private fun levenshtein(
        expected: IntArray,
        actual: IntArray,
    ): Int {
        var previous = IntArray(actual.size + 1) { it }
        expected.forEachIndexed { expectedIndex, expectedCodePoint ->
            val current = IntArray(actual.size + 1)
            current[0] = expectedIndex + 1
            actual.forEachIndexed { actualIndex, actualCodePoint ->
                current[actualIndex + 1] =
                    minOf(
                        current[actualIndex] + 1,
                        previous[actualIndex + 1] + 1,
                        previous[actualIndex] + if (expectedCodePoint == actualCodePoint) 0 else 1,
                    )
            }
            previous = current
        }
        return previous[actual.size]
    }

    private fun writeMetrics(measurements: Map<String, CategoryMeasurement>) {
        val output = requireNotNull(context.getExternalFilesDir(null)).resolve(METRICS_FILE)
        output.writeText(
            buildString {
                appendLine("device=${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                appendLine("api=${android.os.Build.VERSION.SDK_INT}")
                appendLine("mlkit_dependency=text-recognition-japanese:16.0.1")
                appendLine("normalization=remove_unicode_whitespace")
                REQUIRED_CATEGORIES.forEach { category ->
                    val measurement = requireNotNull(measurements[category])
                    appendLine(
                        listOf(
                            "category=$category",
                            "expected=${measurement.expectedCodePoints}",
                            "recognized=${measurement.recognizedCodePoints}",
                            "edit_distance=${measurement.editDistance}",
                            "accuracy=${String.format(Locale.ROOT, "%.6f", measurement.accuracy)}",
                        ).joinToString(","),
                    )
                }
            },
        )
    }

    private fun formatPercent(value: Double): String = String.format(Locale.ROOT, "%.2f%%", value * 100.0)

    private data class Fixture(
        val category: String,
        val expected: String,
        val bitmap: Bitmap,
    )

    private data class Measurement(
        val category: String,
        val expectedCodePoints: Int,
        val recognizedCodePoints: Int,
        val editDistance: Int,
    )

    private data class CategoryMeasurement(
        val expectedCodePoints: Int,
        val recognizedCodePoints: Int,
        val editDistance: Int,
    ) {
        val accuracy: Double = (1.0 - editDistance.toDouble() / expectedCodePoints).coerceAtLeast(0.0)
    }

    private companion object {
        const val WIDTH = 1200
        const val HEIGHT = 1800
        const val FONT_ASSET = "fonts/NotoSansJP-wght.ttf"
        const val METRICS_FILE = "c2s-1-mlkit-ocr-metrics.txt"
        const val OCR_TIMEOUT_SECONDS = 60L
        const val HORIZONTAL_TARGET = 0.95
        const val CATEGORY_HORIZONTAL = "horizontal"
        const val CATEGORY_VERTICAL = "vertical"
        const val CATEGORY_RUBY = "ruby"
        const val CATEGORY_TWO_COLUMN = "two_column"
        const val CATEGORY_FIGURE = "figure"
        const val CATEGORY_THEMED = "inverted_and_sepia"
        const val CATEGORY_SMALL = "small_text"
        val REQUIRED_CATEGORIES =
            linkedSetOf(
                CATEGORY_HORIZONTAL,
                CATEGORY_VERTICAL,
                CATEGORY_RUBY,
                CATEGORY_TWO_COLUMN,
                CATEGORY_FIGURE,
                CATEGORY_THEMED,
                CATEGORY_SMALL,
            )
    }
}
