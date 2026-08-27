package com.pagebinder.app.export

import android.content.res.AssetManager
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font

/** Loads the bundled Noto Sans JP font for subset embedding in generated PDFs. */
internal class NotoSansJpPdfFont(
    private val assets: AssetManager,
) {
    fun load(document: PDDocument): PDType0Font =
        assets.open(FONT_ASSET_PATH).use { input ->
            PDType0Font.load(document, input, true)
        }

    companion object {
        const val FONT_ASSET_PATH = "fonts/NotoSansJP-wght.ttf"
        const val LICENSE_ASSET_PATH = "fonts/OFL-NotoSansJP.txt"
    }
}
