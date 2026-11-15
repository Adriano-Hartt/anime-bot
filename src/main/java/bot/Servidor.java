package bot;

import bot.Modelos.Anime;
import bot.Modelos.Identificacao;
import bot.Modelos.RespostaEpisodio;
import bot.Modelos.Temporada;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * API HTTP local para o front-end. Usa só o servidor embutido do JDK (sem framework) e escuta
 * apenas em 127.0.0.1. Cada chamada é independente: o front guarda o estado da conversa.
 *
 * POST /api/identificar   {descricao}                       -> {candidatos:[{id,titulo,confianca}], pergunta}
 * GET  /api/temporadas?id=                                  -> [{id,titulo,episodios,status,deslocamento}]
 * POST /api/temporada     {id,titulo,pistas}                -> {numero, temporada}   (numero 0 = não sabe)
 * POST /api/episodio      {id,titulo,deslocamento,pistas}   -> {episodio, linha, confianca, pergunta, total}
 * GET  /api/dublagem?id=&titulo=&episodio=                  -> {proximo, ultimo, total, fonte, lidos}
 */
public class Servidor {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ServicoAnime servico;
    private final Catalogo catalogo;
    private final HttpServer http;
    /** Limite de chamadas por pessoa (IP) por hora, para ninguém gastar a cota da chave de IA. */
    private final int limiteHora;
    private final Map<String, java.util.ArrayDeque<Long>> usos = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ExecutorService pool = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "api");
        t.setDaemon(true); // não impede o programa de encerrar
        return t;
    });

    public Servidor(ServicoAnime servico, Catalogo catalogo, int porta) throws IOException {
        this(servico, catalogo, porta, Integer.parseInt(System.getenv().getOrDefault("LIMITE_HORA", "40")));
    }

    public Servidor(ServicoAnime servico, Catalogo catalogo, int porta, int limiteHora) throws IOException {
        this.limiteHora = limiteHora;
        this.servico = servico;
        this.catalogo = catalogo;
        this.http = HttpServer.create(new InetSocketAddress(System.getenv().getOrDefault("HOST", "127.0.0.1"), porta), 0);
        this.http.setExecutor(pool);
        rota("/api/identificar", this::identificar);
        rota("/api/temporadas", this::temporadas);
        rota("/api/temporada", this::temporada);
        rota("/api/episodio", this::episodio);
        rota("/api/dublagem", this::dublagem);
    }

    public static void main(String[] args) {
        int porta = 8080;
        try {
            porta = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
            Catalogo catalogo = CatalogoFactory.criar();
            ServicoAnime servico = new ServicoAnime(LlmFactory.criar(), catalogo);
            Servidor s = new Servidor(servico, catalogo, porta);
            s.iniciar();
            System.out.println("API rodando em http://127.0.0.1:" + s.porta() + "  (Ctrl+C para parar)");
        } catch (java.net.BindException e) {
            System.out.println("A porta " + porta + " já está em uso. Feche o programa que a usa ou escolha outra: set PORT=8085");
            System.exit(1);
        } catch (Exception e) {
            System.out.println("Não consegui iniciar a API: " + e);
            System.exit(1);
        }
    }

    public void iniciar() {
        http.start();
    }

    public void parar() {
        http.stop(0);
        pool.shutdownNow();
    }

    public int porta() {
        return http.getAddress().getPort();
    }

    // ---------------------------------------------------------------- rotas

    private Object identificar(Map<String, String> q, JsonNode corpo) throws Exception {
        String descricao = texto(corpo, "descricao");
        if (descricao.isBlank()) throw new IllegalArgumentException("Descreva o anime.");
        Identificacao id = servico.identificar(descricao);
        ObjectNode saida = JSON.createObjectNode();
        ArrayNode lista = saida.putArray("candidatos");
        for (Anime a : id.animes()) {
            lista.addObject().put("id", a.id()).put("titulo", a.titulo()).put("confianca", a.confianca());
        }
        saida.put("pergunta", id.pergunta());
        return saida;
    }

    private Object temporadas(Map<String, String> q, JsonNode corpo) throws Exception {
        List<Temporada> ts = catalogo.temporadas(inteiro(q.get("id"), "id"));
        ArrayNode saida = JSON.createArrayNode();
        for (int i = 0; i < ts.size(); i++) {
            Temporada t = ts.get(i);
            ObjectNode o = saida.addObject().put("id", t.id()).put("titulo", t.titulo());
            if (t.episodios() == null) o.putNull("episodios"); else o.put("episodios", t.episodios());
            o.put("status", t.status() == null ? "" : t.status()).put("deslocamento", Fluxo.antes(ts, i));
        }
        return saida;
    }

    /** Sugere a temporada a partir das pistas; a pessoa confirma no front. */
    private Object temporada(Map<String, String> q, JsonNode corpo) throws Exception {
        int id = corpo.path("id").asInt();
        List<Temporada> ts = catalogo.temporadas(id);
        int numero = ts.isEmpty() ? 0 : servico.acharTemporada(new Anime(id, texto(corpo, "titulo"), 1.0), ts, texto(corpo, "pistas"));
        ObjectNode saida = JSON.createObjectNode().put("numero", numero);
        if (numero > 0) saida.put("temporada", ts.get(numero - 1).titulo());
        return saida;
    }

    private Object episodio(Map<String, String> q, JsonNode corpo) throws Exception {
        int id = corpo.path("id").asInt();
        Anime anime = new Anime(id, texto(corpo, "titulo"), 1.0);
        List<String> eps = Fluxo.enriquecer(catalogo.listarEpisodios(id), anime, corpo.path("deslocamento").asInt(-1));
        RespostaEpisodio r = servico.acharEpisodio(anime, eps, texto(corpo, "pistas"));
        ObjectNode saida = JSON.createObjectNode().put("episodio", r.episodio()).put("confianca", r.confianca())
                .put("pergunta", r.pergunta()).put("total", eps.size());
        String linha = eps.stream().filter(l -> l.startsWith(r.episodio() + ":")).findFirst().orElse("");
        saida.put("linha", linha);
        return saida;
    }

    /** Pode levar até 1 minuto: abre a Crunchyroll no navegador sem janela. */
    private Object dublagem(Map<String, String> q, JsonNode corpo) throws Exception {
        int id = inteiro(q.get("id"), "id");
        int ultimoVisto = inteiro(q.get("episodio"), "episodio");
        int total = catalogo.listarEpisodios(id).size();
        String[] fonte = new String[1];
        Anime anime = new Anime(id, q.getOrDefault("titulo", ""), 1.0);
        DubStatusCrunchyroll d = Fluxo.resolverDublagem(catalogo, anime, fonte, total);
        ObjectNode saida = JSON.createObjectNode().put("total", total);
        saida.put("proximo", d == null ? "incerto" : d.proximoEpisodioDublado(id, ultimoVisto + 1));
        saida.put("ultimo", d == null ? "incerto" : d.proximoEpisodioDublado(id, total));
        saida.put("numeroProximo", ultimoVisto + 1);
        saida.put("fonte", d == null ? "" : fonte[0]);
        saida.put("lidos", d == null ? 0 : d.episodiosLidos());
        return saida;
    }

    // ------------------------------------------------------------ utilidades

    private interface Acao {
        Object executar(Map<String, String> consulta, JsonNode corpo) throws Exception;
    }

    private void rota(String caminho, Acao acao) {
        http.createContext(caminho, troca -> {
            try {
                cabecalhos(troca);
                if ("OPTIONS".equals(troca.getRequestMethod())) {
                    troca.sendResponseHeaders(204, -1);
                    return;
                }
                if (!permitido(troca)) {
                    responder(troca, 429, JSON.createObjectNode().put("erro", "Muitas chamadas seguidas. Tente de novo mais tarde."));
                    return;
                }
                JsonNode corpo = JSON.createObjectNode();
                byte[] bytes = troca.getRequestBody().readAllBytes();
                if (bytes.length > 0) corpo = JSON.readTree(bytes);
                responder(troca, 200, acao.executar(consulta(troca), corpo));
            } catch (IllegalArgumentException e) {
                responder(troca, 400, JSON.createObjectNode().put("erro", e.getMessage()));
            } catch (Exception e) {
                responder(troca, 500, JSON.createObjectNode().put("erro", String.valueOf(e.getMessage())));
            } finally {
                troca.close();
            }
        });
    }

    /** Janela de 1 hora por IP (atrás de proxy, usa o primeiro X-Forwarded-For). */
    boolean permitido(HttpExchange t) {
        String ip = t.getRequestHeaders().getFirst("X-Forwarded-For");
        ip = ip != null ? ip.split(",")[0].trim() : t.getRemoteAddress().getAddress().getHostAddress();
        long agora = System.currentTimeMillis();
        java.util.ArrayDeque<Long> fila = usos.computeIfAbsent(ip, k -> new java.util.ArrayDeque<>());
        synchronized (fila) {
            while (!fila.isEmpty() && agora - fila.peekFirst() > 3_600_000L) fila.pollFirst();
            if (fila.size() >= limiteHora) return false;
            fila.addLast(agora);
            return true;
        }
    }

    private static void cabecalhos(HttpExchange t) {
        t.getResponseHeaders().set("Access-Control-Allow-Origin", "*"); // front local em outra porta
        t.getResponseHeaders().set("Access-Control-Allow-Headers", "content-type");
        t.getResponseHeaders().set("Access-Control-Allow-Methods", "GET,POST,OPTIONS");
    }

    private static void responder(HttpExchange t, int status, Object corpo) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(corpo);
        t.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        t.sendResponseHeaders(status, bytes.length);
        t.getResponseBody().write(bytes);
    }

    private static Map<String, String> consulta(HttpExchange t) {
        Map<String, String> m = new HashMap<>();
        String q = t.getRequestURI().getRawQuery();
        if (q == null) return m;
        for (String par : q.split("&")) {
            String[] kv = par.split("=", 2);
            m.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8), kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "");
        }
        return m;
    }

    private static String texto(JsonNode corpo, String campo) {
        return corpo.path(campo).asText("");
    }

    private static int inteiro(String valor, String nome) {
        try {
            return Integer.parseInt(valor);
        } catch (Exception e) {
            throw new IllegalArgumentException("Parâmetro inválido: " + nome);
        }
    }
}
