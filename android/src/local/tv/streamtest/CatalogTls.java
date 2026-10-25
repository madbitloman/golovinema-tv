package local.tv.streamtest;

import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.Provider;
import java.util.Arrays;
import java.util.logging.Logger;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import okhttp3.OkHttpClient;
import org.conscrypt.Conscrypt;

/** Client-scoped TLS 1.3 for older TVs; does not replace any global provider. */
public final class CatalogTls {
    private static final Logger LOG = Logger.getLogger("Cinema");

    public static OkHttpClient.Builder configure(OkHttpClient.Builder builder) throws GeneralSecurityException {
        if (Arrays.asList(SSLContext.getDefault().getDefaultSSLParameters().getProtocols()).contains("TLSv1.3")) {
            LOG.info("Catalog TLS: platform supports TLS 1.3");
            return builder;
        }
        try {
            Conscrypt.checkAvailability();
            Provider provider = Conscrypt.newProviderBuilder().setName("CinemaCatalogTLS")
                    .provideTrustManager(false).isTlsV1Enabled(false).build();
            configure(builder, provider);
            LOG.info("Catalog TLS: Conscrypt 2.7.0 enabled; TLS 1.3 available; system certificate trust retained");
            return builder;
        } catch (LinkageError error) {
            throw new GeneralSecurityException("Bundled TLS provider could not load", error);
        }
    }

    // Explicit provider selection applies only to this SSLContext. Retain the
    // platform trust manager (including Android's app network-security policy).
    static void configure(OkHttpClient.Builder builder, Provider provider) throws GeneralSecurityException {
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init((KeyStore) null);
        X509TrustManager manager = null;
        for (TrustManager candidate : trust.getTrustManagers())
            if (candidate instanceof X509TrustManager) { manager = (X509TrustManager) candidate; break; }
        if (manager == null) throw new GeneralSecurityException("System certificate trust unavailable");
        SSLContext context = SSLContext.getInstance("TLSv1.3", provider);
        context.init(null, new TrustManager[] { manager }, null);
        if (!Arrays.asList(context.getDefaultSSLParameters().getProtocols()).contains("TLSv1.3"))
            throw new GeneralSecurityException("Provider did not enable TLS 1.3");
        builder.sslSocketFactory(context.getSocketFactory(), manager);
        // OkHttp's default hostname verifier and its Conscrypt SNI/ALPN support
        // remain in use. Never install a permissive trust manager or verifier.
    }

    private CatalogTls() { }
}
