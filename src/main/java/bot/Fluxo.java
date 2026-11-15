package bot;

import bot.Modelos.Anime;
import bot.Modelos.Temporada;
import java.util.List;

/** Passos do fluxo que o terminal (Main) e a API web (Servidor) usam do mesmo jeito. */
final class Fluxo {
    private Fluxo() {
    }

    /** Soma os episódios das temporadas anteriores; -1 se alguma não tiver o total conhecido. */
    static int antes(List<Temporada> temporadas, int indice) {
        int soma = 0;
        for (int i = 0; i < indice; i++) {
            if (temporadas.get(i).episodios() == null) return -1;
            soma += temporadas.get(i).episodios();
        }
        return soma;
    }

    /** Se o catálogo só deu números, tenta trazer título e sinopse reais para o modelo comparar com as pistas. */
    static List<String> enriquecer(List<String> episodios, Anime anime, int deslocamento) {
        boolean semTitulos = episodios.stream().allMatch(l -> l.replaceFirst("^\\d+:?\\s*", "").isBlank());
        if (!semTitulos) return episodios;

        List<String> ricos = new TvmazeClient().episodios(nomeDaSerie(anime.titulo()), deslocamento, episodios.size());
        if (ricos.isEmpty()) {
            // Kitsu só vale se a quantidade de episódios bater exatamente; senão seriam episódios de outra temporada
            List<String> kitsu = new KitsuClient().episodiosDetalhados(anime.titulo(), episodios.size());
            if (kitsu.size() == episodios.size()) ricos = kitsu;
        }
        if (ricos.isEmpty()) {
            System.out.println("(Não consegui títulos dos episódios; vou usar só os números.)");
            return episodios;
        }
        System.out.println("(Achei títulos e sinopses de " + ricos.size() + " episódios. O primeiro é: "
                + ricos.get(0).substring(0, Math.min(70, ricos.get(0).length())) + ")");
        return ricos;
    }

    /** "Re:ZERO -Starting Life in Another World- Season 4" -> "Re:ZERO -Starting Life in Another World-" */
    static String nomeDaSerie(String titulo) {
        return titulo.replaceAll("(?i)\\s+(season|temporada)\\s+\\d+.*$", "").trim();
    }

    /** 1) baixa a página; 2) abre no Chrome/Edge sem janela; 3) lê crunchyroll.html salvo; 4) crunchyroll.txt; senão null. */
    static DubStatusCrunchyroll resolverDublagem(Catalogo catalogo, Anime anime, String[] fonte, int total) {
        try {
            var url = CrunchyrollPagina.urlPtBr(catalogo.plataformas(anime.id()));
            if (url.isPresent()) {
                System.out.println("\nConferindo a dublagem na Crunchyroll (pode levar até 1 minuto)...");
                DubStatusCrunchyroll d = DubStatusCrunchyroll.doHtml(CrunchyrollPagina.baixar(url.get()));
                if (d != null) {
                    fonte[0] = "página da Crunchyroll baixada agora";
                    return d;
                }
                String navegador = CrunchyrollPagina.acharNavegador();
                if (navegador == null) {
                    System.out.println("(Não achei Chrome nem Edge para abrir a página. Defina CHROME_PATH com o caminho do navegador.)");
                } else {
                    // a página só carrega os episódios quando alguém rola: o bot rola sozinho
                    String htmlRolado = CrunchyrollPagina.baixarRolando(url.get(), total);
                    DubStatusCrunchyroll melhor = DubStatusCrunchyroll.doHtml(htmlRolado);
                    if (melhor == null || melhor.episodiosLidos() < total) {
                        CrunchyrollPagina.salvarDiagnostico(htmlRolado);
                        System.out.println("(Li " + (melhor == null ? 0 : melhor.episodiosLidos()) + " de " + total
                                + " episódios. Salvei debug-crunchyroll.txt para diagnóstico.)");
                    }
                    if (melhor == null || melhor.episodiosLidos() < total) { // plano B: abrir sem rolar
                        DubStatusCrunchyroll simples = DubStatusCrunchyroll.doHtml(CrunchyrollPagina.baixarComNavegador(url.get(), 9000));
                        if (simples != null && (melhor == null || simples.episodiosLidos() > melhor.episodiosLidos())) melhor = simples;
                    }
                    if (melhor != null) {
                        fonte[0] = "página da Crunchyroll aberta no navegador agora";
                        return melhor;
                    }
                    System.out.println("(Abri a página no navegador, mas não achei os rótulos de dublagem nela.)");
                }
            } else {
                System.out.println("(O catálogo não trouxe o link da Crunchyroll para essa temporada.)");
            }
        } catch (Exception e) {
            System.out.println("(Erro ao consultar a Crunchyroll: " + e.getMessage() + ")");
        }
        for (String nome : new String[]{"crunchyroll.html", "crunchyroll.txt"}) {
            DubStatusCrunchyroll d = DubStatusCrunchyroll.doArquivo(java.nio.file.Path.of(nome));
            if (d != null) {
                fonte[0] = "arquivo " + nome;
                return d;
            }
        }
        return null;
    }
}
