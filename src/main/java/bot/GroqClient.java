package bot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;

/**
 * Chama a API do Groq (plano gratuito, formato compatível com OpenAI).
 * Modelo padrão: llama-3.3-70b-versatile. Tenta de novo em 429 e erros do servidor.
 */
public class GroqClient implements Llm {
    private static final String URL = "https://api.groq.com/openai/v1/chat/completions";
    private static final int MAX_TENTATIVAS = 4;
    private static final long INTERVALO_MINIMO_MS = 2500; // limite grátis ~30 chamadas/min
    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
    private final String apiKey;
    private String modelo;
    private boolean jaProcurouModelo = false;
    private static final java.util.List<String> PREFERIDOS = java.util.List.of(
            "llama-3.3-70b-versatile", "openai/gpt-oss-120b", "llama-3.1-8b-instant", "openai/gpt-oss-20b");
    private long ultimaChamada = 0;

    public GroqClient() {
        this(System.getenv("GROQ_API_KEY"),
                System.getenv().getOrDefault("GROQ_MODEL", "llama-3.3-70b-versatile"));
    }

    public GroqClient(String apiKey, String modelo) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("Defina a variável de ambiente GROQ_API_KEY.");
        }
        this.apiKey = apiKey;
        this.modelo = modelo;
    }

    @Override
    public String perguntar(String sistema, String usuario) throws Exception {
        for (int tentativa = 1; ; tentativa++) {
            esperarVez();
            HttpRequest req = montarRequisicao(sistema, usuario);
            HttpResponse<String> resp;
            try {
                resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            } catch (HttpTimeoutException e) {
                throw new RuntimeException("O Groq (modelo " + modelo + ") demorou demais para responder. Tente de novo.");
            }
            int status = resp.statusCode();
            if (status == 200) return extrairTexto(resp.body());
            if (status == 404 && resp.body().contains("model_not_found") && !jaProcurouModelo) {
                jaProcurouModelo = true;
                String achado = procurarModeloDisponivel();
                if (achado != null && !achado.equals(modelo)) {
                    System.out.println("(O modelo " + modelo + " não está liberado na sua conta; usando " + achado + ".)");
                    modelo = achado;
                    tentativa--;
                    continue;
                }
            }
            boolean temporario = status == 429 || status >= 500;
            if (!temporario || tentativa == MAX_TENTATIVAS) {
                throw new RuntimeException("A API respondeu " + status + ": " + resp.body());
            }
            Thread.sleep((status == 429 ? 8_000L : 2_000L) * tentativa);
        }
    }

    private HttpRequest montarRequisicao(String sistema, String usuario) throws Exception {
        return HttpRequest.newBuilder(URI.create(URL))
                .timeout(Duration.ofSeconds(60))
                .header("content-type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(montarCorpo(modelo, sistema, usuario)))
                .build();
    }

    /** Pergunta ao Groq quais modelos a chave pode usar e escolhe o melhor da lista de preferidos. */
    private String procurarModeloDisponivel() {
        try {
            HttpRequest r = HttpRequest.newBuilder(URI.create("https://api.groq.com/openai/v1/models"))
                    .timeout(Duration.ofSeconds(30)).header("Authorization", "Bearer " + apiKey).GET().build();
            HttpResponse<String> resp = http.send(r, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return null;
            return escolherModelo(JSON.readTree(resp.body()), PREFERIDOS);
        } catch (Exception e) {
            return null;
        }
    }

    static String escolherModelo(JsonNode resposta, java.util.List<String> preferidos) {
        java.util.Set<String> ids = new java.util.HashSet<>();
        resposta.path("data").forEach(m -> ids.add(m.path("id").asText()));
        for (String p : preferidos) if (ids.contains(p)) return p;
        return null;
    }

    private synchronized void esperarVez() throws InterruptedException {
        long espera = ultimaChamada + INTERVALO_MINIMO_MS - System.currentTimeMillis();
        if (espera > 0) Thread.sleep(espera);
        ultimaChamada = System.currentTimeMillis();
    }

    static String montarCorpo(String modelo, String sistema, String usuario) throws Exception {
        ObjectNode corpo = JSON.createObjectNode();
        corpo.put("model", modelo);
        corpo.put("temperature", 0.2);
        corpo.put("max_tokens", 2048);
        corpo.putObject("response_format").put("type", "json_object"); // os prompts pedem só JSON
        var msgs = corpo.putArray("messages");
        msgs.addObject().put("role", "system").put("content", sistema);
        msgs.addObject().put("role", "user").put("content", usuario);
        return JSON.writeValueAsString(corpo);
    }

    static String extrairTexto(String corpoResposta) throws Exception {
        JsonNode texto = JSON.readTree(corpoResposta).path("choices").path(0).path("message").path("content");
        return texto.asText("");
    }
}
