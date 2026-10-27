package io.github.justintuckett.nextup

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

data class Show(
    val id: Int,
    val name: String,
    val network: String,
    val poster: String,
    val url: String,
    val summary: String = "",
)

data class Episode(
    val id: Int,
    val showId: Int,
    val code: String,
    val name: String,
    val airdate: LocalDate,
    val airstamp: Instant?,   // null = date only (e.g. streaming drops)
    val runtime: Int,
    val summary: String,
    val have: Boolean?,       // in your Plex library? null = unknown
    val watched: Boolean?,
) {
    fun localDate(zone: ZoneId): LocalDate = airstamp?.atZone(zone)?.toLocalDate() ?: airdate
    fun sortKey(zone: ZoneId): Instant = airstamp ?: airdate.atTime(23, 59).atZone(zone).toInstant()
}

data class Feed(
    val generated: Instant?,
    val shows: Map<Int, Show>,
    val episodes: List<Episode>,
)

// ---------- parsing episodes.json ----------

private fun JSONObject.optBool(key: String): Boolean? =
    if (!has(key) || isNull(key)) null else optBoolean(key)

private fun JSONObject.str(key: String): String =
    if (!has(key) || isNull(key)) "" else optString(key, "")

fun parseFeed(text: String): Feed {
    val root = JSONObject(text)
    val showsJson = root.optJSONObject("shows") ?: JSONObject()
    val shows = mutableMapOf<Int, Show>()
    for (key in showsJson.keys()) {
        val s = showsJson.getJSONObject(key)
        val id = key.toIntOrNull() ?: continue
        shows[id] = Show(id, s.str("name"), s.str("network"), s.str("poster"), s.str("url"), s.str("summary"))
    }
    val arr = root.optJSONArray("episodes") ?: JSONArray()
    val episodes = (0 until arr.length()).mapNotNull { i ->
        runCatching {
            val e = arr.getJSONObject(i)
            Episode(
                id = e.getInt("id"),
                showId = e.getInt("show"),
                code = e.str("code"),
                name = e.str("name"),
                airdate = LocalDate.parse(e.getString("airdate")),
                airstamp = e.str("airstamp").takeIf { it.isNotBlank() }
                    ?.let { OffsetDateTime.parse(it).toInstant() },
                runtime = e.optInt("runtime", 30),
                summary = e.str("summary"),
                have = e.optBool("have"),
                watched = e.optBool("watched"),
            )
        }.getOrNull()
    }
    val generated = runCatching { Instant.parse(root.getString("generated")) }.getOrNull()
    return Feed(generated, shows, episodes)
}

// ---------- list building (shared by the app and the widget) ----------

sealed interface ListRow { val key: String }
data class DayRow(val date: LocalDate) : ListRow { override val key = "d$date" }
data class EpRow(val ep: Episode, val show: Show?) : ListRow {
    override val key = "e${ep.id}"
    /** Episode description, or the show's description until TVmaze posts one. */
    val description: String get() = ep.summary.ifBlank { show?.summary?.takeIf { it.isNotBlank() }?.let { "About the show: $it" } ?: "" }
}

fun buildRows(
    feed: Feed,
    zone: ZoneId,
    from: LocalDate? = null,
    hideWatched: Boolean = false,
    limit: Int = Int.MAX_VALUE,
): List<ListRow> {
    val out = mutableListOf<ListRow>()
    var lastDay: LocalDate? = null
    var count = 0
    val sorted = feed.episodes.sortedWith(compareBy({ it.localDate(zone) }, { it.sortKey(zone) }))
    for (e in sorted) {
        if (hideWatched && e.watched == true) continue
        val day = e.localDate(zone)
        if (from != null && day < from) continue
        if (count >= limit) break
        if (day != lastDay) { out += DayRow(day); lastDay = day }
        out += EpRow(e, feed.shows[e.showId])
        count++
    }
    return out
}

// ---------- formatting ----------

enum class Status(val label: String) { WATCHED("Watched"), IN_PLEX("In Plex"), MISSING("Not in Plex") }

object Fmt {
    private val dayFmt = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.getDefault())
    private val timeFmt = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(Locale.getDefault())

    fun dayLabel(day: LocalDate, today: LocalDate): String {
        val date = day.format(dayFmt)
        return when (ChronoUnit.DAYS.between(today, day)) {
            0L -> "Today · $date"
            1L -> "Tomorrow · $date"
            -1L -> "Yesterday · $date"
            else -> date
        }
    }

    fun shortDate(day: LocalDate): String = day.format(dayFmt)

    fun time(e: Episode, zone: ZoneId): String =
        e.airstamp?.atZone(zone)?.toLocalTime()?.format(timeFmt) ?: ""

    fun status(e: Episode, now: Instant, zone: ZoneId): Status? {
        val aired = e.sortKey(zone) <= now
        return when {
            e.watched == true -> Status.WATCHED
            e.have == true -> Status.IN_PLEX
            e.have == false && aired -> Status.MISSING
            else -> null
        }
    }

    fun ago(millis: Long): String {
        if (millis <= 0) return "Not updated yet"
        val min = (System.currentTimeMillis() - millis) / 60000
        return when {
            min < 1 -> "Updated just now"
            min < 60 -> "Updated $min min ago"
            min < 60 * 24 -> "Updated ${min / 60} hr ago"
            else -> "Updated ${min / (60 * 24)} days ago"
        }
    }
}
