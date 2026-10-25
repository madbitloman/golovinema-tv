package local.tv.streamtest;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;
import java.io.File;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.lang.ref.WeakReference;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class PosterLoader {
    private final ExecutorService pool = Executors.newFixedThreadPool(3);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final File directory;
    private final HashMap<String, List<WeakReference<ImageView>>> pending = new HashMap<>();
    private final HashMap<String, Long> failures = new HashMap<>();
    private volatile boolean closed;
    private final LruCache<String, Bitmap> memory = new LruCache<String, Bitmap>(16 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap bitmap) { return bitmap.getByteCount(); }
    };
    public PosterLoader(Context context) { directory = new File(context.getCacheDir(), "posters"); directory.mkdirs(); }
    public synchronized void load(String url, ImageView image) {
        image.setTag(url);
        if (closed || url == null || !url.startsWith("https://")) return;
        Bitmap cached = memory.get(url);
        if (cached != null) { image.setImageBitmap(cached); return; }
        Long failedAt = failures.get(url);
        if (failedAt != null && System.currentTimeMillis() - failedAt < 60000) return;
        List<WeakReference<ImageView>> waiting = pending.get(url);
        if (waiting != null) { waiting.add(new WeakReference<>(image)); return; }
        waiting = new ArrayList<>(); waiting.add(new WeakReference<>(image)); pending.put(url, waiting);
        pool.execute(() -> {
            Bitmap loaded = null;
            try {
                StringBuilder key = new StringBuilder();
                for (byte value : MessageDigest.getInstance("SHA-256").digest(url.getBytes("UTF-8"))) key.append(String.format("%02x", value & 255));
                File file = new File(directory, key.toString());
                if (!file.isFile()) {
                    byte[] bytes = CatalogStore.bytes(url, 4194304);
                    BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
                    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new Exception("Not an image");
                    try (FileOutputStream out = new FileOutputStream(file)) { out.write(bytes); }
                    trim();
                }
                BitmapFactory.Options options = new BitmapFactory.Options(); options.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(file.getPath(), options);
                int sample = 1;
                while (options.outWidth / sample > 1400 || options.outHeight / sample > 900) sample *= 2;
                options.inJustDecodeBounds = false; options.inSampleSize = sample;
                Bitmap bitmap = BitmapFactory.decodeFile(file.getPath(), options);
                if (bitmap == null) { file.delete(); throw new Exception("Invalid cached image"); }
                memory.put(url, bitmap);
                file.setLastModified(System.currentTimeMillis());
                loaded = bitmap;
            } catch (Exception ignored) { /* The title remains readable on the card. */ }
            Bitmap result = loaded;
            List<WeakReference<ImageView>> targets;
            synchronized (PosterLoader.this) { targets = pending.remove(url); if (result == null) failures.put(url, System.currentTimeMillis()); }
            if (!closed && result != null && targets != null) main.post(() -> {
                if (closed) return;
                for (WeakReference<ImageView> reference : targets) { ImageView target = reference.get(); if (target != null && url.equals(target.getTag())) target.setImageBitmap(result); }
            });
        });
    }
    private synchronized void trim() {
        File[] files = directory.listFiles(); if (files == null) return;
        long bytes = 0; for (File file : files) bytes += file.length();
        if (bytes < 48L * 1024 * 1024) return;
        Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (File file : files) { if (bytes < 36L * 1024 * 1024) break; long length = file.length(); if (file.delete()) bytes -= length; }
    }
    public synchronized void close() { closed = true; pool.shutdownNow(); pending.clear(); main.removeCallbacksAndMessages(null); memory.evictAll(); }
}
