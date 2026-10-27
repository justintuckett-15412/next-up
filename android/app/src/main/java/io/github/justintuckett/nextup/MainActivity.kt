package io.github.justintuckett.nextup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

private val Bg = Color(0xFF0F1115)
private val CardC = Color(0xFF181B21)
private val CardOpen = Color(0xFF1F232B)
private val Line = Color(0xFF2A2F39)
private val TextC = Color(0xFFEEF0F4)
private val Muted = Color(0xFF9AA3B2)
private val Accent = Color(0xFF58C9B9)
private val Warn = Color(0xFFF0B45A)
private val Placeholder = Color(0xFF252A33)

class MainActivity : ComponentActivity() {
    private val focusId = mutableIntStateOf(-1)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        RefreshWorker.schedule(this)
        focusId.intValue = intent?.getIntExtra(EXTRA_EPISODE, -1) ?: -1
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Accent,
                    onPrimary = Color(0xFF062B26),
                    background = Bg,
                    surface = Bg,
                    onBackground = TextC,
                    onSurface = TextC,
                    surfaceVariant = CardC,
                ),
            ) {
                MainScreen(focusId.intValue) { focusId.intValue = -1 }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        focusId.intValue = intent.getIntExtra(EXTRA_EPISODE, -1)
    }

    companion object {
        const val EXTRA_EPISODE = "episodeId"
    }
}

private fun openUrl(ctx: Context, url: String) {
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}

private fun openPlex(ctx: Context) {
    val app = ctx.packageManager.getLaunchIntentForPackage("com.plexapp.android")
    if (app != null) ctx.startActivity(app) else openUrl(ctx, "https://app.plex.tv")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(focusId: Int, onFocused: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val zone = remember { ZoneId.systemDefault() }

    var feed by remember { mutableStateOf<Feed?>(null) }
    var recs by remember { mutableStateOf<Recs?>(null) }
    var hiddenRecs by remember { mutableStateOf(Repo.hiddenRecs(ctx)) }
    var tab by remember { mutableIntStateOf(0) }
    var loaded by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var updateUrl by remember { mutableStateOf<String?>(null) }
    var hideWatched by remember { mutableStateOf(Repo.hideWatched(ctx)) }
    var showSettings by remember { mutableStateOf(false) }
    var posterVersion by remember { mutableIntStateOf(0) }
    var lastFetch by remember { mutableStateOf(Repo.lastFetch(ctx)) }
    val expanded = remember { mutableStateListOf<Int>() }
    val listState = rememberLazyListState()
    var didFirstScroll by remember { mutableStateOf(false) }

    fun refresh() {
        if (refreshing) return
        refreshing = true
        error = null
        scope.launch {
            Repo.refresh(ctx)
                .onSuccess { feed = it; recs = Repo.loadRecs(ctx); posterVersion++; lastFetch = Repo.lastFetch(ctx); NextUpWidget.redrawAll(ctx) }
                .onFailure { error = "Couldn't update (${it.message}). Showing your saved list." }
            refreshing = false
        }
    }

    LaunchedEffect(Unit) {
        feed = withContext(Dispatchers.IO) { Repo.load(ctx) }
        recs = withContext(Dispatchers.IO) { Repo.loadRecs(ctx) }
        loaded = true
        if (feed == null || System.currentTimeMillis() - Repo.lastFetch(ctx) > 60 * 60 * 1000) refresh()
        updateUrl = Repo.newerBuildUrl()
    }

    val today = LocalDate.now(zone)
    val rows = remember(feed, hideWatched, today) {
        feed?.let { buildRows(it, zone, hideWatched = hideWatched) } ?: emptyList()
    }
    val todayIndex = rows.indexOfFirst { it is DayRow && it.date >= today }.let { if (it < 0) rows.lastIndex.coerceAtLeast(0) else it }

    LaunchedEffect(rows, focusId) {
        if (rows.isEmpty()) return@LaunchedEffect
        val target = if (focusId > 0) rows.indexOfFirst { it is EpRow && it.ep.id == focusId } else -1
        if (target >= 0) {
            tab = 0
            if (focusId !in expanded) expanded.add(focusId)
            // Show the day header above the episode too.
            listState.scrollToItem((target - 1).coerceAtLeast(0))
            onFocused()
        } else if (!didFirstScroll) {
            listState.scrollToItem(todayIndex)
        }
        didFirstScroll = true
    }

    Scaffold(
        containerColor = Bg,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg, titleContentColor = TextC),
                title = {
                    Column {
                        Text("Next Up", fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
                        Text(
                            if (refreshing) "Updating…" else Fmt.ago(lastFetch),
                            color = Muted, fontSize = 12.sp,
                        )
                    }
                },
                actions = {
                    if (tab == 0) {
                        TextButton(onClick = { scope.launch { listState.animateScrollToItem(todayIndex) } }) {
                            Text("Today", color = Accent)
                        }
                    }
                    if (refreshing) {
                        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Accent)
                        }
                    } else {
                        IconButton(onClick = { refresh() }) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Refresh", tint = TextC)
                        }
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = TextC)
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar(containerColor = CardC) {
                val itemColors = NavigationBarItemDefaults.colors(
                    selectedIconColor = Accent, selectedTextColor = Accent,
                    unselectedIconColor = Muted, unselectedTextColor = Muted,
                    indicatorColor = Accent.copy(alpha = 0.18f),
                )
                NavigationBarItem(
                    selected = tab == 0, onClick = { tab = 0 }, colors = itemColors,
                    icon = { Icon(Icons.Filled.DateRange, contentDescription = null) },
                    label = { Text("Episodes") },
                )
                NavigationBarItem(
                    selected = tab == 1, onClick = { tab = 1 }, colors = itemColors,
                    icon = { Icon(Icons.Filled.Star, contentDescription = null) },
                    label = { Text("For You") },
                )
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            updateUrl?.let { url ->
                Banner("A new version of Next Up is ready. Tap to download.", Accent.copy(alpha = 0.15f), Accent) { openUrl(ctx, url) }
            }
            error?.let { msg -> Banner(msg, Color(0xFF2A2416), Color(0xFFF3DCA8)) { error = null } }
            if (tab == 1) {
                RecsScreen(
                    recs = recs,
                    hidden = hiddenRecs,
                    onHide = { id -> hiddenRecs = hiddenRecs + id; Repo.setHiddenRecs(ctx, hiddenRecs) },
                    onUnhideAll = { hiddenRecs = emptySet(); Repo.setHiddenRecs(ctx, emptySet()) },
                )
            } else when {
                !loaded -> {}
                feed == null -> Empty(
                    if (refreshing) "Loading your shows…"
                    else "Couldn't load your shows yet.\nMake sure the \"Update TV calendar\" job has run, then tap refresh.",
                )
                rows.isEmpty() -> Empty("Nothing to show.")
                else -> LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 40.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(rows, key = { it.key }) { row ->
                        when (row) {
                            is DayRow -> DayHeader(row.date, today)
                            is EpRow -> EpisodeCard(
                                row = row,
                                zone = zone,
                                expanded = row.ep.id in expanded,
                                posterVersion = posterVersion,
                            ) {
                                if (row.ep.id in expanded) expanded.remove(row.ep.id) else expanded.add(row.ep.id)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showSettings) {
        SettingsDialog(
            hideWatched = hideWatched,
            onDismiss = { showSettings = false },
            onSave = { hide, widgetStatus, url ->
                showSettings = false
                val urlChanged = url.trim() != Repo.dataUrl(ctx)
                hideWatched = hide
                Repo.setHideWatched(ctx, hide)
                Repo.setStatusOnWidget(ctx, widgetStatus)
                Repo.setDataUrl(ctx, url)
                scope.launch { NextUpWidget.redrawAll(ctx) }
                if (urlChanged) refresh()
            },
        )
    }
}

@Composable
private fun Banner(text: String, bg: Color, fg: Color, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = bg,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Text(text, color = fg, fontSize = 13.sp, modifier = Modifier.padding(12.dp))
    }
}

@Composable
private fun Empty(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = Muted, fontSize = 15.sp, lineHeight = 22.sp)
    }
}

@Composable
private fun DayHeader(day: LocalDate, today: LocalDate) {
    Text(
        Fmt.dayLabel(day, today).uppercase(),
        color = if (day == today) Accent else Muted,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(top = 18.dp, bottom = 6.dp),
    )
}

@Composable
private fun Poster(showId: Int, version: Int, modifier: Modifier) {
    val ctx = LocalContext.current
    val bmp by produceState<ImageBitmap?>(null, showId, version) {
        value = withContext(Dispatchers.IO) { Repo.poster(ctx, showId, 200)?.asImageBitmap() }
    }
    Box(modifier.clip(RoundedCornerShape(8.dp)).background(Placeholder)) {
        bmp?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
    }
}

@Composable
private fun EpisodeCard(row: EpRow, zone: ZoneId, expanded: Boolean, posterVersion: Int, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val e = row.ep
    val show = row.show
    val time = Fmt.time(e, zone)
    val status = Fmt.status(e, Instant.now(), zone)
    val whenLine = listOf(time, show?.network.orEmpty()).filter { it.isNotBlank() }.joinToString(" · ")

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = if (expanded) CardOpen else CardC,
        border = BorderStroke(1.dp, Line),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Poster(e.showId, posterVersion, Modifier.size(52.dp, 76.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        show?.name ?: "Unknown show",
                        fontWeight = FontWeight.SemiBold, fontSize = 16.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        e.code + if (e.name.isNotBlank()) " · ${e.name}" else "",
                        color = TextC.copy(alpha = 0.85f), fontSize = 13.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    if (whenLine.isNotEmpty()) {
                        Text(whenLine, color = Accent, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                    }
                    if (!expanded && row.description.isNotBlank()) {
                        Text(
                            row.description, color = Muted, fontSize = 12.sp, lineHeight = 16.sp,
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
                if (status != null) {
                    Spacer(Modifier.width(8.dp))
                    StatusChip(status)
                }
            }
            if (expanded) {
                if (row.description.isNotBlank()) {
                    Text(
                        row.description, color = TextC.copy(alpha = 0.8f), fontSize = 14.sp, lineHeight = 20.sp,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { openPlex(ctx) }) { Text("Open Plex") }
                    if (!show?.url.isNullOrBlank()) {
                        OutlinedButton(onClick = { openUrl(ctx, show!!.url) }) { Text("TVmaze", color = TextC) }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusChip(status: Status) {
    val (fg, bg) = when (status) {
        Status.WATCHED -> Muted to Color(0xFF23272F)
        Status.IN_PLEX -> Accent to Accent.copy(alpha = 0.15f)
        Status.MISSING -> Warn to Warn.copy(alpha = 0.15f)
    }
    Text(
        status.label,
        color = fg, fontSize = 11.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier.clip(RoundedCornerShape(99.dp)).background(bg).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
private fun SettingsDialog(hideWatched: Boolean, onDismiss: () -> Unit, onSave: (Boolean, Boolean, String) -> Unit) {
    val ctx = LocalContext.current
    var hide by remember { mutableStateOf(hideWatched) }
    var widgetStatus by remember { mutableStateOf(Repo.statusOnWidget(ctx)) }
    var url by remember { mutableStateOf(Repo.dataUrl(ctx)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardC,
        title = { Text("Settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Hide watched episodes", color = TextC)
                        Text("Applies to the app and the widget", color = Muted, fontSize = 12.sp)
                    }
                    Switch(checked = hide, onCheckedChange = { hide = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Plex status on widget", color = TextC)
                        Text("Show In Plex / Watched / Not in Plex on the home screen too", color = Muted, fontSize = 12.sp)
                    }
                    Switch(checked = widgetStatus, onCheckedChange = { widgetStatus = it })
                }
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Episode list address") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = { url = BuildConfig.DATA_URL }) { Text("Reset address", color = Accent) }
                Text("Version ${BuildConfig.VERSION_NAME}", color = Muted, fontSize = 12.sp)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(hide, widgetStatus, url) }) { Text("Done", color = Accent) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Muted) } },
    )
}

// ---------- For You ----------

@Composable
private fun RecsScreen(recs: Recs?, hidden: Set<Int>, onHide: (Int) -> Unit, onUnhideAll: () -> Unit) {
    if (recs == null || recs.items.isEmpty()) {
        Empty(
            "No recommendations yet.\n\nAdd your TMDB key to the repo as the TMDB_KEY secret, " +
                "run the \"Update TV calendar\" job, then tap refresh.",
        )
        return
    }
    val visible = recs.items.filter { it.id !in hidden }
    val expanded = remember { mutableStateListOf<Int>() }
    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 40.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (recs.basedOn.isNotEmpty()) {
            item(key = "intro") {
                Text(
                    "Picked from shows you've watched a lot of or rated highly in Plex, like " +
                        recs.basedOn.take(4).joinToString(", ") + ".",
                    color = Muted, fontSize = 13.sp, lineHeight = 18.sp,
                    modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
                )
            }
        }
        items(visible, key = { "r${it.id}" }) { rec ->
            RecCard(rec, rec.id in expanded, onClick = {
                if (rec.id in expanded) expanded.remove(rec.id) else expanded.add(rec.id)
            }, onHide = { onHide(rec.id) })
        }
        item(key = "footer") {
            Column(Modifier.padding(top = 12.dp)) {
                if (hidden.isNotEmpty()) {
                    TextButton(onClick = onUnhideAll) { Text("Show ${hidden.size} hidden", color = Accent) }
                }
                Text(
                    "Recommendations and streaming info from TMDB (via JustWatch). " +
                        "This product uses the TMDB API but is not endorsed or certified by TMDB.",
                    color = Muted.copy(alpha = 0.7f), fontSize = 11.sp, lineHeight = 15.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun RecPoster(rec: Rec, modifier: Modifier) {
    val ctx = LocalContext.current
    val bmp by produceState<ImageBitmap?>(null, rec.id) {
        value = withContext(Dispatchers.IO) { Repo.recPoster(ctx, rec.id, rec.poster)?.asImageBitmap() }
    }
    Box(modifier.clip(RoundedCornerShape(8.dp)).background(Placeholder)) {
        bmp?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
    }
}

@Composable
private fun RecCard(rec: Rec, expanded: Boolean, onClick: () -> Unit, onHide: () -> Unit) {
    val ctx = LocalContext.current
    val sub = listOf(rec.year, rec.genres.joinToString(", ")).filter { it.isNotBlank() }.joinToString(" · ")
    val score = if (rec.rating > 0) "★ ${"%.1f".format(rec.rating)}" else ""
    val where = if (rec.providers.isNotEmpty()) "On ${rec.providers.joinToString(", ")}" else ""
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = if (expanded) CardOpen else CardC,
        border = BorderStroke(1.dp, Line),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Column(Modifier.padding(10.dp)) {
            Row {
                RecPoster(rec, Modifier.size(70.dp, 104.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(rec.name, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (sub.isNotEmpty()) Text(sub, color = Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val line = listOf(score, where).filter { it.isNotBlank() }.joinToString(" · ")
                    if (line.isNotEmpty()) Text(line, color = Accent, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 2)
                    val why = becauseText(rec.because)
                    if (why.isNotEmpty()) {
                        Text(why, color = TextC.copy(alpha = 0.8f), fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
            if (rec.overview.isNotBlank()) {
                Text(
                    rec.overview,
                    color = if (expanded) TextC.copy(alpha = 0.8f) else Muted,
                    fontSize = if (expanded) 14.sp else 12.sp,
                    lineHeight = if (expanded) 20.sp else 16.sp,
                    maxLines = if (expanded) Int.MAX_VALUE else 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            if (expanded) {
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onHide) { Text("Not interested", color = TextC) }
                    if (rec.url.isNotBlank()) OutlinedButton(onClick = { openUrl(ctx, rec.url) }) { Text("TMDB", color = TextC) }
                }
            }
        }
    }
}
