package app.turbobox;

/** The online AI services a key can belong to. All but Gemini speak the common "OpenAI-style" chat API, so one client serves them. */
final class AiProviders {
    private AiProviders() {
    }

    static final String GROQ = "groq";
    static final String OPENROUTER = "openrouter";
    static final String GEMINI = "gemini";
    static final String CUSTOM = "custom";

    /** In the order they are offered. */
    static final String[] IDS = {GROQ, OPENROUTER, GEMINI, CUSTOM};

    static String name(String id) {
        switch (id) {
            case GROQ: return "Groq";
            case OPENROUTER: return "OpenRouter";
            case GEMINI: return "Gemini";
            default: return "Other (OpenAI-style)";
        }
    }

    /** Where the person makes a key; null when there is no fixed place. */
    static String keyPage(String id) {
        switch (id) {
            case GROQ: return "https://console.groq.com/keys";
            case OPENROUTER: return "https://openrouter.ai/keys";
            case GEMINI: return "https://aistudio.google.com/apikey";
            default: return null;
        }
    }

    static String baseUrl(String id) {
        switch (id) {
            case GROQ: return "https://api.groq.com/openai/v1";
            case OPENROUTER: return "https://openrouter.ai/api/v1";
            default: return "";
        }
    }

    static String defaultModel(String id) {
        switch (id) {
            case GROQ: return "llama-3.3-70b-versatile";
            case OPENROUTER: return "meta-llama/llama-3.3-70b-instruct:free";
            case GEMINI: return GeminiClient.DEFAULT_MODEL;
            default: return "";
        }
    }

    /** About how many words of transcript one request carries: free tiers elsewhere allow far fewer tokens per minute than Gemini. */
    static int partWords(String id) {
        return GEMINI.equals(id) ? 25000 : 3000;
    }

    static boolean keyLooksRight(String id, String key) {
        String k = key == null ? "" : key.trim();
        switch (id) {
            case GROQ: return k.startsWith("gsk_") && k.length() > 20;
            case OPENROUTER: return k.startsWith("sk-or-") && k.length() > 20;
            case GEMINI: return AiKeys.looksLikeKey(k);
            default: return k.length() >= 8 && !k.contains(" ");
        }
    }
}
