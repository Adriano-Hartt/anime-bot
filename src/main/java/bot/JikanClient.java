package bot;

import bot.Modelos.Achado;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Catálogo de animes: lê os dados públicos do MyAnimeList pela API não oficial Jikan. */
public class JikanClient implements Catalogo {
    private static final String BASE = "https://api.jikan.moe/v4";
    private static final int MAX_TENTATIVAS = 3;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
    private final ObjectMapper json = new ObjectMapper();

    private JsonNode get(String caminho) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(BASE + caminho))
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build();
        for (int tentativa = 1; ; tentativa++) {
            HttpResponse<String> resp;
            try {
                resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            } catch (HttpTimeoutException e) {
                throw new RuntimeException("A Jikan (catálogo de animes) demorou demais para responder. Tente de novo em instantes.");
            }
            int status = resp.statusCode();
            if (status == 200) {
                Thread.sleep(400); // respeita o limite de requisições da Jikan
                return json.readTree(resp.body());
            }
            if (status != 429 || tentativa == MAX_TENTATIVAS) {
                throw new RuntimeException("Jikan respondeu " + status);
            }
            Thread.sleep(1000L * tentativa); // limite estourado: espera e tenta de novo
        }
    }

    /** Busca o título; devolve o id e o título oficial do MyAnimeList, ou null se não achar. */
    @Override
    public Achado buscar(String titulo) throws Exception {
        String q = URLEncoder.encode(titulo, StandardCharsets.UTF_8);
        JsonNode dados = get("/anime?q=" + q + "&limit=1").get("data");
        if (dados == null || dados.isEmpty()) return null;
        JsonNode primeiro = dados.get(0);
        return new Achado(primeiro.get("mal_id").asInt(), primeiro.path("title").asText(titulo));
    }

    /** Atalho que devolve só o id do MyAnimeList, ou -1 se não achar. */
    public int buscarId(String titulo) throws Exception {
        Achado achado = buscar(titulo);
        return achado == null ? -1 : achado.id();
    }

    /** Lista os episódios (número e título), página por página. */
    @Override
    public List<String> listarEpisodios(int malId) throws Exception {
        List<String> eps = new ArrayList<>();
        int pagina = 1;
        boolean temMais = true;
        while (temMais) {
            JsonNode r = get("/anime/" + malId + "/episodes?page=" + pagina);
            for (JsonNode ep : r.get("data")) {
                eps.add(ep.get("mal_id").asInt() + ": " + ep.path("title").asText(""));
            }
            temMais = r.path("pagination").path("has_next_page").asBoolean(false);
            pagina++;
        }
        return eps;
    }

    /** Lista onde o anime está disponível (nome e link), segundo o MyAnimeList. */
    @Override
    public List<String> plataformas(int malId) throws Exception {
        List<String> lista = new ArrayList<>();
        for (JsonNode p : get("/anime/" + malId + "/streaming").path("data")) {
            lista.add(p.path("name").asText("") + " - " + p.path("url").asText(""));
        }
        return lista;
    }
}
