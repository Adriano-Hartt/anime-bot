package bot;

import java.io.File;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Tenta baixar a página pública de episódios da Crunchyroll (em português). Pode falhar: o site pode bloquear. */
final class CrunchyrollPagina {
    private static final Pattern SERIE = Pattern.compile("crunchyroll\\.com/(?:[a-z-]+/)?series/([A-Z0-9]+)");

    private CrunchyrollPagina() {
    }

    /** Acha o link da série nas plataformas do catálogo ("Crunchyroll - https://...") e monta a URL em pt-br. */
    static Optional<String> urlPtBr(List<String> plataformas) {
        for (String p : plataformas) {
            Matcher m = SERIE.matcher(p);
            if (m.find()) return Optional.of("https://www.crunchyroll.com/pt-br/series/" + m.group(1));
        }
        return Optional.empty();
    }

    /** Devolve o HTML ou null se não conseguir. */
    static String baixar(String url) {
        try {
            HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20))
                    .followRedirects(HttpClient.Redirect.NORMAL).build();
            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(40))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36")
                    .header("Accept-Language", "pt-BR,pt;q=0.9").GET().build();
            HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
            return r.statusCode() == 200 ? r.body() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36";

    /** Lugares comuns do Chrome/Edge no Windows (e CHROME_PATH, se a pessoa definir). */
    static List<String> navegadoresPossiveis() {
        List<String> l = new ArrayList<>();
        String env = System.getenv("CHROME_PATH");
        if (env != null && !env.isBlank()) l.add(env);
        for (String base : new String[]{System.getenv("ProgramFiles"), System.getenv("ProgramFiles(x86)"), System.getenv("LOCALAPPDATA")}) {
            if (base == null) continue;
            l.add(base + "\\Google\\Chrome\\Application\\chrome.exe");
            l.add(base + "\\Microsoft\\Edge\\Application\\msedge.exe");
        }
        l.add("/usr/bin/google-chrome");
        l.add("/usr/bin/chromium");
        return l;
    }

    static String acharNavegador() {
        for (String c : navegadoresPossiveis()) if (new File(c).isFile()) return c;
        return null;
    }

    /**
     * Abre a página num Chrome/Edge sem janela (--headless --dump-dom), espera o JavaScript montar a página
     * e devolve o HTML pronto. Devolve null se não houver navegador ou se der erro.
     */
    static String baixarComNavegador(String url, int altura) {
        String exe = acharNavegador();
        if (exe == null) return null;
        Path perfil = null;
        Process p = null;
        try {
            perfil = Files.createTempDirectory("anime-bot-chrome");
            List<String> cmd = new ArrayList<>(List.of(exe, "--headless=new", "--disable-gpu", "--no-first-run",
                    "--user-data-dir=" + perfil, "--lang=pt-BR", "--user-agent=" + UA,
                    "--window-size=1400," + altura, "--virtual-time-budget=25000", "--dump-dom"));
            if ("root".equals(System.getProperty("user.name"))) cmd.add("--no-sandbox"); // só para testes em Linux como root
            cmd.add(url);
            p = new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            final Process proc = p;
            CompletableFuture<String> saida = CompletableFuture.supplyAsync(() -> {
                try {
                    return new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                } catch (Exception e) {
                    return null;
                }
            });
            return saida.get(90, TimeUnit.SECONDS);
        } catch (Exception e) {
            return null;
        } finally {
            if (p != null) p.destroyForcibly();
            if (perfil != null) apagar(perfil.toFile());
        }
    }

    private static void apagar(File f) {
        File[] filhos = f.listFiles();
        if (filhos != null) for (File x : filhos) apagar(x);
        f.delete();
    }

    /** Abre a página no navegador e ROLA até todos os episódios aparecerem. Devolve o HTML ou null. */
    static String baixarRolando(String url, int episodiosEsperados) {
        String exe = acharNavegador();
        if (exe == null) return null;
        return NavegadorRolagem.baixar(exe, url, "[data-t^=episode-card]", episodiosEsperados);
    }

    /** Salva o que o navegador viu, para diagnóstico quando faltam episódios. */
    static void salvarDiagnostico(String html) {
        try {
            Files.writeString(Path.of("debug-crunchyroll.txt"), NavegadorRolagem.ultimoRelatorio);
            if (html != null) Files.writeString(Path.of("debug-crunchyroll.html"), html);
        } catch (Exception e) {
            // diagnóstico é opcional
        }
    }
}
