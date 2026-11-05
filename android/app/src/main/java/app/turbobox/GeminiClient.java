package app.turbobox;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** A plain call to Google's Gemini API (generateContent) with the person's own key. The key is sent in a header, never put in a URL or a log. */
final class GeminiClient {
    private GeminiClient() {
    }

    static final String DEFAULT_MODEL = "gemini-flash-latest";

    static final class ApiException extends IOException {
        final int code;
        final int retryAfterSec;   // from the Retry-After header; 0 when not given

        ApiException(int code, String message) {
            this(code, message, 0);
        }

        ApiException(int code, String message, int retryAfterSec) {
            super(message);
            this.code = code;
            this.retryAfterSec = retryAfterSec;
        }
    }

    static String requestJson(String prompt) throws JSONException {
        JSONObject content = new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text", prompt)));
        return new JSONObject().put("contents", new JSONArray().put(content))
                .put("generationConfig", new JSONObject().put("temperature", 0.2)).toString();
    }

    /** The text of the first answer, or an IOException saying why there is none. */
    static String parseReply(String json) throws IOException {
        try {
            JSONObject o = new JSONObject(json);
            JSONArray c = o.optJSONArray("candidates");
            if (c != null && c.length() > 0) {
                JSONObject content = c.getJSONObject(0).optJSONObject("content");
                JSONArray parts = content == null ? null : content.optJSONArray("parts");
                StringBuilder sb = new StringBuilder();
                if (parts != null) for (int i = 0; i < parts.length(); i++) sb.append(parts.getJSONObject(i).optString("text", ""));
                if (sb.length() > 0) return sb.toString();
            }
            JSONObject fb = o.optJSONObject("promptFeedback");
            if (fb != null && fb.has("blockReason")) throw new IOException("Gemini declined this text (" + fb.optString("blockReason") + ")");
            throw new IOException("Gemini sent no answer");
        } catch (JSONException e) {
            throw new IOException("Gemini's reply could not be read");
        }
    }

    /** What went wrong, in words, from an error response. */
    static String explain(int code, String body) {
        String msg = "";
        try {
            msg = new JSONObject(body).getJSONObject("error").optString("message", "");
        } catch (JSONException ignored) {
            // Not JSON.
        }
        if (code == 429) return "Gemini's free limit was reached for now (wait a minute, or try again later).";
        if (code == 400 && msg.toLowerCase().contains("api key")) return "Gemini does not accept this key.";
        if (code == 401 || code == 403) return "Gemini refused this key (" + (msg.isEmpty() ? "HTTP " + code : msg) + ").";
        if (code == 404) return "Gemini does not know that model name (Settings > AI for cutting > Model).";
        return "Gemini error " + code + (msg.isEmpty() ? "" : ": " + msg);
    }

    static String cleanModel(String model) {
        String m = model == null ? "" : model.trim();
        if (m.startsWith("models/")) m = m.substring(7);
        return m.matches("[A-Za-z0-9._-]{1,80}") ? m : DEFAULT_MODEL;
    }

    /** Blocking; call off the main thread. */
    static String generate(String key, String model, String prompt) throws IOException {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL("https://generativelanguage.googleapis.com/v1beta/models/" + cleanModel(model) + ":generateContent").openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(20_000);
            c.setReadTimeout(240_000);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("x-goog-api-key", key);
            byte[] body = requestJson(prompt).getBytes("UTF-8");
            try (OutputStream o = c.getOutputStream()) {
                o.write(body);
            }
            int code = c.getResponseCode();
            String text = read(code >= 400 ? c.getErrorStream() : c.getInputStream());
            if (code >= 400) throw new ApiException(code, explain(code, text), retryAfter(c));
            return parseReply(text);
        } catch (JSONException e) {
            throw new IOException("could not build the request");
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** The Retry-After header in seconds (0 when absent or not a number). */
    static int retryAfter(HttpURLConnection c) {
        try {
            String v = c.getHeaderField("Retry-After");
            return v == null ? 0 : Math.max(0, Math.min(300, (int) Math.ceil(Double.parseDouble(v.trim()))));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static String read(InputStream in) throws IOException {
        if (in == null) return "";
        try (InputStream i = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            for (int n; (n = i.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }
}
