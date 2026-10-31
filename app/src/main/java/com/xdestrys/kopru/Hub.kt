package com.xdestrys.kopru

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface

/** Paylaşılan/gelen bir dosya. [incoming]=true → bilgisayardan geldi. */
data class Item(
    val id: String,
    val name: String,
    val uri: String,
    val size: Long,
    val incoming: Boolean,
    val time: Long,
    val downloads: Int = 0,
)

/** Metin notu (pano). [fromPc]=true → bilgisayardan yazıldı. */
data class Note(val id: String, val text: String, val fromPc: Boolean, val time: Long)

/** Süren bir aktarım. [incoming]=true → bilgisayardan telefona. */
data class Transfer(
    val id: String,
    val name: String,
    val total: Long,
    val done: Long,
    val incoming: Boolean,
    val startedAt: Long,
) {
    val fraction get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
    /** bayt/sn */
    val speed: Double get() {
        val sec = (System.currentTimeMillis() - startedAt) / 1000.0
        return if (sec > 0.2) done / sec else 0.0
    }
    val etaSec: Long get() = speed.takeIf { it > 0 }?.let { ((total - done) / it).toLong() } ?: -1
}

/** Uygulama ile sunucu arasındaki ortak durum: dosyalar, notlar, aktarımlar, adres. */
object Hub {
    const val PORT = 8080
    const val FOLDER = "Köprü"

    private val _items = MutableStateFlow<List<Item>>(emptyList())
    val items: StateFlow<List<Item>> = _items

    private val _notes = MutableStateFlow<List<Note>>(emptyList())
    val notes: StateFlow<List<Note>> = _notes

    private val _transfers = MutableStateFlow<Map<String, Transfer>>(emptyMap())
    val transfers: StateFlow<Map<String, Transfer>> = _transfers

    private val _address = MutableStateFlow<String?>(null)
    val address: StateFlow<String?> = _address

    private lateinit var app: Context
    private val itemsFile by lazy { File(app.filesDir, "items.json") }
    private val notesFile by lazy { File(app.filesDir, "notes.json") }

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        _items.value = loadItems()
        _notes.value = loadNotes()
    }

    fun setAddress(a: String?) { _address.value = a }

    fun find(id: String): Item? = _items.value.firstOrNull { it.id == id }

    // ── Dosyalar ──────────────────────────────────────────────

    /** Telefondan seçilen dosyaları paylaşım listesine ekler. */
    fun share(uris: List<Uri>) {
        val added = uris.mapNotNull { uri ->
            runCatching {
                app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val (name, size) = describe(uri) ?: return@mapNotNull null
            Item(newId(), name, uri.toString(), size, incoming = false, time = System.currentTimeMillis())
        }
        if (added.isNotEmpty()) updateItems { added + it }
    }

    /** Bilgisayardan gelen akışı İndirilenler/Köprü klasörüne yazar. */
    fun receive(name: String, length: Long, input: InputStream): Item {
        val safe = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "dosya" }
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, safe)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = app.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Dosya oluşturulamadı")
        val tid = newId()
        try {
            resolver.openOutputStream(uri)!!.use { out ->
                pump(input, out, length, Transfer(tid, safe, length, 0, incoming = true, startedAt = System.currentTimeMillis()))
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        } catch (e: Throwable) {
            resolver.delete(uri, null, null)
            throw e
        } finally {
            _transfers.update { it - tid }
        }
        val finalName = describe(uri)?.first ?: safe
        val item = Item(newId(), finalName, uri.toString(), length, incoming = true, time = System.currentTimeMillis())
        updateItems { listOf(item) + it }
        return item
    }

    /** Dosyayı bilgisayara akıtır; ilerlemeyi telefonda gösterir. */
    fun serve(item: Item, out: OutputStream) {
        val src = open(item) ?: error("Dosya açılamadı")
        val tid = newId()
        try {
            src.use { pump(it, out, item.size, Transfer(tid, item.name, item.size, 0, incoming = false, startedAt = System.currentTimeMillis())) }
            updateItems { list -> list.map { if (it.id == item.id) it.copy(downloads = it.downloads + 1) else it } }
        } finally {
            _transfers.update { it - tid }
        }
    }

    /** Kopyalar; ilerlemeyi ~5 kez/sn yayınlar. [length]<0 → akış sonuna kadar. */
    /** Bir aktarımı başlatır, [block] bitince listeden kaldırır (bulut yüklemeleri için). */
    fun track(name: String, size: Long, incoming: Boolean, block: (Transfer) -> Unit) {
        val t = Transfer(newId(), name, size, 0, incoming, System.currentTimeMillis())
        try { block(t) } finally { _transfers.update { it - t.id } }
    }

    fun pump(input: InputStream, out: OutputStream, length: Long, start: Transfer) {
        _transfers.update { it + (start.id to start) }
        val buf = ByteArray(256 * 1024)
        var done = 0L
        var lastEmit = 0L
        while (length < 0 || done < length) {
            val want = if (length < 0) buf.size else minOf(buf.size.toLong(), length - done).toInt()
            val n = input.read(buf, 0, want)
            if (n < 0) { if (length < 0) break else error("Bağlantı kesildi") }
            out.write(buf, 0, n)
            done += n
            val now = System.currentTimeMillis()
            if (now - lastEmit > 200) {
                lastEmit = now
                _transfers.update { it + (start.id to start.copy(done = done)) }
            }
        }
        out.flush()
    }

    fun open(item: Item): InputStream? =
        runCatching { app.contentResolver.openInputStream(Uri.parse(item.uri)) }.getOrNull()

    fun mime(item: Item): String =
        app.contentResolver.getType(Uri.parse(item.uri)) ?: "application/octet-stream"

    /** Listeden kaldırır; [fromDisk] ise gelen dosyayı telefondan da siler. */
    fun remove(id: String, fromDisk: Boolean = false) {
        val item = find(id) ?: return
        if (fromDisk && item.incoming) runCatching { app.contentResolver.delete(Uri.parse(item.uri), null, null) }
        if (!item.incoming) runCatching {
            app.contentResolver.releasePersistableUriPermission(Uri.parse(item.uri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        updateItems { list -> list.filterNot { it.id == id } }
    }

    fun clearAll() = _items.value.forEach { remove(it.id) }

    // ── Notlar ────────────────────────────────────────────────

    fun addNote(text: String, fromPc: Boolean): Note? {
        val t = text.trim().take(100_000)
        if (t.isEmpty()) return null
        val note = Note(newId(), t, fromPc, System.currentTimeMillis())
        updateNotes { listOf(note) + it }
        return note
    }

    fun removeNote(id: String) = updateNotes { list -> list.filterNot { it.id == id } }

    // ── Ağ ───────────────────────────────────────────────────

    fun localIp(): String? = NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }
        .filterIsInstance<Inet4Address>()
        .map { it.hostAddress!! }
        // Wi‑Fi ve hotspot adresleri önce gelsin.
        .sortedBy { if (it.startsWith("192.168.")) 0 else if (it.startsWith("10.") || it.startsWith("172.")) 1 else 2 }
        .firstOrNull()

    // ── İç işler ─────────────────────────────────────────────

    private fun describe(uri: Uri): Pair<String, Long>? = runCatching {
        app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { c ->
                if (!c.moveToFirst()) null
                else (c.getString(0) ?: "dosya") to (if (c.isNull(1)) -1L else c.getLong(1))
            }
    }.getOrNull()

    private fun newId() = java.lang.Long.toString(System.nanoTime(), 36)

    @Synchronized
    private fun updateItems(f: (List<Item>) -> List<Item>) {
        _items.value = f(_items.value)
        val arr = JSONArray()
        _items.value.forEach {
            arr.put(JSONObject().put("id", it.id).put("name", it.name).put("uri", it.uri)
                .put("size", it.size).put("in", it.incoming).put("t", it.time).put("dl", it.downloads))
        }
        itemsFile.writeText(arr.toString())
    }

    @Synchronized
    private fun updateNotes(f: (List<Note>) -> List<Note>) {
        _notes.value = f(_notes.value)
        val arr = JSONArray()
        _notes.value.forEach {
            arr.put(JSONObject().put("id", it.id).put("text", it.text).put("pc", it.fromPc).put("t", it.time))
        }
        notesFile.writeText(arr.toString())
    }

    private fun loadItems(): List<Item> = runCatching {
        val arr = JSONArray(itemsFile.readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Item(o.getString("id"), o.getString("name"), o.getString("uri"), o.getLong("size"),
                o.getBoolean("in"), o.getLong("t"), o.optInt("dl"))
        }
    }.getOrDefault(emptyList())

    private fun loadNotes(): List<Note> = runCatching {
        val arr = JSONArray(notesFile.readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Note(o.getString("id"), o.getString("text"), o.getBoolean("pc"), o.getLong("t"))
        }
    }.getOrDefault(emptyList())
}
