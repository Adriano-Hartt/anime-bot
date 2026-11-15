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
import java.util.List;

/**
 * Busca título e sinopse de cada episódio na Kitsu (API gratuita, sem chave).
 * É um complemento: o AniList só dá números. Se a Kitsu falhar, o bot segue sem ela.
 */
public class KitsuClient {

    private static final String BASE = "https://kitsu.io/api/edge";
    private static final int MAX_SINOPSE = 220;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
    private final ObjectMapper json = new ObjectMapper();

    /** Devolve linhas "N: título - sinopse curta", ou lista vazia se não achar nada. */
    public List<String> episodiosDetalhados(String tituloSerie, Integer totalEsperado) {
        try {
            String q = URLEncoder.encode(tituloSerie, StandardCharsets.UTF_8);
            JsonNode busca = get(BASE + "/anime?filter[text]=" + q + "&page[limit]=5");
            String id = escolherId(busca.path("data"), totalEsperado);
            if (id == null) return List.of();

            List<JsonNode> eps = new ArrayList<>();
            for (int offset = 0; offset < 200; offset += 20) {
                JsonNode pag = get(BASE + "/anime/" + id + "/episodes?sort=number&page[limit]=20&page[offset]=" + offset);
                JsonNode dados = pag.path("data");
                if (!dados.isArray() || dados.isEmpty()) break;
                dados.forEach(eps::add);
                if (dados.size() < 20) break;
            }
            return montarLinhas(eps);
        } catch (Exception e) {
            return List.of(); // sem Kitsu o bot continua funcionando
        }
    }

    /** Prefere a série cuja quantidade de episódios bate com a do AniList; senão a primeira. */
    static String escolherId(JsonNode data, Integer totalEsperado) {
        if (!data.isArray() || data.isEmpty()) return null;
        if (totalEsperado != null) {
            for (JsonNode d : data) {
                if (d.path("attributes").path("episodeCount").asInt(-1) == totalEsperado) return d.path("id").asText();
            }
        }
        return data.get(0).path("id").asText();
    }

    static List<String> montarLinhas(List<JsonNode> eps) {
        List<String> saida = new ArrayList<>();
        for (JsonNode e : eps) {
            JsonNode a = e.path("attributes");
            int n = a.path("number").asInt(0);
            if (n <= 0) continue;
            String titulo = a.path("canonicalTitle").asText("").trim();
            String sinopse = a.path("synopsis").asText("").replaceAll("\\s+", " ").trim();
            if (sinopse.length() > MAX_SINOPSE) sinopse = sinopse.substring(0, MAX_SINOPSE) + "...";
            String detalhe = titulo.isEmpty() ? sinopse : (sinopse.isEmpty() ? titulo : titulo + " - " + sinopse);
            saida.add(n + ": " + detalhe);
        }
        return saida;
    }

    private JsonNode get(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/vnd.api+json").GET().build();
        HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) throw new IllegalStateException("Kitsu HTTP " + r.statusCode());
        return json.readTree(r.body());
    }
}
