package bot;

/**
 * Escolhe qual modelo de linguagem usar, sem o resto do código saber qual é.
 * Regra: LLM_PROVIDER manda ("groq", "gemini" ou "claude"); se não estiver definida, usa o que tiver chave.
 */
public final class LlmFactory {
    private LlmFactory() {
    }

    public static Llm criar() {
        String escolhido = System.getenv().getOrDefault("LLM_PROVIDER", "").trim().toLowerCase();
        boolean temGroq = temValor("GROQ_API_KEY");
        boolean temGemini = temValor("GEMINI_API_KEY");
        boolean temClaude = temValor("ANTHROPIC_API_KEY");

        if (escolhido.isEmpty()) {
            if (!temGroq && !temGemini && !temClaude) {
                throw new IllegalStateException(
                        "Defina GROQ_API_KEY ou GEMINI_API_KEY (ambas gratuitas) ou ANTHROPIC_API_KEY.");
            }
            escolhido = temGroq ? "groq" : temGemini ? "gemini" : "claude";
        }
        return switch (escolhido) {
            case "groq" -> new GroqClient();
            case "gemini" -> new GeminiClient();
            case "claude" -> new LlmClient();
            default -> throw new IllegalStateException("LLM_PROVIDER inválido: use groq, gemini ou claude.");
        };
    }

    private static boolean temValor(String variavel) {
        String valor = System.getenv(variavel);
        return valor != null && !valor.isBlank();
    }
}
