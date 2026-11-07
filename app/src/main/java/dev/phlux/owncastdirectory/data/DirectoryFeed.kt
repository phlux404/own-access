package dev.phlux.owncastdirectory.data

import java.net.HttpURLConnection
import java.net.URL

/** One live stream from the Owncast directory feed. */
data class StreamEntry(
    val name: String,
    val logoUrl: String?,
    val tags: List<String>,
    val streamUrl: String,
    val isCustom: Boolean = false,
) {
    val categories: Set<Category> get() = categoriesFor(tags)
}

/**
 * The directory website's four categories (owncast.directory):
 * Music, Chatting, Tech, Video Games. The public IPTV feed has no
 * category field, so membership is derived from each stream's
 * self-supplied tags — a heuristic, close to the site's grouping
 * but not guaranteed identical to it.
 */
enum class Category(val label: String) {
    MUSIC("Music"),
    CHATTING("Chatting"),
    TECH("Tech"),
    GAMES("Video Games"),
}

private val CATEGORY_KEYWORDS: Map<Category, Set<String>> = mapOf(
    Category.MUSIC to setOf(
        "music", "dj", "radio", "chiptune", "kpop", "vapor", "vaporwave",
        "house", "drumandbass", "dnb", "jazz", "rock", "metal", "ambient",
        "electronic", "techno", "trance", "algorave", "algorithmic music",
        "live music", "concert", "songs", "piano", "guitar", "synthwave",
        "lofi", "lo-fi", "hip hop", "hiphop", "indiemusic", "musicvideos",
    ),
    Category.CHATTING to setOf(
        "chatting", "chat", "just chatting", "talk", "talkshow", "irl",
        "podcast", "interview", "discussion", "hangout", "community",
        "entertainment", "comedy", "news", "politics", "talk show",
    ),
    Category.TECH to setOf(
        "tech", "technology", "coding", "live coding", "code",
        "programming", "developer", "software", "linux", "opensource",
        "open source", "science", "maker", "electronics", "hacking",
        "dev", "gamedev", "android", "computers", "engineering",
    ),
    Category.GAMES to setOf(
        "gaming", "games", "video games", "videogames", "video game",
        "esports", "speedrun", "speedrunning", "retro", "retrogaming",
        "minecraft", "rpg", "fps", "mmorpg", "streaming games", "game",
        "playthrough", "letsplay", "let's play",
    ),
)

fun categoriesFor(tags: List<String>): Set<Category> {
    val normalized = tags.map { it.trim().lowercase() }.toSet()
    val out = mutableSetOf<Category>()
    for ((category, keywords) in CATEGORY_KEYWORDS) {
        if (normalized.any { tag -> tag in keywords || keywords.any { k -> tag.contains(k) && k.length > 3 } }) {
            out += category
        }
    }
    return out
}

object InstanceProbe {
    /**
     * Blocking check that [base] answers like an Owncast instance, via
     * its public /api/status. Returns the current stream title when the
     * instance reports one, null otherwise. Throws on any failure.
     */
    fun probe(base: String): String? {
        val conn = URL(base + "/api/status").openConnection() as HttpURLConnection
        conn.connectTimeout = 8_000
        conn.readTimeout = 8_000
        conn.setRequestProperty("User-Agent", "owncast-directory-client")
        val code = conn.responseCode
        if (code != 200) throw Exception("HTTP $code from /api/status")
        val text = conn.inputStream.bufferedReader().use { it.readText() }
        val json = org.json.JSONObject(text)
        return json.optString("streamTitle").ifEmpty { null }
    }
}

object DirectoryFeed {
    const val FEED_URL = "https://owncast.directory/api/iptv"

    /** Fetches and parses the directory's public IPTV (M3U) feed. Blocking; call off the main thread. */
    fun fetch(): List<StreamEntry> {
        val conn = URL(FEED_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 20_000
        conn.setRequestProperty("User-Agent", "owncast-directory-client/0.1")
        conn.inputStream.bufferedReader().useLines { lines ->
            return parse(lines.toList())
        }
    }

    fun parse(lines: List<String>): List<StreamEntry> {
        val out = mutableListOf<StreamEntry>()
        var pending: StreamEntry? = null
        val attr = Regex("""([a-zA-Z-]+)="([^"]*)"""")
        for (raw in lines) {
            val line = raw.trim()
            when {
                line.startsWith("#EXTINF") -> {
                    val attrs = attr.findAll(line).associate { it.groupValues[1] to it.groupValues[2] }
                    val name = line.substringAfterLast(",").trim()
                        .ifEmpty { attrs["tvg-ID"] ?: "Untitled stream" }
                    val tags = (attrs["tvg-tags"] ?: "")
                        .split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    pending = StreamEntry(
                        name = name,
                        logoUrl = attrs["tvg-logo"]?.ifEmpty { null },
                        tags = tags,
                        streamUrl = "",
                    )
                }
                line.isNotEmpty() && !line.startsWith("#") && pending != null -> {
                    out += pending.copy(streamUrl = line)
                    pending = null
                }
            }
        }
        return out
    }
}
