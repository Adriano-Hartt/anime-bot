package bot;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lê o texto copiado da página de episódios da Crunchyroll (em português) e descobre quais
 * episódios têm o rótulo "Dub | Leg" (dublado + legendado) ou só "Leg" (somente legendado).
 * Não acessa a Crunchyroll: quem copia a página é a pessoa, então não há raspagem nem login.
 */
public class DubStatusCrunchyroll implements DubStatusProvider {
    private static final Pattern CABECALHO = Pattern.compile("^E(\\d{1,3})\\s*[-–]\\s*\\S.*$");
    private static final Pattern DUB = Pattern.compile("\\bDub\\b");
    private static final Pattern LEG = Pattern.compile("\\bLeg\\b");

    private final Map<Integer, String> status;

    public DubStatusCrunchyroll(String textoDaPagina) {
        this.status = analisar(textoDaPagina);
    }

    private static final Pattern CARTAO = Pattern.compile("data-t=\"episode-card ?\"");
    private static final Pattern TITULO_HTML = Pattern.compile(">E(\\d{1,3})\\s*[-–]\\s*[^<]+<");
    private static final Pattern ROTULO_HTML = Pattern.compile("data-t=\"meta-tags\">(.*?)</div></div>", Pattern.DOTALL);

    /** Lê o HTML da página (baixado ou salvo com Ctrl+S): cada cartão de episódio traz "Dub | Leg", "Dublado" ou "Legendado". */
    public static DubStatusCrunchyroll doHtml(String html) {
        DubStatusCrunchyroll d = new DubStatusCrunchyroll(null);
        d.status.putAll(analisarHtml(html));
        return d.temRotulos() ? d : null;
    }

    static Map<Integer, String> analisarHtml(String html) {
        Map<Integer, String> mapa = new HashMap<>();
        if (html == null) return mapa;
        String[] cartoes = CARTAO.split(html);
        for (int i = 1; i < cartoes.length; i++) {
            String c = cartoes[i];
            int fim = c.indexOf("data-t=\"carousel-card-wrapper\"");
            if (fim >= 0) c = c.substring(0, fim);
            Matcher t = TITULO_HTML.matcher(c);
            if (!t.find()) continue;
            int n = Integer.parseInt(t.group(1));
            if (mapa.containsKey(n)) break; // numeração repetida: outra temporada
            Matcher r = ROTULO_HTML.matcher(c);
            String rotulo = r.find() ? r.group(1).replaceAll("<[^>]+>", " ") : "";
            mapa.put(n, classificarRotulo(rotulo));
        }
        return mapa;
    }

    private static String classificarRotulo(String rotulo) {
        String r = rotulo.toLowerCase();
        if (r.contains("dub")) return "sim";
        if (r.contains("leg")) return "nao";
        return "incerto";
    }

    /** Lê o arquivo; se não existir ou não tiver episódios, devolve null. */
    public static DubStatusCrunchyroll doArquivo(Path arquivo) {
        try {
            if (!Files.exists(arquivo)) return null;
            String conteudo = Files.readString(arquivo);
            if (arquivo.toString().toLowerCase().endsWith(".html")) return doHtml(conteudo);
            DubStatusCrunchyroll d = new DubStatusCrunchyroll(conteudo);
            return d.temRotulos() ? d : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Só vale usar o arquivo se pelo menos um episódio trouxe "Dub" ou "Leg". */
    boolean temRotulos() {
        return status.values().stream().anyMatch(v -> !v.equals("incerto"));
    }

    public int episodiosLidos() {
        return status.size();
    }

    @Override
    public String proximoEpisodioDublado(int id, int episodio) {
        return status.getOrDefault(episodio, "incerto");
    }

    /** "sim" se o bloco do episódio tem "Dub", "nao" se tem só "Leg", senão "incerto". Para na 1ª temporada lida. */
    static Map<Integer, String> analisar(String texto) {
        Map<Integer, String> mapa = new HashMap<>();
        if (texto == null) return mapa;
        int atual = -1;
        int anterior = 0;
        StringBuilder bloco = new StringBuilder();
        for (String linha : texto.split("\\R")) {
            Matcher m = CABECALHO.matcher(linha.trim());
            if (m.matches()) {
                if (atual > 0) mapa.put(atual, classificar(bloco.toString()));
                int n = Integer.parseInt(m.group(1));
                if (n <= anterior) { // a numeração recomeçou: é outra temporada
                    atual = -1;
                    bloco.setLength(0);
                    return mapa;
                }
                atual = n;
                anterior = n;
                bloco.setLength(0);
            } else if (atual > 0) {
                bloco.append(linha).append('\n');
            }
        }
        if (atual > 0) mapa.put(atual, classificar(bloco.toString()));
        return mapa;
    }

    private static String classificar(String bloco) {
        if (DUB.matcher(bloco).find()) return "sim";
        if (LEG.matcher(bloco).find()) return "nao";
        return "incerto";
    }
}
