package eu.kanade.tachiyomi.ui.reader.translator

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Environment.getExternalStorageDirectory
import android.os.Handler
import android.os.Looper
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.room.Room
import ca.fuwafuwa.kaku.DB_JMDICT_NAME
import ca.fuwafuwa.kaku.DB_KANJIDICT_NAME
import ca.fuwafuwa.kaku.Deinflictor.DeinflectionInfo
import ca.fuwafuwa.kaku.Deinflictor.Deinflector
import com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.ichi2.anki.api.AddContentApi
import com.ichi2.anki.api.AddContentApi.READ_WRITE_PERMISSION
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.databinding.DictionaryEntryBinding
import eu.kanade.tachiyomi.databinding.OcrResultCharacterBinding
import eu.kanade.tachiyomi.databinding.OcrTranslationSheetBinding
import eu.kanade.tachiyomi.util.lang.launchUI
import timber.log.Timber
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URL
import java.net.URLConnection
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.*
import java.util.Collections.rotate
import java.util.concurrent.Executors
import kotlin.collections.HashSet

class OCRTranslationSheet(activity: Activity, private val ocrResult: List<List<String>> = listOf()) : BottomSheetDialog(activity) {
    private val binding = OcrTranslationSheetBinding.inflate(layoutInflater, null, false)
    private val db: JmdictDatabase
    private val mDeinflector: Deinflector = Deinflector(context)
    private val ocrResultText: String
        get() = ocrResult.joinToString("") { it.first() }

    init {
        setContentView(binding.root)
        setOwnerActivity(activity)
        db = Room.databaseBuilder(context, JmdictDatabase::class.java, "JMDict.db").createFromAsset("DB_KakuDict-02-16-2019.db").build()
        binding.lookupText.setHorizontallyScrolling(false)
        binding.lookupText.ellipsize = null
        binding.lookupButton.setOnClickListener { launchUI { searchText(binding.lookupText.text.toString()) } }
        for (i in ocrResult.indices) {
            val butt = OcrResultCharacterBinding.inflate(layoutInflater, binding.ocrCharacters, true)
            butt.character.text = ocrResult[i].first()
            butt.character.setOnClickListener { launchUI { searchText(ocrResultText, i) } }
            butt.character.setOnLongClickListener { launchUI { replaceWithNext(it as TextView, i) }; true }
        }
        val scale = context.resources.displayMetrics.density
        val pixels = (76 * scale + 0.5f)
        behavior.peekHeight = pixels.toInt()
    }

    private fun replaceWithNext(symbol: TextView, i: Int) {
        rotate(ocrResult[i], -1)
        symbol.text = ocrResult[i].first()
    }

    private suspend fun searchText(text: String, index: Int = 0) {
        behavior.state = STATE_EXPANDED
        val imm: InputMethodManager = context.getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.root.windowToken, 0)
        val result = db.entryOptimizedDao().findByName(text[index].toString() + "%")
        binding.entriesLayout.removeAllViews()
        populateResults(rankResults(getMatchedEntries(text, index, result)))
    }

    private fun pickReading(readings: String): String {
        return if (readings.contains(",")) {
            readings.substring(0, readings.indexOf(","))
        } else {
            readings
        }
    }

    private fun downloadAudio(reading: String, kanji: String, file: File) {
        try {
            val url = "https://assets.languagepod101.com/dictionary/japanese/audiomp3.php?kanji=%s&kana=%s".format(URLEncoder.encode(kanji, "utf-8"), URLEncoder.encode(reading, "utf-8"))
            val cn: URLConnection = URL(url).openConnection()
            cn.connect()
            val stream: InputStream = cn.getInputStream()
            BufferedInputStream(stream).use { `in` ->
                FileOutputStream(file).use { fileOutputStream ->
                    val dataBuffer = ByteArray(1024)
                    var bytesRead: Int
                    while (`in`.read(dataBuffer, 0, 1024).also { bytesRead = it } != -1) {
                        fileOutputStream.write(dataBuffer, 0, bytesRead)
                    }
                }
            }
        } catch (e: IOException) {
            Timber.e(e.toString())
        }
    }

    private fun playAudio(readings: String, kanji: String) {
        val reading = pickReading(readings)
        val audioFile = File(context.cacheDir, "file.mp3")
        downloadAudio(reading, kanji, audioFile)
        if (audioFile.length() != 52288L) { // The audio file with this length is a spoken 404 not found message
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                setDataSource(context, Uri.parse(context.cacheDir.toString() + "/file.mp3"))
                prepare()
                start()
            }
        }
    }

    private fun createToast(context: Context?, text: String?) {
        val handler = Handler(Looper.getMainLooper())
        handler.post { Toast.makeText(context, text, Toast.LENGTH_LONG).show() }
    }

    @SuppressLint("SetTextI18n", "DirectDateInstantiation")
    private fun populateResults(results: List<EntryOptimized>) {
        binding.dictResults.isVisible = results.isNotEmpty()
        binding.dictNoResults.isVisible = results.isEmpty()
        for (result: EntryOptimized in results) {
            if (result.dictionary == "JMDICT") {
                val entry = DictionaryEntryBinding.inflate(layoutInflater, binding.entriesLayout, true)
                entry.dictionaryWord.text = result.kanji
                entry.dictionaryReading.text = """(${result.readings})"""
                entry.dictionaryMeaning.text = """ • ${result.meanings!!.replace("￼", "\n • ")}"""
                entry.playAudio.setOnClickListener {
                    Executors.newSingleThreadExecutor().submit { playAudio(result.readings.toString(), result.kanji.toString()) }
                }
                entry.addToAnki.setOnClickListener {
                    if (context.checkSelfPermission(READ_WRITE_PERMISSION) != PERMISSION_GRANTED) {
                        return@setOnClickListener Toast.makeText(context, "You must setup anki integration in the settings first", Toast.LENGTH_SHORT).show()
                    }
                    AddContentApi.getAnkiDroidPackageName(context)
                        ?: return@setOnClickListener Toast.makeText(context, "Couldn't find ankiDroid", Toast.LENGTH_SHORT).show()
                    val pref = PreferencesHelper(context)
                    val api = AddContentApi(context)
                    val deckName = pref.ankiDeckName().get()
                    val modelName = pref.ankiModelName().get()
                    val deck = api.deckList.entries.firstOrNull { it.value == deckName }
                        ?: return@setOnClickListener Toast.makeText(context, "Deck '$deckName' was not found", Toast.LENGTH_SHORT).show()
                    val model = api.modelList.entries.firstOrNull { it.value == modelName }
                        ?: return@setOnClickListener Toast.makeText(context, "Note type '$modelName' was not found", Toast.LENGTH_SHORT).show()

                    val sentenceFields = pref.ankiSentenceExportFields()
                    val wordFields = pref.ankiWordExportFields()
                    val readingFields = pref.ankiReadingExportFields()
                    val meaningFields = pref.ankiMeaningExportFields()
                    val audioFields = pref.ankiAudioExportFields()
                    Executors.newSingleThreadExecutor().submit {
                        val fields = api.getFieldList(model.key).map {
                            var content = arrayOf<String>()
                            if (sentenceFields.contains(it)) {
                                content += ocrResultText
                            }
                            if (wordFields.contains(it)) {
                                content += result.kanji ?: ""
                            }
                            if (readingFields.contains(it)) {
                                content += result.readings ?: ""
                            }
                            if (meaningFields.contains(it)) {
                                content += entry.dictionaryMeaning.text.toString()
                            }
                            if (audioFields.contains(it)) {
                                val filename: String = "A_" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.getDefault()).format(Date()) + ".mp3"
                                val collectionPath = File(getExternalStorageDirectory().absolutePath.toString() + "/AnkiDroid/collection.media/")
                                if (!collectionPath.exists()) {
                                    collectionPath.mkdirs()
                                }
                                val audioFile = File(collectionPath, filename)
                                downloadAudio(pickReading(result.readings.toString()), result.kanji.toString(), audioFile)
                                if (audioFile.length() != 52288L) { // The audio file with this length is a spoken 404 not found message
                                    content += "[sound:$filename]"
                                } else {
                                    audioFile.delete()
                                }
                            }
                            content.joinToString("\n")
                        }
                        api.addNote(model.key, deck.key, fields.toTypedArray(), null)
                        createToast(context, "Card added successfully!")
                    }
                }
            }
        }
    }

    private fun getMatchedEntries(text: String, textOffset: Int, entries: List<EntryOptimized>): List<EntryOptimized> {
        val end = if (textOffset + 80 >= text.length) text.length else textOffset + 80
        var word = text.substring(textOffset, end)
        val seenEntries = HashSet<EntryOptimized>()
        val results = ArrayList<EntryOptimized>()

        while (word.isNotEmpty()) {
            // Find deinflections and add them
            val deinfResultsList: List<DeinflectionInfo> = mDeinflector.getPotentialDeinflections(word)
            var count = 0
            for (deinfInfo in deinfResultsList) {
                val filteredEntry: List<EntryOptimized> = entries.filter { entry -> entry.kanji == deinfInfo.word }

                if (filteredEntry.isEmpty()) {
                    continue
                }

                for (entry in filteredEntry) {
                    if (seenEntries.contains(entry)) {
                        continue
                    }

                    var valid = true

                    if (count > 0) {
                        valid = (deinfInfo.type and 1 != 0) && (entry.pos?.contains("v1") == true) ||
                            (deinfInfo.type and 2 != 0) && (entry.pos?.contains("v5") == true) ||
                            (deinfInfo.type and 4 != 0) && (entry.pos?.contains("adj-i") == true) ||
                            (deinfInfo.type and 8 != 0) && (entry.pos?.contains("vk") == true) ||
                            (deinfInfo.type and 16 != 0) && (entry.pos?.contains("vs-") == true)
                    }

                    if (valid) {
                        results.add(entry)
                        seenEntries.add(entry)
                    }

                    count++
                }
            }

            // Add all exact matches as well
            val filteredEntry: List<EntryOptimized> = entries.filter { entry -> entry.kanji == word }
            for (entry in filteredEntry) {
                if (seenEntries.contains(entry)) {
                    continue
                }

                results.add(entry)
                seenEntries.add(entry)
            }

            word = word.substring(0, word.length - 1)
        }

        return results
    }

    private fun rankResults(results: List<EntryOptimized>): List<EntryOptimized> {
        return results.sortedWith(
            compareBy(
                { getDictPriority(it) },
                { 0 - it.kanji!!.length },
                { getEntryPriority(it) },
                { getPriority(it) }
            )
        )
    }

    private fun getDictPriority(result: EntryOptimized): Int {
        return when {
            result.dictionary == DB_JMDICT_NAME -> Int.MAX_VALUE - 2
            result.dictionary == DB_KANJIDICT_NAME -> Int.MAX_VALUE - 1
            else -> Int.MAX_VALUE
        }
    }

    private fun getEntryPriority(result: EntryOptimized): Int {
        return if (result.primaryEntry == true) 0 else 1
    }

    private fun getPriority(result: EntryOptimized): Int {
        val priorities = result.priorities!!.split(",")
        var lowestPriority = Int.MAX_VALUE

        for (priority in priorities) {
            var pri = Int.MAX_VALUE

            if (priority.contains("nf")) { // looks like the range is nf01-nf48
                pri = priority.substring(2).toInt()
            } else if (priority == "news1") {
                pri = 60
            } else if (priority == "news2") {
                pri = 70
            } else if (priority == "ichi1") {
                pri = 80
            } else if (priority == "ichi2") {
                pri = 90
            } else if (priority == "spec1") {
                pri = 100
            } else if (priority == "spec2") {
                pri = 110
            } else if (priority == "gai1") {
                pri = 120
            } else if (priority == "gai2") {
                pri = 130
            }

            lowestPriority = if (pri < lowestPriority) pri else lowestPriority
        }

        return lowestPriority
    }
}
