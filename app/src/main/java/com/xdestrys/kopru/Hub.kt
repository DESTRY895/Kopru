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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
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
)

/** Uygulama ile sunucu arasındaki tek ortak durum: dosya listesi + sunucu adresi. */
object Hub {
    const val PORT = 8080
    const val FOLDER = "Köprü"

    private val _items = MutableStateFlow<List<Item>>(emptyList())
    val items: StateFlow<List<Item>> = _items

    private val _address = MutableStateFlow<String?>(null)
    val address: StateFlow<String?> = _address

    private lateinit var app: Context
    private val db by lazy { File(app.filesDir, "items.json") }

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        _items.value = load()
    }

    fun setAddress(a: String?) { _address.value = a }

    fun find(id: String): Item? = _items.value.firstOrNull { it.id == id }

    /** Telefondan seçilen dosyaları paylaşım listesine ekler. */
    fun share(uris: List<Uri>) {
        val added = uris.mapNotNull { uri ->
            runCatching {
                app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val (name, size) = describe(uri) ?: return@mapNotNull null
            Item(newId(), name, uri.toString(), size, incoming = false, time = System.currentTimeMillis())
        }
        if (added.isNotEmpty()) update { added + it }
    }

    /** Bilgisayardan gelen akışı İndirilenler/Köprü klasörüne yazar. */
    fun receive(name: String, length: Long, input: InputStream, onProgress: (Long) -> Unit = {}): Item {
        val safe = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "dosya" }
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, safe)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = app.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Dosya oluşturulamadı")
        try {
            resolver.openOutputStream(uri)!!.use { out ->
                val buf = ByteArray(256 * 1024)
                var left = length
                var done = 0L
                while (left > 0) {
                    val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n < 0) error("Bağlantı kesildi")
                    out.write(buf, 0, n)
                    left -= n; done += n
                    onProgress(done)
                }
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        } catch (e: Throwable) {
            resolver.delete(uri, null, null)
            throw e
        }
        val finalName = describe(uri)?.first ?: safe
        val item = Item(newId(), finalName, uri.toString(), length, incoming = true, time = System.currentTimeMillis())
        update { listOf(item) + it }
        return item
    }

    fun open(item: Item): InputStream? =
        runCatching { app.contentResolver.openInputStream(Uri.parse(item.uri)) }.getOrNull()

    fun mime(item: Item): String =
        app.contentResolver.getType(Uri.parse(item.uri)) ?: "application/octet-stream"

    /** Listeden kaldırır; gelen dosyalar diskte kalır. */
    fun remove(id: String) = update { list -> list.filterNot { it.id == id } }

    fun localIp(): String? = NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }
        .filterIsInstance<Inet4Address>()
        .map { it.hostAddress!! }
        // Wi‑Fi ve hotspot adresleri önce gelsin.
        .sortedBy { if (it.startsWith("192.168.")) 0 else if (it.startsWith("10.") || it.startsWith("172.")) 1 else 2 }
        .firstOrNull()

    private fun describe(uri: Uri): Pair<String, Long>? = runCatching {
        app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { c ->
                if (!c.moveToFirst()) null
                else (c.getString(0) ?: "dosya") to (if (c.isNull(1)) -1L else c.getLong(1))
            }
    }.getOrNull()

    private fun newId() = java.lang.Long.toString(System.nanoTime(), 36)

    @Synchronized
    private fun update(f: (List<Item>) -> List<Item>) {
        _items.value = f(_items.value)
        save(_items.value)
    }

    private fun load(): List<Item> = runCatching {
        val arr = JSONArray(db.readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Item(o.getString("id"), o.getString("name"), o.getString("uri"), o.getLong("size"),
                o.getBoolean("in"), o.getLong("t"))
        }
    }.getOrDefault(emptyList())

    private fun save(list: List<Item>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("id", it.id).put("name", it.name).put("uri", it.uri)
                .put("size", it.size).put("in", it.incoming).put("t", it.time))
        }
        db.writeText(arr.toString())
    }
}
