package local.tv.streamtest;

import java.io.BufferedInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import javax.xml.parsers.SAXParserFactory;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/** M3U playlists and XMLTV guides supplied by the user; the app ships no channels of its own. */
public final class IptvPlaylist {
    public static final int MAX_CHANNELS = 20000;
    private static final Pattern ATTRIBUTE = Pattern.compile("([A-Za-z0-9-]+)=\"([^\"]*)\"");
    // Precompiled: String.replaceAll recompiles on every call, which is very slow on TV CPUs.
    private static final Pattern QUALITY = Pattern.compile("\\((?:[0-9]{3,4}p|hd|sd|fhd|uhd|4k)\\)");
    private static final Pattern NOT_KEY = Pattern.compile("[^\\p{L}\\p{N}.]");
    private static final Pattern TAGS = Pattern.compile("\\[[^\\]]*\\]");
    private static final Pattern RESOLUTION = Pattern.compile("\\(([0-9]{3,4})p\\)");

    public static final class Channel {
        public final int number;
        public final String name, url, logo, group, tvgId, userAgent, referrer;
        Channel(int number, String name, String url, String logo, String group, String tvgId, String userAgent, String referrer) {
            this.number = number; this.name = name; this.url = url; this.logo = logo; this.group = group;
            this.tvgId = tvgId; this.userAgent = userAgent; this.referrer = referrer;
        }
        /** Other streams of the same channel, best first; tried when this one fails. */
        public final List<Channel> backups = new ArrayList<>();
        /** Guide key: iptv-org ids carry a feed suffix ("Channel2.us@SD") that guides omit. */
        public String guideKey() { return key(tvgId.isEmpty() ? name : tvgId); }
        public boolean has(String address) {
            if (url.equals(address)) return true;
            for (Channel backup : backups) if (backup.url.equals(address)) return true;
            return false;
        }
        /** Prefer streams that work everywhere and all day, then higher resolution. */
        int score() {
            Matcher resolution = RESOLUTION.matcher(name);
            int value = resolution.find() ? Math.min(Integer.parseInt(resolution.group(1)), 2160) : 0;
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.contains("[geo-blocked]")) value -= 10000;
            if (lower.contains("[not 24/7]")) value -= 5000;
            return value;
        }
    }
    public static final class Playlist {
        public final List<Channel> channels;
        public final List<String> groups;
        public final String guideUrl;
        public final int skipped, duplicates;
        Playlist(List<Channel> channels, List<String> groups, String guideUrl, int skipped, int duplicates) {
            this.channels = Collections.unmodifiableList(channels); this.groups = Collections.unmodifiableList(groups);
            this.guideUrl = guideUrl; this.skipped = skipped; this.duplicates = duplicates;
        }
    }
    public static final class Programme {
        public final long start, stop;
        public final String title, description;
        Programme(long start, long stop, String title, String description) {
            this.start = start; this.stop = stop; this.title = title; this.description = description;
        }
    }

    static String key(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        int feed = lower.indexOf('@'); if (feed >= 0) lower = lower.substring(0, feed);
        return NOT_KEY.matcher(QUALITY.matcher(TAGS.matcher(lower).replaceAll("")).replaceAll("")).replaceAll("");
    }

    public static Playlist parse(String text) throws IOException {
        if (text.startsWith("﻿")) text = text.substring(1);
        String[] lines = text.split("\r\n|\r|\n");
        if (lines.length == 0 || !lines[0].trim().startsWith("#EXTM3U")) throw new IOException("Not an M3U playlist");
        String guideUrl = "";
        Matcher header = ATTRIBUTE.matcher(lines[0]);
        while (header.find()) if (header.group(1).equalsIgnoreCase("url-tvg") || header.group(1).equalsIgnoreCase("x-tvg-url"))
            guideUrl = header.group(2).split(",")[0].trim();
        List<Channel> channels = new ArrayList<>(); Set<String> groups = new LinkedHashSet<>();
        Map<String, String> info = null; String name = "", agent = "", referrer = "", group = "";
        int skipped = 0, duplicates = 0;
        // One entry per channel: same tvg-id (feed suffix ignored), or same name when there is no id.
        Map<String, Integer> byChannel = new HashMap<>(); Set<String> urls = new java.util.HashSet<>();
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("#EXTINF")) {
                info = new HashMap<>(); agent = ""; referrer = ""; group = "";
                int comma = commaAfterAttributes(line);
                Matcher attributes = ATTRIBUTE.matcher(comma < 0 ? line : line.substring(0, comma));
                while (attributes.find()) info.put(attributes.group(1).toLowerCase(Locale.ROOT), attributes.group(2).trim());
                name = comma < 0 ? "" : line.substring(comma + 1).trim();
                agent = info.getOrDefault("http-user-agent", ""); referrer = info.getOrDefault("http-referrer", "");
            } else if (line.startsWith("#EXTVLCOPT:")) {
                String option = line.substring(11);
                if (option.startsWith("http-user-agent=")) agent = option.substring(16).trim();
                if (option.startsWith("http-referrer=")) referrer = option.substring(14).trim();
            } else if (line.startsWith("#EXTGRP:")) {
                group = line.substring(8).trim();
            } else if (!line.startsWith("#")) {
                if (info == null) { skipped++; continue; }
                String ace = AceStream.contentId(line);
                if (!ace.isEmpty()) line = AceStream.link(ace);
                else if (!playable(line)) { skipped++; info = null; continue; }
                if (!urls.add(line)) { duplicates++; info = null; continue; }
                String channelGroup = info.getOrDefault("group-title", group);
                if (channelGroup.isEmpty()) channelGroup = "Other";
                // iptv-org joins several categories with ';' — the first is the channel's main group.
                channelGroup = channelGroup.split(";")[0].trim();
                if (name.isEmpty()) name = info.getOrDefault("tvg-name", "Channel " + (channels.size() + 1));
                String logo = info.getOrDefault("tvg-logo", ""), tvgId = info.getOrDefault("tvg-id", "");
                String identity = tvgId.isEmpty() ? "name:" + key(name) : "id:" + key(tvgId);
                Integer existing = byChannel.get(identity);
                if (existing == null && channels.size() >= MAX_CHANNELS) { skipped++; info = null; continue; }
                int number = existing == null ? channels.size() + 1 : existing + 1;
                Channel channel = new Channel(number, name, line, logo.startsWith("https://") ? logo : "", channelGroup, tvgId, header(agent), header(referrer));
                if (existing == null) {
                    byChannel.put(identity, channels.size()); channels.add(channel); groups.add(channelGroup);
                } else {
                    duplicates++;
                    Channel primary = channels.get(existing);
                    if (channel.score() > primary.score()) {
                        // Better stream takes the slot; keep the original group so lists don't shuffle.
                        Channel promoted = new Channel(number, channel.name, channel.url, channel.logo.isEmpty() ? primary.logo : channel.logo,
                                primary.group, channel.tvgId, channel.userAgent, channel.referrer);
                        promoted.backups.add(primary); promoted.backups.addAll(primary.backups); primary.backups.clear();
                        channels.set(existing, promoted);
                    } else {
                        int at = 0;
                        while (at < primary.backups.size() && primary.backups.get(at).score() >= channel.score()) at++;
                        primary.backups.add(at, channel);
                    }
                }
                info = null;
            }
        }
        if (channels.isEmpty()) throw new IOException("Playlist has no playable channels");
        List<String> sorted = new ArrayList<>(groups); Collections.sort(sorted, String.CASE_INSENSITIVE_ORDER);
        return new Playlist(channels, sorted, playable(guideUrl) ? guideUrl : "", skipped, duplicates);
    }
    private static int commaAfterAttributes(String line) {
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') quoted = !quoted; else if (c == ',' && !quoted) return i;
        }
        return -1;
    }
    static boolean playable(String address) {
        try {
            URI uri = new URI(address);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            return (scheme.equals("http") || scheme.equals("https")) && uri.getHost() != null && uri.getUserInfo() == null;
        } catch (Exception error) { return false; }
    }
    private static String header(String value) { return value.length() > 512 || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0 ? "" : value; }

    /** Streams XMLTV, keeping only wanted channels and programmes overlapping [from, to]. */
    public static Map<String, List<Programme>> guide(InputStream input, Set<String> wanted, long from, long to, int limit) throws Exception {
        Map<String, String> ids = new HashMap<>(), keys = new HashMap<>();
        Map<String, List<Programme>> guide = new HashMap<>();
        int[] count = {0};
        SAXParserFactory factory = SAXParserFactory.newInstance();
        factory.setNamespaceAware(false);
        try { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); } catch (Exception unsupported) { }
        try { factory.setFeature("http://xml.org/sax/features/external-general-entities", false); } catch (Exception unsupported) { }
        DefaultHandler handler = new DefaultHandler() {
            String channelId, element, programmeKey; long start, stop;
            final StringBuilder text = new StringBuilder(); String title = "", description = "";
            @Override public void startElement(String uri, String local, String name, Attributes attributes) throws SAXException {
                if (Thread.currentThread().isInterrupted()) throw new SAXException(new InterruptedException());
                element = name; text.setLength(0);
                if (name.equals("channel")) { channelId = attributes.getValue("id"); if (channelId != null && wanted.contains(key(channelId))) ids.put(channelId, key(channelId)); }
                else if (name.equals("programme")) {
                    String channel = attributes.getValue("channel");
                    if (channel == null) programmeKey = null;
                    else if (ids.containsKey(channel)) programmeKey = ids.get(channel);
                    else {
                        String key = keys.computeIfAbsent(channel, IptvPlaylist::key);
                        programmeKey = wanted.contains(key) ? key : null;
                    }
                    start = time(attributes.getValue("start")); stop = time(attributes.getValue("stop"));
                    title = ""; description = "";
                }
            }
            @Override public void characters(char[] chars, int offset, int length) { if (text.length() < 2048) text.append(chars, offset, Math.min(length, 2048 - text.length())); }
            @Override public void endElement(String uri, String local, String name) {
                if (name.equals("display-name") && channelId != null && !ids.containsKey(channelId) && wanted.contains(key(text.toString())))
                    ids.put(channelId, key(text.toString()));
                else if (name.equals("channel")) channelId = null;
                else if (name.equals("title") && programmeKey != null && title.isEmpty()) title = text.toString().trim();
                else if (name.equals("desc") && programmeKey != null && description.isEmpty()) description = text.toString().trim();
                else if (name.equals("programme")) {
                    if (programmeKey != null && start > 0 && stop > start && stop > from && start < to && !title.isEmpty() && count[0] < limit) {
                        guide.computeIfAbsent(programmeKey, k -> new ArrayList<>()).add(new Programme(start, stop, title, description));
                        count[0]++;
                    }
                    programmeKey = null;
                }
                text.setLength(0);
            }
        };
        factory.newSAXParser().parse(new InputSource(input), handler);
        for (List<Programme> list : guide.values()) list.sort((a, b) -> Long.compare(a.start, b.start));
        return guide;
    }
    static long time(String value) {
        if (value == null) return 0;
        value = value.trim();
        if (value.length() < 14) return 0;
        for (int i = 0; i < 14; i++) if (!Character.isDigit(value.charAt(i))) return 0;
        int year = Integer.parseInt(value.substring(0, 4)), month = Integer.parseInt(value.substring(4, 6)), day = Integer.parseInt(value.substring(6, 8));
        int hour = Integer.parseInt(value.substring(8, 10)), minute = Integer.parseInt(value.substring(10, 12)), second = Integer.parseInt(value.substring(12, 14));
        if (month < 1 || month > 12 || day < 1 || day > 31 || hour > 23 || minute > 59 || second > 60) return 0;
        long offset = 0;
        String zone = value.substring(14).trim();
        if (!zone.isEmpty()) {
            if (!zone.matches("[+-][0-9]{4}")) return 0;
            offset = (Integer.parseInt(zone.substring(1, 3)) * 60L + Integer.parseInt(zone.substring(3, 5))) * 60000L * (zone.charAt(0) == '-' ? -1 : 1);
        }
        // Days from civil date (proleptic Gregorian), Howard Hinnant's algorithm.
        int y = month <= 2 ? year - 1 : year;
        long era = (y >= 0 ? y : y - 399) / 400, yoe = y - era * 400;
        long doy = (153L * (month + (month > 2 ? -3 : 9)) + 2) / 5 + day - 1, doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
        long days = era * 146097 + doe - 719468;
        return ((days * 24 + hour) * 60 + minute) * 60000L + second * 1000L - offset;
    }
    public static Programme now(List<Programme> programmes, long at) {
        if (programmes != null) for (Programme p : programmes) if (p.start <= at && at < p.stop) return p;
        return null;
    }
    public static Programme next(List<Programme> programmes, long at) {
        if (programmes != null) for (Programme p : programmes) if (p.start >= at) return p;
        return null;
    }

    // Playlists and guides may come from any user-chosen host, over http or https.
    private static OkHttpClient client;
    private static synchronized OkHttpClient client() throws Exception {
        if (client == null) client = CatalogTls.configure(new OkHttpClient.Builder())
                .connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build();
        return client;
    }
    public static InputStream open(String address, long limit) throws Exception {
        if (!playable(address)) throw new IOException("Unsupported playlist address");
        Response response = client().newCall(new Request.Builder().url(address).header("User-Agent", "GOLOVINEMA/0.6 (Android TV)").build()).execute();
        if (response.code() != 200 || response.body() == null) { response.close(); throw new IOException("Server returned HTTP " + response.code()); }
        InputStream body = new BufferedInputStream(new Limited(response.body().byteStream(), limit, response));
        body.mark(2);
        boolean gzip = body.read() == 0x1f && body.read() == 0x8b;
        body.reset();
        return gzip ? new GZIPInputStream(body) : body;
    }
    public static String text(String address, int limit) throws Exception {
        try (InputStream input = open(address, limit)) { return new String(CatalogHttp.read(input, limit), "UTF-8"); }
    }
    private static final class Limited extends FilterInputStream {
        private final long limit; private long read; private final Response response;
        Limited(InputStream input, long limit, Response response) { super(input); this.limit = limit; this.response = response; }
        private void count(long n) throws IOException { if (n > 0 && (read += n) > limit) throw new IOException("Download too large"); }
        @Override public int read() throws IOException { int b = super.read(); if (b >= 0) count(1); return b; }
        @Override public int read(byte[] buffer, int offset, int length) throws IOException { int n = super.read(buffer, offset, length); count(n); return n; }
        @Override public void close() throws IOException { try { super.close(); } finally { response.close(); } }
    }

    private IptvPlaylist() { }
}
