package dev.phlux.owncastdirectory.data

import android.text.Html
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** One chat message, flattened for display. */
data class ChatMessage(
    val id: String,
    val userName: String,
    val bodyText: String,
    val timestamp: String,
)

/**
 * Read-only Owncast chat.
 *
 * Owncast has no anonymous firehose: the supported route is the same one
 * the web player uses. Register a display name with POST
 * /api/chat/register to get an access token, then connect.
 *
 * The live socket lives at /ws?accessToken=... on current Owncast;
 * older instances serve it at /api/chat?accessToken=... instead. We try
 * /ws first and fall back to the legacy path. /api/chat is also the
 * history endpoint: a plain GET returns past messages as a JSON array,
 * which we load first either way (the backscroll), and if neither
 * socket path upgrades we fall back to polling that GET for new
 * messages by id.
 *
 * Sending is possible only while the WebSocket is live; in polling
 * mode there is no socket to post on and send() returns false.
 * State callbacks report "connecting", "live", "polling", or
 * "error:<detail>" naming exactly which door shut.
 */
class OwncastChat(
    private val instanceBase: String,
    private val displayName: String = DEFAULT_DISPLAY_NAME,
    private val onMessage: (ChatMessage) -> Unit,
    private val onState: (String) -> Unit,
) {
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var socket: WebSocket? = null

    @Volatile
    private var closed = false

    private var accessToken: String? = null
    private val seenIds = HashSet<String>()
    private var chatScope: CoroutineScope? = null

    fun start(scope: CoroutineScope) {
        chatScope = scope
        onState("connecting")
        scope.launch(Dispatchers.IO) {
            try {
                accessToken = register()
                if (closed) return@launch
                // Backscroll first, in both modes — matches the browser.
                runCatching { fetchHistory() }
                if (closed) return@launch
                connect()
            } catch (e: Exception) {
                if (!closed) onState("error:" + (e.message ?: "unknown failure"))
            }
        }
    }

    private fun register(): String {
        val body = JSONObject().put("displayName", displayName).toString()
            .toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(instanceBase + "/api/chat/register")
            .header("User-Agent", USER_AGENT)
            .post(body)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("register refused (HTTP " + response.code + ")")
            }
            val text = response.body?.string()
                ?: throw Exception("register: empty response")
            return JSONObject(text).optString("accessToken").ifEmpty {
                throw Exception("register: no token in response")
            }
        }
    }

    /** GET the history endpoint; emit any messages we have not shown. */
    private fun fetchHistory() {
        val token = accessToken ?: return
        val request = Request.Builder()
            .url(instanceBase + "/api/chat?accessToken=" + token)
            .header("User-Agent", USER_AGENT)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("history refused (HTTP " + response.code + ")")
            }
            val text = response.body?.string() ?: return
            val array = JSONArray(text)
            for (i in 0 until array.length()) {
                val msg = parseObject(array.optJSONObject(i) ?: continue)
                if (msg != null && seenIds.add(msg.id)) {
                    onMessage(msg)
                }
            }
            if (seenIds.size > 3000) {
                // Bound the dedupe set; ids are only compared against
                // the recent window anyway.
                seenIds.clear()
            }
        }
    }

    @Volatile
    private var wsLive = false

    @Volatile
    private var polling = false

    private fun connect() {
        val token = accessToken ?: return
        connectTo("/ws", token, isFallback = false)
    }

    private fun connectTo(path: String, token: String, isFallback: Boolean) {
        val wsBase = instanceBase
            .replaceFirst("https://", "wss://")
            .replaceFirst("http://", "ws://")
        val request = Request.Builder()
            .url(wsBase + path + "?accessToken=" + token)
            .header("User-Agent", USER_AGENT)
            .build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                wsLive = true
                onState("live")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val msg = parse(text) ?: return
                if (seenIds.add(msg.id)) onMessage(msg)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (closed) return
                if (!isFallback) {
                    // The /ws door didn't open; older instances serve the
                    // socket at /api/chat. Try that before giving up.
                    connectTo("/api/chat", token, isFallback = true)
                } else if (response?.code == 200) {
                    // The legacy door answered with the history JSON
                    // instead of an upgrade. Poll that endpoint.
                    startPolling()
                } else {
                    val code = response?.code
                    onState(
                        "error:socket failed" +
                            (if (code != null) " (HTTP $code)" else "") +
                            (t.message?.let { ": $it" } ?: ""),
                    )
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!closed && code != 1000) {
                    onState("error:socket closed ($code $reason)")
                }
            }
        })
    }

    /** Poll the history endpoint for new messages, by id. */
    private fun startPolling() {
        if (polling || closed) return
        polling = true
        onState("polling")
        val scope = chatScope ?: return
        scope.launch(Dispatchers.IO) {
            while (!closed && !wsLive) {
                delay(POLL_INTERVAL_MS)
                if (closed || wsLive) break
                try {
                    fetchHistory()
                } catch (e: Exception) {
                    // A refused history read usually means the token
                    // died; re-register once and keep going.
                    runCatching { accessToken = register() }
                }
            }
        }
    }

    private fun parse(text: String): ChatMessage? {
        return try {
            parseObject(JSONObject(text))
        } catch (e: Exception) {
            null
        }
    }

    private fun parseObject(obj: JSONObject): ChatMessage? {
        return try {
            if (obj.optString("type") != "CHAT") return null
            val user = obj.optJSONObject("user") ?: return null
            val plain = Html.fromHtml(obj.optString("body"), Html.FROM_HTML_MODE_COMPACT)
                .toString().trim()
            if (plain.isEmpty()) return null
            ChatMessage(
                id = obj.optString("id"),
                userName = user.optString("displayName", "unknown"),
                bodyText = plain,
                timestamp = obj.optString("timestamp"),
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Send a chat message. Only possible on a live WebSocket — instances
     * whose proxies ate the upgrade (we are polling their history instead)
     * offer no way to post, and this returns false there.
     */
    fun send(text: String): Boolean {
        val s = socket ?: return false
        if (!wsLive || closed) return false
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        return try {
            val payload = JSONObject().put("type", "CHAT").put("body", trimmed).toString()
            s.send(payload)
        } catch (e: Exception) {
            false
        }
    }

    fun close() {
        closed = true
        try {
            socket?.close(1000, "leaving")
        } catch (e: Exception) {
            // Already gone; nothing to do.
        }
        client.dispatcher.executorService.shutdown()
    }

    companion object {
        const val DEFAULT_DISPLAY_NAME = "directory-viewer"
        const val POLL_INTERVAL_MS = 12_000L

        // Present as a browser, like the web player does; some instance
        // hosts run bot rules that refuse library-default user agents.
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"
    }
}
