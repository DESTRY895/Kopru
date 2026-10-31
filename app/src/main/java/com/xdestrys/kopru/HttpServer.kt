package com.xdestrys.kopru

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.Executors

/**
 * Bağımlılıksız, akışlı küçük HTTP sunucusu. Yollar:
 *  GET  /              → web arayüzü (assets/index.html)
 *  GET  /api/list      → dosya listesi (JSON)
 *  GET  /dl/{id}       → dosyayı indir
 *  PUT  /up?name=…     → gövdeyi telefona kaydet (Content-Length zorunlu)
 */
class HttpServer(private val context: Context, private val port: Int) {
    private var socket: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()

    fun start() {
        val s = ServerSocket(port)
        socket = s
        pool.execute {
            while (!s.isClosed) {
                val client = runCatching { s.accept() }.getOrNull() ?: break
                pool.execute { handle(client) }
            }
        }
    }

    fun stop() {
        runCatching { socket?.close() }
        pool.shutdownNow()
    }

    private fun handle(client: Socket): Unit = client.use { sock ->
        runCatching {
            val input = BufferedInputStream(sock.getInputStream(), 64 * 1024)
            val out = sock.getOutputStream()
            val lines = readHead(input) ?: return
            val (method, target) = lines[0].split(" ").let { it[0] to it.getOrElse(1) { "/" } }
            val headers = lines.drop(1).mapNotNull { l ->
                val i = l.indexOf(':'); if (i < 0) null else l.substring(0, i).trim().lowercase() to l.substring(i + 1).trim()
            }.toMap()
            val path = target.substringBefore('?')
            val query = target.substringAfter('?', "").split('&').filter { it.isNotEmpty() }.associate {
                URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
            }

            when {
                method == "GET" && path == "/" -> {
                    val html = context.assets.open("index.html").readBytes()
                    send(out, 200, "text/html; charset=utf-8", html)
                }
                method == "GET" && path == "/api/list" -> {
                    val arr = JSONArray()
                    Hub.items.value.forEach {
                        arr.put(JSONObject().put("id", it.id).put("name", it.name).put("size", it.size)
                            .put("in", it.incoming).put("t", it.time))
                    }
                    send(out, 200, "application/json; charset=utf-8", arr.toString().toByteArray())
                }
                method == "GET" && path.startsWith("/dl/") -> {
                    val item = Hub.find(path.removePrefix("/dl/"))
                    val stream = item?.let { Hub.open(it) }
                    if (item == null || stream == null) return send(out, 404, "text/plain", "Bulunamadı".toByteArray())
                    stream.use { src ->
                        val enc = URLEncoder.encode(item.name, "UTF-8").replace("+", "%20")
                        val head = buildString {
                            append("HTTP/1.1 200 OK\r\n")
                            append("Content-Type: ${Hub.mime(item)}\r\n")
                            if (item.size >= 0) append("Content-Length: ${item.size}\r\n")
                            append("Content-Disposition: attachment; filename*=UTF-8''$enc\r\n")
                            append("Connection: close\r\n\r\n")
                        }
                        out.write(head.toByteArray())
                        src.copyTo(out, 256 * 1024)
                        out.flush()
                    }
                }
                method == "PUT" && path == "/up" -> {
                    val len = headers["content-length"]?.toLongOrNull()
                        ?: return send(out, 411, "text/plain", "Content-Length gerekli".toByteArray())
                    val item = Hub.receive(query["name"] ?: "dosya", len, input)
                    send(out, 200, "application/json", JSONObject().put("id", item.id).toString().toByteArray())
                }
                method == "OPTIONS" -> send(out, 204, "text/plain", ByteArray(0))
                else -> send(out, 404, "text/plain", "Bulunamadı".toByteArray())
            }
        }
    }

    /** İstek satırı + başlıkları boş satıra kadar okur; gövde akışta kalır. */
    private fun readHead(input: InputStream): List<String>? {
        val lines = mutableListOf<String>()
        val sb = StringBuilder()
        var total = 0
        while (true) {
            val b = input.read()
            if (b < 0) return null
            if (++total > 16 * 1024) return null
            if (b == '\n'.code) {
                val line = sb.toString().trimEnd('\r')
                if (line.isEmpty()) return if (lines.isEmpty()) null else lines
                lines += line
                sb.clear()
            } else sb.append(b.toChar())
        }
    }

    private fun send(out: OutputStream, code: Int, type: String, body: ByteArray) {
        val reason = mapOf(200 to "OK", 204 to "No Content", 404 to "Not Found", 411 to "Length Required")[code] ?: "OK"
        out.write("HTTP/1.1 $code $reason\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(body)
        out.flush()
    }
}
