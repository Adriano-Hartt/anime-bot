package bot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Chama a API do Google Gemini (plano gratuito do Google AI Studio).
 * Como o plano gratuito tem limite de requisições por minuto, o cliente espera um intervalo mínimo
 * entre chamadas e tenta de novo, com espera maior, quando recebe 429 ou erro do servidor.
 */
public class GeminiClient implements Llm {
    private static final String BASE = "https://generativelanguage.googleapis.com/v1beta/models/";
    private static final int MAX_TENTATIVAS = 4;
    private static final long INTERVALO_MINIMO_MS = 4000; // no máximo ~15 chamadas por minuto
    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
    private final String apiKey;
    private final String modelo;
    private long ultimaChamada = 0;

    /** Lê a chave de GEMINI_API_KEY e o modelo de GEMINI_MODEL (padrão: gemini-3.8-flash). */
    public GeminiClient() {
        this(System.getenv("GEMINI_API_KEY"),
                System.getenv().getOrDefault("GEMINI_MODEL", "gemini-3.8-flash"));
    }

    public GeminiClient(String apiKey, String modelo) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("Defina a variável de ambiente GEMINI_API_KEY.");
        }
        this.apiKey = apiKey;
        this.modelo = modelo;
    }

    @Override
    public String perguntar(String sistema, String usuario) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(BASE + modelo + ":generateContent"))
                .timeout(Duration.ofSeconds(120))
                .header("content-type", "application/json")
                .header("x-goog-api-key", apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(montarCorpo(sistema, usuario)))
                .build();

        for (int tentativa = 1; ; tentativa++) {
            esperarVez();
            HttpResponse<String> resp;
            try {
                resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            } catch (HttpTimeoutException e) {
                throw new RuntimeException("O Gemini (modelo " + modelo + ") demorou demais para responder. "
                        + "Tente de novo, ou troque o modelo com GEMINI_MODEL.");
            }
            int status = resp.statusCode();
            if (status == 200) {
                return extrairTexto(resp.body());
            }
            boolean temporario = status == 429 || status >= 500;
            if (!temporario || tentativa == MAX_TENTATIVAS) {
                throw new RuntimeException("A API respondeu " + status + ": " + resp.body());
            }
            // limite de requisições (429) pede espera longa; erro do servidor, espera curta
            Thread.sleep((status == 429 ? 10_000L : 2_000L) * tentativa);
        }
    }

    /** Garante um intervalo mínimo entre chamadas para respeitar o limite do plano gratuito. */
    private synchronized void esperarVez() throws InterruptedException {
        long espera = ultimaChamada + INTERVALO_MINIMO_MS - System.currentTimeMillis();
        if (espera > 0) Thread.sleep(espera);
        ultimaChamada = System.currentTimeMillis();
    }

    /** Monta o corpo JSON da requisição. Fica separado para poder ser testado sem internet. */
    static String montarCorpo(String sistema, String usuario) throws Exception {
        ObjectNode corpo = JSON.createObjectNode();
        corpo.putObject("systemInstruction").putArray("parts").addObject().put("text", sistema);
        ObjectNode conteudo = corpo.putArray("contents").addObject();
        conteudo.put("role", "user");
        conteudo.putArray("parts").addObject().put("text", usuario);
        ObjectNode config = corpo.putObject("generationConfig");
        config.put("temperature", 0.2);
        config.put("maxOutputTokens", 2048); // modelos com raciocínio gastam parte deste limite pensando
        config.put("responseMimeType", "application/json"); // os dois prompts pedem somente JSON
        return JSON.writeValueAsString(corpo);
    }

    /** Junta o texto da resposta, ignorando partes de "raciocínio". Devolve "" se não houver texto. */
    static String extrairTexto(String corpoResposta) throws Exception {
        StringBuilder texto = new StringBuilder();
        for (JsonNode parte : JSON.readTree(corpoResposta).path("candidates").path(0).path("content").path("parts")) {
            if (parte.path("thought").asBoolean(false)) continue;
            texto.append(parte.path("text").asText(""));
        }
        return texto.toString();
    }
}
