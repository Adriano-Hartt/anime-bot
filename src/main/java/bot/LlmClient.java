package bot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Chama a API de mensagens da Anthropic (Claude) com tentativas automáticas em falhas temporárias. */
public class LlmClient implements Llm {
    private static final String URL = "https://api.anthropic.com/v1/messages";
    private static final int MAX_TENTATIVAS = 3;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper json = new ObjectMapper();
    private final String apiKey;
    private final String modelo;

    /** Lê a chave de ANTHROPIC_API_KEY e o modelo de ANIME_BOT_MODEL (padrão: claude-haiku-5-5). */
    public LlmClient() {
        this(System.getenv("ANTHROPIC_API_KEY"),
                System.getenv().getOrDefault("ANIME_BOT_MODEL", "claude-haiku-5-5"));
    }

    public LlmClient(String apiKey, String modelo) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("Defina a variável de ambiente ANTHROPIC_API_KEY.");
        }
        this.apiKey = apiKey;
        this.modelo = modelo;
    }

    @Override
    public String perguntar(String sistema, String usuario) throws Exception {
        ObjectNode corpo = json.createObjectNode();
        corpo.put("model", modelo);
        corpo.put("max_tokens", 600);
        corpo.put("system", sistema);
        corpo.putArray("messages").addObject().put("role", "user").put("content", usuario);

        HttpRequest req = HttpRequest.newBuilder(URI.create(URL))
                .timeout(Duration.ofSeconds(60))
                .header("content-type", "application/json")
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(corpo)))
                .build();

        for (int tentativa = 1; ; tentativa++) {
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            int status = resp.statusCode();
            if (status == 200) {
                return json.readTree(resp.body()).path("content").path(0).path("text").asText("");
            }
            boolean temporario = status == 429 || status >= 500;
            if (!temporario || tentativa == MAX_TENTATIVAS) {
                throw new RuntimeException("A API respondeu " + status + ": " + resp.body());
            }
            Thread.sleep(1000L * tentativa); // espera cada vez mais antes de tentar de novo
        }
    }
}
