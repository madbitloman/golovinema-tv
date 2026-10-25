package local.tv.streamtest;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Reject unavailable playlists before handing them to MediaPlayer. */
public final class PlaylistProbe {
    private PlaylistProbe() { }

    public static void check(String url) throws Exception {
        check(url, "https://ployan.me/watch/");
    }

    public static void check(String url, String referer) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(10000);
        connection.setRequestProperty("Accept", "application/vnd.apple.mpegurl, application/x-mpegURL, */*");
        connection.setRequestProperty("Referer", referer);
        connection.setRequestProperty("User-Agent", "TVStreamTest/0.1 (Android TV)");
        try {
            int status = connection.getResponseCode();
            if (status != 200) throw new IOException("Playlist HTTP " + status);
            try (InputStream input = connection.getInputStream()) {
                requirePlaylist(status, readPrefix(input));
            }
        } finally { connection.disconnect(); }
    }

    public static byte[] readPrefix(InputStream input) throws Exception {
        // Do not download the whole movie playlist or wait for its response body to end.
        byte[] prefix = new byte[16];
        int size = 0;
        while (size < prefix.length) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            int count = input.read(prefix, size, prefix.length - size);
            if (count == -1) break;
            size += count;
        }
        return java.util.Arrays.copyOf(prefix, size);
    }

    public static void requirePlaylist(int status, byte[] prefix) throws IOException {
        if (status != 200) throw new IOException("Playlist HTTP " + status);
        String text = new String(prefix, StandardCharsets.UTF_8);
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        if (!text.startsWith("#EXTM3U\n") && !text.startsWith("#EXTM3U\r\n")) {
            throw new IOException("Provider returned an invalid playlist");
        }
    }
}
