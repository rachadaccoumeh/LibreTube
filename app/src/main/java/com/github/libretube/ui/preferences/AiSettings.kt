package com.github.libretube.ui.preferences

import android.os.Bundle
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import com.github.libretube.R
import com.github.libretube.constants.PreferenceKeys
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.ui.base.BasePreferenceFragment

class AiSettings : BasePreferenceFragment() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.ai_settings, rootKey)

        val providerPref = findPreference<ListPreference>(PreferenceKeys.AI_PROVIDER)
        val apiUrlPref = findPreference<EditTextPreference>(PreferenceKeys.AI_API_URL)
        val apiKeyPref = findPreference<EditTextPreference>(PreferenceKeys.AI_API_KEY)
        val modelPref = findPreference<EditTextPreference>(PreferenceKeys.AI_MODEL)

        providerPref?.setOnPreferenceChangeListener { _, newValue ->
            updateDefaultsForProvider(newValue.toString(), apiUrlPref, modelPref)
            true
        }

        // Show current value as summary for EditTextPreferences
        apiUrlPref?.summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance()
        modelPref?.summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance()

        // Mask API key in summary
        apiKeyPref?.summaryProvider = androidx.preference.Preference.SummaryProvider<EditTextPreference> { pref ->
            val value = pref.text
            if (value.isNullOrBlank()) "Not set" else "••••••••"
        }

        // Set defaults based on current provider on first load
        val currentProvider = PreferenceHelper.getString(PreferenceKeys.AI_PROVIDER, "openai")
        if (PreferenceHelper.getString(PreferenceKeys.AI_API_URL, "").isEmpty()) {
            updateDefaultsForProvider(currentProvider, apiUrlPref, modelPref)
        }
    }

    private fun updateDefaultsForProvider(
        provider: String,
        apiUrlPref: EditTextPreference?,
        modelPref: EditTextPreference?
    ) {
        when (provider) {
            "openai" -> {
                apiUrlPref?.text = "https://api.openai.com/v1"
                modelPref?.text = "gpt-4o-mini"
            }
            "gemini" -> {
                apiUrlPref?.text = "https://generativelanguage.googleapis.com/v1beta/openai"
                modelPref?.text = "gemini-2.0-flash"
            }
            "ollama" -> {
                apiUrlPref?.text = "http://localhost:11434/v1"
                modelPref?.text = "llama3.2"
            }
            "custom" -> {
                // Don't override — let user enter everything
            }
        }
    }
}
