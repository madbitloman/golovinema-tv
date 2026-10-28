import local.tv.streamtest.PlayerProtocol;

public final class NativeProtocolCheck {
    public static void main(String[] args) throws Exception {
        if (args.length == 3) { System.out.println(PlayerProtocol.resolverUrl(args[0], args[1], Integer.parseInt(args[2]))); return; }
        byte[] iv = new byte[12], salt = new byte[8];
        for (int i = 0; i < iv.length; i++) iv[i] = (byte)i;
        for (int i = 0; i < salt.length; i++) salt[i] = (byte)(16 + i);
        String movieRoute = PlayerProtocol.browserSource(PlayerProtocol.seal("movie/12345-token", salt, iv), 2);
        if (!movieRoute.equals("https://embos.top/movie/?mid=12345")) throw new AssertionError(movieRoute);
        String tvRoute = PlayerProtocol.browserSource(PlayerProtocol.seal("tv/123/2-4", salt, iv), 2);
        if (!tvRoute.equals("https://embos.top/tv/?mid=123&s=2&e=4&sv=2")) throw new AssertionError(tvRoute);
        // The resolver now appends a timestamp to TV routes.
        String stamped = PlayerProtocol.browserSource(PlayerProtocol.seal("tv/250923/3-1-1793194749", salt, iv), 2);
        if (!stamped.equals("https://embos.top/tv/?mid=250923&s=3&e=1&sv=2")) throw new AssertionError(stamped);
        if (!PlayerProtocol.browserAllowed(movieRoute) || PlayerProtocol.browserAllowed("https://embos.top.evil/movie/?mid=123")) throw new AssertionError("Browser origin validation");
        boolean rejected = false;
        try { PlayerProtocol.browserSource(PlayerProtocol.seal("https://evil.example/", salt, iv), 2); } catch (IllegalArgumentException expected) { rejected = true; }
        if (!rejected) throw new AssertionError("Unexpected route accepted");
        System.out.println(PlayerProtocol.seal("2101+1+1+1791987000", salt, iv));
    }
}
