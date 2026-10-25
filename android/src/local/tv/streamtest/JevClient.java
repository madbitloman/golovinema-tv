package local.tv.streamtest;

import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public final class JevClient {
    private volatile HttpURLConnection active;
    public void cancel() { HttpURLConnection connection = active; if (connection != null) connection.disconnect(); }
    public JSONObject evaluate(JSONObject request, String key) throws Exception {
        byte[] body = request.toString().getBytes(StandardCharsets.UTF_8);
        if (body.length > 65536) throw new IllegalArgumentException("Preference request too large");
        HttpURLConnection connection = (HttpURLConnection)new URL("https://api.typesafe.ai/v1/systemone").openConnection();
        active = connection;
        connection.setInstanceFollowRedirects(false); connection.setConnectTimeout(8000); connection.setReadTimeout(15000);
        connection.setRequestMethod("POST"); connection.setDoOutput(true); connection.setFixedLengthStreamingMode(body.length);
        connection.setRequestProperty("Content-Type", "application/json"); connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Authorization", "Bearer " + key);
        try {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            try (OutputStream output = connection.getOutputStream()) { output.write(body); }
            int status = connection.getResponseCode();
            if (status != 200) throw new java.io.IOException("Jev HTTP " + status);
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096]; int length;
                while ((length = input.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                    if (output.size() + length > 131072) throw new java.io.IOException("Jev response too large");
                    output.write(buffer, 0, length);
                }
                return new JSONObject(new String(output.toByteArray(), StandardCharsets.UTF_8));
            }
        } finally { connection.disconnect(); if (active == connection) active = null; }
    }
}
