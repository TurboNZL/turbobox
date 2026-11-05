package app.turbobox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

public class OpenAiClientTest {
    @Test
    public void parsesChatReply() throws Exception {
        assertEquals("0:00 - 1:00", OpenAiClient.parseReply("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"0:00 - 1:00\"}}]}"));
    }

    @Test
    public void requestIsAChatCompletion() throws Exception {
        String j = OpenAiClient.requestJson("m1", "hi");
        assertTrue(j.contains("\"model\":\"m1\""));
        assertTrue(j.contains("\"role\":\"user\""));
    }

    @Test
    public void modelListPutsFreeFirstAndDropsNonChat() throws Exception {
        List<String> m = OpenAiClient.parseModels("{\"data\":[{\"id\":\"b-paid\",\"pricing\":{\"prompt\":\"0.001\"}},{\"id\":\"a-free:free\"},"
                + "{\"id\":\"whisper-large\"},{\"id\":\"c-zero\",\"pricing\":{\"prompt\":\"0\"}}]}");
        assertEquals("a-free:free", m.get(0));
        assertEquals("c-zero", m.get(1));
        assertEquals(3, m.size());
        assertFalse(m.contains("whisper-large"));
    }

    @Test
    public void providerKeyShapes() {
        assertTrue(AiProviders.keyLooksRight(AiProviders.GROQ, "gsk_abcdefghijklmnopqrstuvwxyz"));
        assertFalse(AiProviders.keyLooksRight(AiProviders.GROQ, "sk-or-abcdefghijklmnopqrstuvwxyz"));
        assertTrue(AiProviders.keyLooksRight(AiProviders.OPENROUTER, "sk-or-v1-abcdefghijklmnop"));
        assertTrue(AiProviders.partWords(AiProviders.GEMINI) > AiProviders.partWords(AiProviders.GROQ));
    }
}
