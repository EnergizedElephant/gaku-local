package ca.fuwafuwa.gaku.Llm

import android.os.Handler
import android.os.Looper
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.MalformedURLException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

class LlmMessage(val role: String, val content: String)

/**
 * Talks to an Ollama server (lan build only) through its native API, wherever it is: on the local
 * network, over Tailscale or hosted. [server] is as typed on the start screen: "192.168.1.10",
 * "192.168.1.10:11434", "http://host:port" or "https://host/path". A non-blank [apiKey] is sent as a
 * bearer token, for servers that need one.
 *
 * Each request runs on its own thread and reports on the main thread, until it ends or is cancelled.
 * Nothing sent or received is logged.
 */
class OllamaClient(private val server: String, private val apiKey: String)
{
    interface ModelsListener
    {
        /** [unencrypted]: the server is outside the local network and reached over plain http. */
        fun onModels(models: List<String>, unencrypted: Boolean)
        fun onError(message: String)
    }

    interface ChatListener
    {
        /** The model is thinking before it answers (reasoning models); called once, before any text. */
        fun onThinking()
        fun onText(text: String)
        fun onDone()
        fun onError(message: String)
    }

    /** A request in progress. After [cancel], its listener hears nothing more. */
    class Call internal constructor()
    {
        @Volatile
        internal var cancelled = false
        @Volatile
        internal var connection: HttpURLConnection? = null
        internal var unencrypted = false

        fun cancel()
        {
            cancelled = true
            // Unblocks a read waiting for the next token. Off the main thread, as it closes a socket.
            connection?.let { Thread { it.disconnect() }.start() }
        }

        internal fun post(action: () -> Unit)
        {
            mainHandler.post { if (!cancelled) action() }
        }
    }

    fun listModels(listener: ModelsListener): Call
    {
        return start(listener::onError) { call ->
            val connection = open(call, "/api/tags", LIST_TIMEOUT_MILLIS)
            val json = JsonParser.parseString(readAll(connection)).asJsonObject
            val models = (json.getAsJsonArray("models") ?: throw JsonParseException("No models")).map { it.asJsonObject.get("name").asString }.sorted()
            call.post { listener.onModels(models, call.unencrypted) }
        }
    }

    fun chat(model: String, messages: List<LlmMessage>, listener: ChatListener): Call
    {
        return start(listener::onError) { call ->
            val body = JsonObject()
            body.addProperty("model", model)
            body.addProperty("stream", true)
            val jsonMessages = JsonArray()
            for (message in messages)
            {
                val jsonMessage = JsonObject()
                jsonMessage.addProperty("role", message.role)
                jsonMessage.addProperty("content", message.content)
                jsonMessages.add(jsonMessage)
            }
            body.add("messages", jsonMessages)

            val connection = open(call, "/api/chat", CHAT_TIMEOUT_MILLIS, body.toString())
            var answering = false
            var thinking = false

            connection.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (line in lines)
                {
                    if (call.cancelled) break
                    val chunk = parseChatLine(line) ?: continue
                    if (chunk.error != null) throw ServerException(chunk.error)

                    if (chunk.content.isNotEmpty())
                    {
                        answering = true
                        call.post { listener.onText(chunk.content) }
                    }
                    else if (chunk.thinking && !answering && !thinking)
                    {
                        thinking = true
                        call.post { listener.onThinking() }
                    }

                    if (chunk.done) break
                }
            }

            call.post { listener.onDone() }
        }
    }

    private fun start(onError: (String) -> Unit, request: (Call) -> Unit): Call
    {
        val call = Call()
        Thread {
            try
            {
                request(call)
            }
            catch (e: Exception)
            {
                if (!call.cancelled) call.post { onError(describe(e)) }
            }
            finally
            {
                call.connection?.disconnect()
            }
        }.start()
        return call
    }

    /** Connects to [path] under the server's URL and sends [body] if given. */
    private fun open(call: Call, path: String, readTimeoutMillis: Int, body: String? = null): HttpURLConnection
    {
        val url = parseServer(server) ?: throw ServerException("\"$server\" isn't a server address")
        call.unencrypted = isUnencryptedOutside(url, InetAddress.getAllByName(url.host))

        val connection = URL(url.protocol, url.host, url.port, url.path + path).openConnection() as HttpURLConnection
        call.connection = connection
        if (call.cancelled) throw IOException("Cancelled")

        connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
        connection.readTimeout = readTimeoutMillis
        if (apiKey.isNotBlank()) connection.setRequestProperty("Authorization", "Bearer ${apiKey.trim()}")
        if (body != null)
        {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }

        if (connection.responseCode == 401 || connection.responseCode == 403)
        {
            throw ServerException(if (apiKey.isBlank()) "${url.host} needs an API key" else "${url.host} didn't accept the API key")
        }
        if (connection.responseCode !in 200..299)
        {
            // Ollama explains errors as {"error": "..."}
            val error = connection.errorStream?.let { stream ->
                try { JsonParser.parseString(stream.bufferedReader(Charsets.UTF_8).readText()).asJsonObject.get("error")?.asString }
                catch (e: Exception) { null }
            }
            throw ServerException(error ?: "The server answered ${connection.responseCode} ${connection.responseMessage ?: ""}".trim())
        }

        return connection
    }

    private fun readAll(connection: HttpURLConnection): String
    {
        return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    private fun describe(e: Exception): String
    {
        val where = parseServer(server)?.let { "${it.host}:${it.port}" } ?: server
        return when (e)
        {
            is ServerException -> e.message!!
            is UnknownHostException -> "Can't find $where"
            is ConnectException -> "Can't reach $where. Is Ollama running, and listening on the network (OLLAMA_HOST=0.0.0.0)?"
            is SocketTimeoutException -> "$where didn't answer in time"
            is JsonParseException, is IllegalStateException -> "$where didn't answer like an Ollama server"
            else -> "Can't talk to $where: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    private class ServerException(message: String) : Exception(message)

    /** One line of a streamed /api/chat reply. */
    internal class ChatChunk(val content: String, val thinking: Boolean, val done: Boolean, val error: String?)

    companion object
    {
        const val DEFAULT_PORT = 11434

        private const val CONNECT_TIMEOUT_MILLIS = 3_000
        private const val LIST_TIMEOUT_MILLIS = 10_000
        // The first request may wait for the model to load, and reasoning models pause between tokens
        private const val CHAT_TIMEOUT_MILLIS = 120_000

        private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

        /**
         * The server's URL from what was typed; null if it isn't one. No scheme means http. With no port,
         * http gets Ollama's 11434 and https gets 443, where a hosted server listens. A path is kept, for
         * a server behind a proxy, except a trailing /api, which each request adds.
         */
        @JvmStatic
        fun parseServer(text: String): URL?
        {
            val trimmed = text.trim().trimEnd('/')
            if (trimmed.isEmpty()) return null

            return try
            {
                val url = URL(if ("://" in trimmed) trimmed else "http://$trimmed")
                if (url.protocol != "http" && url.protocol != "https") return null
                if (url.host.isNullOrEmpty()) return null
                val port = when
                {
                    url.port != -1 -> url.port
                    url.protocol == "https" -> 443
                    else -> DEFAULT_PORT
                }
                URL(url.protocol, url.host, port, url.path.removeSuffix("/api").trimEnd('/'))
            }
            catch (e: MalformedURLException)
            {
                null
            }
        }

        /** Whether text sent to [url] would cross the internet unencrypted: plain http, and not all local. */
        internal fun isUnencryptedOutside(url: URL, addresses: Array<InetAddress>): Boolean
        {
            return url.protocol == "http" && !addresses.all { isLocalAddress(it) }
        }

        /**
         * Loopback, private (10/8, 172.16/12, 192.168/16), link-local, IPv6 unique local (fc00::/7), or
         * carrier-grade NAT (100.64/10, which Tailscale uses). Only used to warn about plain http.
         */
        internal fun isLocalAddress(address: InetAddress): Boolean
        {
            if (address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress) return true

            val bytes = address.address
            return when (bytes.size)
            {
                4 -> (bytes[0].toInt() and 0xff) == 100 && (bytes[1].toInt() and 0xc0) == 64
                16 -> (bytes[0].toInt() and 0xfe) == 0xfc
                else -> false
            }
        }

        /** Null for a blank line. Throws JsonParseException or IllegalStateException if it isn't JSON from Ollama. */
        internal fun parseChatLine(line: String): ChatChunk?
        {
            if (line.isBlank()) return null

            val json = JsonParser.parseString(line).asJsonObject
            json.get("error")?.let { return ChatChunk("", false, true, it.asString) }

            val message = json.getAsJsonObject("message")
            val content = message?.get("content")?.asString ?: ""
            val thinking = !message?.get("thinking")?.asString.isNullOrEmpty()
            return ChatChunk(content, thinking, json.get("done")?.asBoolean ?: false, null)
        }
    }
}
