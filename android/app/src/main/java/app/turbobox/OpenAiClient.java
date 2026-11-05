package app.turbobox;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A plain call to any service that speaks the OpenAI-style chat API (Groq, OpenRouter, many others) with the person's own key. */
final class OpenAiClient {
    private OpenAiClient() {
    }

    static String requestJson(String model, String prompt) throws JSONException {
        JSONArray messages = new JSONArray().put(new JSONObject().put("role", "user").put("content", prompt));
        return new JSONObject().put("model", model).put("messages", messages).put("temperature", 0.2).toString();
    }

    static String parseReply(String json) throws IOException {
        try {
            JSONArray c = new JSONObject(json).optJSONArray("choices");
            if (c != null && c.length() > 0) {
                JSONObject msg = c.getJSONObject(0).optJSONObject("message");
                String text = msg == null ? "" : msg.optString("content", "");
                if (!text.trim().isEmpty()) return text;
            }
            throw new IOException("The service sent no answer");
        } catch (JSONException e) {
            throw new IOException("The service's reply could not be read");
        }
    }

    static String explain(int code, String body) {
        String msg = "";
        try {
            JSONObject e = new JSONObject(body).optJSONObject("error");
            if (e != null) msg = e.optString("message", "");
        } catch (JSONException ignored) {
            // Not JSON.
        }
        if (code == 429) return "The free limit was reached for now (wait a minute, or try again later).";
        if (code == 401 || code == 403) return "The service refused this key" + (msg.isEmpty() ? "." : " (" + msg + ").");
        if (code == 404 || code == 400) return "The service did not accept the request" + (msg.isEmpty() ? "" : ": " + msg) + " (check the model name).";
        return "Error " + code + (msg.isEmpty() ? "" : ": " + msg);
    }

    private static String clean(String base) {
        String b = base == null ? "" : base.trim();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        return b;
    }

    /** Blocking; call off the main thread. */
    static String generate(String baseUrl, String key, String model, String prompt) throws IOException {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(clean(baseUrl) + "/chat/completions").openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(20_000);
            c.setReadTimeout(240_000);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("Authorization", "Bearer " + key);
            try (OutputStream o = c.getOutputStream()) {
                o.write(requestJson(model, prompt).getBytes("UTF-8"));
            }
            int code = c.getResponseCode();
            String text = GeminiClient.read(code >= 400 ? c.getErrorStream() : c.getInputStream());
            if (code >= 400) throw new GeminiClient.ApiException(code, explain(code, text), GeminiClient.retryAfter(c));
            return parseReply(text);
        } catch (JSONException e) {
            throw new IOException("could not build the request");
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** Model ids the key can use (free ones first when the service says which are free). Blocking. */
    static List<String> listModels(String baseUrl, String key) throws IOException {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(clean(baseUrl) + "/models").openConnection();
            c.setConnectTimeout(20_000);
            c.setReadTimeout(30_000);
            c.setRequestProperty("Authorization", "Bearer " + key);
            int code = c.getResponseCode();
            String text = GeminiClient.read(code >= 400 ? c.getErrorStream() : c.getInputStream());
            if (code >= 400) throw new GeminiClient.ApiException(code, explain(code, text), 0);
            return parseModels(text);
        } finally {
            if (c != null) c.disconnect();
        }
    }

    static List<String> parseModels(String json) throws IOException {
        List<String> free = new ArrayList<>(), other = new ArrayList<>();
        try {
            JSONArray d = new JSONObject(json).optJSONArray("data");
            if (d == null) throw new IOException("No model list in the reply");
            for (int i = 0; i < d.length(); i++) {
                JSONObject m = d.getJSONObject(i);
                String id = m.optString("id", "");
                if (id.isEmpty() || id.contains("whisper") || id.contains("embed") || id.contains("tts") || id.contains("guard")) continue;
                JSONObject pricing = m.optJSONObject("pricing");
                boolean isFree = id.endsWith(":free") || (pricing != null && "0".equals(pricing.optString("prompt", "x")));
                (isFree ? free : other).add(id);
            }
        } catch (JSONException e) {
            throw new IOException("The model list could not be read");
        }
        Collections.sort(free);
        Collections.sort(other);
        free.addAll(other);
        return free;
    }
}
