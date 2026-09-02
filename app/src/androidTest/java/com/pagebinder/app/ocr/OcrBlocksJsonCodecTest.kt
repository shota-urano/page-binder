package com.pagebinder.app.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

class OcrBlocksJsonCodecTest {
    @Test
    fun schemaExample_roundTrips() {
        val decoded = OcrBlocksJsonCodec.decode(SCHEMA_EXAMPLE)

        assertEquals(
            OcrBlocksJson(
                blocks =
                    listOf(
                        OcrBlockJson(
                            index = 0,
                            text = "ブロック全文",
                            rect = OcrJsonRect(left = 0, top = 0, right = 0, bottom = 0),
                            lines =
                                listOf(
                                    OcrLineJson(
                                        index = 0,
                                        text = "行テキスト",
                                        rect = OcrJsonRect(left = 0, top = 0, right = 0, bottom = 0),
                                        elements =
                                            listOf(
                                                OcrElementJson(
                                                    index = 0,
                                                    text = "要素",
                                                    rect =
                                                        OcrJsonRect(
                                                            left = 0,
                                                            top = 0,
                                                            right = 0,
                                                            bottom = 0,
                                                        ),
                                                ),
                                            ),
                                    ),
                                ),
                        ),
                    ),
            ),
            decoded,
        )
        assertEquals(decoded, OcrBlocksJsonCodec.decode(OcrBlocksJsonCodec.encode(decoded)))
    }

    private companion object {
        val SCHEMA_EXAMPLE =
            """
            {
              "schemaVersion": 1,
              "blocks": [
                {
                  "index": 0,
                  "text": "ブロック全文",
                  "rect": { "left": 0, "top": 0, "right": 0, "bottom": 0 },
                  "lines": [
                    {
                      "index": 0,
                      "text": "行テキスト",
                      "rect": { "left": 0, "top": 0, "right": 0, "bottom": 0 },
                      "elements": [
                        { "index": 0, "text": "要素", "rect": { "left": 0, "top": 0, "right": 0, "bottom": 0 } }
                      ]
                    }
                  ]
                }
              ]
            }
            """.trimIndent()
    }
}
