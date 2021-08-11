package eu.kanade.tachiyomi.ui.setting

import android.content.pm.PackageManager.PERMISSION_GRANTED
import androidx.preference.PreferenceScreen
import com.ichi2.anki.api.AddContentApi
import com.ichi2.anki.api.AddContentApi.READ_WRITE_PERMISSION
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.preference.asImmediateFlow
import eu.kanade.tachiyomi.util.preference.*
import kotlinx.coroutines.flow.launchIn
import eu.kanade.tachiyomi.data.preference.PreferenceKeys as Keys

class SettingsOCRController : SettingsController() {

    override fun setupPreferenceScreen(screen: PreferenceScreen) = screen.apply {
        titleRes = R.string.pref_category_ocr

        preferenceCategory {
            titleRes = R.string.anki
            if (context.checkSelfPermission(READ_WRITE_PERMISSION) == PERMISSION_GRANTED) {
                val api = AddContentApi(context)

                listPreference {
                    key = Keys.ankiDeckName
                    titleRes = R.string.pref_anki_deck_name
                    entries = api.deckList.values.toTypedArray()
                    entryValues = api.deckList.values.toTypedArray()
                    summary = "%s"
                }

                listPreference {
                    key = Keys.ankiModelName
                    titleRes = R.string.pref_anki_model_name
                    entries = api.modelList.values.toTypedArray()
                    entryValues = api.modelList.values.toTypedArray()
                    summary = "%s"
                }

                preferenceCategory {
                    titleRes = R.string.anki_fields_routing

                    multiSelectListPreference {
                        key = Keys.ankiSentenceExportFields
                        titleRes = R.string.anki_field_sentence
                        entries = arrayOf()
                        entryValues = arrayOf()
                        setSummaryProvider { values.joinToString() }
                        preferences.ankiModelName().asImmediateFlow { modelName ->
                            if (modelName.isNotEmpty()) {
                                val modelId = api.modelList.toList().first { it.second == modelName }.first
                                val fields = api.getFieldList(modelId)
                                entries = fields
                                entryValues = fields
                            }
                        }.launchIn(viewScope)
                    }

                    multiSelectListPreference {
                        key = Keys.ankiWordExportFields
                        titleRes = R.string.anki_field_word
                        entries = arrayOf()
                        entryValues = arrayOf()
                        setSummaryProvider { values.joinToString() }
                        preferences.ankiModelName().asImmediateFlow { modelName ->
                            if (modelName.isNotEmpty()) {
                                val modelId = api.modelList.toList().first { it.second == modelName }.first
                                val fields = api.getFieldList(modelId)
                                entries = fields
                                entryValues = fields
                            }
                        }.launchIn(viewScope)
                    }

                    multiSelectListPreference {
                        key = Keys.ankiReadingExportFields
                        titleRes = R.string.anki_field_reading
                        entries = arrayOf()
                        entryValues = arrayOf()
                        setSummaryProvider { values.joinToString() }
                        preferences.ankiModelName().asImmediateFlow { modelName ->
                            if (modelName.isNotEmpty()) {
                                val modelId = api.modelList.toList().first { it.second == modelName }.first
                                val fields = api.getFieldList(modelId)
                                entries = fields
                                entryValues = fields
                            }
                        }.launchIn(viewScope)
                    }

                    multiSelectListPreference {
                        key = Keys.ankiMeaningExportFields
                        titleRes = R.string.anki_field_meaning
                        entries = arrayOf()
                        entryValues = arrayOf()
                        setSummaryProvider { values.joinToString() }
                        preferences.ankiModelName().asImmediateFlow { modelName ->
                            if (modelName.isNotEmpty()) {
                                val modelId = api.modelList.toList().first { it.second == modelName }.first
                                val fields = api.getFieldList(modelId)
                                entries = fields
                                entryValues = fields
                            }
                        }.launchIn(viewScope)
                    }
                }
            }
        }
    }
}
