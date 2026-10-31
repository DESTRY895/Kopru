package com.xdestrys.kopru

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val Ground = Color(0xFF0D0E10)
private val Surface = Color(0xFF151619)
private val Chip = Color(0xFF23262B)
private val Line = Color(0xFF2C2F35)
private val Ink = Color(0xFFEDEEF0)
private val Muted = Color(0xFF8A8F98)
private val Accent = Color(0xFF4C8DFF)
private val Good = Color(0xFF3FB97A)
private val Bad = Color(0xFFE5534B)
private val Blue = Color(0xFF4C8DFF)
private val TR = Locale.forLanguageTag("tr-TR")

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Hub.init(this)
        Cloud.init(this)
        ServerService.start(this)
        handleShare(intent)
        setContent { App() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    /** "Paylaş → Köprü" ile gelen dosyaları (veya metni) listeye ekle. */
    private fun handleShare(intent: Intent?) {
        @Suppress("DEPRECATION")
        val uris = when (intent?.action) {
            Intent.ACTION_SEND -> listOfNotNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
            Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
            else -> emptyList()
        }
        if (uris.isNotEmpty()) { Hub.share(uris); Cloud.push(uris) }
        else if (intent?.action == Intent.ACTION_SEND) intent.getStringExtra(Intent.EXTRA_TEXT)?.let { Hub.addNote(it, fromPc = false); Cloud.pushNote(it) }
    }
}

private enum class Tab(val label: String) { All("Tümü"), In("Gelen"), Out("Giden"), Notes("Notlar") }

@Composable
private fun App() {
    val context = LocalContext.current
    val address by Hub.address.collectAsStateWithLifecycle()
    val items by Hub.items.collectAsStateWithLifecycle()
    val notes by Hub.notes.collectAsStateWithLifecycle()
    val transfers by Hub.transfers.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(Tab.All) }
    var query by rememberSaveable { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }

    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { Hub.share(it); Cloud.push(it) }
    val media = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { Hub.share(it); Cloud.push(it) }

    val shown = items
        .filter { when (tab) { Tab.In -> it.incoming; Tab.Out -> !it.incoming; else -> true } }
        .filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }
    val shownNotes = notes.filter { query.isBlank() || it.text.contains(query.trim(), ignoreCase = true) }

    MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Ground, surface = Surface)) {
        LazyColumn(
            Modifier.fillMaxSize().background(Ground).systemBarsPadding(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { Header(address) }
            item { AddressCard(address) }
            item { Stats(items, notes) }
            items(transfers.values.toList(), key = { "t" + it.id }) { TransferCard(it) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 4.dp)) {
                    Button(
                        onClick = { picker.launch(arrayOf("*/*")) },
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.White),
                    ) { Text("Dosya gönder", fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
                    OutlinedButton(
                        onClick = { media.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = RoundedCornerShape(10.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Line),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Ink),
                    ) { Text("Foto / Video", fontSize = 15.sp, fontWeight = FontWeight.Medium) }
                }
            }
            item { Tabs(tab, items, notes) { tab = it } }
            item { SearchBox(query) { query = it } }

            if (tab == Tab.Notes) {
                item { NoteComposer() }
                if (shownNotes.isEmpty()) item { Empty("Not yok. Buraya yazdığın metin bilgisayarda görünür; bilgisayardan yazılan da buraya düşer.") }
                items(shownNotes, key = { "n" + it.id }) { NoteCard(it) }
            } else {
                if (shown.isEmpty()) item {
                    Empty(if (query.isNotBlank()) "“$query” ile eşleşen dosya yok." else "Henüz dosya yok. Gönderdiklerin bilgisayarda indirilebilir; bilgisayardan yüklenenler İndirilenler/${Hub.FOLDER} klasörüne düşer.")
                }
                shown.groupBy { dayLabel(it.time) }.forEach { (day, group) ->
                    item(key = "d$day$tab") {
                        Text(day, color = Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                    }
                    items(group, key = { it.id }) { FileRow(it) }
                }
                if (items.isNotEmpty()) item {
                    TextButton(onClick = { confirmClear = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("Listeyi temizle", color = Muted, fontSize = 13.sp)
                    }
                }
            }
        }

        if (confirmClear) AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Liste temizlensin mi?") },
            text = { Text("Dosyalar telefondan silinmez, yalnızca listeden kalkar.") },
            confirmButton = { TextButton(onClick = { Hub.clearAll(); confirmClear = false }) { Text("Temizle", color = Bad) } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Vazgeç") } },
            containerColor = Surface,
        )
    }
}

@Composable
private fun Header(address: String?) {
    val context = LocalContext.current
    val on = address != null
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Köprü", color = Ink, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            val cloudOn by Cloud.configured.collectAsStateWithLifecycle()
            Text(if (cloudOn) "Wi‑Fi + internet eşitleme" else "Yalnızca Wi‑Fi", color = Muted, fontSize = 12.sp)
        }
        val pulse by rememberInfiniteTransition(label = "p").animateFloat(
            0.35f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "a",
        )
        Row(
            Modifier.clip(RoundedCornerShape(10.dp)).background(Surface).border(1.dp, Line, RoundedCornerShape(10.dp))
                .clickable { if (on) ServerService.stop(context) else ServerService.start(context) }
                .padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(if (on) Good.copy(alpha = pulse) else Muted))
            Spacer(Modifier.width(8.dp))
            Text(if (on) "Açık" else "Kapalı", color = if (on) Good else Muted, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun AddressCard(address: String?) {
    val context = LocalContext.current
    var showQr by rememberSaveable { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Surface).border(1.dp, Line, RoundedCornerShape(10.dp)).animateContentSize().padding(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        when {
            address == null -> Text("Sunucu kapalı — açmak için sağ üstteki düğmeye dokun", color = Muted, fontSize = 14.sp)
            address.isEmpty() -> Text("Wi‑Fi ağına bağlan (veya hotspot aç)", color = Muted, fontSize = 14.sp)
            else -> {
                Text("Wi‑Fi adresi · bilgisayardaki Köprü kendisi bulur", color = Muted, fontSize = 12.sp)
                Text(
                    address, color = Ink, fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable {
                        copy(context, "http://$address"); toast(context, "Adres kopyalandı")
                    }.padding(horizontal = 6.dp),
                )
                AnimatedVisibility(showQr) {
                    val qr = remember(address) { qrBitmap("http://$address") }
                    Image(qr.asImageBitmap(), contentDescription = "Adresin QR kodu",
                        modifier = Modifier.size(150.dp).clip(RoundedCornerShape(10.dp)))
                }
                Text(if (showQr) "QR'ı gizle" else "QR'ı göster", color = Muted, fontSize = 12.sp,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { showQr = !showQr }.padding(6.dp))
            }
        }
    }
}

@Composable
private fun Stats(items: List<Item>, notes: List<Note>) {
    val inc = items.filter { it.incoming }
    val out = items.filter { !it.incoming }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatBox("Gelen", "${inc.size}", formatSize(inc.sumOf { it.size.coerceAtLeast(0) }), Good, Modifier.weight(1f))
        StatBox("Giden", "${out.size}", formatSize(out.sumOf { it.size.coerceAtLeast(0) }), Blue, Modifier.weight(1f))
        StatBox("Not", "${notes.size}", "pano", Accent, Modifier.weight(1f))
    }
}

@Composable
private fun StatBox(label: String, value: String, sub: String, color: Color, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(10.dp)).background(Surface).border(1.dp, Line, RoundedCornerShape(10.dp)).padding(horizontal = 14.dp, vertical = 12.dp)) {
        Text(label, color = Muted, fontSize = 12.sp)
        Text(value, color = Ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Text(sub, color = Muted, fontSize = 11.sp)
    }
}

/** İnce ilerleme çubuğu. */
@Composable
private fun FancyBar(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    val animated by animateFloatAsState(fraction, tween(250), label = "f")
    Canvas(modifier.fillMaxWidth().height(4.dp)) {
        val r = CornerRadius(size.height / 2)
        drawRoundRect(Chip, cornerRadius = r)
        drawRoundRect(color, size = Size(size.width * animated, size.height), cornerRadius = r)
    }
}

@Composable
private fun TransferCard(t: Transfer) {
    val color = if (t.incoming) Good else Blue
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Surface).border(1.dp, Line, RoundedCornerShape(10.dp))
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(10.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (t.incoming) "↓ Bilgisayardan geliyor" else "↑ Bilgisayara gidiyor", color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text("%${(t.fraction * 100).toInt()}", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        Text(t.name, color = Ink, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        FancyBar(t.fraction, color)
        Text(
            "${formatSize(t.done)} / ${formatSize(t.total)} · ${formatSize(t.speed.toLong())}/sn · ${formatEta(t.etaSec)}",
            color = Muted, fontSize = 12.sp,
        )
    }
}

@Composable
private fun Tabs(selected: Tab, items: List<Item>, notes: List<Note>, onPick: (Tab) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(10.dp)).background(Surface).border(1.dp, Line, RoundedCornerShape(10.dp)).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Tab.entries.forEach { t ->
            val count = when (t) {
                Tab.All -> items.size; Tab.In -> items.count { it.incoming }
                Tab.Out -> items.count { !it.incoming }; Tab.Notes -> notes.size
            }
            val on = t == selected
            Row(
                Modifier.weight(1f).clip(RoundedCornerShape(7.dp)).background(if (on) Chip else Color.Transparent)
                    .clickable { onPick(t) }.padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(t.label, color = if (on) Ink else Muted, fontSize = 13.sp, fontWeight = if (on) FontWeight.Medium else FontWeight.Normal)
                Text("  $count", color = Muted, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun SearchBox(value: String, onChange: (String) -> Unit) {
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Surface).border(1.dp, Line, RoundedCornerShape(10.dp)).padding(horizontal = 14.dp, vertical = 13.dp)) {
        if (value.isEmpty()) Text("Ara…", color = Muted, fontSize = 14.sp)
        BasicTextField(value, onChange, singleLine = true, textStyle = TextStyle(color = Ink, fontSize = 14.sp),
            cursorBrush = SolidColor(Accent), modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun Empty(text: String) = Text(text, color = Muted, fontSize = 14.sp, modifier = Modifier.padding(vertical = 12.dp))

@Composable
private fun FileRow(item: Item) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val (kind, kindColor) = kindOf(item.name)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Surface).border(1.dp, Line, RoundedCornerShape(10.dp))
            .clickable { openFile(context, item) }.padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(42.dp).clip(RoundedCornerShape(10.dp)).background(kindColor.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            Text(kind, color = kindColor, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.weight(1f)) {
            Text(item.name, color = Ink, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(if (item.incoming) "↓ gelen" else "↑ giden", color = if (item.incoming) Good else Blue, fontSize = 12.sp)
                Text("${clock(item.time)} · ${formatSize(item.size)}" + (if (item.downloads > 0) " · ${item.downloads}× indirildi" else ""),
                    color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Box {
            TextButton(onClick = { menu = true }) { Text("⋮", color = Muted, fontSize = 20.sp) }
            DropdownMenu(menu, onDismissRequest = { menu = false }, containerColor = Chip) {
                DropdownMenuItem(text = { Text("Aç") }, onClick = { menu = false; openFile(context, item) })
                DropdownMenuItem(text = { Text("Başka uygulamayla paylaş") }, onClick = { menu = false; shareFile(context, item) })
                DropdownMenuItem(text = { Text("Adını kopyala") }, onClick = { menu = false; copy(context, item.name); toast(context, "Kopyalandı") })
                DropdownMenuItem(text = { Text("Listeden kaldır") }, onClick = { menu = false; Hub.remove(item.id) })
                if (item.incoming) DropdownMenuItem(text = { Text("Telefondan sil", color = Bad) }, onClick = { menu = false; confirmDelete = true })
            }
        }
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Dosya silinsin mi?") },
        text = { Text("“${item.name}” telefondan kalıcı olarak silinecek.") },
        confirmButton = { TextButton(onClick = { Hub.remove(item.id, fromDisk = true); confirmDelete = false }) { Text("Sil", color = Bad) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Vazgeç") } },
        containerColor = Surface,
    )
}

@Composable
private fun NoteComposer() {
    var text by rememberSaveable { mutableStateOf("") }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Surface).border(1.dp, Line, RoundedCornerShape(10.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.fillMaxWidth().heightIn(min = 60.dp)) {
            if (text.isEmpty()) Text("Bilgisayara metin, link, şifre… gönder", color = Muted, fontSize = 14.sp)
            BasicTextField(text, { text = it }, textStyle = TextStyle(color = Ink, fontSize = 14.sp),
                cursorBrush = SolidColor(Accent), modifier = Modifier.fillMaxWidth())
        }
        Button(
            onClick = { Hub.addNote(text, fromPc = false); Cloud.pushNote(text); text = "" },
            enabled = text.isNotBlank(),
            modifier = Modifier.align(Alignment.End).height(44.dp),
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.White),
        ) { Text("Gönder", fontWeight = FontWeight.SemiBold) }
    }
}

@Composable
private fun NoteCard(note: Note) {
    val context = LocalContext.current
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Surface).border(1.dp, Line, RoundedCornerShape(10.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(note.text, color = Ink, fontSize = 14.sp, maxLines = 8, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text((if (note.fromPc) "Bilgisayardan · " else "Telefondan · ") + dayLabel(note.time) + " " + clock(note.time),
                color = if (note.fromPc) Good else Blue, fontSize = 12.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = { copy(context, note.text); toast(context, "Kopyalandı") }) { Text("Kopyala", color = Accent, fontSize = 13.sp) }
            TextButton(onClick = { Hub.removeNote(note.id) }) { Text("Sil", color = Muted, fontSize = 13.sp) }
        }
    }
}

// ── İnternet (GitHub deposu) ──────────────────────────────────

@Composable
private fun CloudPanel(query: String) {
    val configured by Cloud.configured.collectAsStateWithLifecycle()
    if (!configured) { CloudSetup(); return }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val files by Cloud.files.collectAsStateWithLifecycle()
    val notes by Cloud.notes.collectAsStateWithLifecycle()
    val error by Cloud.error.collectAsStateWithLifecycle()
    var busy by remember { mutableStateOf(0) }
    fun run(block: suspend () -> Unit) { scope.launch { busy++; try { block() } finally { busy-- } } }
    LaunchedEffect(Unit) { while (true) { Cloud.refresh(); delay(8000) } }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        run { uris.forEach { Cloud.upload(it) }; if (uris.isNotEmpty() && Cloud.error.value == null) toast(context, "Depoya yüklendi") }
    }
    var noteText by rememberSaveable { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Farklı ağlardayken dosyalar GitHub deposu üzerinden geçer (${Cloud.repo}). Aynı Wi‑Fi'dayken doğrudan aktarım daha hızlıdır.",
            color = Muted, fontSize = 12.sp)
        error?.let { Text(it, color = Bad, fontSize = 13.sp) }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = { picker.launch(arrayOf("*/*")) },
                modifier = Modifier.weight(1f).height(50.dp), shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.White),
            ) { Text("Depoya yükle", fontWeight = FontWeight.SemiBold) }
            OutlinedButton(
                onClick = { run { Cloud.refresh() } },
                modifier = Modifier.height(50.dp), shape = RoundedCornerShape(10.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Line),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Ink),
            ) { Text(if (busy > 0) "…" else "Yenile") }
        }
        val shown = files.filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }
        if (shown.isEmpty()) Empty("Depoda dosya yok. Bilgisayardaki Köprü telefonu bulamayınca bu depoyu açar; oradan yüklenenler burada görünür.")
        shown.forEach { f ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Surface).border(1.dp, Line, RoundedCornerShape(10.dp)).padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val (kind, kindColor) = kindOf(f.name)
                Box(Modifier.size(42.dp).clip(RoundedCornerShape(10.dp)).background(kindColor.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                    Text(kind, color = kindColor, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
                Column(Modifier.weight(1f)) {
                    Text(f.name, color = Ink, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text((if (f.fromPc) "bilgisayardan · " else "telefondan · ") + dayLabel(f.time) + " " + clock(f.time) + " · " + formatSize(f.size),
                        color = if (f.fromPc) Good else Blue, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                TextButton(onClick = { run { Cloud.download(f); if (Cloud.error.value == null) toast(context, "İndirilenler/${Hub.FOLDER} klasörüne kaydedildi") } }) { Text("İndir", color = Accent, fontSize = 13.sp) }
                TextButton(onClick = { run { Cloud.delete(f) } }) { Text("Sil", color = Muted, fontSize = 13.sp) }
            }
        }

        Text("Depo notları", color = Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Surface).border(1.dp, Line, RoundedCornerShape(10.dp)).padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                if (noteText.isEmpty()) Text("Metin, link… gönder", color = Muted, fontSize = 14.sp)
                BasicTextField(noteText, { noteText = it }, textStyle = TextStyle(color = Ink, fontSize = 14.sp), cursorBrush = SolidColor(Accent), modifier = Modifier.fillMaxWidth())
            }
            TextButton(onClick = { val t = noteText; noteText = ""; run { Cloud.addNote(t) } }, enabled = noteText.isNotBlank()) { Text("Gönder", color = Accent) }
        }
        notes.forEach { n ->
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Surface).border(1.dp, Line, RoundedCornerShape(10.dp)).padding(14.dp)) {
                Text(n.text, color = Ink, fontSize = 14.sp, maxLines = 8, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text((if (n.fromPc) "Bilgisayardan · " else "Telefondan · ") + dayLabel(n.time) + " " + clock(n.time),
                        color = if (n.fromPc) Good else Blue, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = { copy(context, n.text); toast(context, "Kopyalandı") }) { Text("Kopyala", color = Accent, fontSize = 13.sp) }
                    TextButton(onClick = { run { Cloud.removeNote(n.id) } }) { Text("Sil", color = Muted, fontSize = 13.sp) }
                }
            }
        }
        TextButton(onClick = { Cloud.forget() }, modifier = Modifier.fillMaxWidth()) { Text("Token'ı unut", color = Muted, fontSize = 13.sp) }
    }
}

@Composable
private fun CloudSetup() {
    val context = LocalContext.current
    var repo by rememberSaveable { mutableStateOf(Cloud.repo) }
    var token by rememberSaveable { mutableStateOf("") }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Surface).border(1.dp, Line, RoundedCornerShape(10.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("İnternet üzerinden paylaşım", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Text("Telefon ve bilgisayar farklı ağlardayken dosyalar gizli GitHub deponda buluşur. Bir kez GitHub token'ı yapıştırman yeter; token yalnızca bu telefonda saklanır.",
            color = Muted, fontSize = 13.sp)
        Text("Token oluştur →", color = Accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/settings/personal-access-tokens/new")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }.padding(4.dp))
        Text("Repository access: Only select → Kopru-depo · Permissions: Contents → Read and write", color = Muted, fontSize = 12.sp)
        Field(repo, "kullanıcı/depo") { repo = it }
        Field(token, "github_pat_…") { token = it }
        Button(
            onClick = { Cloud.configure(repo, token) }, enabled = token.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.White),
        ) { Text("Kaydet", fontWeight = FontWeight.SemiBold) }
    }
}

@Composable
private fun Field(value: String, hint: String, onChange: (String) -> Unit) {
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Chip).padding(horizontal = 12.dp, vertical = 12.dp)) {
        if (value.isEmpty()) Text(hint, color = Muted, fontSize = 14.sp)
        BasicTextField(value, onChange, singleLine = true, textStyle = TextStyle(color = Ink, fontSize = 14.sp), cursorBrush = SolidColor(Accent), modifier = Modifier.fillMaxWidth())
    }
}


// ── Yardımcılar ───────────────────────────────────────────────

private fun kindOf(name: String): Pair<String, Color> {
    val ext = name.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "jpg", "jpeg", "png", "gif", "webp", "heic", "bmp" -> "RESİM" to Color(0xFFE7A6FF)
        "mp4", "mkv", "mov", "avi", "webm", "3gp" -> "VİDEO" to Color(0xFFFF9E7A)
        "mp3", "wav", "flac", "m4a", "ogg", "aac" -> "SES" to Color(0xFF7AE0FF)
        "pdf" -> "PDF" to Color(0xFFFF7A8A)
        "zip", "rar", "7z", "tar", "gz" -> "ARŞİV" to Accent
        "apk" -> "APK" to Good
        "doc", "docx", "txt", "md", "odt", "rtf" -> "BELGE" to Blue
        "xls", "xlsx", "csv" -> "TABLO" to Good
        "" -> "DOSYA" to Muted
        else -> ext.take(5).uppercase() to Muted
    }
}

private fun dayLabel(t: Long): String {
    val c = Calendar.getInstance().apply { timeInMillis = t }
    val now = Calendar.getInstance()
    fun same(a: Calendar, b: Calendar) = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    if (same(c, now)) return "Bugün"
    if (same(c, (now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) })) return "Dün"
    val pattern = if (c.get(Calendar.YEAR) == now.get(Calendar.YEAR)) "d MMMM EEEE" else "d MMMM yyyy"
    return SimpleDateFormat(pattern, TR).format(Date(t))
}

private fun clock(t: Long) = SimpleDateFormat("HH:mm", TR).format(Date(t))

private fun formatSize(b: Long): String = when {
    b < 0 -> "?"
    b < 1024 -> "$b B"
    b < 1024 * 1024 -> String.format(TR, "%.0f KB", b / 1024.0)
    b < 1024L * 1024 * 1024 -> String.format(TR, "%.1f MB", b / 1048576.0)
    else -> String.format(TR, "%.2f GB", b / 1073741824.0)
}

private fun formatEta(sec: Long): String = when {
    sec < 0 -> "hesaplanıyor"
    sec < 60 -> "$sec sn kaldı"
    sec < 3600 -> "${sec / 60} dk ${sec % 60} sn kaldı"
    else -> "${sec / 3600} sa ${sec % 3600 / 60} dk kaldı"
}

private fun openFile(context: Context, item: Item) {
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(item.uri), Hub.mime(item))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }.onFailure { toast(context, "Bu dosyayı açacak uygulama yok") }
}

private fun shareFile(context: Context, item: Item) {
    val send = Intent(Intent.ACTION_SEND).setType(Hub.mime(item)).putExtra(Intent.EXTRA_STREAM, Uri.parse(item.uri))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, item.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

private fun copy(context: Context, text: String) =
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Köprü", text))

private fun toast(context: Context, text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

private fun qrBitmap(text: String): Bitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 512, 512)
    val px = IntArray(m.width * m.height) { i -> if (m.get(i % m.width, i / m.width)) 0xFF111317.toInt() else 0xFFECEDEE.toInt() }
    return Bitmap.createBitmap(px, m.width, m.height, Bitmap.Config.ARGB_8888)
}
