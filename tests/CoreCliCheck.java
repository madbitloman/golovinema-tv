package local.tv.streamtest;

import java.util.List;
import local.tv.core.CoreCli;
import org.json.JSONArray;
import org.json.JSONObject;

/** CoreCli renders the shared playlist/guide logic as normalized JSON for other platforms. */
public final class CoreCliCheck {
    private static void check(boolean result, String failure) { if (!result) throw new AssertionError(failure); }

    public static void main(String[] args) throws Exception {
        String m3u = "#EXTM3U url-tvg=\"https://guide.example/epg.xml.gz\"\n"
                + "#EXTINF:-1 tvg-id=\"One.ua@SD\" group-title=\"News;Sports\",One (1080p)\nhttps://one.example/1080/playlist.m3u8\n"
                + "#EXTINF:-1 tvg-id=\"One.ua@SD\" group-title=\"News\",One (720p)\nhttps://one.example/720/playlist.m3u8\n"
                + "#EXTINF:-1 tvg-id=\"Two.ua\" group-title=\"Kids\",Two\nhttp://two.example/live\n";
        IptvPlaylist.Playlist list = IptvPlaylist.parse(m3u);
        JSONObject json = CoreCli.toJson(list, 1234L);

        check(json.getLong("fetchedAt") == 1234L, "fetchedAt missing");
        check(json.getString("guideUrl").equals("https://guide.example/epg.xml.gz"), "guide url lost");
        JSONArray groups = json.getJSONArray("groups");
        check(groups.length() == 2 && groups.getString(0).equals("Kids") && groups.getString(1).equals("News"),
                "group order wrong: " + groups);
        JSONArray channels = json.getJSONArray("channels");
        check(channels.length() == 2, "duplicate streams became separate channels: " + channels.length());
        JSONObject one = channels.getJSONObject(0), two = channels.getJSONObject(1);
        check(one.getInt("number") == 1 && one.getString("name").equals("One (1080p)") && one.getString("group").equals("News")
                        && one.getString("tvgId").equals("One.ua@SD") && one.getString("url").startsWith("https://"),
                "primary row fields wrong: " + one);
        check(one.getString("referrer").isEmpty() && one.has("userAgent"), "header fields missing");
        JSONArray backups = one.getJSONArray("backups");
        check(backups.length() == 1 && backups.getJSONObject(0).getString("url").equals("https://one.example/720/playlist.m3u8"),
                "better-stream promotion or backup listing wrong");
        check(two.getInt("number") == 2 && two.getString("group").equals("Kids") && !two.has("backups"),
                "second row mis-numbered or stray backups: " + two);
        check(2 == json.getJSONObject("counts").getInt("channels"), "counts wrong");

        check(IptvPlaylist.playable("https://a.example/x.m3u8") && !IptvPlaylist.playable("ftp://old"), "playable() not shared");

        if (args.length > 0) {
            org.json.JSONObject fileJson = CoreCli.toJson(IptvPlaylist.parse(new String(
                    java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(args[0])), java.nio.charset.StandardCharsets.UTF_8)), 0L);
            List<String> all = new java.util.ArrayList<>();
            JSONArray fileChannels = fileJson.getJSONArray("channels");
            for (int i = 0; i < fileChannels.length(); i++) all.add(fileChannels.getJSONObject(i).getString("url"));
            check(fileChannels.length() > 100 && all.stream().allMatch(u -> u.startsWith("http")), "real playlist parse failed");
            System.out.println("CoreCliCheck: file path ok (" + fileChannels.length() + " channels)");
        }
        System.out.println("PASS: CoreCli JSON rendering of playlists, groups, backups and counts");
    }

    private CoreCliCheck() { }
}
