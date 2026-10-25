package local.tv.streamtest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import javax.net.ssl.SSLException;
import java.util.concurrent.TimeUnit;
import java.security.GeneralSecurityException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;
import java.util.logging.Logger;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** Bounded catalog transport. Errors contain no response bodies, cookies or query strings. */
public final class CatalogHttp {
    private static final String CATALOG_HOST = "ww8.123moviesfree.net";
    private static final int MAX_REDIRECTS = 5;
    private static final Logger LOG = Logger.getLogger("Cinema");
    private static final Set<String> REPORTED_HOSTS = ConcurrentHashMap.newKeySet();
    private static OkHttpClient client;

    private static synchronized OkHttpClient client() throws Failure {
        if (client == null) {
            try {
                client = CatalogTls.configure(new OkHttpClient.Builder()).dns(new CatalogDns())
                        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS).callTimeout(25, TimeUnit.SECONDS)
                        .followRedirects(false).followSslRedirects(false).build();
            } catch (GeneralSecurityException | RuntimeException error) {
                LOG.warning("Catalog TLS provider failed: " + error.getClass().getSimpleName());
                throw new Failure("Catalog TLS 1.3 support could not initialize");
            }
        }
        return client;
    }

    public static class Failure extends IOException {
        Failure(String message) { super(message); }
    }
    public static final class BrowserVerification extends Failure {
        BrowserVerification() { super("Catalog requires browser verification"); }
    }

    public static String problem(Throwable error) {
        if (cancelled(error)) return "Catalog request canceled";
        for (int depth = 0; error != null && depth < 8; depth++, error = error.getCause()) {
            if (error instanceof Failure) return error.getMessage();
            if (error instanceof CatalogDns.FallbackFailure) return "Catalog DNS-over-HTTPS fallback unavailable";
            if (error instanceof UnknownHostException) return "Cannot find catalog server (DNS)";
            if (error instanceof SocketTimeoutException) return "Catalog connection timed out";
            if (error instanceof SSLException) return "Secure catalog connection failed";
            if (error instanceof ConnectException || error instanceof NoRouteToHostException) return "Cannot connect to catalog server";
        }
        return "Catalog unavailable";
    }

    public static boolean cancelled(Throwable error) { return CatalogDns.cancelled(error); }

    public static byte[] bytes(String address, int limit) throws Exception {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        URL original = new URL(address), current = original;
        requireHttps(original);
        HashSet<String> visited = new HashSet<>();
        for (int redirects = 0; ; redirects++) {
            if (!visited.add(current.toExternalForm())) throw new Failure("Catalog redirect loop");
            // The URL keeps its hostname: OkHttp uses custom DNS for routing only,
            // while its default trust manager, SNI and hostname verification stay intact.
            Request request = new Request.Builder().url(current)
                    .header("User-Agent", "Cinema/0.3 (Android TV)").header("Accept", "*/*").build();
            try (Response response = client().newCall(request).execute()) {
                int status = response.code();
                String host = current.getHost();
                if (response.handshake() != null && REPORTED_HOSTS.add(host))
                    LOG.info("Catalog HTTPS: " + host + "; " + response.handshake().tlsVersion()
                            + "; HTTP " + status + "; " + response.protocol());
                if ("challenge".equalsIgnoreCase(response.header("cf-mitigated")))
                    throw new BrowserVerification();
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    if (redirects >= MAX_REDIRECTS) throw new Failure("Too many catalog redirects");
                    current = redirect(original, current, response.header("Location"));
                    continue;
                }
                if (status != 200) throw new Failure(status == 403 ? "Catalog access denied (HTTP 403)"
                        : status == 429 ? "Catalog busy; try again later (HTTP 429)" : "Catalog server returned HTTP " + status);
                if (response.body() == null) throw new Failure("Catalog returned no data");
                try (InputStream input = response.body().byteStream()) { return read(input, limit); }
            }
        }
    }

    public static String text(String address, int limit) throws Exception {
        String body = new String(bytes(address, limit), StandardCharsets.UTF_8);
        requireNoVerification(body);
        return body;
    }
    public static void requireNoVerification(String body) throws BrowserVerification {
        // Do not mistake a successful HTTP challenge response for an empty catalog.
        String start = body.substring(0, Math.min(body.length(), 16384)).toLowerCase(Locale.ROOT);
        if (start.contains("<title>just a moment") || start.contains("id=\"challenge-form\"")
                || start.contains("id='challenge-form'")) throw new BrowserVerification();
    }

    public static URL redirect(URL original, URL current, String location) throws Exception {
        if (location == null || location.trim().isEmpty()) throw new Failure("Catalog redirect has no destination");
        URL next = new URL(current, location);
        requireHttps(next);
        if (CATALOG_HOST.equalsIgnoreCase(original.getHost()) && (!sameOrigin(original, next)
                || !normalizedPath(original).equals(normalizedPath(next))
                || !java.util.Objects.equals(original.getQuery(), next.getQuery())))
            throw new Failure("Catalog redirected to a different page");
        return next;
    }

    private static boolean sameOrigin(URL a, URL b) {
        return a.getHost().equalsIgnoreCase(b.getHost())
                && (a.getPort() == -1 ? 443 : a.getPort()) == (b.getPort() == -1 ? 443 : b.getPort());
    }
    private static String normalizedPath(URL url) {
        String path = url.getPath().replaceAll("/+", "/");
        return path.endsWith("/") ? path : path + "/";
    }
    private static void requireHttps(URL url) throws Failure {
        if (!"https".equalsIgnoreCase(url.getProtocol()) || url.getUserInfo() != null)
            throw new Failure("Catalog redirected to an unsupported address");
    }
    public static byte[] read(InputStream input, int limit) throws Exception {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                if (count > limit - output.size()) throw new Failure("Catalog response too large");
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }
    private CatalogHttp() { }
}
