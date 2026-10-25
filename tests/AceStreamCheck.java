package local.tv.streamtest;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

public final class AceStreamCheck {
    private static void check(boolean result, String failure) { if (!result) throw new AssertionError(failure); }
    public static void main(String[] args) throws Exception {
        String id = "0123456789abcdef0123456789abcdef01234567";
        check(AceStream.contentId("acestream://" + id).equals(id), "acestream:// link not read");
        check(AceStream.contentId("  ACESTREAM://" + id.toUpperCase() + "/  ").equals(id), "Case/trailing slash not normalised");
        check(AceStream.contentId(id).equals(id), "Bare content ID not read");
        check(AceStream.contentId("http://127.0.0.1:6878/ace/getstream?id=" + id).equals(id), "Engine getstream URL not read");
        check(AceStream.contentId("http://localhost:6878/ace/manifest.m3u8?format=json&content_id=" + id).equals(id), "Engine manifest URL not read");
        for (String bad : new String[]{null, "", "acestream://" + id.substring(1), "acestream://" + id.replace('a', 'g'),
                "https://example.com/ace/getstream?id=" + id, "http://127.0.0.1:8080/ace/getstream?id=" + id,
                "http://127.0.0.1:6878/other?id=" + id, "https://cdn.example/live.m3u8"})
            check(AceStream.contentId(bad).isEmpty(), "Accepted non-AceStream value: " + bad);
        check(AceStream.streamUrl(id, "golovinema").equals("http://127.0.0.1:6878/ace/manifest.m3u8?id=" + id + "&pid=golovinema"), "Engine stream URL wrong");

        String m3u = "#EXTM3U\n#EXTINF:-1 group-title=\"Sports\",Match A\nacestream://" + id + "\n"
                + "#EXTINF:-1,Match A\nhttp://127.0.0.1:6878/ace/getstream?id=" + id + "\n"
                + "#EXTINF:-1,Broken\nacestream://nothex\n";
        IptvPlaylist.Playlist list = IptvPlaylist.parse(m3u);
        check(list.channels.size() == 1 && list.channels.get(0).url.equals("acestream://" + id), "AceStream playlist entry not kept canonical");
        check(list.duplicates == 1 && list.skipped == 1, "AceStream duplicate/invalid counts wrong: " + list.duplicates + "/" + list.skipped);

        HttpServer engine;
        try { engine = HttpServer.create(new InetSocketAddress("127.0.0.1", 6878), 0); }
        catch (java.io.IOException busy) { System.out.println("PASS: AceStream link parsing, engine URLs and playlist entries (engine probe skipped: port 6878 busy)"); return; }
        check(!AceStream.engineRunning(500), "Engine reported running with nothing listening");
        engine.createContext("/webui/api/service", exchange -> {
            byte[] body = "{\"result\":{\"code\":3012100,\"version\":\"3.1.21\"},\"error\":null}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        engine.start();
        try { check(AceStream.engineRunning(2000), "Running engine not detected"); }
        finally { engine.stop(0); }
        System.out.println("PASS: AceStream link parsing, engine URLs, playlist entries and engine detection");
    }
}
