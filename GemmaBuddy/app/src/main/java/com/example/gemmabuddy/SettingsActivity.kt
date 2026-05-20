package com.example.gemmabuddy

import android.content.Context
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceFragmentCompat

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settings_container, SettingsFragment())
                .commit()
        }
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            preferenceManager.sharedPreferencesName = OverlayService.PREFS_NAME
            setPreferencesFromResource(R.xml.preferences, rootKey)

            findPreference<ListPreference>("interval_choice")?.setOnPreferenceChangeListener { _, newValue ->
                val ms = (newValue as String).toLong()
                requireContext().getSharedPreferences(OverlayService.PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().putLong(OverlayService.PREF_INTERVAL_MS, ms).apply()
                true
            }

            findPreference<EditTextPreference>("openai_api_key")?.apply {
                setOnBindEditTextListener { editText ->
                    editText.inputType = android.text.InputType.TYPE_CLASS_TEXT or
                            android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                }
                summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance()
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}
