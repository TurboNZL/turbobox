package app.turbobox;

import android.content.Context;

import java.io.IOException;
import java.util.List;

/** Sends one prompt to whichever service a key belongs to, waiting out the free tier's limits and falling back to the next saved key. */
final class Ai {
    private Ai() {
    }

    interface Progress {
        void say(String text);   // any thread
    }

    static String generate(AiKeys.Key k, String prompt) throws IOException {
        if (AiProviders.GEMINI.equals(k.provider)) return GeminiClient.generate(k.key, k.modelName(), prompt);
        return OpenAiClient.generate(k.base(), k.key, k.modelName(), prompt);
    }

    /** One key, waiting out a rate limit (up to 5 tries). */
    static String withRetry(AiKeys.Key k, String prompt, Progress p) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < 5; attempt++) {
            try {
                return generate(k, prompt);
            } catch (GeminiClient.ApiException e) {
                last = e;
                if (e.code != 429 && e.code < 500) throw e;
                int wait = e.retryAfterSec > 0 ? e.retryAfterSec + 2 : 62;
                if (p != null) p.say(k.describe() + " is at its free limit; waiting " + wait + " s...");
                if (!sleep(wait * 1000L)) throw new IOException("stopped");
            } catch (IOException e) {
                last = e;
                if (attempt >= 2) throw e;
                if (!sleep(3_000)) throw e;
            }
        }
        throw last == null ? new IOException("no answer") : last;
    }

    /** Tries the key in use, then the others, until one answers. */
    static String any(Context c, String prompt, Progress p) throws IOException {
        List<AiKeys.Key> keys = AiKeys.inOrder(c);
        if (keys.isEmpty()) throw new IOException("No AI key is saved (Settings > AI for cutting and recaps).");
        IOException last = null;
        for (AiKeys.Key k : keys) {
            try {
                return withRetry(k, prompt, p);
            } catch (IOException e) {
                last = e;
                if (p != null && keys.size() > 1) p.say(k.describe() + " did not work (" + e.getMessage() + "). Trying the next key...");
            }
        }
        throw last;
    }

    /** Words one request may carry: the smallest of the saved keys', so a fall-back key can always take the same request. */
    static int partWords(Context c) {
        int w = Integer.MAX_VALUE;
        for (AiKeys.Key k : AiKeys.all(c)) w = Math.min(w, AiProviders.partWords(k.provider));
        return w == Integer.MAX_VALUE ? 3000 : w;
    }

    private static boolean sleep(long ms) {
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException e) {
            return false;
        }
    }
}
