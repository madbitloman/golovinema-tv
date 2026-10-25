package local.tv.streamtest;

import okhttp3.Dns;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.logging.Logger;

/** HTTPS DNS first for the two public catalog hosts, system DNS as their fallback; other hosts use system DNS only. */
public final class CatalogDns implements Dns {
    private static final Logger LOG = Logger.getLogger("Cinema");
    private static final Set<String> HOSTS = new HashSet<>(Arrays.asList("ww8.123moviesfree.net", "img.icdn.my.id"));
    private final Dns system;
    private final Lookup secure;
    private final LongSupplier clock;
    private final Map<String, Entry> cache = new HashMap<>();

    interface Lookup { Answer lookup(String hostname) throws Exception; }
    static final class Answer {
        final List<InetAddress> addresses;
        final long ttlSeconds;
        Answer(List<InetAddress> addresses, long ttlSeconds) {
            this.addresses = Collections.unmodifiableList(new ArrayList<>(addresses));
            this.ttlSeconds = Math.max(0, Math.min(300, ttlSeconds));
        }
    }
    private static final class Entry {
        final Answer answer;
        final long expires;
        Entry(Answer answer, long expires) { this.answer = answer; this.expires = expires; }
    }
    public static final class FallbackFailure extends UnknownHostException {
        FallbackFailure() { super("Catalog DNS-over-HTTPS fallback unavailable"); }
    }
    public static final class LookupCancelled extends UnknownHostException {
        LookupCancelled(Throwable cause) { super("Catalog request canceled"); if (cause != null) initCause(cause); }
    }

    static boolean cancelled(Throwable error) {
        if (Thread.currentThread().isInterrupted()) return true;
        for (int depth = 0; error != null && depth < 8; depth++, error = error.getCause())
            if (error instanceof LookupCancelled || error instanceof InterruptedException) return true;
        return false;
    }

    private static void requireActive() throws LookupCancelled {
        if (Thread.currentThread().isInterrupted()) throw new LookupCancelled(null);
    }

    public CatalogDns() { this(Dns.SYSTEM, cloudflare(), System::nanoTime); }
    CatalogDns(Dns system, Lookup secure, LongSupplier clock) { this.system = system; this.secure = secure; this.clock = clock; }

    @Override public List<InetAddress> lookup(String hostname) throws UnknownHostException {
        requireActive();
        String host = normalize(hostname);
        if (!HOSTS.contains(host)) return system.lookup(hostname);
        // Some resolvers answer the catalog name successfully with an unrelated server (Oct 21 TV probe:
        // certificate for t112726.com), so a successful system answer cannot be trusted to trigger a fallback.
        // TLS hostname verification still guards whichever address is used.
        FallbackFailure failure;
        try { return secureLookup(host); }
        catch (FallbackFailure error) { failure = error; }
        try { return system.lookup(hostname); }
        catch (UnknownHostException original) { requireActive(); throw failure; }
    }

    private List<InetAddress> secureLookup(String host) throws UnknownHostException {
        // Coalesce the simultaneous poster requests. Only two names can enter this cache.
        synchronized (cache) {
            requireActive();
            Entry cached = cache.get(host);
            if (cached != null && clock.getAsLong() < cached.expires) {
                if (cached.answer == null) throw new FallbackFailure();
                return cached.answer.addresses;
            }
            LOG.info("Catalog DNS: resolving over HTTPS for " + host);
            long started = System.nanoTime();
            try {
                Answer answer = secure.lookup(host);
                requireActive();
                if (answer.addresses.isEmpty()) throw new IOException("Empty DNS answer");
                cache.put(host, new Entry(answer, clock.getAsLong() + TimeUnit.SECONDS.toNanos(answer.ttlSeconds)));
                LOG.info("Catalog DNS: HTTPS lookup succeeded for " + host + " (" + answer.addresses.size() + " addresses)");
                return answer.addresses;
            } catch (Exception error) {
                if (cancelled(error)) {
                    Thread.currentThread().interrupt();
                    // Activity/worker cancellation is not evidence of resolver failure.
                    // Leave the shared cache untouched so another worker can try now.
                    LOG.info("Catalog DNS: lookup canceled for " + host + "; failure not cached");
                    throw new LookupCancelled(error);
                }
                // No response text, URLs, search phrases or credentials enter the log.
                boolean timeout = error instanceof SocketTimeoutException || (error instanceof InterruptedIOException
                        && ("timeout".equals(error.getMessage()) || "deadline reached".equals(error.getMessage())));
                String cause = error.getCause() == null ? "none" : error.getCause().getClass().getSimpleName();
                LOG.warning("Catalog DNS: HTTPS lookup failed for " + host + " (" + error.getClass().getSimpleName()
                        + "; timeout=" + timeout + "; interrupted=false; elapsedMs="
                        + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) + "; cause=" + cause + ")");
                LOG.info("Catalog DNS: using system resolver for " + host + " for 15s");
                cache.put(host, new Entry(null, clock.getAsLong() + TimeUnit.SECONDS.toNanos(15)));
                FallbackFailure failure = new FallbackFailure(); failure.initCause(error); throw failure;
            }
        }
    }

    static Lookup cloudflare() {
        // Bootstrap the DNS service itself without depending on the failing system resolver.
        // These are Cloudflare's published resolver IPs, never pinned catalog/CDN addresses.
        Dns bootstrap = hostname -> {
            if (!"cloudflare-dns.com".equalsIgnoreCase(hostname)) throw new UnknownHostException("Unexpected DNS service host");
            return Arrays.asList(InetAddress.getByAddress(hostname, new byte[]{1, 1, 1, 1}),
                    InetAddress.getByAddress(hostname, new byte[]{1, 0, 0, 1}));
        };
        OkHttpClient resolver = new OkHttpClient.Builder().dns(bootstrap)
                .connectTimeout(3, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).callTimeout(8, TimeUnit.SECONDS)
                .followRedirects(false).followSslRedirects(false).build();
        return hostname -> {
            HttpUrl url = HttpUrl.get("https://cloudflare-dns.com/dns-query").newBuilder()
                    .addQueryParameter("name", hostname).addQueryParameter("type", "A").build();
            Request request = new Request.Builder().url(url).header("Accept", "application/dns-json").build();
            try (Response response = resolver.newCall(request).execute()) {
                if (response.code() != 200 || response.body() == null) throw new IOException("DNS service response unavailable");
                String type = response.header("Content-Type", "").toLowerCase(Locale.ROOT);
                if (!type.startsWith("application/dns-json") && !type.startsWith("application/json"))
                    throw new IOException("Invalid DNS content type");
                try (InputStream input = response.body().byteStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[4096]; int count;
                    while ((count = input.read(buffer)) != -1) {
                        if (output.size() + count > 65536) throw new IOException("Oversize DNS response");
                        output.write(buffer, 0, count);
                    }
                    return parse(hostname, output.toString("UTF-8"));
                }
            }
        };
    }

    static Answer parse(String hostname, String json) throws Exception {
        if (json.length() > 65536) throw new IOException("Oversize DNS response");
        JSONObject response = new JSONObject(json);
        if (response.getInt("Status") != 0 || response.optBoolean("TC", false)) throw new IOException("DNS response unsuccessful");
        JSONArray questions = response.getJSONArray("Question");
        if (questions.length() != 1 || questions.getJSONObject(0).getInt("type") != 1
                || !normalize(questions.getJSONObject(0).getString("name")).equals(normalize(hostname)))
            throw new IOException("DNS question mismatch");
        JSONArray records = response.getJSONArray("Answer");
        if (records.length() > 32) throw new IOException("Too many DNS records");
        Set<String> owners = new HashSet<>(); owners.add(normalize(hostname));
        long ttl = 300;
        // Accept A records belonging to this name or its authenticated CNAME chain only.
        for (int pass = 0; pass < 8; pass++) {
            boolean added = false;
            for (int i = 0; i < records.length(); i++) {
                JSONObject record = records.getJSONObject(i);
                if (record.getInt("type") == 5 && owners.contains(normalize(record.getString("name")))) {
                    ttl = Math.min(ttl, Math.max(0, record.getLong("TTL")));
                    added |= owners.add(normalize(record.getString("data")));
                }
            }
            if (!added) break;
        }
        List<InetAddress> addresses = new ArrayList<>();
        for (int i = 0; i < records.length(); i++) {
            JSONObject record = records.getJSONObject(i);
            if (record.getInt("type") != 1 || !owners.contains(normalize(record.getString("name")))) continue;
            String data = record.getString("data");
            if (!data.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) throw new IOException("Invalid DNS address");
            String[] parts = data.split("\\."); byte[] bytes = new byte[4];
            for (int j = 0; j < 4; j++) {
                int value = Integer.parseInt(parts[j]); if (value > 255) throw new IOException("Invalid DNS address");
                bytes[j] = (byte) value;
            }
            InetAddress address = InetAddress.getByAddress(hostname, bytes);
            if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                    || address.isSiteLocalAddress() || address.isMulticastAddress()) throw new IOException("Non-public DNS address");
            if (!addresses.contains(address)) addresses.add(address);
            ttl = Math.min(ttl, Math.max(0, record.getLong("TTL")));
        }
        if (addresses.isEmpty()) throw new IOException("No usable DNS addresses");
        return new Answer(addresses, ttl);
    }
    private static String normalize(String host) {
        String value = host.toLowerCase(Locale.ROOT);
        return value.endsWith(".") ? value.substring(0, value.length() - 1) : value;
    }
}
