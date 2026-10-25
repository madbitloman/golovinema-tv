import local.tv.streamtest.PlaylistProbe;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

public final class PlaylistProbeCheck {
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static void rejected(int code, String body, String expected) throws Exception {
        try { PlaylistProbe.requirePlaylist(code, bytes(body)); }
        catch (IOException error) {
            if (error.getMessage().equals(expected)) return;
            throw new AssertionError("Unexpected diagnostic: " + error.getMessage());
        }
        throw new AssertionError("Accepted unavailable or invalid playlist");
    }
    public static void main(String[] args) throws Exception {
        // HTTP 502 is the observed Hope response. A successful resolver must not
        // be mistaken for a playable stream, including HTML delivered with HTTP 200.
        rejected(502, "error code: 502\n", "Playlist HTTP 502");
        rejected(403, "Forbidden", "Playlist HTTP 403");
        rejected(200, "<!doctype html>", "Provider returned an invalid playlist");
        rejected(200, "", "Provider returned an invalid playlist");
        rejected(200, "#EXTM3U-fake", "Provider returned an invalid playlist");
        PlaylistProbe.requirePlaylist(200, bytes("#EXTM3U\n#EXT-X-VERSION:3"));
        PlaylistProbe.requirePlaylist(200, bytes("#EXTM3U\r\n#EXT-X-VERSION:3"));
        PlaylistProbe.requirePlaylist(200, bytes("\uFEFF#EXTM3U\n#EXT-X-VERSION:3"));
        byte[] playlist = bytes("#EXTM3U\n#EXT-X-VERSION:3\n#EXTINF:2.0,\nclip.ts\n");
        ByteArrayInputStream input = new ByteArrayInputStream(playlist);
        byte[] prefix = PlaylistProbe.readPrefix(input);
        if (prefix.length != 16 || input.available() != playlist.length - 16) throw new AssertionError("Probe must stop after 16 bytes");
        PlaylistProbe.requirePlaylist(200, prefix);
        Thread.currentThread().interrupt();
        try {
            PlaylistProbe.readPrefix(new ByteArrayInputStream(playlist));
            throw new AssertionError("Cancelled probe must stop");
        } catch (InterruptedException expected) { Thread.interrupted(); }
        // Optional captured live response: status and path, no expiring URLs in logs.
        if (args.length == 2) {
            try {
                PlaylistProbe.requirePlaylist(Integer.parseInt(args[0]), Files.readAllBytes(Paths.get(args[1])));
                System.out.println("Live capture: valid playlist");
            } catch (IOException error) { System.out.println("Live capture: " + error.getMessage()); }
        }
        System.out.println("PASS: HTTP errors, HTML/empty responses, HLS headers, bounded read and cancellation");
    }
}
