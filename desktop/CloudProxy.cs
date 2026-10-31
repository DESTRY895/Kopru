using System.Net;
using System.Net.Http.Headers;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace Kopru;

/// <summary>
/// Telefon ve bilgisayar farklı ağlardayken: gizli GitHub deposunu telefonun HTTP arayüzüyle
/// birebir aynı yollarla (/api/state, /up, /dl, …) yerelde sunar; böylece aynı web arayüzü çalışır.
/// Dosyalar "depo" sürümünün ekleri ("{zaman}-{P|T}-{ascii}", gerçek ad etikette), notlar notes.json.
/// </summary>
sealed class CloudProxy
{
    public const string DefaultRepo = "DESTRY895/Kopru-depo";
    static readonly string ConfigFile = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Kopru", "github.bin");

    readonly string repo;
    readonly HttpClient gh;
    readonly HttpClient plain = new();
    readonly HttpListener listener = new();
    JsonArray? cache; DateTime cacheAt; long releaseId;
    public string Url { get; }

    CloudProxy(string repo, string token)
    {
        this.repo = repo;
        gh = new HttpClient { Timeout = Timeout.InfiniteTimeSpan };
        gh.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", token);
        gh.DefaultRequestHeaders.UserAgent.ParseAdd("Kopru/1.0");
        gh.DefaultRequestHeaders.Accept.ParseAdd("application/vnd.github+json");
        var port = FreePort();
        Url = $"http://localhost:{port}/";
        listener.Prefixes.Add(Url);
    }

    // ── Ayar ─────────────────────────────────────────────────

    public static (string repo, string token)? Load()
    {
        try
        {
            var raw = Encoding.UTF8.GetString(ProtectedData.Unprotect(File.ReadAllBytes(ConfigFile), null, DataProtectionScope.CurrentUser)).Split('\n');
            return (raw[0], raw[1]);
        }
        catch { return Builtin() is { } t ? (DefaultRepo, t) : null; }
    }

    /// Derlemede gömülen token (Actions secret'ı); kaynakta yok.
    static string? Builtin()
    {
        using var s = typeof(CloudProxy).Assembly.GetManifestResourceStream("token.txt");
        var t = s == null ? null : new StreamReader(s).ReadToEnd().Trim();
        return string.IsNullOrEmpty(t) ? null : t;
    }

    public static void Save(string repo, string token) =>
        File.WriteAllBytes(ConfigFile, ProtectedData.Protect(Encoding.UTF8.GetBytes(repo + "\n" + token), null, DataProtectionScope.CurrentUser));

    public static void Forget() { try { File.Delete(ConfigFile); } catch { } }

    /// Token'ı dener; olursa sunucuyu başlatır, olmazsa hata metni döner.
    public static async Task<(CloudProxy? proxy, string? error)> Open(string repo, string token)
    {
        var p = new CloudProxy(repo, token);
        try { await p.Release(); }
        catch (Exception e) { return (null, e.Message); }
        p.listener.Start();
        _ = Task.Run(p.Loop);
        return (p, null);
    }

    public void Stop() { try { listener.Stop(); } catch { } }

    // ── Sunucu ───────────────────────────────────────────────

    async Task Loop()
    {
        while (listener.IsListening)
        {
            HttpListenerContext ctx;
            try { ctx = await listener.GetContextAsync(); } catch { return; }
            _ = Task.Run(async () =>
            {
                try { await Handle(ctx); }
                catch (Exception e) { try { await Text(ctx.Response, 502, e.Message); } catch { } }
                finally { try { ctx.Response.Close(); } catch { } }
            });
        }
    }

    async Task Handle(HttpListenerContext ctx)
    {
        var req = ctx.Request; var res = ctx.Response;
        var path = req.Url!.AbsolutePath;
        var m = req.HttpMethod;
        if (m == "GET" && path == "/")
        {
            using var s = typeof(CloudProxy).Assembly.GetManifestResourceStream("index.html")!;
            res.ContentType = "text/html; charset=utf-8";
            await s.CopyToAsync(res.OutputStream);
        }
        else if (m == "GET" && path == "/api/state") await Json(res, await State());
        else if (m == "GET" && path.StartsWith("/dl/")) await Download(path[4..], req.QueryString["inline"] == "1", res);
        else if (m == "PUT" && path == "/up") await Upload(req.QueryString["name"] ?? "dosya", req, res);
        else if (m == "DELETE" && path.StartsWith("/api/item/"))
        {
            (await gh.DeleteAsync($"https://api.github.com/repos/{repo}/releases/assets/{path[10..]}")).EnsureSuccessStatusCode();
            cache = null;
            await Json(res, new JsonObject { ["ok"] = true });
        }
        else if (m == "POST" && path == "/api/note")
        {
            var text = (await new StreamReader(req.InputStream, Encoding.UTF8).ReadToEndAsync()).Trim();
            var now = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
            if (text.Length > 0) await EditNotes(a => a.Insert(0, new JsonObject { ["id"] = now.ToString(), ["text"] = text, ["pc"] = true, ["t"] = now }));
            await Json(res, new JsonObject { ["ok"] = true });
        }
        else if (m == "DELETE" && path.StartsWith("/api/note/"))
        {
            var id = path[10..];
            await EditNotes(a => { foreach (var n in a.Where(n => (string?)n!["id"] == id).ToList()) a.Remove(n); });
            await Json(res, new JsonObject { ["ok"] = true });
        }
        else await Text(res, 404, "Bulunamadı");
    }

    async Task<JsonObject> State()
    {
        if (cache == null || DateTime.UtcNow - cacheAt > TimeSpan.FromSeconds(3))
        {
            var rel = JsonNode.Parse(await gh.GetStringAsync($"https://api.github.com/repos/{repo}/releases/{releaseId}"))!;
            var items = new JsonArray();
            foreach (var a in rel["assets"]!.AsArray())
            {
                var name = (string)a!["name"]!;
                var parts = name.Split('-', 3);
                var label = (string?)a["label"];
                items.Add(new JsonObject
                {
                    ["id"] = a["id"]!.GetValue<long>().ToString(),
                    ["name"] = string.IsNullOrEmpty(label) ? (parts.Length > 2 ? parts[2] : name) : label,
                    ["size"] = a["size"]!.GetValue<long>(),
                    ["in"] = parts.Length > 1 && parts[1] == "P",
                    ["t"] = long.TryParse(parts[0], out var t) ? t : 0,
                    ["dl"] = a["download_count"]!.GetValue<int>(),
                    ["mime"] = "application/octet-stream",
                });
            }
            var sorted = new JsonArray(items.OrderByDescending(i => i!["t"]!.GetValue<long>()).Select(i => i!.DeepClone()).ToArray());
            var (notes, _) = await ReadNotes();
            cache = new JsonArray(sorted, notes);
            cacheAt = DateTime.UtcNow;
        }
        return new JsonObject
        {
            ["items"] = cache[0]!.DeepClone(), ["notes"] = cache[1]!.DeepClone(),
            ["transfers"] = new JsonArray(), ["device"] = "GitHub deposu",
        };
    }

    async Task Download(string id, bool inline, HttpListenerResponse res)
    {
        var name = Name(id);
        var req = new HttpRequestMessage(HttpMethod.Get, $"https://api.github.com/repos/{repo}/releases/assets/{id}");
        req.Headers.Accept.Clear(); req.Headers.Accept.ParseAdd("application/octet-stream");
        // HttpClient yönlendirmede Authorization'ı zaten düşürür; imzalı adres token istemez.
        using var r = await gh.SendAsync(req, HttpCompletionOption.ResponseHeadersRead);
        r.EnsureSuccessStatusCode();
        res.ContentType = Mime(name);
        if (r.Content.Headers.ContentLength is long len) res.ContentLength64 = len;
        res.AddHeader("Content-Disposition", $"{(inline ? "inline" : "attachment")}; filename*=UTF-8''{Uri.EscapeDataString(name)}");
        res.AddHeader("Cache-Control", "max-age=3600");
        await r.Content.CopyToAsync(res.OutputStream);
    }

    async Task Upload(string name, HttpListenerRequest req, HttpListenerResponse res)
    {
        if (req.ContentLength64 < 0) { await Text(res, 411, "Content-Length gerekli"); return; }
        var ascii = new string(name.Select(c => char.IsAscii(c) && (char.IsLetterOrDigit(c) || c is '.' or '_') ? c : '_').ToArray());
        if (ascii.Length > 80) ascii = ascii[^80..];
        var asset = $"{DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()}-P-{ascii}";
        var url = $"https://uploads.github.com/repos/{repo}/releases/{releaseId}/assets?name={Uri.EscapeDataString(asset)}&label={Uri.EscapeDataString(name)}";
        var content = new StreamContent(req.InputStream, 256 * 1024);
        content.Headers.ContentType = new MediaTypeHeaderValue("application/octet-stream");
        content.Headers.ContentLength = req.ContentLength64;
        var r = await gh.PostAsync(url, content);
        if (!r.IsSuccessStatusCode) { await Text(res, 502, "GitHub yüklemeyi reddetti: " + (int)r.StatusCode); return; }
        cache = null;
        var a = JsonNode.Parse(await r.Content.ReadAsStringAsync())!;
        await Json(res, new JsonObject { ["id"] = a["id"]!.GetValue<long>().ToString() });
    }

    // ── Notlar ───────────────────────────────────────────────

    async Task<(JsonArray notes, string? sha)> ReadNotes()
    {
        var r = await gh.GetAsync($"https://api.github.com/repos/{repo}/contents/notes.json");
        if (r.StatusCode == HttpStatusCode.NotFound) return (new JsonArray(), null);
        r.EnsureSuccessStatusCode();
        var o = JsonNode.Parse(await r.Content.ReadAsStringAsync())!;
        var json = Encoding.UTF8.GetString(Convert.FromBase64String(((string)o["content"]!).Replace("\n", "")));
        return (JsonNode.Parse(json)!.AsArray(), (string)o["sha"]!);
    }

    async Task EditNotes(Action<JsonArray> edit)
    {
        for (var attempt = 0; ; attempt++)
        {
            var (notes, sha) = await ReadNotes();
            edit(notes);
            var body = new JsonObject { ["message"] = "not", ["content"] = Convert.ToBase64String(Encoding.UTF8.GetBytes(notes.ToJsonString())) };
            if (sha != null) body["sha"] = sha;
            var r = await gh.PutAsync($"https://api.github.com/repos/{repo}/contents/notes.json",
                new StringContent(body.ToJsonString(), Encoding.UTF8, "application/json"));
            if (r.IsSuccessStatusCode) { cache = null; return; }
            if (attempt == 2) throw new Exception("Not kaydedilemedi");
        }
    }

    // ── Yardımcılar ──────────────────────────────────────────

    async Task Release()
    {
        var r = await gh.GetAsync($"https://api.github.com/repos/{repo}/releases/tags/depo");
        if (r.StatusCode == HttpStatusCode.Unauthorized) throw new Exception("Token geçersiz ya da süresi dolmuş.");
        if (!r.IsSuccessStatusCode) throw new Exception($"{repo} deposuna erişilemedi ({(int)r.StatusCode}). Token bu depoya izin veriyor mu?");
        releaseId = JsonNode.Parse(await r.Content.ReadAsStringAsync())!["id"]!.GetValue<long>();
    }

    string Name(string id) =>
        cache?[0]?.AsArray().FirstOrDefault(i => (string?)i!["id"] == id)?["name"]?.GetValue<string>() ?? "dosya";

    static string Mime(string name) => Path.GetExtension(name).ToLowerInvariant() switch
    {
        ".jpg" or ".jpeg" => "image/jpeg", ".png" => "image/png", ".gif" => "image/gif", ".webp" => "image/webp",
        ".mp4" => "video/mp4", ".webm" => "video/webm", ".mov" => "video/quicktime",
        ".mp3" => "audio/mpeg", ".m4a" => "audio/mp4", ".wav" => "audio/wav", ".ogg" => "audio/ogg",
        ".pdf" => "application/pdf", ".txt" => "text/plain; charset=utf-8",
        _ => "application/octet-stream",
    };

    static async Task Json(HttpListenerResponse res, JsonNode o)
    {
        var b = Encoding.UTF8.GetBytes(o.ToJsonString());
        res.ContentType = "application/json; charset=utf-8";
        res.AddHeader("Cache-Control", "no-store");
        res.ContentLength64 = b.Length;
        await res.OutputStream.WriteAsync(b);
    }

    static async Task Text(HttpListenerResponse res, int code, string text)
    {
        var b = Encoding.UTF8.GetBytes(text);
        res.StatusCode = code; res.ContentType = "text/plain; charset=utf-8"; res.ContentLength64 = b.Length;
        await res.OutputStream.WriteAsync(b);
    }

    static int FreePort()
    {
        var l = new System.Net.Sockets.TcpListener(IPAddress.Loopback, 0);
        l.Start(); var p = ((IPEndPoint)l.LocalEndpoint).Port; l.Stop();
        return p;
    }
}
