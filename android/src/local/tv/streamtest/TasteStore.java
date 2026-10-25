package local.tv.streamtest;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Bounded local history; optional background Jev calls, never on the playback path. */
public final class TasteStore {
    private final SharedPreferences prefs;
    private final DeviceKeyStore keys;
    private final JevClient client = new JevClient();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable changed;
    private Future<?> pending;
    private long revision;
    private boolean closed, running;
    public TasteStore(Context context, Runnable changed) {
        prefs = context.getSharedPreferences("cinema_taste", 0); keys = new DeviceKeyStore(context, "jev"); this.changed = changed;
    }
    public synchronized boolean enabled() { return prefs.getBoolean("enabled", false) && keys.present(); }
    public boolean hasKey() { return keys.present(); }
    public synchronized JSONArray history() {
        try {
            String saved = prefs.getString("history", "[]");
            JSONArray recent = TasteProtocol.recent(new JSONArray(saved), System.currentTimeMillis());
            if (!saved.equals(recent.toString())) prefs.edit().putString("history", recent.toString()).apply();
            return recent;
        } catch (Exception ignored) { prefs.edit().remove("history").apply(); return new JSONArray(); }
    }
    public synchronized void recordSearch(String query) {
        try { cancel(); prefs.edit().putString("history", TasteProtocol.addSearch(history(), query, System.currentTimeMillis()).toString()).apply(); }
        catch (Exception ignored) { }
    }
    private synchronized JSONObject profile() {
        if (!enabled()) return new JSONObject();
        try {
            JSONObject result = new JSONObject(prefs.getString("profile", "{}"));
            if (System.currentTimeMillis() - result.optLong("time") > 30L * 86400000) { prefs.edit().remove("profile").apply(); return new JSONObject(); }
            return result;
        } catch (Exception ignored) { return new JSONObject(); }
    }
    public List<Movie> rank(List<Movie> movies) { return TasteProtocol.rank(movies, profile()); }
    public boolean hasProfile() { return profile().has("time"); }
    public synchronized void saveKey(String key) throws Exception { cancel(); keys.save(key); prefs.edit().putBoolean("enabled", true).remove("last_attempt").apply(); }
    public synchronized void setEnabled(boolean enabled) { cancel(); prefs.edit().putBoolean("enabled", enabled).apply(); }
    public synchronized void removeKey() { setEnabled(false); keys.remove(); }
    public synchronized void clearHistory() { cancel(); prefs.edit().remove("history").remove("profile").remove("status").remove("last_attempt").apply(); }
    private synchronized void cancel() { revision++; if (pending != null) pending.cancel(true); client.cancel(); running = false; }
    public synchronized String summary() {
        StringBuilder text = new StringBuilder("Your choice: Horror\n");
        text.append(history().length()).append(" recent searches saved on this TV (up to 30, from the last 30 days).\n\n");
        if (!hasKey()) text.append("Add your TypeSafe API key to enable Jev.");
        else if (!enabled()) text.append("Jev is paused.");
        else text.append(running ? "Jev is updating your preferences…" : prefs.getString("status", "Jev is ready. Search for titles to begin learning."));
        JSONObject interests = profile().optJSONObject("interests");
        if (interests != null) {
            StringBuilder inferred = new StringBuilder();
            for (String label : TasteProtocol.LABELS) if (!label.equals("Horror") && interests.optDouble(label) >= .7) { if (inferred.length() > 0) inferred.append(", "); inferred.append(label); }
            if (inferred.length() > 0) text.append("\n\nSuggested interests: ").append(inferred).append(". These are guesses from searches, not confirmed likes.");
        }
        text.append("\n\nWhen enabled, recent search text and a small catalog shortlist go to TypeSafe. Browsing and playback continue if Jev is unavailable.");
        return text.toString();
    }
    public synchronized void refresh(List<Movie> catalog, boolean manual) {
        if (closed || running || !enabled() || history().length() == 0) return;
        long now = System.currentTimeMillis(), last = prefs.getLong("last_attempt", 0);
        if (now - last < (manual ? 30000 : 300000)) return;
        final JSONObject request; final long token = revision;
        try {
            request = TasteProtocol.request(history(), catalog);
            if (!manual && TasteProtocol.hash(request).equals(profile().optString("request_hash"))) return;
        } catch (Exception error) { return; }
        prefs.edit().putLong("last_attempt", now).apply(); running = true;
        pending = worker.submit(() -> {
            try {
                JSONObject result = TasteProtocol.validate(request, client.evaluate(request, keys.read()), System.currentTimeMillis());
                synchronized (TasteStore.this) {
                    if (closed || token != revision || !enabled()) return;
                    prefs.edit().putString("profile", result.toString()).putString("status", "Preferences updated from your searches.").apply();
                }
                Log.i("Cinema", "Jev preference update accepted; model=" + TasteProtocol.MODEL);
            } catch (Exception error) {
                synchronized (TasteStore.this) {
                    if (closed || token != revision || !enabled()) return;
                    // Never log request text, credentials, provider bodies or arbitrary exception messages.
                    String message = error.getMessage();
                    boolean auth = "Jev HTTP 401".equals(message) || "Jev HTTP 403".equals(message);
                    prefs.edit().putString("status", auth ? "Jev rejected the API key. Check it in settings." : "Jev is unavailable. Your saved preferences still apply.").apply();
                }
                Log.w("Cinema", "Jev update failed: " + error.getClass().getSimpleName());
            } finally {
                synchronized (TasteStore.this) { if (token == revision) running = false; }
                main.post(() -> { synchronized (TasteStore.this) { if (!closed && token == revision) changed.run(); } });
            }
        });
    }
    public synchronized void close() { closed = true; cancel(); worker.shutdownNow(); main.removeCallbacksAndMessages(null); }
}
