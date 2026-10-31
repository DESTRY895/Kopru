package com.xdestrys.kopru

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Depodaki dosya. [fromPc]=true → bilgisayardan yüklendi. */
data class CloudFile(val id: Long, val name: String, val size: Long, val fromPc: Boolean, val time: Long, val downloads: Int)

/**
 * Farklı ağlardayken ortak depo: gizli bir GitHub deposundaki "depo" sürümü.
 * Dosyalar sürüm eki olarak durur (ad: "{zaman}-{P|T}-{ascii ad}", gerçek ad etikette),
 * notlar depodaki notes.json dosyasında. Biçim masaüstü uygulamasıyla aynıdır.
 */
object Cloud {
    const val DEFAULT_REPO = "DESTRY895/Kopru-depo"
    private const val TAG = "depo"
    private const val API = "https://api.github.com"

    private val _files = MutableStateFlow<List<CloudFile>>(emptyList())
    val files: StateFlow<List<CloudFile>> = _files
    private val _notes = MutableStateFlow<List<Note>>(emptyList())
    val notes: StateFlow<List<Note>> = _notes
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private lateinit var app: Context
    private val prefs get() = app.getSharedPreferences("cloud", Context.MODE_PRIVATE)
    private val _configured = MutableStateFlow(false)
    val configured: StateFlow<Boolean> = _configured

    val repo get() = prefs.getString("repo", DEFAULT_REPO)!!
    private val token get() = prefs.getString("token", "")!!
    private var releaseId: Long? = null

    fun init(context: Context) {
        app = context.applicationContext
        _configured.value = token.isNotBlank()
    }

    fun configure(repo: String, token: String) {
        prefs.edit().putString("repo", repo.trim().ifBlank { DEFAULT_REPO }).putString("token", token.trim()).apply()
        releaseId = null
        _configured.value = token.isNotBlank()
    }

    fun forget() = configure(repo, "")

    // ── Okuma ────────────────────────────────────────────────

    suspend fun refresh() = io {
        val rel = JSONObject(call("GET", "$API/repos/$repo/releases/tags/$TAG"))
        releaseId = rel.getLong("id")
        val assets = rel.getJSONArray("assets")
        _files.value = (0 until assets.length()).map { parse(assets.getJSONObject(it)) }.sortedByDescending { it.time }
        _notes.value = readNotes().first
    }

    private fun parse(a: JSONObject): CloudFile {
        val parts = a.getString("name").split('-', limit = 3)
        val label = a.optString("label").ifBlank { parts.getOrElse(2) { a.getString("name") } }
        return CloudFile(a.getLong("id"), label, a.getLong("size"), parts.getOrNull(1) == "P",
            parts[0].toLongOrNull() ?: System.currentTimeMillis(), a.optInt("download_count"))
    }

    // ── Dosyalar ─────────────────────────────────────────────

    /** Telefondan seçilen dosyayı depoya yükler (ilerleme aktarım kartında görünür). */
    suspend fun upload(uri: Uri) = io {
        val (name, size) = app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)!!
            .use { c -> c.moveToFirst(); (c.getString(0) ?: "dosya") to c.getLong(1) }
        val id = releaseId ?: run { refresh(); releaseId!! }
        val ascii = name.replace(Regex("[^A-Za-z0-9._]"), "_").take(80)
        val asset = "${System.currentTimeMillis()}-T-$ascii"
        val url = "https://uploads.github.com/repos/$repo/releases/$id/assets?name=${enc(asset)}&label=${enc(name)}"
        val c = open("POST", url)
        c.setRequestProperty("Content-Type", "application/octet-stream")
        c.setFixedLengthStreamingMode(size)
        c.doOutput = true
        app.contentResolver.openInputStream(uri)!!.use { input ->
            c.outputStream.use { out -> Hub.track(name, size, incoming = false) { Hub.pump(input, out, size, it) } }
        }
        check(c.responseCode in 200..299) { "Yükleme başarısız (${c.responseCode})" }
        c.disconnect()
        refresh()
    }

    /** Depodaki dosyayı İndirilenler/Köprü klasörüne indirir. */
    suspend fun download(f: CloudFile) = io {
        val c = open("GET", "$API/repos/$repo/releases/assets/${f.id}")
        c.setRequestProperty("Accept", "application/octet-stream")
        c.instanceFollowRedirects = false
        val stream = if (c.responseCode in 300..399) {
            // İndirme adresi imzalıdır; token'ı başka sunucuya göndermeyelim.
            val loc = c.getHeaderField("Location"); c.disconnect()
            (URL(loc).openConnection() as HttpURLConnection).also { it.connectTimeout = 15000 }.inputStream
        } else c.inputStream
        stream.use { Hub.receive(f.name, f.size, it) }
    }

    suspend fun delete(f: CloudFile) = io {
        call("DELETE", "$API/repos/$repo/releases/assets/${f.id}")
        _files.value = _files.value.filterNot { it.id == f.id }
    }

    // ── Notlar ───────────────────────────────────────────────

    suspend fun addNote(text: String) = editNotes { listOf(Note(System.currentTimeMillis().toString(36), text.trim(), false, System.currentTimeMillis())) + it }

    suspend fun removeNote(id: String) = editNotes { list -> list.filterNot { it.id == id } }

    private suspend fun editNotes(f: (List<Note>) -> List<Note>) = io {
        repeat(3) { attempt ->
            val (list, sha) = readNotes()
            val next = f(list)
            val arr = JSONArray()
            next.forEach { arr.put(JSONObject().put("id", it.id).put("text", it.text).put("pc", it.fromPc).put("t", it.time)) }
            val body = JSONObject().put("message", "not").put("content", Base64.encodeToString(arr.toString().toByteArray(), Base64.NO_WRAP))
            if (sha != null) body.put("sha", sha)
            val ok = runCatching { call("PUT", "$API/repos/$repo/contents/notes.json", body.toString()) }.isSuccess
            if (ok) { _notes.value = next; return@io }
            if (attempt == 2) error("Not kaydedilemedi")
        }
    }

    private fun readNotes(): Pair<List<Note>, String?> {
        val raw = runCatching { call("GET", "$API/repos/$repo/contents/notes.json") }.getOrNull() ?: return emptyList<Note>() to null
        val o = JSONObject(raw)
        val arr = JSONArray(String(Base64.decode(o.getString("content"), Base64.DEFAULT)))
        val list = (0 until arr.length()).map { i ->
            val n = arr.getJSONObject(i)
            Note(n.getString("id"), n.getString("text"), n.getBoolean("pc"), n.getLong("t"))
        }
        return list to o.getString("sha")
    }

    // ── HTTP ─────────────────────────────────────────────────

    private fun open(method: String, url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = method
        connectTimeout = 15000
        readTimeout = 60000
        setRequestProperty("Authorization", "Bearer $token")
        setRequestProperty("Accept", "application/vnd.github+json")
        setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
    }

    private fun call(method: String, url: String, body: String? = null): String {
        val c = open(method, url)
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = c.responseCode
        val text = (if (code < 400) c.inputStream else c.errorStream)?.use { it.readBytes().decodeToString() } ?: ""
        c.disconnect()
        when (code) {
            401 -> error("Token geçersiz ya da süresi dolmuş")
            403, 404 -> if (method == "GET" && url.endsWith("notes.json")) error("yok") else error("Depoya erişim yok ($code) — token bu depoya izin veriyor mu?")
        }
        check(code < 400) { "GitHub hatası $code" }
        return text
    }

    private suspend fun <T> io(block: suspend () -> T): T? = withContext(Dispatchers.IO) {
        runCatching { block() }.onSuccess { _error.value = null }.onFailure { _error.value = it.message }.getOrNull()
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
