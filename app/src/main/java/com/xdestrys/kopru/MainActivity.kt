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
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val Ground = Color(0xFF111317)
private val Surface = Color(0xFF1A1D23)
private val Chip = Color(0xFF262A32)
private val Line = Color(0xFF3A3F48)
private val Ink = Color(0xFFECEDEE)
private val Muted = Color(0xFFA0A6B0)
private val Accent = Color(0xFFF2B33D)
private val Good = Color(0xFF8EE6A8)
private val Bad = Color(0xFFFF8A7A)
private val Blue = Color(0xFF7AB8FF)
private val TR = Locale.forLanguageTag("tr-TR")

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Hub.init(this)
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
        if (uris.isNotEmpty()) Hub.share(uris)
        else if (intent?.action == Intent.ACTION_SEND) intent.getStringExtra(Intent.EXTRA_TEXT)?.let { Hub.addNote(it, fromPc = false) }
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
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { Hub.share(it) }
    val media = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { Hub.share(it) }

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
                        modifier = Modifier.weight(1f).height(54.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Ground),
                    ) { Text("Dosya gönder", fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
                    OutlinedButton(
                        onClick = { media.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
                        modifier = Modifier.weight(1f).height(54.dp),
                        shape = RoundedCornerShape(14.dp),
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
        Text("Köprü", color = Ink, fontSize = 30.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        val pulse by rememberInfiniteTransition(label = "p").animateFloat(
            0.35f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "a",
        )
        Row(
            Modifier.clip(RoundedCornerShape(20.dp)).background(Surface)
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
    var showQr by rememberSaveable { mutableStateOf(true) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Surface).animateContentSize().padding(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        when {
            address == null -> Text("Sunucu kapalı — açmak için sağ üstteki düğmeye dokun", color = Muted, fontSize = 14.sp)
            address.isEmpty() -> Text("Wi‑Fi ağına bağlan (veya hotspot aç)", color = Muted, fontSize = 14.sp)
            else -> {
                Text("Bilgisayarda Köprü uygulaması telefonu kendisi bulur — ya da tarayıcıya yaz:", color = Muted, fontSize = 12.sp)
                Text(
                    address, color = Accent, fontSize = 26.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable {
                        copy(context, "http://$address"); toast(context, "Adres kopyalandı")
                    }.padding(horizontal = 6.dp),
                )
                AnimatedVisibility(showQr) {
                    val qr = remember(address) { qrBitmap("http://$address") }
                    Image(qr.asImageBitmap(), contentDescription = "Adresin QR kodu",
                        modifier = Modifier.size(150.dp).clip(RoundedCornerShape(12.dp)))
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
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(Surface).padding(horizontal = 14.dp, vertical = 12.dp)) {
        Text(label, color = Muted, fontSize = 12.sp)
        Text(value, color = color, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(sub, color = Muted, fontSize = 11.sp)
    }
}

/** Akan çizgili, parlayan ilerleme çubuğu. */
@Composable
private fun FancyBar(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    val animated by animateFloatAsState(fraction, tween(250), label = "f")
    val t = rememberInfiniteTransition(label = "s")
    val shift by t.animateFloat(0f, 1f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "x")
    val glow by t.animateFloat(-0.3f, 1.3f, infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing)), label = "g")
    Canvas(modifier.fillMaxWidth().height(10.dp)) {
        val r = CornerRadius(size.height / 2)
        drawRoundRect(Chip, cornerRadius = r)
        val w = size.width * animated
        if (w <= 0f) return@Canvas
        drawRoundRect(color, size = Size(w, size.height), cornerRadius = r)
        // Çapraz şeritler
        val stripe = 14.dp.toPx()
        clipRect(right = w, bottom = size.height) {
            var x = -stripe * 2 + shift * stripe * 2
            while (x < w) {
                drawLine(Color.White.copy(alpha = 0.18f), Offset(x, size.height), Offset(x + size.height, 0f), strokeWidth = stripe / 2)
                x += stripe
            }
            // Gezen parıltı
            val gx = glow * w
            drawRect(Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.35f), Color.Transparent), gx - 60f, gx + 60f),
                size = Size(w, size.height))
        }
    }
}


@Composable
private fun TransferCard(t: Transfer) {
    val color = if (t.incoming) Good else Blue
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Surface)
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(14.dp)).padding(14.dp),
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
        Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(12.dp)).background(Surface).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Tab.entries.forEach { t ->
            val count = when (t) {
                Tab.All -> items.size; Tab.In -> items.count { it.incoming }
                Tab.Out -> items.count { !it.incoming }; Tab.Notes -> notes.size
            }
            val on = t == selected
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(9.dp)).background(if (on) Chip else Color.Transparent)
                    .clickable { onPick(t) }.padding(vertical = 9.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(t.label, color = if (on) Ink else Muted, fontSize = 13.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
                Text("$count", color = if (on) Accent else Muted, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun SearchBox(value: String, onChange: (String) -> Unit) {
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Surface).padding(horizontal = 14.dp, vertical = 13.dp)) {
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
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Surface)
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
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Surface).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.fillMaxWidth().heightIn(min = 60.dp)) {
            if (text.isEmpty()) Text("Bilgisayara metin, link, şifre… gönder", color = Muted, fontSize = 14.sp)
            BasicTextField(text, { text = it }, textStyle = TextStyle(color = Ink, fontSize = 14.sp),
                cursorBrush = SolidColor(Accent), modifier = Modifier.fillMaxWidth())
        }
        Button(
            onClick = { Hub.addNote(text, fromPc = false); text = "" },
            enabled = text.isNotBlank(),
            modifier = Modifier.align(Alignment.End).height(44.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Ground),
        ) { Text("Gönder", fontWeight = FontWeight.SemiBold) }
    }
}

@Composable
private fun NoteCard(note: Note) {
    val context = LocalContext.current
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Surface).padding(14.dp),
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
