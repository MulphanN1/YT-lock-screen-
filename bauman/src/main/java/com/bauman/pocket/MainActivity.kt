package com.bauman.pocket

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.bauman.pocket.ui.BaumanTheme
import com.bauman.pocket.ui.Hairline
import com.bauman.pocket.ui.LocalPalette
import com.bauman.pocket.ui.SettingsScreen
import com.bauman.pocket.ui.SettingsState
import com.bauman.pocket.ui.Type
import com.bauman.pocket.ui.deadlinesScreen
import com.bauman.pocket.ui.gradesScreen
import com.bauman.pocket.ui.todayScreen
import com.bauman.pocket.ui.weekScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Tab(val title: String) { TODAY("Сегодня"), WEEK("Неделя"), DEADLINES("Сроки"), GRADES("Баллы") }

class MainActivity : ComponentActivity() {
    companion object {
        const val EXTRA_TAB = "tab"
        const val TAB_DEADLINES = "deadlines"
        private const val AUTO_SYNC_MS = 30 * 60 * 1000L
    }

    private var data by mutableStateOf(Data.EMPTY)
    private var tab by mutableStateOf(Tab.TODAY)
    private var showSettings by mutableStateOf(false)
    private var refreshing by mutableStateOf(false)
    private var settings by mutableStateOf<SettingsState?>(null)

    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        lifecycleScope.launch(Dispatchers.IO) { Ticker.tick(applicationContext) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        data = Store.load(this)
        settings = readSettings()
        handle(intent)
        Ticker.start(this)
        if (Build.VERSION.SDK_INT >= 33 && savedInstanceState == null) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent { BaumanTheme { Root() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onResume() {
        super.onResume()
        data = Store.load(this)
        val stale = System.currentTimeMillis() - Store.syncedAt(this) > AUTO_SYNC_MS
        if (Store.token(this).isNotBlank() && stale) refresh(quiet = true)
    }

    private fun handle(intent: Intent?) {
        if (intent?.getStringExtra(EXTRA_TAB) == TAB_DEADLINES) {
            tab = Tab.DEADLINES
            showSettings = false
        }
    }

    private fun readSettings() = SettingsState(
        repo = Store.repo(this),
        token = Store.token(this),
        syncedAt = Store.syncedAt(this),
        error = Store.lastError(this),
        notifyNow = Store.notifyNow(this),
        notifyDeadlines = Store.notifyDeadlines(this),
    )

    private fun refresh(quiet: Boolean = false) {
        if (refreshing) return
        if (Store.token(this).isBlank()) {
            if (!quiet) showSettings = true
            return
        }
        refreshing = true
        lifecycleScope.launch {
            val error = withContext(Dispatchers.IO) {
                Store.syncCatching(applicationContext).also { Ticker.tick(applicationContext) }
            }
            data = Store.load(this@MainActivity)
            settings = readSettings()
            refreshing = false
            if (error != null && !quiet) Toast.makeText(this@MainActivity, error, Toast.LENGTH_LONG).show()
        }
    }

    private fun save(repo: String, token: String) {
        Store.prefs(this).edit().putString("repo", repo).putString("token", token).apply()
        settings = readSettings()
        refresh()
    }

    private fun toggle(key: String, value: Boolean) {
        Store.prefs(this).edit().putBoolean(key, value).apply()
        settings = readSettings()
        lifecycleScope.launch(Dispatchers.IO) { Ticker.tick(applicationContext) }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Root() {
        val p = LocalPalette.current
        val at by produceState(Semester.now()) {
            while (true) {
                delay(15_000)
                value = Semester.now()
            }
        }
        val states = Tab.entries.associateWith { rememberLazyListState() }
        var week by rememberSaveable { mutableIntStateOf(Semester.weekOf(at.toLocalDate()).coerceIn(1, Semester.WEEKS)) }

        BackHandler(enabled = showSettings) { showSettings = false }
        BackHandler(enabled = !showSettings && tab != Tab.TODAY) { tab = Tab.TODAY }

        val s = settings
        Box(Modifier.fillMaxSize().background(p.bg).statusBarsPadding()) {
            if (showSettings && s != null) {
                Box(Modifier.navigationBarsPadding()) {
                    SettingsScreen(
                        state = s, busy = refreshing, onBack = { showSettings = false },
                        onSave = ::save,
                        onNotifyNow = { toggle("notify_now", it) },
                        onNotifyDeadlines = { toggle("notify_deadlines", it) },
                    )
                }
            } else Column(Modifier.fillMaxSize()) {
                PullToRefreshBox(isRefreshing = refreshing, onRefresh = { refresh() }, modifier = Modifier.weight(1f)) {
                    LazyColumn(Modifier.fillMaxSize(), state = states.getValue(tab)) {
                        when (tab) {
                            Tab.TODAY -> todayScreen(data, at, { showSettings = true }, { tab = Tab.DEADLINES })
                            Tab.WEEK -> weekScreen(data, at, week) { week = it }
                            Tab.DEADLINES -> deadlinesScreen(data, at) { showSettings = true }
                            Tab.GRADES -> gradesScreen(data) { showSettings = true }
                        }
                    }
                }
                Tabs(overdue = Plan.buckets(data, at).overdue.size)
            }
        }
    }

    @Composable
    private fun Tabs(overdue: Int) {
        val p = LocalPalette.current
        Column(Modifier.fillMaxWidth().background(p.bg).navigationBarsPadding()) {
            Hairline()
            Row(Modifier.fillMaxWidth().height(56.dp)) {
                Tab.entries.forEach { t ->
                    val selected = t == tab
                    Column(
                        Modifier.weight(1f).fillMaxSize()
                            .clickable(remember { MutableInteractionSource() }, indication = null) { tab = t },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(Modifier.fillMaxWidth().padding(horizontal = 18.dp).height(2.dp).background(if (selected) p.ink else Color.Transparent))
                        Spacer(Modifier.height(14.dp))
                        Row(verticalAlignment = Alignment.Top) {
                            Text(t.title, style = Type.smallStrong, color = if (selected) p.ink else p.ink3)
                            if (t == Tab.DEADLINES && overdue > 0) {
                                Text(" $overdue", style = Type.label, color = p.hot)
                            }
                        }
                    }
                }
            }
        }
    }
}
