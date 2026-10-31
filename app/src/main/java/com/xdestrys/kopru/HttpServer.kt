package com.xdestrys.kopru

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.Executors

/**
 * Bağımlılıksız, akışlı küçük HTTP sunucusu. Yollar:
 *  GET    /                 → web arayüzü (assets/index.html)
 *  GET    /api/state        → dosyalar + notlar + süren aktarımlar (JSON)
 *  GET    /dl/{id}          → dosyayı indir (?inline=1 → tarayıcıda göster)
 *  PUT    /up?name=…        → gövdeyi telefona kaydet (Content-Length zorunlu)
 *  DELETE /api/item/{id}    → listeden kaldır (?disk=1 → telefondan da sil)
 *  POST   /api/note         → gövdeyi not olarak ekle
 *  DELETE /api/note/{id}    → notu sil
 * Ayrıca UDP [DISCOVERY_PORT]: "KOPRU?" gelen her pakete "KOPRU ip:port" ile yanıt (masaüstü uygulaması bulsun diye).
 */
class HttpServer(private val context: Context, private val port: Int) {
    private var socket: ServerSocket? = null
    private var udp: DatagramSocket? = null
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
        pool.execute { discovery() }
    }

    fun stop() {
        runCatching { socket?.close() }
        runCatching { udp?.close() }
        pool.shutdownNow()
    }

    private fun discovery() = runCatching {
        val u = DatagramSocket(DISCOVERY_PORT).also { udp = it }
        val buf = ByteArray(64)
        while (!u.isClosed) {
            val p = DatagramPacket(buf, buf.size)
            u.receive(p)
            if (String(p.data, 0, p.length).startsWith("KOPRU?")) {
                val ip = Hub.localIp() ?: continue
                val reply = "KOPRU $ip:$port ${android.os.Build.MODEL}".toByteArray()
                u.send(DatagramPacket(reply, reply.size, p.socketAddress))
            }
        }
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
            val len = headers["content-length"]?.toLongOrNull()

            when {
                method == "GET" && path == "/" -> {
                    val html = context.assets.open("index.html").readBytes()
                    send(out, 200, "text/html; charset=utf-8", html)
                }
                method == "GET" && path == "/api/state" -> sendJson(out, state())
                method == "GET" && path.startsWith("/dl/") -> {
                    val item = Hub.find(path.removePrefix("/dl/"))
                        ?: return send(out, 404, "text/plain", "Bulunamadı".toByteArray())
                    val enc = URLEncoder.encode(item.name, "UTF-8").replace("+", "%20")
                    val disposition = if (query["inline"] == "1") "inline" else "attachment"
                    val head = buildString {
                        append("HTTP/1.1 200 OK\r\n")
                        append("Content-Type: ${Hub.mime(item)}\r\n")
                        if (item.size >= 0) append("Content-Length: ${item.size}\r\n")
                        append("Content-Disposition: $disposition; filename*=UTF-8''$enc\r\n")
                        append("Cache-Control: max-age=3600\r\n")
                        append("Connection: close\r\n\r\n")
                    }
                    out.write(head.toByteArray())
                    // Önizleme küçük resimleri telefonda "aktarım" diye görünmesin.
                    if (disposition == "inline") Hub.open(item)?.use { it.copyTo(out, 256 * 1024) } else Hub.serve(item, out)
                    out.flush()
                }
                method == "PUT" && path == "/up" -> {
                    if (len == null) return send(out, 411, "text/plain", "Content-Length gerekli".toByteArray())
                    val item = Hub.receive(query["name"] ?: "dosya", len, input)
                    sendJson(out, JSONObject().put("id", item.id))
                }
                method == "DELETE" && path.startsWith("/api/item/") -> {
                    Hub.remove(path.removePrefix("/api/item/"), fromDisk = query["disk"] == "1")
                    sendJson(out, JSONObject().put("ok", true))
                }
                method == "POST" && path == "/api/note" -> {
                    if (len == null || len > 1_000_000) return send(out, 411, "text/plain", "Geçersiz uzunluk".toByteArray())
                    val text = String(readExactly(input, len.toInt()), Charsets.UTF_8)
                    Hub.addNote(text, fromPc = true)
                    sendJson(out, JSONObject().put("ok", true))
                }
                method == "DELETE" && path.startsWith("/api/note/") -> {
                    Hub.removeNote(path.removePrefix("/api/note/"))
                    sendJson(out, JSONObject().put("ok", true))
                }
                else -> send(out, 404, "text/plain", "Bulunamadı".toByteArray())
            }
        }
    }

    private fun state(): JSONObject {
        val items = JSONArray()
        Hub.items.value.forEach {
            items.put(JSONObject().put("id", it.id).put("name", it.name).put("size", it.size)
                .put("in", it.incoming).put("t", it.time).put("dl", it.downloads).put("mime", Hub.mime(it)))
        }
        val notes = JSONArray()
        Hub.notes.value.forEach {
            notes.put(JSONObject().put("id", it.id).put("text", it.text).put("pc", it.fromPc).put("t", it.time))
        }
        val transfers = JSONArray()
        Hub.transfers.value.values.forEach {
            transfers.put(JSONObject().put("id", it.id).put("name", it.name).put("total", it.total)
                .put("done", it.done).put("in", it.incoming).put("speed", it.speed))
        }
        return JSONObject().put("items", items).put("notes", notes).put("transfers", transfers)
            .put("device", android.os.Build.MODEL)
    }

    private fun readExactly(input: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(buf, off, n - off)
            if (r < 0) break
            off += r
        }
        return buf.copyOf(off)
    }

    /** İstek satırı + başlıkları boş satıra kadar okur; gövde akışta kalır. */
    private fun readHead(input: InputStream): List<String>? {
        val lines = mutableListOf<String>()
        val raw = java.io.ByteArrayOutputStream()
        var total = 0
        while (true) {
            val b = input.read()
            if (b < 0) return null
            if (++total > 16 * 1024) return null
            if (b == '\n'.code) {
                val line = raw.toString("UTF-8").trimEnd('\r')
                if (line.isEmpty()) return if (lines.isEmpty()) null else lines
                lines += line
                raw.reset()
            } else raw.write(b)
        }
    }

    private fun sendJson(out: OutputStream, o: JSONObject) =
        send(out, 200, "application/json; charset=utf-8", o.toString().toByteArray())

    private fun send(out: OutputStream, code: Int, type: String, body: ByteArray) {
        val reason = mapOf(200 to "OK", 404 to "Not Found", 411 to "Length Required")[code] ?: "OK"
        out.write("HTTP/1.1 $code $reason\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(body)
        out.flush()
    }

    companion object {
        const val DISCOVERY_PORT = 8081
    }
}
