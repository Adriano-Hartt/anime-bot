package bot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Busca título e sinopse dos episódios no TVmaze (API gratuita, sem chave).
 * O TVmaze lista a série inteira em ordem; o bot recorta só os episódios da temporada escolhida.
 */
public class TvmazeClient {
    private static final int MAX_SINOPSE = 220;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
    private final ObjectMapper json = new ObjectMapper();

    /**
     * @param nomeSerie     nome da série (ex.: "Re:ZERO -Starting Life in Another World-")
     * @param deslocamento  quantos episódios vêm antes da temporada escolhida
     * @param quantidade    quantos episódios a temporada tem
     * @return linhas "N: título - sinopse" numeradas de 1 a quantidade, ou vazio se não bater
     */
    public List<String> episodios(String nomeSerie, int deslocamento, int quantidade) {
        try {
            String q = URLEncoder.encode(nomeSerie, StandardCharsets.UTF_8);
            HttpRequest req = HttpRequest.newBuilder(URI.create(
                    "https://api.tvmaze.com/singlesearch/shows?q=" + q + "&embed=episodes"))
                    .timeout(Duration.ofSeconds(30)).GET().build();
            HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) return List.of();
            return recortar(json.readTree(r.body()).path("_embedded").path("episodes"), deslocamento, quantidade);
        } catch (Exception e) {
            return List.of();
        }
    }

    /** Ordena por temporada/número, recorta a fatia e numera de 1 a quantidade. Vazio se a série for curta demais. */
    static List<String> recortar(JsonNode episodios, int deslocamento, int quantidade) {
        if (!episodios.isArray() || deslocamento < 0 || quantidade <= 0) return List.of();
        List<JsonNode> todos = new ArrayList<>();
        episodios.forEach(e -> {
            if (e.path("number").isInt()) todos.add(e); // ignora especiais sem número
        });
        todos.sort(Comparator.comparingInt((JsonNode e) -> e.path("season").asInt())
                .thenComparingInt(e -> e.path("number").asInt()));
        if (todos.size() < deslocamento + quantidade) return List.of();

        List<String> saida = new ArrayList<>();
        for (int i = 0; i < quantidade; i++) {
            JsonNode e = todos.get(deslocamento + i);
            String nome = e.path("name").asText("").trim();
            String sinopse = e.path("summary").asText("").replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
            if (sinopse.length() > MAX_SINOPSE) sinopse = sinopse.substring(0, MAX_SINOPSE) + "...";
            String detalhe = nome.isEmpty() ? sinopse : (sinopse.isEmpty() ? nome : nome + " - " + sinopse);
            saida.add((i + 1) + ": " + detalhe);
        }
        return saida;
    }
}
