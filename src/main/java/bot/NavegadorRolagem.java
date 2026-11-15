package bot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.File;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Controla o Chrome/Edge sem janela pelo protocolo de depuração (CDP): abre a página, rola até o fim
 * para o site carregar todos os episódios e devolve o HTML final. Não precisa de bibliotecas extras.
 */
final class NavegadorRolagem implements WebSocket.Listener {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36";

    private final Map<Integer, CompletableFuture<JsonNode>> pendentes = new ConcurrentHashMap<>();
    private final AtomicInteger ids = new AtomicInteger();
    private final StringBuilder parcial = new StringBuilder();
    private WebSocket ws;
    private final StringBuilder relatorio = new StringBuilder();
    /** Resumo do que o navegador viu na última execução (para diagnóstico). */
    static volatile String ultimoRelatorio = "";

    private NavegadorRolagem() {
    }

    /** Abre a url, rola até o número de cartões "seletor" parar de crescer (ou chegar em esperado) e devolve o HTML. */
    static String baixar(String exe, String url, String seletorCartao, int esperado) {
        Path perfil = null;
        Process p = null;
        try {
            int porta;
            try (ServerSocket ss = new ServerSocket(0)) {
                porta = ss.getLocalPort();
            }
            perfil = Files.createTempDirectory("anime-bot-cdp");
            List<String> cmd = new ArrayList<>(List.of(exe, "--headless=new", "--disable-gpu", "--no-first-run",
                    "--user-data-dir=" + perfil, "--remote-debugging-port=" + porta, "--remote-allow-origins=*",
                    "--lang=pt-BR", "--user-agent=" + UA, "--window-size=1400,1000"));
            if ("root".equals(System.getProperty("user.name"))) cmd.add("--no-sandbox"); // só testes em Linux como root
            cmd.add("about:blank");
            p = new ProcessBuilder(cmd).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();

            String alvo = esperarAlvo(porta);
            if (alvo == null) return null;
            NavegadorRolagem n = new NavegadorRolagem();
            n.ws = HttpClient.newHttpClient().newWebSocketBuilder().buildAsync(URI.create(alvo), n).get(20, TimeUnit.SECONDS);
            return n.rolarELer(url, seletorCartao, esperado);
        } catch (Exception e) {
            return null;
        } finally {
            if (p != null) p.destroyForcibly();
            if (perfil != null) apagar(perfil.toFile());
        }
    }

    private String rolarELer(String url, String seletor, int esperado) throws Exception {
        call("Page.navigate", JSON.createObjectNode().put("url", url));
        for (int i = 0; i < 40 && !"complete".equals(avaliar("document.readyState")); i++) Thread.sleep(500);

        // rola a janela E leva o último cartão para a vista (funciona mesmo se a lista tiver rolagem própria)
        String contar = "(function(){var c=document.querySelectorAll('" + seletor + "');"
                + "if(c.length){c[c.length-1].scrollIntoView({block:'end'})}window.scrollBy(0,700);"
                + "return c.length+'|'+document.body.scrollHeight+'|'+window.innerHeight+'|'+Math.round(window.scrollY)})()";
        // se a página tiver um botão "Mostrar mais", o bot clica (algumas temporadas só mostram parte dos episódios)
        String clicar = "(function(){var b=[].slice.call(document.querySelectorAll('button,a,[role=button]')).filter(function(e){"
                + "return /^\\s*(mostrar mais|ver mais|show more|carregar mais)\\s*$/i.test(e.textContent||'')});"
                + "b.forEach(function(e){e.click()});return b.length})()";
        int anterior = -1, iguais = 0;
        for (int i = 0; i < 60; i++) {
            Thread.sleep(800);
            avaliar(clicar);
            int n;
            String leitura = avaliar(contar);
            relatorio.append(i).append(": cartões|altura|janela|rolagem = ").append(leitura).append('\n');
            try {
                n = Integer.parseInt(leitura.split("\\|")[0]);
            } catch (NumberFormatException e) {
                n = 0;
            }
            if (esperado > 0 && n >= esperado) break;
            iguais = n == anterior ? iguais + 1 : 0;
            anterior = n;
            if (iguais == 3) avaliar("window.scrollTo(0,0)"); // volta ao topo e desce de novo para o site continuar carregando
            if (n > 0 && iguais >= 14) break; // parou de crescer há ~11 s
            if (n == 0 && iguais >= 25) break; // nada apareceu
        }
        relatorio.append("botões na página: ").append(avaliar(
                "[].slice.call(document.querySelectorAll('button,[role=button]')).map(function(e){return (e.textContent||'').trim().slice(0,30)}).filter(Boolean).join(' / ')")).append('\n');
        ultimoRelatorio = relatorio.toString();
        return avaliar("document.documentElement.outerHTML");
    }

    private String avaliar(String expressao) throws Exception {
        ObjectNode params = JSON.createObjectNode().put("expression", expressao).put("returnByValue", true);
        return call("Runtime.evaluate", params).path("result").path("result").path("value").asText("");
    }

    private JsonNode call(String metodo, ObjectNode params) throws Exception {
        int id = ids.incrementAndGet();
        CompletableFuture<JsonNode> f = new CompletableFuture<>();
        pendentes.put(id, f);
        ObjectNode msg = JSON.createObjectNode().put("id", id).put("method", metodo);
        msg.set("params", params);
        ws.sendText(JSON.writeValueAsString(msg), true);
        return f.get(60, TimeUnit.SECONDS);
    }

    private static String esperarAlvo(int porta) throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        for (int i = 0; i < 40; i++) {
            try {
                HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + porta + "/json/list"))
                        .timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.ofString());
                for (JsonNode t : JSON.readTree(r.body())) {
                    if ("page".equals(t.path("type").asText()) && t.hasNonNull("webSocketDebuggerUrl")) {
                        return t.path("webSocketDebuggerUrl").asText();
                    }
                }
            } catch (Exception e) {
                // o Chrome ainda está abrindo
            }
            Thread.sleep(500);
        }
        return null;
    }

    @Override
    public void onOpen(WebSocket webSocket) {
        webSocket.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        parcial.append(data);
        if (last) {
            String texto = parcial.toString();
            parcial.setLength(0);
            try {
                JsonNode n = JSON.readTree(texto);
                if (n.has("id")) {
                    CompletableFuture<JsonNode> f = pendentes.remove(n.get("id").asInt());
                    if (f != null) f.complete(n);
                }
            } catch (Exception e) {
                // mensagem de evento que não interessa
            }
        }
        webSocket.request(1);
        return null;
    }

    private static void apagar(File f) {
        File[] filhos = f.listFiles();
        if (filhos != null) for (File x : filhos) apagar(x);
        f.delete();
    }
}
