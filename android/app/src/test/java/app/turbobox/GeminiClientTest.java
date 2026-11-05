package app.turbobox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import org.junit.Test;

public class GeminiClientTest {
    @Test
    public void parsesAnswerText() throws Exception {
        String json = "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"0:00 - \"},{\"text\":\"1:00\"}]}}]}";
        assertEquals("0:00 - 1:00", GeminiClient.parseReply(json));
    }

    @Test
    public void blockedAndEmptyAreErrors() {
        try {
            GeminiClient.parseReply("{\"promptFeedback\":{\"blockReason\":\"OTHER\"}}");
            assertTrue(false);
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("OTHER"));
        }
    }

    @Test
    public void explainsCommonFailures() {
        assertTrue(GeminiClient.explain(429, "{}").contains("limit"));
        assertTrue(GeminiClient.explain(400, "{\"error\":{\"message\":\"API key not valid\"}}").contains("key"));
        assertTrue(GeminiClient.explain(404, "").contains("model"));
    }

    @Test
    public void modelNamesAreCleaned() {
        assertEquals("gemini-flash-latest", GeminiClient.cleanModel("models/gemini-flash-latest"));
        assertEquals(GeminiClient.DEFAULT_MODEL, GeminiClient.cleanModel("a/b?c"));
    }

    @Test
    public void keyShape() {
        assertTrue(AiKeys.looksLikeKey("AIzaSyA1234567890abcdefghijklmnopqrstuv"));
        assertFalse(AiKeys.looksLikeKey("sk-123"));
    }
}
