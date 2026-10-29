package com.ytbg.app

import android.app.StatusBarManager
import android.appwidget.AppWidgetManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.preference.MultiSelectListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById<View>(R.id.settingsRoot)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }

        findViewById<MaterialToolbar>(R.id.toolbar).apply {
            setTitle(R.string.settings_title)
            setTitleTextColor(ContextCompat.getColor(context, R.color.text_primary))
            setNavigationIcon(R.drawable.ic_back)
            setNavigationContentDescription(R.string.close)
            setNavigationOnClickListener { finish() }
        }

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settingsContainer, SettingsFragment())
                .commit()
        }
    }

    class SettingsFragment : PreferenceFragmentCompat() {

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            // Same file as Prefs, so the app reads what is changed here.
            preferenceManager.sharedPreferencesName = Prefs.FILE
            setPreferencesFromResource(R.xml.settings, rootKey)

            findPreference<MultiSelectListPreference>(Prefs.KEY_SPONSOR_CATEGORIES)?.summaryProvider =
                Preference.SummaryProvider<MultiSelectListPreference> { preference ->
                    val names = preference.entryValues.indices
                        .filter { preference.entryValues[it].toString() in preference.values }
                        .map { preference.entries[it] }
                    if (names.isEmpty()) getString(R.string.settings_nothing_selected) else names.joinToString(", ")
                }

            findPreference<Preference>("add_widget")?.setOnPreferenceClickListener {
                addWidget()
                true
            }
            findPreference<Preference>("add_tile")?.setOnPreferenceClickListener {
                addTile()
                true
            }
            findPreference<Preference>("crash_log")?.setOnPreferenceClickListener {
                showCrashLog()
                true
            }
            findPreference<Preference>("version")?.summary = getString(R.string.settings_version, appVersion())
        }

        private fun appVersion(): String {
            val context = requireContext()
            return context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
        }

        private fun addWidget() {
            val context = requireContext()
            val manager = AppWidgetManager.getInstance(context)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager.isRequestPinAppWidgetSupported) {
                manager.requestPinAppWidget(ComponentName(context, PlayerWidget::class.java), null, null)
            } else {
                showHint(R.string.settings_widget, R.string.settings_widget_manual)
            }
        }

        private fun addTile() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requestTile()
            } else {
                showHint(R.string.tiles_dialog_title, R.string.tiles_dialog_text)
            }
        }

        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
        private fun requestTile() {
            val context = requireContext()
            context.getSystemService(StatusBarManager::class.java).requestAddTileService(
                ComponentName(context, BackgroundTileService::class.java),
                getString(R.string.tile_background),
                Icon.createWithResource(context, R.drawable.ic_power),
                context.mainExecutor,
            ) { result ->
                if (result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED) {
                    Toast.makeText(context, R.string.settings_tile_already, Toast.LENGTH_SHORT).show()
                }
            }
        }

        private fun showCrashLog() {
            val context = requireContext()
            val report = Diagnostics.report(context)
            val dialog = MaterialAlertDialogBuilder(context)
                .setTitle(R.string.settings_crash_log)
                .setMessage(report ?: getString(R.string.settings_crash_log_empty))
                .setPositiveButton(android.R.string.ok, null)
            if (report != null) {
                dialog.setNeutralButton(R.string.settings_copy) { _, _ ->
                    context.getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText(getString(R.string.settings_crash_log), report))
                }
            }
            dialog.show()
        }

        private fun showHint(title: Int, message: Int) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }
}
