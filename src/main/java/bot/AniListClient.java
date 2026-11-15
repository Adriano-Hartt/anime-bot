package bot;

import bot.Modelos.Achado;
import bot.Modelos.Temporada;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Catálogo de animes pela API GraphQL pública do AniList (não precisa de chave). */
public class AniListClient implements Catalogo {
    private static final String URL = "https://graphql.anilist.co";
    private static final int MAX_TENTATIVAS = 3;
    private static final Pattern TITULO_EPISODIO =
            Pattern.compile("(?i)^(?:episode|episódio|episodio|ep\\.?)\\s*(\\d+)\\s*[-–:.]*\\s*(.*)$");

    private static final String BUSCA =
            "query($s:String){Page(perPage:5){media(search:$s,type:ANIME,sort:SEARCH_MATCH){id title{romaji english}}}}";
    private static final String EPISODIOS =
            "query($id:Int){Media(id:$id,type:ANIME){episodes nextAiringEpisode{episode} streamingEpisodes{title}}}";
    private static final String SERIE =
            "query($id:Int){Media(id:$id,type:ANIME){id title{romaji english} format status episodes "
                    + "startDate{year month day} relations{edges{relationType node{id type format}}}}}";
    private static final String LINKS =
            "query($id:Int){Media(id:$id,type:ANIME){externalLinks{site url type}}}";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
    private final ObjectMapper json = new ObjectMapper();

    @Override
    public Achado buscar(String titulo) throws Exception {
        ObjectNode vars = json.createObjectNode().put("s", titulo);
        JsonNode midias = consultar(BUSCA, vars).path("Page").path("media");
        if (!midias.isArray() || midias.isEmpty()) return null;
        JsonNode primeiro = midias.get(0);
        return new Achado(primeiro.path("id").asInt(), tituloDe(primeiro.path("title"), titulo));
    }

    /** Segue as ligações "prequel" e "sequência" entre séries de TV para achar todas as temporadas. */
    @Override
    public List<Temporada> temporadas(int id) throws Exception {
        Set<Integer> vistos = new HashSet<>();
        Deque<Integer> fila = new ArrayDeque<>();
        List<JsonNode> medias = new ArrayList<>();
        fila.add(id);
        while (!fila.isEmpty() && vistos.size() < 12) {
            int atual = fila.poll();
            if (!vistos.add(atual)) continue;
            JsonNode midia = consultar(SERIE, json.createObjectNode().put("id", atual)).path("Media");
            medias.add(midia);
            for (JsonNode aresta : midia.path("relations").path("edges")) {
                String relacao = aresta.path("relationType").asText("");
                JsonNode no = aresta.path("node");
                boolean serieDeTv = "ANIME".equals(no.path("type").asText()) && "TV".equals(no.path("format").asText());
                if (serieDeTv && (relacao.equals("PREQUEL") || relacao.equals("SEQUEL"))) {
                    fila.add(no.path("id").asInt());
                }
            }
        }
        return montarTemporadas(medias);
    }

    @Override
    public List<String> listarEpisodios(int id) throws Exception {
        JsonNode midia = consultar(EPISODIOS, json.createObjectNode().put("id", id)).path("Media");
        Integer total = midia.path("episodes").isInt() ? midia.path("episodes").asInt() : null;
        Integer proximo = midia.path("nextAiringEpisode").path("episode").isInt()
                ? midia.path("nextAiringEpisode").path("episode").asInt() : null;
        List<String> titulos = new ArrayList<>();
        for (JsonNode e : midia.path("streamingEpisodes")) titulos.add(e.path("title").asText(""));
        return montarLista(total, proximo, titulos);
    }

    @Override
    public List<String> plataformas(int id) throws Exception {
        JsonNode links = consultar(LINKS, json.createObjectNode().put("id", id)).path("Media").path("externalLinks");
        List<String> lista = new ArrayList<>();
        for (JsonNode l : links) {
            if ("STREAMING".equalsIgnoreCase(l.path("type").asText(""))) {
                lista.add(l.path("site").asText("") + " - " + l.path("url").asText(""));
            }
        }
        return lista;
    }

    /** Envia a consulta GraphQL e devolve o campo "data", com tentativas em caso de limite ou erro do servidor. */
    private JsonNode consultar(String query, ObjectNode variaveis) throws Exception {
        ObjectNode corpo = json.createObjectNode();
        corpo.put("query", query);
        corpo.set("variables", variaveis);
        HttpRequest req = HttpRequest.newBuilder(URI.create(URL))
                .timeout(Duration.ofSeconds(30))
                .header("content-type", "application/json")
                .header("accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(corpo)))
                .build();

        for (int tentativa = 1; ; tentativa++) {
            HttpResponse<String> resp;
            try {
                resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            } catch (HttpTimeoutException e) {
                throw new RuntimeException("O AniList (catálogo de animes) demorou demais para responder. Tente de novo em instantes.");
            }
            int status = resp.statusCode();
            if (status == 200) {
                JsonNode raiz = json.readTree(resp.body());
                if (raiz.has("errors")) {
                    throw new RuntimeException("O AniList respondeu com erro: "
                            + raiz.path("errors").path(0).path("message").asText("desconhecido"));
                }
                Thread.sleep(700); // respeita o limite de requisições por minuto
                return raiz.path("data");
            }
            boolean temporario = status == 429 || status >= 500;
            if (!temporario || tentativa == MAX_TENTATIVAS) {
                throw new RuntimeException("O AniList respondeu " + status);
            }
            Thread.sleep(5000L * tentativa); // limite estourado ou servidor ocupado: espera e tenta de novo
        }
    }

    /** Tira o que ainda não estreou (ou foi cancelado) e ordena da mais antiga para a mais recente. */
    static List<Temporada> montarTemporadas(List<JsonNode> medias) {
        List<JsonNode> validas = new ArrayList<>();
        for (JsonNode m : medias) {
            String status = m.path("status").asText("");
            if (!status.equals("NOT_YET_RELEASED") && !status.equals("CANCELLED")) validas.add(m);
        }
        validas.sort(Comparator.comparingLong(AniListClient::dataDeEstreia));
        List<Temporada> temporadas = new ArrayList<>();
        for (JsonNode m : validas) {
            Integer episodios = m.path("episodes").isInt() ? m.path("episodes").asInt() : null;
            temporadas.add(new Temporada(m.path("id").asInt(), tituloDe(m.path("title"), "Temporada"),
                    episodios, m.path("status").asText("")));
        }
        return temporadas;
    }

    private static long dataDeEstreia(JsonNode midia) {
        JsonNode d = midia.path("startDate");
        return d.path("year").asInt(9999) * 10000L + d.path("month").asInt(1) * 100L + d.path("day").asInt(1);
    }

    /** Prefere o título em inglês; se não houver, usa o romaji; por último, o que a pessoa digitou. */
    static String tituloDe(JsonNode titulos, String padrao) {
        String ingles = titulos.path("english").asText("").trim();
        if (!ingles.isEmpty()) return ingles;
        String romaji = titulos.path("romaji").asText("").trim();
        return romaji.isEmpty() ? padrao : romaji;
    }

    /**
     * Monta a lista "N: título". O AniList nem sempre informa o título de cada episódio: onde faltar,
     * a linha fica só com o número. Se o total for desconhecido (anime em exibição), usa o próximo
     * episódio a ir ao ar menos um; em último caso, o maior número que apareceu nos títulos.
     */
    static List<String> montarLista(Integer episodios, Integer proximoEpisodio, List<String> titulosStreaming) {
        Map<Integer, String> titulos = new TreeMap<>();
        for (String bruto : titulosStreaming) {
            Matcher m = TITULO_EPISODIO.matcher(bruto.trim());
            if (m.matches()) titulos.put(Integer.parseInt(m.group(1)), m.group(2).trim());
        }
        int total = 0;
        if (episodios != null) {
            total = episodios;
        } else if (proximoEpisodio != null) {
            total = proximoEpisodio - 1;
        }
        if (total <= 0 && !titulos.isEmpty()) {
            total = ((TreeMap<Integer, String>) titulos).lastKey();
        }
        List<String> lista = new ArrayList<>();
        for (int n = 1; n <= total; n++) {
            lista.add(n + ": " + titulos.getOrDefault(n, ""));
        }
        return lista;
    }
}
