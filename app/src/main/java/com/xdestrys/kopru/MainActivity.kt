package com.xdestrys.kopru

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

private val Ground = Color(0xFF111317)
private val Surface = Color(0xFF1A1D23)
private val Chip = Color(0xFF262A32)
private val Ink = Color(0xFFECEDEE)
private val Muted = Color(0xFFA0A6B0)
private val Accent = Color(0xFFF2B33D)
private val Good = Color(0xFF8EE6A8)

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

    /** "Paylaş → Köprü" ile gelen dosyaları listeye ekle. */
    private fun handleShare(intent: Intent?) {
        @Suppress("DEPRECATION")
        val uris = when (intent?.action) {
            Intent.ACTION_SEND -> listOfNotNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
            Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
            else -> emptyList()
        }
        if (uris.isNotEmpty()) Hub.share(uris)
    }
}

@Composable
private fun App() {
    val context = LocalContext.current
    val address by Hub.address.collectAsStateWithLifecycle()
    val items by Hub.items.collectAsStateWithLifecycle()

    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { Hub.share(it) }

    MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Ground, surface = Surface)) {
        Column(
            Modifier.fillMaxSize().background(Ground).systemBarsPadding().padding(horizontal = 20.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Köprü", color = Ink, fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                val on = address != null
                Row(
                    Modifier.clip(RoundedCornerShape(10.dp)).clickable {
                        if (on) ServerService.stop(context) else ServerService.start(context)
                    }.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(if (on) Good else Muted))
                    Spacer(Modifier.width(6.dp))
                    Text(if (on) "Açık" else "Kapalı", color = if (on) Good else Muted, fontSize = 13.sp)
                }
            }

            AddressCard(address)

            Button(
                onClick = { picker.launch(arrayOf("*/*")) },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Ground),
            ) { Text("Bilgisayara dosya gönder", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Text("Dosyalar", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text("İndirilenler/${Hub.FOLDER}", color = Muted, fontSize = 12.sp)
            }

            if (items.isEmpty()) {
                Text(
                    "Henüz dosya yok. Buradan gönderdiklerin bilgisayarda indirilebilir; bilgisayardan yüklenenler buraya düşer.",
                    color = Muted, fontSize = 14.sp,
                )
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(items, key = { it.id }) { FileRow(it) }
            }
        }
    }
}

@Composable
private fun AddressCard(address: String?) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Surface).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        when {
            address == null -> Text("Sunucu kapalı — açmak için sağ üstteki düğmeye dokun", color = Muted, fontSize = 14.sp)
            address.isEmpty() -> Text("Wi‑Fi ağına bağlan (veya hotspot aç)", color = Muted, fontSize = 14.sp)
            else -> {
                Text("Bilgisayarda tarayıcıya yaz", color = Muted, fontSize = 13.sp)
                Text(address, color = Accent, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                val qr = remember(address) { qrBitmap("http://$address") }
                Image(qr.asImageBitmap(), contentDescription = "Adresin QR kodu",
                    modifier = Modifier.size(140.dp).clip(RoundedCornerShape(10.dp)))
                Text("Aynı Wi‑Fi ağında olmalısınız", color = Muted, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun FileRow(item: Item) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Surface).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(38.dp).clip(RoundedCornerShape(9.dp)).background(Chip), contentAlignment = Alignment.Center) {
            Text(item.name.substringAfterLast('.', "").take(4).uppercase(), color = Muted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        }
        Column(Modifier.weight(1f)) {
            Text(item.name, color = Ink, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                (if (item.incoming) "↓ geldi" else "↑ paylaşıldı") + " · " + formatSize(item.size),
                color = if (item.incoming) Good else Muted, fontSize = 12.sp,
            )
        }
        TextButton(onClick = { Hub.remove(item.id) }) { Text("Kaldır", color = Muted, fontSize = 12.sp) }
    }
}

private fun formatSize(b: Long): String = when {
    b < 0 -> "?"
    b < 1024 -> "$b B"
    b < 1024 * 1024 -> "%.0f KB".format(b / 1024.0)
    b < 1024L * 1024 * 1024 -> "%.1f MB".format(b / 1048576.0)
    else -> "%.2f GB".format(b / 1073741824.0)
}

private fun qrBitmap(text: String): Bitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 512, 512)
    val px = IntArray(m.width * m.height) { i -> if (m.get(i % m.width, i / m.width)) 0xFF111317.toInt() else 0xFFECEDEE.toInt() }
    return Bitmap.createBitmap(px, m.width, m.height, Bitmap.Config.ARGB_8888)
}
