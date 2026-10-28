package local.tv.streamtest;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

public final class PlayerProtocol {
    private static final SecureRandom RANDOM = new SecureRandom();
    public static String resolverUrl(String movie, int server) throws Exception {
        return resolverUrl(movie, "1", server);
    }
    public static String resolverUrl(String movie, String episode, int server) throws Exception {
        if (!movie.matches("[0-9]{1,12}")) throw new IllegalArgumentException("Enter a numeric movie ID.");
        if (!episode.matches("[0-9]{1,12}")) throw new IllegalArgumentException("Invalid episode ID.");
        byte[] salt = new byte[8], iv = new byte[12];
        RANDOM.nextBytes(salt);
        RANDOM.nextBytes(iv);
        String payload = movie + "+" + episode + "+" + server + "+" + System.currentTimeMillis() / 1000;
        return "https://ployan.me/get/" + seal(payload, salt, iv);
    }

    // The password is the public web player's protocol constant, not a user credential.
    public static String seal(String payload, byte[] salt, byte[] iv) throws Exception {
        if (salt.length != 8 || iv.length != 12) throw new IllegalArgumentException("Invalid salt/IV");
        PBEKeySpec spec = new PBEKeySpec("player".toCharArray(), salt, 1000, 256);
        byte[] key;
        try { key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
        finally { spec.clearPassword(); }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        return hex(salt) + "-" + hex(iv) + "-" + hex(cipher.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    }

    public static String browserSource(String envelope, int server) throws Exception {
        if (server != 2) throw new IllegalArgumentException("Unsupported browser source");
        String[] parts = envelope.split("-");
        if (parts.length != 3) throw new IllegalArgumentException("Invalid source envelope");
        byte[] salt = unhex(parts[0]), iv = unhex(parts[1]), data = unhex(parts[2]);
        if (salt.length != 8 || iv.length != 12 || data.length < 16) throw new IllegalArgumentException("Invalid source envelope");
        PBEKeySpec spec = new PBEKeySpec("player".toCharArray(), salt, 1000, 256);
        byte[] key;
        try { key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
        finally { spec.clearPassword(); }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        String route = new String(cipher.doFinal(data), StandardCharsets.UTF_8);
        java.util.regex.Matcher movie = java.util.regex.Pattern.compile("^(?:movie/)?([0-9]+)(?:-.*)?$").matcher(route);
        if (movie.matches()) return "https://embos.top/movie/?mid=" + movie.group(1);
        java.util.regex.Matcher show = java.util.regex.Pattern.compile("^tv/([0-9]+)/([0-9]+)-([0-9]+)(?:-[0-9]+)?$").matcher(route);
        // Inner server 2 (VidNest) plays series reliably and supports the remote's seek/pause controls.
        if (show.matches()) return "https://embos.top/tv/?mid=" + show.group(1) + "&s=" + show.group(2) + "&e=" + show.group(3) + "&sv=2";
        throw new IllegalArgumentException("Unsupported browser route");
    }
    public static boolean browserAllowed(String address) {
        try {
            java.net.URI uri = new java.net.URI(address);
            return "https".equals(uri.getScheme()) && "embos.top".equals(uri.getHost())
                && uri.getUserInfo() == null && (uri.getPort() == -1 || uri.getPort() == 443)
                && ("/movie/".equals(uri.getPath()) || "/tv/".equals(uri.getPath()));
        } catch (Exception error) { return false; }
    }
    private static byte[] unhex(String value) {
        if (value.length() > 8192 || !value.matches("(?:[0-9a-fA-F]{2})+")) throw new IllegalArgumentException("Invalid hexadecimal source");
        byte[] bytes = new byte[value.length()/2];
        for (int i=0;i<bytes.length;i++) bytes[i]=(byte)Integer.parseInt(value.substring(i*2,i*2+2),16);
        return bytes;
    }

    private static String hex(byte[] bytes) {
        char[] alphabet = "0123456789abcdef".toCharArray();
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(alphabet[(value & 255) >>> 4]).append(alphabet[value & 15]);
        return result.toString();
    }
}
