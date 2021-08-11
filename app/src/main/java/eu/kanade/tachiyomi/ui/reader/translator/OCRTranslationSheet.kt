package eu.kanade.tachiyomi.ui.reader.translator

import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.PackageManager.PERMISSION_GRANTED
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
import java.util.*
import java.util.Collections.rotate
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

    @SuppressLint("SetTextI18n")
    private fun populateResults(results: List<EntryOptimized>) {
        binding.dictResults.isVisible = results.isNotEmpty()
        binding.dictNoResults.isVisible = results.isEmpty()
        for (result: EntryOptimized in results) {
            if (result.dictionary == "JMDICT") {
                val entry = DictionaryEntryBinding.inflate(layoutInflater, binding.entriesLayout, true)
                entry.dictionaryWord.text = result.kanji
                entry.dictionaryReading.text = """(${result.readings})"""
                entry.dictionaryMeaning.text = """ • ${result.meanings!!.replace("￼", "\n • ")}"""
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
                        content.joinToString("\n")
                    }
                    api.addNote(model.key, deck.key, fields.toTypedArray(), null)
                    Toast.makeText(context, "Card added successfully!", Toast.LENGTH_SHORT).show()
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
