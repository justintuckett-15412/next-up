package io.github.justintuckett.nextup

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

private val WeekOffset = intPreferencesKey("weekOffset")
private val DataVersion = longPreferencesKey("dataVersion")
private val Delta = ActionParameters.Key<Int>("delta")

private const val MAX_ITEMS = 40      // keeps the widget well under Android's size limits
private const val POSTER_PX = 60
private const val MAX_POSTERS = 20   // distinct shows with artwork; the rest get a placeholder

private object W {
    val bg = Color(0xF20F1115)
    val text = ColorProvider(Color(0xFFEEF0F4))
    val muted = ColorProvider(Color(0xFF9AA3B2))
    val accent = ColorProvider(Color(0xFF58C9B9))
    val warn = ColorProvider(Color(0xFFF0B45A))
    val placeholder = Color(0xFF252A33)
}

class NextUpWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NextUpWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        RefreshWorker.schedule(context)
        RefreshWorker.runNow(context)
    }
}

class NextUpWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            val version = currentState(DataVersion) ?: 0L
            val offset = currentState(WeekOffset) ?: 0
            val feed = remember(version) { Repo.load(context) }
            WidgetBody(context, feed, offset, version)
        }
    }

    companion object {
        /** Bumps each widget's state so it re-reads the cached episode list. */
        suspend fun redrawAll(ctx: Context) {
            val ids = GlanceAppWidgetManager(ctx).getGlanceIds(NextUpWidget::class.java)
            val stamp = System.currentTimeMillis()
            for (id in ids) {
                updateAppWidgetState(ctx, id) { it[DataVersion] = stamp }
                NextUpWidget().update(ctx, id)
            }
        }
    }
}

@Composable
private fun WidgetBody(context: Context, feed: Feed?, offset: Int, version: Long) {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val start = today.plusWeeks(offset.toLong())
    val rows = remember(version, offset, today) {
        feed?.let { buildRows(it, zone, from = start, hideWatched = Repo.hideWatched(context), limit = MAX_ITEMS) } ?: emptyList()
    }
    val posters: Map<Int, Bitmap?> = remember(version, offset, today) {
        rows.filterIsInstance<EpRow>().map { it.ep.showId }.distinct().take(MAX_POSTERS)
            .associateWith { Repo.poster(context, it, POSTER_PX) }
    }
    val showStatus = remember(version) { Repo.statusOnWidget(context) }
    val openApp = actionStartActivity(Intent(context, MainActivity::class.java))
    val label = if (offset == 0) "Upcoming" else "From ${Fmt.shortDate(start)}"

    Column(
        modifier = GlanceModifier.fillMaxSize().background(W.bg).cornerRadius(20.dp)
            .padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 4.dp),
    ) {
        // Header: title + week back / today / week forward / refresh
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = GlanceModifier.defaultWeight().clickable(openApp)) {
                Text("Next Up", style = TextStyle(color = W.text, fontSize = 16.sp, fontWeight = FontWeight.Bold))
                Text(label, style = TextStyle(color = if (offset == 0) W.muted else W.accent, fontSize = 11.sp), maxLines = 1)
            }
            HeaderButton("◀", actionRunCallback<ShiftWeek>(actionParametersOf(Delta to -1)))
            if (offset != 0) HeaderButton("●", actionRunCallback<ShiftWeek>(actionParametersOf(Delta to 0)))
            HeaderButton("▶", actionRunCallback<ShiftWeek>(actionParametersOf(Delta to 1)))
            HeaderButton("↻", actionRunCallback<RefreshNow>())
        }
        Spacer(GlanceModifier.height(4.dp))

        when {
            feed == null -> Message("Tap ↻ to load your shows", openApp, GlanceModifier.defaultWeight())
            rows.isEmpty() -> Message("Nothing from here on. Tap ◀ to go back.", openApp, GlanceModifier.defaultWeight())
            else -> LazyColumn(modifier = GlanceModifier.fillMaxWidth().defaultWeight()) {
                items(rows, itemId = { row -> rowId(row) }) { row ->
                    when (row) {
                        is DayRow -> DayHeader(row.date, today)
                        is EpRow -> EpisodeItem(context, row, posters[row.ep.showId], zone, showStatus)
                    }
                }
            }
        }
    }
}

private fun rowId(row: ListRow): Long = when (row) {
    is DayRow -> -row.date.toEpochDay() - 1
    is EpRow -> row.ep.id.toLong()
}

@Composable
private fun HeaderButton(label: String, action: androidx.glance.action.Action) {
    Box(
        modifier = GlanceModifier.size(36.dp, 36.dp).clickable(action),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = TextStyle(color = W.accent, fontSize = 16.sp, fontWeight = FontWeight.Bold))
    }
}

@Composable
private fun Message(text: String, action: androidx.glance.action.Action, modifier: GlanceModifier) {
    Box(modifier = modifier.fillMaxWidth().clickable(action), contentAlignment = Alignment.Center) {
        Text(text, style = TextStyle(color = W.muted, fontSize = 13.sp))
    }
}

@Composable
private fun DayHeader(day: LocalDate, today: LocalDate) {
    Text(
        Fmt.dayLabel(day, today).uppercase(),
        modifier = GlanceModifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp),
        style = TextStyle(
            color = if (day == today) W.accent else W.muted,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
        ),
    )
}

@Composable
private fun EpisodeItem(context: Context, row: EpRow, poster: Bitmap?, zone: ZoneId, showStatus: Boolean) {
    val e = row.ep
    val open = actionStartActivity(
        Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_EPISODE, e.id)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
    )
    val time = Fmt.time(e, zone)
    val status = if (showStatus) Fmt.status(e, Instant.now(), zone) else null

    Row(
        modifier = GlanceModifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp, end = 6.dp).clickable(open),
        verticalAlignment = Alignment.Top,
    ) {
        if (poster != null) {
            Image(
                provider = ImageProvider(poster),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = GlanceModifier.size(36.dp, 52.dp).cornerRadius(5.dp),
            )
        } else {
            Box(modifier = GlanceModifier.size(36.dp, 52.dp).background(W.placeholder).cornerRadius(5.dp)) {}
        }
        Spacer(GlanceModifier.width(10.dp))
        Column(modifier = GlanceModifier.defaultWeight()) {
            Row(modifier = GlanceModifier.fillMaxWidth()) {
                Text(
                    row.show?.name ?: "Unknown show",
                    modifier = GlanceModifier.defaultWeight(),
                    style = TextStyle(color = W.text, fontSize = 14.sp, fontWeight = FontWeight.Bold),
                    maxLines = 1,
                )
                if (time.isNotEmpty()) {
                    Text(time, style = TextStyle(color = W.accent, fontSize = 12.sp, fontWeight = FontWeight.Medium))
                }
            }
            Text(
                e.code + if (e.name.isNotBlank()) " · ${e.name}" else "",
                style = TextStyle(color = W.text, fontSize = 12.sp),
                maxLines = 1,
            )
            if (status != null) {
                Text(
                    status.label,
                    style = TextStyle(
                        color = when (status) {
                            Status.WATCHED -> W.muted
                            Status.IN_PLEX -> W.accent
                            Status.MISSING -> W.warn
                        },
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                )
            }
            if (row.description.isNotBlank()) {
                Text(row.description, style = TextStyle(color = W.muted, fontSize = 11.sp), maxLines = 2)
            }
        }
    }
}

class ShiftWeek : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val delta = parameters[Delta] ?: 0
        updateAppWidgetState(context, glanceId) { prefs ->
            val current = prefs[WeekOffset] ?: 0
            prefs[WeekOffset] = if (delta == 0) 0 else (current + delta).coerceIn(-5, 12)
        }
        NextUpWidget().update(context, glanceId)
    }
}

class RefreshNow : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        RefreshWorker.runNow(context)
    }
}
