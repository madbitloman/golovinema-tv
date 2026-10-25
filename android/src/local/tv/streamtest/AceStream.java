package local.tv.streamtest;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * AceStream links play through the separately installed Ace Stream Engine app, which serves
 * them on the TV's loopback HTTP API. The app ships no AceStream content of its own.
 */
public final class AceStream {
    public static final String ENGINE = "http://127.0.0.1:6878";
    /** Engine/media apps that provide the loopback API, most common first. */
    public static final String[] PACKAGES = {"org.acestream.node", "org.acestream.media.atv", "org.acestream.media", "org.acestream.core"};
    public static final String STORE_PACKAGE = "org.acestream.node";
    private static final Pattern ID = Pattern.compile("[0-9a-f]{40}");

    /** Content id from acestream://ID, a bare ID, or a loopback engine URL; "" when not AceStream. */
    public static String contentId(String value) {
        if (value == null) return "";
        String text = value.trim();
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.startsWith("acestream://")) return id(text.substring(12).split("[/?#]", 2)[0]);
        if (ID.matcher(lower).matches()) return lower;
        try {
            URI uri = new URI(text);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            if (!"http".equalsIgnoreCase(uri.getScheme()) || !(host.equals("127.0.0.1") || host.equals("localhost"))
                    || uri.getPort() != 6878 || uri.getPath() == null || !uri.getPath().startsWith("/ace/") || uri.getRawQuery() == null) return "";
            for (String part : uri.getRawQuery().split("&"))
                if (part.startsWith("id=") || part.startsWith("content_id=")) return id(part.substring(part.indexOf('=') + 1));
        } catch (Exception ignored) { }
        return "";
    }
    private static String id(String value) {
        String lower = value.trim().toLowerCase(Locale.ROOT);
        return ID.matcher(lower).matches() ? lower : "";
    }
    /** Canonical playlist form, so the same stream added twice is recognised as a duplicate. */
    public static String link(String id) { return "acestream://" + id; }
    /** HLS from the engine; Android's MediaPlayer handles HLS more reliably than raw MPEG-TS. */
    public static String streamUrl(String id, String player) {
        return ENGINE + "/ace/manifest.m3u8?id=" + id + "&pid=" + player;
    }

    /** True when the engine answers on loopback. Blocking; call off the main thread. */
    public static boolean engineRunning(int timeoutMs) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(ENGINE + "/webui/api/service?method=get_version").openConnection();
            connection.setConnectTimeout(timeoutMs); connection.setReadTimeout(timeoutMs);
            if (connection.getResponseCode() != 200) return false;
            byte[] buffer = new byte[2048]; int length = 0, read;
            try (InputStream input = connection.getInputStream()) {
                while (length < buffer.length && (read = input.read(buffer, length, buffer.length - length)) > 0) length += read;
            }
            return new String(buffer, 0, length, "UTF-8").contains("version");
        } catch (Exception error) {
            return false;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }
    private AceStream() { }
}
