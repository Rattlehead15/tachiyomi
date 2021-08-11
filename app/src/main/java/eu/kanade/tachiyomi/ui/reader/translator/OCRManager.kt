package eu.kanade.tachiyomi.ui.reader.translator

import android.content.Context
import android.graphics.Bitmap
import com.googlecode.tesseract.android.TessBaseAPI
import com.googlecode.tesseract.android.TessBaseAPI.PageIteratorLevel.RIL_SYMBOL
import com.googlecode.tesseract.android.TessBaseAPI.VAR_SAVE_BLOB_CHOICES
import com.googlecode.tesseract.android.TessBaseAPI.VAR_TRUE
import java.io.*

class OCRManager(context: Context) {
    private val api: TessBaseAPI

    init {
        val dir = context.getExternalFilesDir(null)
        val tessdata = File(dir!!.path + "/tessdata")
        if (!tessdata.exists()) {
            tessdata.mkdirs()
            copyAssetFolderToFolder(context, "tessdata", tessdata)
        }
        api = TessBaseAPI()
        api.init(dir.path + "/", "jpn", TessBaseAPI.OEM_LSTM_ONLY)
        api.setDebug(true)
        api.setVariable(VAR_SAVE_BLOB_CHOICES, VAR_TRUE)
        api.setVariable("lstm_choice_mode", "2")
    }

    private fun copyAssetFolderToFolder(activity: Context, assetsFolder: String, destinationFolder: File?) {
        var stream: InputStream?
        var output: OutputStream?
        try {
            for (fileName in activity.assets.list(assetsFolder)!!) {
                stream = activity.assets.open("$assetsFolder/$fileName")
                output = BufferedOutputStream(FileOutputStream(File(destinationFolder, fileName)))
                val data = ByteArray(1024)
                var count: Int
                while (stream.read(data).also { count = it } != -1) {
                    output.write(data, 0, count)
                }
                output.flush()
                output.close()
                stream.close()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun recognize(b: Bitmap): List<List<String>> {
        api.pageSegMode = if (b.width > b.height) TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK else TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK_VERT_TEXT
        api.setImage(b)
        api.getHOCRText(0)
        val result = mutableListOf<List<String>>()
        val iterator = api.resultIterator
        do
            result.add(iterator.symbolChoicesAndConfidence.map { it.first })
        while (iterator.next(RIL_SYMBOL))
        return result
    }
}
