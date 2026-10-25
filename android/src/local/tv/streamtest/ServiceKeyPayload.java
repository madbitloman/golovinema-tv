package local.tv.streamtest;

import org.json.JSONObject;
import java.nio.charset.StandardCharsets;

/** Strict schema for the private ADB stdin handoff; contains no credentials itself. */
public final class ServiceKeyPayload {
    public static final int LIMIT = 12288;
    public static JSONObject parse(String text) throws Exception {
        if (text.getBytes(StandardCharsets.UTF_8).length > LIMIT) throw new IllegalArgumentException("Key setup too large");
        JSONObject data = new JSONObject(text);
        if (data.optInt("version") != 1 || !data.optString("request").matches("[a-f0-9]{32}")) throw new IllegalArgumentException("Invalid key setup");
        JSONObject keys = data.getJSONObject("keys");
        if (keys.length() < 1 || keys.length() > 2) throw new IllegalArgumentException("Invalid key setup");
        java.util.Iterator<String> names = keys.keys();
        while (names.hasNext()) {
            String name = names.next();
            if (!name.equals("omdb") && !name.equals("jev")) throw new IllegalArgumentException("Unknown key provider");
            Object raw = keys.get(name);
            if (!(raw instanceof String)) throw new IllegalArgumentException("Invalid API key");
            String value = ((String)raw).trim();
            if (value.isEmpty() || value.length() > 4096 || ((String)raw).matches("(?s).*[\\p{Cntrl}].*")) throw new IllegalArgumentException("Invalid API key");
            keys.put(name, value);
        }
        return data;
    }
}
