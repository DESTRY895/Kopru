using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Text;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.WinForms;

namespace Kopru;

/// <summary>
/// Köprü masaüstü: telefonu ağda kendisi bulur (UDP 8081), telefonun web arayüzünü
/// kendi penceresinde açar. Bağlantı koparsa yeniden aramaya döner.
/// </summary>
static class Program
{
    [STAThread]
    static void Main()
    {
        ApplicationConfiguration.Initialize();
        Application.Run(new MainForm());
    }
}

sealed class MainForm : Form
{
    const int HttpPort = 8080;
    const int DiscoveryPort = 8081;
    static readonly string DataDir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Kopru");
    static readonly string LastFile = Path.Combine(DataDir, "son-adres.txt");

    readonly WebView2 web = new() { Dock = DockStyle.Fill, DefaultBackgroundColor = Color.FromArgb(17, 19, 23) };
    readonly HttpClient http = new() { Timeout = TimeSpan.FromSeconds(2) };
    readonly System.Windows.Forms.Timer health = new() { Interval = 4000 };
    CancellationTokenSource? search;
    string? connected;
    int misses;

    public MainForm()
    {
        Text = "Köprü";
        Width = 1180; Height = 860;
        MinimumSize = new Size(420, 500);
        StartPosition = FormStartPosition.CenterScreen;
        BackColor = Color.FromArgb(17, 19, 23);
        using (var s = typeof(MainForm).Assembly.GetManifestResourceStream("Kopru.kopru.ico"))
            if (s != null) Icon = new Icon(s);
        Controls.Add(web);
        Load += async (_, _) => await Start();
        health.Tick += async (_, _) => await CheckHealth();
    }

    async Task Start()
    {
        Directory.CreateDirectory(DataDir);
        var env = await CoreWebView2Environment.CreateAsync(null, Path.Combine(DataDir, "WebView2"));
        await web.EnsureCoreWebView2Async(env);
        var core = web.CoreWebView2;
        core.Settings.AreDevToolsEnabled = false;
        core.Settings.IsStatusBarEnabled = false;
        core.WebMessageReceived += (_, e) =>
        {
            var msg = e.TryGetWebMessageAsString();
            if (msg == "retry") _ = Search();
            else if (msg.StartsWith("manual:")) _ = TryManual(msg[7..].Trim());
        };
        // Yeni sekme açmak isteyen bağlantılar (önizleme) varsayılan tarayıcıda açılsın.
        core.NewWindowRequested += (_, e) =>
        {
            e.Handled = true;
            System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo(e.Uri) { UseShellExecute = true });
        };
        await Search();
    }

    /// Önce son bilinen adres, sonra ağda yayın; bulana kadar dener.
    async Task Search()
    {
        search?.Cancel();
        var cts = search = new CancellationTokenSource();
        health.Stop();
        connected = null;
        Text = "Köprü — telefon aranıyor";
        web.CoreWebView2.NavigateToString(SearchPage(""));

        var last = File.Exists(LastFile) ? File.ReadAllText(LastFile).Trim() : null;
        var attempt = 0;
        while (!cts.IsCancellationRequested)
        {
            if (last != null && await Alive(last)) { Connect(last, null); return; }
            var found = await Discover(TimeSpan.FromMilliseconds(1500));
            if (cts.IsCancellationRequested) return;
            if (found != null) { Connect(found.Value.addr, found.Value.model); return; }
            attempt++;
            if (attempt == 3)
                web.CoreWebView2.NavigateToString(SearchPage("Bulunamadı. Telefonda Köprü açık mı, ikisi aynı Wi‑Fi'da mı? Aramaya devam ediyorum…"));
            try { await Task.Delay(1500, cts.Token); } catch (TaskCanceledException) { return; }
        }
    }

    async Task TryManual(string input)
    {
        var addr = input.Replace("http://", "").TrimEnd('/');
        if (!addr.Contains(':')) addr += ":" + HttpPort;
        if (await Alive(addr)) { search?.Cancel(); Connect(addr, null); }
        else web.CoreWebView2.NavigateToString(SearchPage($"{addr} adresine ulaşılamadı."));
    }

    void Connect(string addr, string? model)
    {
        connected = addr;
        misses = 0;
        File.WriteAllText(LastFile, addr);
        Text = "Köprü — " + (model ?? addr);
        web.CoreWebView2.Navigate($"http://{addr}/");
        health.Start();
    }

    /// Telefon kapanır/ağ değişirse aramaya dön.
    async Task CheckHealth()
    {
        if (connected == null) return;
        if (await Alive(connected)) { misses = 0; return; }
        if (++misses >= 3) await Search();
    }

    async Task<bool> Alive(string addr)
    {
        try { return (await http.GetAsync($"http://{addr}/api/state")).IsSuccessStatusCode; }
        catch { return false; }
    }

    /// Tüm ağ arayüzlerinin yayın adresine "KOPRU?" gönderir, ilk yanıtı döndürür.
    static async Task<(string addr, string model)?> Discover(TimeSpan wait)
    {
        using var udp = new UdpClient(0) { EnableBroadcast = true };
        var ask = Encoding.ASCII.GetBytes("KOPRU?");
        foreach (var target in BroadcastAddresses())
            try { await udp.SendAsync(ask, ask.Length, new IPEndPoint(target, DiscoveryPort)); } catch { }

        using var cts = new CancellationTokenSource(wait);
        try
        {
            while (true)
            {
                var r = await udp.ReceiveAsync(cts.Token);
                var text = Encoding.UTF8.GetString(r.Buffer);
                if (!text.StartsWith("KOPRU ")) continue;
                var parts = text.Split(' ', 3);
                return (parts[1], parts.Length > 2 ? parts[2] : parts[1]);
            }
        }
        catch (OperationCanceledException) { return null; }
    }

    static IEnumerable<IPAddress> BroadcastAddresses()
    {
        yield return IPAddress.Broadcast;
        foreach (var ni in NetworkInterface.GetAllNetworkInterfaces())
        {
            if (ni.OperationalStatus != OperationalStatus.Up || ni.NetworkInterfaceType == NetworkInterfaceType.Loopback) continue;
            foreach (var ua in ni.GetIPProperties().UnicastAddresses)
            {
                if (ua.Address.AddressFamily != AddressFamily.InterNetwork || ua.IPv4Mask == null) continue;
                var ip = ua.Address.GetAddressBytes();
                var mask = ua.IPv4Mask.GetAddressBytes();
                var b = new byte[4];
                for (var i = 0; i < 4; i++) b[i] = (byte)(ip[i] | ~mask[i]);
                yield return new IPAddress(b);
            }
        }
    }

    static string SearchPage(string note) => $$$"""
        <!doctype html><html lang="tr"><head><meta charset="utf-8"><style>
          body{margin:0;height:100vh;display:flex;align-items:center;justify-content:center;background:#111317;color:#ECEDEE;font-family:"Segoe UI",system-ui,sans-serif}
          .box{display:flex;flex-direction:column;align-items:center;gap:16px;text-align:center;max-width:440px;padding:24px}
          h1{margin:0;font-size:34px}
          p{margin:0;color:#A0A6B0;font-size:14px;line-height:1.5}
          .note{color:#F2B33D}
          .radar{width:120px;height:120px;position:relative}
          .radar i{position:absolute;inset:0;border:2px solid #F2B33D;border-radius:50%;opacity:0;animation:r 2.4s ease-out infinite}
          .radar i:nth-child(2){animation-delay:.8s}.radar i:nth-child(3){animation-delay:1.6s}
          .radar b{position:absolute;inset:44px;border-radius:50%;background:#F2B33D}
          @keyframes r{from{transform:scale(.3);opacity:1}to{transform:scale(1);opacity:0}}
          form{display:flex;gap:8px;margin-top:8px}
          input{height:42px;border-radius:11px;border:1px solid #3A3F48;background:#1A1D23;color:#ECEDEE;padding:0 14px;font:inherit;width:220px}
          button{height:42px;border-radius:11px;border:0;background:#F2B33D;color:#111317;font:inherit;font-weight:600;padding:0 18px;cursor:pointer}
        </style></head><body><div class="box">
          <div class="radar"><i></i><i></i><i></i><b></b></div>
          <h1>Köprü</h1>
          <p>Telefon aranıyor… Telefonda Köprü uygulamasını aç; iki cihaz aynı Wi‑Fi ağında olmalı.</p>
          <p class="note">{{{WebUtility.HtmlEncode(note)}}}</p>
          <p>Ya da telefondaki adresi yaz:</p>
          <form onsubmit="chrome.webview.postMessage('manual:'+document.getElementById('a').value);return false">
            <input id="a" placeholder="192.168.1.24:8080" aria-label="Telefon adresi"><button>Bağlan</button>
          </form>
        </div></body></html>
        """;
}
