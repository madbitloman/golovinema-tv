package local.tv.streamtest;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** The debuggable personal APK accepts keys through an authorized ADB run-as pipe. */
public final class ServiceKeyImporter {
    public static String importPending(Context context) {
        File pending = new File(context.getFilesDir(), "cinema-keys.json");
        if (!pending.isFile()) return "";
        JSONObject receipt = new JSONObject(); JSONArray saved = new JSONArray();
        boolean accepted = false, removed;
        try {
            if ((context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0) throw new IllegalStateException();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (FileInputStream input = new FileInputStream(pending)) {
                byte[] buffer = new byte[1024]; int count;
                while ((count = input.read(buffer)) != -1) {
                    if (bytes.size() + count > ServiceKeyPayload.LIMIT) throw new IllegalArgumentException();
                    bytes.write(buffer, 0, count);
                }
            }
            JSONObject payload = ServiceKeyPayload.parse(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
            receipt.put("request", payload.getString("request"));
            JSONObject keys = payload.getJSONObject("keys");
            for (String provider : new String[]{"omdb", "jev"}) if (keys.has(provider)) {
                DeviceKeyStore store = new DeviceKeyStore(context, provider);
                store.save(keys.getString(provider));
                if (!keys.getString(provider).equals(store.read())) throw new IllegalStateException();
                saved.put(provider);
            }
            // Adding Jev through this script leaves it paused until enabled on the TV.
            if (keys.has("jev") && !context.getSharedPreferences("cinema_taste", 0).edit().putBoolean("enabled", false).commit()) throw new IllegalStateException();
            accepted = true;
        } catch (Exception ignored) {
            // Provider keys, payloads, cipher text and exception messages never enter logs.
        } finally { removed = pending.delete(); }
        try {
            receipt.put("saved", saved).put("ok", accepted && removed).put("pending_removed", removed);
            try (FileOutputStream output = context.openFileOutput("cinema-keys-result.json", Context.MODE_PRIVATE)) {
                output.write(receipt.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) { return "Key setup could not be confirmed. Open settings to check."; }
        return accepted && removed ? "Service keys saved securely." : "Key setup could not be completed. Check Terminal.";
    }
}
