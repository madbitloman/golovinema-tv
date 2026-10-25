package local.tv.streamtest;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

public final class IptvPlaylistCheck {
    private static void check(boolean result, String failure) { if (!result) throw new AssertionError(failure); }
    public static void main(String[] args) throws Exception {
        String m3u = "﻿#EXTM3U url-tvg=\"https://guide.example/epg.xml.gz\"\n"
                + "#EXTINF:-1 tvg-id=\"Channel2.us@SD\" tvg-logo=\"https://i.example/2.png\" group-title=\"General;News\",Channel 2 (1080p)\n"
                + "#EXTVLCOPT:http-user-agent=Mozilla/5.0 Test\n#EXTVLCOPT:http-referrer=https://ref.example/\n"
                + "http://stream.example/2/index.m3u8\n"
                + "#EXTINF:-1 tvg-name=\"Name, with comma\" tvg-logo=\"http://insecure.example/logo.png\",Cinema, Live\n"
                + "https://cdn.example/cinema.m3u8\n"
                + "#EXTINF:-1,Bad scheme\nrtmp://old.example/live\n"
                + "#EXTINF:-1,Credentials\nhttps://user:pass@host.example/x.m3u8\n"
                + "https://orphan.example/no-extinf.m3u8\n";
        IptvPlaylist.Playlist list = IptvPlaylist.parse(m3u);
        check(list.channels.size() == 2 && list.skipped == 3, "Wrong playable/skipped split: " + list.channels.size() + "/" + list.skipped);
        IptvPlaylist.Channel first = list.channels.get(0), second = list.channels.get(1);
        check(first.name.equals("Channel 2 (1080p)") && first.group.equals("General") && first.number == 1, "EXTINF attributes misread");
        check(first.userAgent.equals("Mozilla/5.0 Test") && first.referrer.equals("https://ref.example/"), "EXTVLCOPT headers lost");
        check(second.name.equals("Cinema, Live") && second.group.equals("Other") && second.logo.isEmpty(), "Quoted comma or insecure logo mishandled");
        check(list.guideUrl.equals("https://guide.example/epg.xml.gz"), "Header guide URL lost");
        check(first.guideKey().equals("channel2.us"), "Guide key kept feed suffix: " + first.guideKey());
        String dupes = "#EXTM3U\n"
                + "#EXTINF:-1 tvg-id=\"News.us@SD\" group-title=\"News\",News (720p)\nhttps://a.example/sd.m3u8\n"
                + "#EXTINF:-1 tvg-id=\"News.us@HD\" group-title=\"General\",News (1080p)\nhttps://a.example/hd.m3u8\n"
                + "#EXTINF:-1 tvg-id=\"News.us@HD\",News (1080p)\nhttps://a.example/hd.m3u8\n"
                + "#EXTINF:-1 tvg-id=\"Sport.ca\",Sport (1080p) [Geo-blocked]\nhttps://b.example/geo.m3u8\n"
                + "#EXTINF:-1 tvg-id=\"Sport.ca\",Sport (480p)\nhttps://b.example/open.m3u8\n"
                + "#EXTINF:-1,Local TV [Not 24/7]\nhttps://c.example/1.m3u8\n"
                + "#EXTINF:-1,Local TV (720p)\nhttps://c.example/2.m3u8\n"
                + "#EXTINF:-1 tvg-id=\"Other.ca\",News (720p)\nhttps://d.example/other.m3u8\n";
        IptvPlaylist.Playlist merged = IptvPlaylist.parse(dupes);
        check(merged.channels.size() == 4 && merged.duplicates == 4, "Dedup count wrong: " + merged.channels.size() + "/" + merged.duplicates);
        IptvPlaylist.Channel news = merged.channels.get(0), sport = merged.channels.get(1), local = merged.channels.get(2);
        check(news.url.endsWith("/hd.m3u8") && news.group.equals("News") && news.number == 1 && news.backups.size() == 1 && news.has("https://a.example/sd.m3u8"), "Best feed not promoted with backups");
        check(sport.url.endsWith("/open.m3u8") && sport.backups.get(0).url.endsWith("/geo.m3u8"), "Geo-blocked stream preferred");
        check(local.url.endsWith("/2.m3u8") && local.backups.size() == 1, "Name-only duplicates not merged");
        check(merged.channels.get(3).number == 4 && merged.channels.get(3).backups.isEmpty(), "Different ids with the same name were merged");
        try { IptvPlaylist.parse("<html>nope</html>"); throw new AssertionError("Accepted HTML as playlist"); } catch (IOException expected) { }

        IptvPlaylist.Playlist real = IptvPlaylist.parse(new String(Files.readAllBytes(Paths.get(args[0])), StandardCharsets.UTF_8));
        check(real.duplicates >= 0 && real.channels.size() > 100 && real.groups.size() > 3, "Real iptv-org playlist parsed too few channels");
        check(real.channels.stream().anyMatch(c -> !c.userAgent.isEmpty()), "Real playlist user agents lost");

        long base = IptvPlaylist.time("20261023120000 +0000");
        check(base == 1792756800000L, "XMLTV time parsed wrong: " + base);
        check(IptvPlaylist.time("20261023140000 +0200") == base, "XMLTV offset ignored");
        check(IptvPlaylist.time("20261023063000 -0530") == base, "Negative XMLTV offset wrong");
        check(IptvPlaylist.time("20240229000000 +0000") == 1709164800000L, "Leap day wrong");
        check(IptvPlaylist.time("20261023120000") == base, "Zone-less XMLTV time not UTC");
        check(IptvPlaylist.time("2026102312") == 0 && IptvPlaylist.time("2026AB23120000 +0000") == 0 && IptvPlaylist.time("20261023120000 UTC") == 0, "Accepted malformed XMLTV time");
        String xml = "<?xml version=\"1.0\"?><tv>"
                + "<channel id=\"Channel2.us\"><display-name>Channel 2</display-name></channel>"
                + "<channel id=\"k1\"><display-name>Cinema, Live</display-name></channel>"
                + "<channel id=\"other\"><display-name>Other</display-name></channel>"
                + "<programme start=\"20261023110000 +0000\" stop=\"20261023120000 +0000\" channel=\"Channel2.us\"><title>Past</title></programme>"
                + "<programme start=\"20261023120000 +0000\" stop=\"20261023130000 +0000\" channel=\"Channel2.us\"><title lang=\"es\">Noticias</title><desc>Édition du soir</desc></programme>"
                + "<programme start=\"20261023130000 +0000\" stop=\"20261023140000 +0000\" channel=\"Channel2.us\"><title>Película</title></programme>"
                + "<programme start=\"20261023120000 +0000\" stop=\"20261023130000 +0000\" channel=\"k1\"><title>Matched by name</title></programme>"
                + "<programme start=\"20261023120000 +0000\" stop=\"20261023130000 +0000\" channel=\"other\"><title>Unwanted</title></programme>"
                + "</tv>";
        Map<String, List<IptvPlaylist.Programme>> guide = IptvPlaylist.guide(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)),
                new HashSet<>(Arrays.asList(first.guideKey(), second.guideKey())), base, base + 86400000L, 1000);
        List<IptvPlaylist.Programme> two = guide.get(first.guideKey());
        check(two != null && two.size() == 2, "Guide window/channel filter wrong");
        check(IptvPlaylist.now(two, base + 60000).title.equals("Noticias") && IptvPlaylist.now(two, base + 60000).description.equals("Édition du soir"), "Now playing wrong");
        check(IptvPlaylist.next(two, base + 60000).title.equals("Película"), "Up next wrong");
        check(guide.containsKey(second.guideKey()) && guide.size() == 2, "Display-name match or unwanted channel filter failed");
        String hostile = "<?xml version=\"1.0\"?><!DOCTYPE tv [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><tv><programme channel=\"Channel2.us\"><title>&x;</title></programme></tv>";
        try { IptvPlaylist.guide(new ByteArrayInputStream(hostile.getBytes(StandardCharsets.UTF_8)), new HashSet<>(Arrays.asList("channel2.us")), 0, Long.MAX_VALUE, 10); throw new AssertionError("Accepted DOCTYPE in guide"); }
        catch (org.xml.sax.SAXException expected) { }
        System.out.println("PASS: M3U attributes/headers/groups/skips, duplicate merging with backup streams, real iptv-org playlist (" + real.channels.size() + " channels), XMLTV time zones, now/next, guide filtering and DOCTYPE rejection");
    }
}
