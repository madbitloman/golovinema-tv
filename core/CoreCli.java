// Cross-platform core façade: renders the shared JVM logic as JSON and serves it
// to other platforms (Roku, web) over a local HTTP bridge. JDK-only; reuses the
// Android tree's parser classes which already compile without android.* imports.
package local.tv.core;

import local.tv.streamtest.IptvPlaylist;

public final class CoreCli {
    public static final String VERSION = "0.1.0";

    public static void main(String[] args) throws Exception {
        String command = args.length == 0 ? "help" : args[0];
        String[] rest = args.length > 1 ? java.util.Arrays.copyOfRange(args, 1, args.length) : new String[0];
        if (command.equals("playlist")) playlist(rest);
        else if (command.equals("guide")) guide(rest);
        else if (command.equals("serve")) serve(rest);
        else help();
    }

    private static void playlist(String[] args) throws Exception {
        if (args.length != 1) { System.err.println("usage: playlist <m3u-url>"); System.exit(2); }
        System.out.println(toJson(load(args[0], 0), System.currentTimeMillis()).toString(0));
    }

    private static void guide(String[] args) throws Exception {
        if (args.length != 3) { System.err.println("usage: guide <playlist-url> <xmltv-url> <hours>"); System.exit(2); }
        IptvPlaylist.Playlist list = load(args[0], 0);
        long hours = Long.parseLong(args[2]);
        long now = System.currentTimeMillis();
        System.out.println(guideJson(list, args[1], now, now + hours * 3600_000L, 20000).toString(0));
    }

    public static IptvPlaylist.Playlist load(String address, long cachedAt) throws Exception {
        return IptvPlaylist.parse(IptvPlaylist.text(address, 30_000_000));
    }

    public static org.json.JSONObject toJson(IptvPlaylist.Playlist list, long fetchedAt) {
        org.json.JSONObject out = new org.json.JSONObject();
        out.put("version", VERSION);
        out.put("fetchedAt", fetchedAt);
        out.put("guideUrl", list.guideUrl);
        org.json.JSONObject counts = new org.json.JSONObject();
        counts.put("channels", list.channels.size());
        counts.put("skipped", list.skipped);
        counts.put("duplicates", list.duplicates);
        out.put("counts", counts);
        out.put("groups", new org.json.JSONArray(list.groups));
        org.json.JSONArray channels = new org.json.JSONArray();
        for (int i = 0; i < list.channels.size(); i++) channels.put(channelJson(list.channels.get(i)));
        out.put("channels", channels);
        return out;
    }

    private static org.json.JSONObject channelJson(IptvPlaylist.Channel channel) {
        org.json.JSONObject row = new org.json.JSONObject();
        row.put("number", channel.number);
        row.put("name", channel.name);
        row.put("url", channel.url);
        row.put("group", channel.group);
        row.put("logo", channel.logo);
        row.put("tvgId", channel.tvgId);
        row.put("userAgent", channel.userAgent);
        row.put("referrer", channel.referrer);
        if (!channel.backups.isEmpty()) {
            org.json.JSONArray backups = new org.json.JSONArray();
            for (int i = 0; i < channel.backups.size(); i++) {
                IptvPlaylist.Channel backup = channel.backups.get(i);
                org.json.JSONObject row2 = new org.json.JSONObject();
                row2.put("number", backup.number);
                row2.put("name", backup.name);
                row2.put("url", backup.url);
                row2.put("group", backup.group);
                row2.put("logo", backup.logo);
                row2.put("tvgId", backup.tvgId);
                row2.put("userAgent", backup.userAgent);
                row2.put("referrer", backup.referrer);
                backups.put(row2);
            }
            row.put("backups", backups);
        }
        return row;
    }

    public static org.json.JSONObject guideJson(IptvPlaylist.Playlist list, String guideUrl, long from, long to, int limit) throws Exception {
        java.util.Set<String> wanted = new java.util.HashSet<>();
        for (IptvPlaylist.Channel channel : list.channels) wanted.add(channel.guideKey());
        java.io.InputStream input = IptvPlaylist.open(guideUrl, 60_000_000);
        java.util.Map<String, java.util.List<IptvPlaylist.Programme>> guide;
        try { guide = IptvPlaylist.guide(input, wanted, from, to, limit); } finally { input.close(); }
        org.json.JSONObject out = new org.json.JSONObject();
        out.put("version", VERSION);
        org.json.JSONObject window = new org.json.JSONObject();
        window.put("from", from); window.put("to", to);
        out.put("window", window);
        org.json.JSONObject channels = new org.json.JSONObject();
        for (java.util.Map.Entry<String, java.util.List<IptvPlaylist.Programme>> entry : guide.entrySet()) {
            org.json.JSONArray rows = new org.json.JSONArray();
            for (IptvPlaylist.Programme programme : entry.getValue()) {
                org.json.JSONObject row = new org.json.JSONObject();
                row.put("start", programme.start);
                row.put("stop", programme.stop);
                row.put("title", programme.title);
                row.put("desc", programme.description);
                rows.put(row);
            }
            channels.put(entry.getKey(), rows);
        }
        out.put("channels", channels);
        return out;
    }

    // ------------------------------------------------------------------ serve

    private static long cache = 600_000L;
    private static IptvPlaylist.Playlist cached;
    private static String sourceUrl;
    private static long fetchedAt;
    private static String guideUrl;

    private static void serve(String[] args) throws Exception {
        String playlist = null, host = "127.0.0.1";
        int port = 8090;
        for (int i = 0; i < args.length; i++) {
            String option = args[i];
            if (option.equals("--port") && i + 1 < args.length) port = Integer.parseInt(args[++i]);
            else if (option.equals("--playlist") && i + 1 < args.length) playlist = args[++i];
            else if (option.equals("--guide") && i + 1 < args.length) guideUrl = args[++i];
            else if (option.equals("--cache") && i + 1 < args.length) cache = Long.parseLong(args[++i]) * 1000L;
            else if (option.equals("--host") && i + 1 < args.length) host = args[++i];
            else if (i == 0) playlist = option;
        }
        sourceUrl = playlist;
        guideUrl = System.getenv().getOrDefault("TV_CORE_GUIDE", guideUrl == null ? "" : guideUrl);
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress(host, port), 0);
        server.createContext("/playlist.json", response("playlist"));
        server.createContext("/channels.json", response("playlist"));
        server.createContext("/guide.json", response("guide"));
        server.createContext("/events.json", response("events"));
        server.createContext("/health.json", response("health"));
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(4));
        server.start();
        System.out.println("core " + VERSION + " listening on http://" + host + ":" + port
                + (sourceUrl == null ? " (no playlist loaded; POST /playlist.json?playlist=<url>)" : " for " + sourceUrl));
    }

    private static com.sun.net.httpserver.HttpHandler response(final String kind) {
        return exchange -> {
            boolean served = false;
            try {
                if ("OPTIONS".equals(exchange.getRequestMethod())) {
                    exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
                    exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, OPTIONS");
                    exchange.sendResponseHeaders(204, -1);
                    return;
                }
                if ("GET".equals(exchange.getRequestMethod())) {
                    String query = exchange.getRequestURI().getRawQuery();
                    java.util.Map<String, String> params = params(query);
                    if (kind.equals("playlist") && params.containsKey("playlist")) sourceUrl = params.get("playlist");
                    try {
                        String body = kind.equals("playlist") ? playlistJson().toString()
                                : kind.equals("guide") ? guideJson().toString()
                                : kind.equals("health") ? healthJson().toString()
                                : eventsJson(params);
                        byte[] bytes = body.getBytes("UTF-8");
                        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
                        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
                        exchange.sendResponseHeaders(200, bytes.length);
                        exchange.getResponseBody().write(bytes);
                        served = true;
                    } catch (Exception error) {
                        String message = new org.json.JSONObject().put("error", String.valueOf(error)).toString();
                        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
                        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
                        byte[] bytes = message.getBytes("UTF-8");
                        exchange.sendResponseHeaders(502, bytes.length);
                        exchange.getResponseBody().write(bytes);
                        served = true;
                    }
                }
            } finally {
                if (!served) exchange.sendResponseHeaders(404, -1);
                exchange.close();
            }
        };
    }

    private static java.util.Map<String, String> params(String query) {
        java.util.Map<String, String> out = new java.util.HashMap<>();
        if (query != null) for (String pair : query.split("&")) {
            int at = pair.indexOf('=');
            if (at > 0) out.put(urlDecode(pair.substring(0, at)), urlDecode(pair.substring(at + 1)));
        }
        return out;
    }

    private static String urlDecode(String value) {
        try { return java.net.URLDecoder.decode(value, "UTF-8"); } catch (Exception error) { return value; }
    }

    private static synchronized IptvPlaylist.Playlist playlist() throws Exception {
        if (sourceUrl == null) throw new IllegalStateException("No playlist configured. Start serve with --playlist <url>.");
        long now = System.currentTimeMillis();
        if (cached == null || now - fetchedAt > cache) {
            cached = load(sourceUrl, fetchedAt);
            fetchedAt = now;
        }
        return cached;
    }

    private static synchronized org.json.JSONObject playlistJson() throws Exception {
        return toJson(playlist(), fetchedAt);
    }

    private static org.json.JSONObject guideJson() throws Exception {
        IptvPlaylist.Playlist list = playlist();
        String chosen = guideUrl != null && !guideUrl.isEmpty() && IptvPlaylist.playable(guideUrl) ? guideUrl : list.guideUrl;
        if (chosen == null || chosen.isEmpty()) throw new IllegalStateException("Playlist has no url-tvg guide and --guide was not given");
        long now = System.currentTimeMillis();
        return guideJson(list, chosen, now, now + 24 * 3600_000L, 20000);
    }

    private static org.json.JSONObject healthJson() {
        org.json.JSONObject out = new org.json.JSONObject();
        out.put("version", VERSION);
        out.put("fetchedAt", fetchedAt);
        out.put("source", sourceUrl);
        out.put("channels", cached == null ? 0 : cached.channels.size());
        return out;
    }

    // Local-only plugin hook: if a CoreSpi class is on the classpath it owns /events.json.
    private static String eventsJson(java.util.Map<String, String> params) throws Exception {
        Class<?> plugin;
        try { plugin = Class.forName("CoreSpi"); }
        catch (ClassNotFoundException missing) { throw new IllegalStateException("events plugin not loaded"); }
        return (String) plugin.getMethod("events", java.util.Map.class).invoke(null, params);
    }

    private static void help() {
        System.err.println("core " + VERSION + " — shared TV data core (JSON)");
        System.err.println("  playlist <m3u-url>                     parse playlist to JSON");
        System.err.println("  guide <playlist-url> <xmltv-url> <h>   guide window to JSON");
        System.err.println("  serve [--playlist URL] [--guide URL] [--cache s] [--port n] [--host addr]");
        System.exit(1);
    }

    private CoreCli() { }
}
