import local.tv.streamtest.CatalogHttp;
import java.io.ByteArrayInputStream;
import java.net.URL;
import java.net.UnknownHostException;
import java.net.SocketTimeoutException;
import javax.net.ssl.SSLHandshakeException;

public final class CatalogHttpCheck {
    private static final URL HORROR;
    static { try { HORROR = new URL("https://ww8.123moviesfree.net/genre/horror/"); }
        catch (Exception error) { throw new RuntimeException(error); } }
    private static void reject(String destination) throws Exception {
        try { CatalogHttp.redirect(HORROR, HORROR, destination); }
        catch (CatalogHttp.Failure expected) { return; }
        throw new AssertionError("Accepted a redirect outside the requested category: " + destination);
    }
    private static void message(Throwable error, String expected) {
        String actual = CatalogHttp.problem(error);
        if (!actual.equals(expected) || actual.contains("secret")) throw new AssertionError(actual);
    }
    public static void main(String[] args) throws Exception {
        for (String html : new String[]{"<html><title>Just a moment...</title></html>", "<form id='challenge-form'>", "<form id=\"challenge-form\">"}) {
            try { CatalogHttp.requireNoVerification(html); throw new AssertionError("Challenge must trigger interactive fallback"); }
            catch (CatalogHttp.BrowserVerification expected) { message(expected, "Catalog requires browser verification"); }
        }
        CatalogHttp.requireNoVerification("<html><h1>Film</h1><iframe src='https://player.example/vod/123'></iframe></html>");
        // A home-page redirect must never have its titles stored as Horror.
        reject("/home/"); reject("/genre/comedy/"); reject("/genre/horror/2/");
        reject("https://another-host.example/genre/horror/");
        reject("//another-host.example/genre/horror/");
        reject("http://ww8.123moviesfree.net/genre/horror/");
        reject("https://secret@ww8.123moviesfree.net/genre/horror/");
        reject("https://ww8.123moviesfree.net:8443/genre/horror/");
        reject("/genre/horror/?reset=1"); reject(null); reject("");
        URL harmless = CatalogHttp.redirect(HORROR, HORROR, "/genre//horror");
        if (!harmless.getHost().equals(HORROR.getHost())) throw new AssertionError("Lost host");
        URL search = new URL("https://ww8.123moviesfree.net/search/?q=alien");
        try { CatalogHttp.redirect(search, search, "/search/?q=another"); throw new AssertionError("Lost query"); }
        catch (CatalogHttp.Failure expected) { }
        message(new UnknownHostException("secret URL"), "Cannot find catalog server (DNS)");
        message(new Exception(new UnknownHostException("secret URL")), "Cannot find catalog server (DNS)");
        message(new SocketTimeoutException("secret URL"), "Catalog connection timed out");
        message(new SSLHandshakeException("secret URL"), "Secure catalog connection failed");
        message(new Exception("secret URL"), "Catalog unavailable");
        if (CatalogHttp.read(new ByteArrayInputStream(new byte[10]), 10).length != 10) throw new AssertionError("Truncated response");
        try { CatalogHttp.read(new ByteArrayInputStream(new byte[11]), 10); throw new AssertionError("Unbounded response"); }
        catch (CatalogHttp.Failure expected) { }
        Thread.currentThread().interrupt();
        try { CatalogHttp.read(new ByteArrayInputStream(new byte[1]), 10); throw new AssertionError("Ignored cancellation"); }
        catch (InterruptedException expected) { Thread.interrupted(); }
        System.out.println("PASS: catalog redirect/category guards, sanitized network errors, size bounds and cancellation");
    }
}
