package local.tv.streamtest;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.lang.reflect.Proxy;
import java.security.Provider;
import java.security.Security;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import okhttp3.OkHttpClient;
import org.conscrypt.Conscrypt;

/** Offline trust-boundary checks; optional Mac Conscrypt jar exercises the bundled-provider path. */
public final class CatalogTlsCheck {
    public static void main(String[] args) throws Exception {
        Provider[] before = Security.getProviders();
        SSLContext defaultContext = SSLContext.getDefault();
        boolean conscrypt = args.length > 1 && "--conscrypt".equals(args[1]);
        Provider provider = conscrypt ? Conscrypt.newProviderBuilder().provideTrustManager(false)
                .isTlsV1Enabled(false).build() : defaultContext.getProvider();
        OkHttpClient.Builder builder = new OkHttpClient.Builder();
        CatalogTls.configure(builder, provider);
        OkHttpClient client = builder.build();
        try (SSLSocket socket = (SSLSocket) client.sslSocketFactory().createSocket()) {
            if (!Arrays.asList(socket.getEnabledProtocols()).contains("TLSv1.3"))
                throw new AssertionError("TLS 1.3 not enabled");
        }
        if (conscrypt && !Conscrypt.isConscrypt(client.sslSocketFactory()))
            throw new AssertionError("Did not exercise the Conscrypt provider");
        if (!Arrays.equals(before, Security.getProviders()) || defaultContext != SSLContext.getDefault())
            throw new AssertionError("Changed global TLS or crypto providers");
        X509Certificate selfSigned;
        try (InputStream input = Files.newInputStream(Paths.get(args[0]))) {
            selfSigned = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(input);
        }
        try {
            client.x509TrustManager().checkServerTrusted(new X509Certificate[] { selfSigned }, "RSA");
            throw new AssertionError("Accepted an untrusted self-signed certificate");
        } catch (CertificateException expected) { }
        // The TLS trust check above and the HTTP client's hostname check are separate.
        // A certificate's names must be checked even after its issuer is trusted.
        SSLSession session = (SSLSession) Proxy.newProxyInstance(CatalogTlsCheck.class.getClassLoader(),
                new Class<?>[] { SSLSession.class }, (proxy, method, values) -> {
                    if (method.getName().equals("getPeerCertificates")) return new X509Certificate[] { selfSigned };
                    throw new UnsupportedOperationException(method.getName());
                });
        if (!client.hostnameVerifier().verify("localhost", session)
                || client.hostnameVerifier().verify("ww8.123moviesfree.net", session))
            throw new AssertionError("Hostname verification failed");
        System.out.println("PASS: " + (conscrypt ? "Conscrypt" : "platform")
                + " TLS 1.3; untrusted certificates and wrong hostnames rejected; global providers unchanged");
    }
}
