package bot;

import bot.Modelos.Anime;
import bot.Modelos.Identificacao;
import bot.Modelos.RespostaEpisodio;
import bot.Modelos.Temporada;
import java.util.List;
import java.util.Scanner;

public class Main {
    private static final int MAX_RODADAS = 3;

    public static void main(String[] args) {
        Scanner in = new Scanner(System.in);
        try {
            Catalogo catalogo = CatalogoFactory.criar();
            ServicoAnime servico = new ServicoAnime(LlmFactory.criar(), catalogo);

            System.out.println("Bot de Anime Dublado: acho seu anime e vejo se o próximo episódio saiu dublado.\n");

            Anime anime = escolherAnime(in, servico);
            if (anime == null) {
                System.out.println("Não consegui identificar o anime. Tente de novo com mais detalhes.");
                return;
            }

            Escolha escolha = escolherTemporada(in, servico, catalogo, anime);
            anime = escolha.anime();

            String plataforma = perguntar(in, "Em qual plataforma você assiste (ou quer assistir)? ");
            List<String> episodios = catalogo.listarEpisodios(anime.id());
            episodios = enriquecer(episodios, anime, escolha.deslocamento());
            int ultimo = descobrirUltimoEpisodio(in, servico, anime, episodios, escolha.pistas());
            if (ultimo == 0) {
                System.out.println("Não consegui descobrir o episódio. Se lembrar o número, rode de novo.");
                return;
            }

            int total = episodios.size();
            String[] fonte = new String[1];
            DubStatusCrunchyroll daPagina = resolverDublagem(catalogo, anime, fonte, total);
            DubStatusProvider dublagem = daPagina != null ? daPagina : new DubStatusProviderIncerto();
            String status = dublagem.proximoEpisodioDublado(anime.id(), ultimo + 1);
            String statusUltimo = dublagem.proximoEpisodioDublado(anime.id(), total);
            boolean manual = daPagina == null;
            if (manual) {
                System.out.println("\nNão consegui ler a dublagem sozinho. Abra a página da série na Crunchyroll em português e olhe embaixo do título de cada episódio.");
                status = perguntarDublagem(in, ultimo + 1);
                statusUltimo = ultimo + 1 == total ? status : perguntarDublagem(in, total);
            }
            responder(anime, plataforma, ultimo, status, catalogo);
            System.out.println("\nÚltimo episódio da temporada (" + total + "): " + descricao(statusUltimo, manual) + ".");
            System.out.println(manual ? "(Fonte: o que você viu na página da Crunchyroll.)"
                    : "(Fonte: " + fonte[0] + "; " + daPagina.episodiosLidos() + " episódios lidos.)");
        } catch (IllegalStateException e) {
            System.out.println(e.getMessage()); // por exemplo: falta a chave da API
        } catch (Exception e) {
            System.out.println("Algo deu errado: " + e.getMessage());
        }
    }

    /** Fase 1: descobre o anime por descrição livre, com confirmação da pessoa. */
    private static Anime escolherAnime(Scanner in, ServicoAnime servico) throws Exception {
        StringBuilder descricao = new StringBuilder(perguntar(in, "Qual anime? Pode descrever do seu jeito: "));
        for (int rodada = 0; rodada < MAX_RODADAS; rodada++) {
            Identificacao id = servico.identificar(descricao.toString());
            String proxima = "Ok. Me dá mais uma pista sobre o anime: ";

            if (id.animes().isEmpty()) {
                proxima = id.pergunta() + " ";
            } else if (id.animes().size() == 1) {
                Anime unico = id.animes().get(0);
                String r = perguntar(in, "Você quis dizer \"" + unico.titulo() + "\"? (s/n) ");
                if (r.toLowerCase().startsWith("s")) return unico;
            } else {
                System.out.println("Pode ser um destes:");
                for (int i = 0; i < id.animes().size(); i++) {
                    System.out.println("  " + (i + 1) + ") " + id.animes().get(i).titulo());
                }
                String r = perguntar(in, "Digite o número, ou 0 se não for nenhum: ");
                if (r.matches("[1-9]") && Integer.parseInt(r) <= id.animes().size()) {
                    return id.animes().get(Integer.parseInt(r) - 1);
                }
            }
            String resposta = perguntar(in, proxima);
            descricao.append(" | Pergunta: ").append(proxima.trim()).append(" Resposta: ").append(resposta);
        }
        return null;
    }

    /** Anime já na temporada certa, mais as pistas que a pessoa deu pelo caminho (podem estar vazias). */
    private record Escolha(Anime anime, String pistas, int deslocamento) {}

    /**
     * Se o anime tem várias temporadas, pergunta qual. Se a pessoa não souber, usa as pistas dela
     * para o modelo sugerir a temporada, e a pessoa confirma.
     */
    private static Escolha escolherTemporada(Scanner in, ServicoAnime servico, Catalogo catalogo, Anime anime) {
        List<Temporada> temporadas;
        try {
            temporadas = catalogo.temporadas(anime.id());
        } catch (Exception e) {
            System.out.println("Não consegui listar as temporadas; vou usar \"" + anime.titulo() + "\".");
            return new Escolha(anime, "", 0);
        }
        if (temporadas.size() < 2) return new Escolha(anime, "", 0);

        System.out.println("\nEsse anime tem " + temporadas.size() + " temporadas:");
        for (int i = 0; i < temporadas.size(); i++) {
            Temporada t = temporadas.get(i);
            String eps = t.episodios() == null ? "" : " (" + t.episodios() + " episódios)";
            System.out.println("  " + (i + 1) + ") " + t.titulo() + eps);
        }
        String r = perguntar(in, "Qual temporada você está assistindo? (número, ou Enter se não souber) ");
        if (r.matches("\\d{1,2}") && Integer.parseInt(r) >= 1 && Integer.parseInt(r) <= temporadas.size()) {
            return new Escolha(daTemporada(temporadas.get(Integer.parseInt(r) - 1), anime), "", antes(temporadas, Integer.parseInt(r) - 1));
        }

        // não sabe a temporada: descobre pelas pistas
        String pergunta = ServicoAnime.PERGUNTA_EPISODIO;
        String resposta = perguntar(in, "Sem problema. " + pergunta + " ");
        String pistas = "Pergunta do bot: " + pergunta + "\nResposta da pessoa: " + resposta + "\n";
        try {
            int numero = servico.acharTemporada(anime, temporadas, pistas);
            if (numero > 0) {
                Temporada sugerida = temporadas.get(numero - 1);
                String ok = perguntar(in, "Pelas suas pistas, parece ser \"" + sugerida.titulo() + "\". É essa? (s/n) ");
                if (ok.toLowerCase().startsWith("s")) return new Escolha(daTemporada(sugerida, anime), pistas, antes(temporadas, numero - 1));
            }
        } catch (Exception e) {
            System.out.println("Não consegui descobrir a temporada pelas pistas.");
        }
        Temporada maisRecente = temporadas.get(temporadas.size() - 1);
        System.out.println("Vou usar a temporada mais recente: " + maisRecente.titulo() + ".");
        return new Escolha(daTemporada(maisRecente, anime), pistas, antes(temporadas, temporadas.size() - 1));
    }

    /** Soma os episódios das temporadas anteriores; -1 se alguma não tiver o total conhecido. */
    private static int antes(List<Temporada> temporadas, int indice) {
        int soma = 0;
        for (int i = 0; i < indice; i++) {
            if (temporadas.get(i).episodios() == null) return -1;
            soma += temporadas.get(i).episodios();
        }
        return soma;
    }

    /** Se o catálogo só deu números, tenta trazer título e sinopse reais para o modelo comparar com as pistas. */
    private static List<String> enriquecer(List<String> episodios, Anime anime, int deslocamento) {
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

    private static Anime daTemporada(Temporada t, Anime original) {
        return new Anime(t.id(), t.titulo(), original.confianca());
    }

    /** Fase 2: pega o número direto ou ajuda a pessoa a lembrar por pistas. Devolve 0 se não achou. */
    private static int descobrirUltimoEpisodio(Scanner in, ServicoAnime servico, Anime anime,
                                               List<String> episodios, String pistasIniciais) throws Exception {
        String r = perguntar(in, "Qual foi o último episódio que você viu dublado? (número, ou 'não lembro') ");
        if (r.matches("\\d{1,5}")) {
            int n = Integer.parseInt(r);
            if (ServicoAnime.numeros(episodios).contains(n)) return n;
            System.out.println("Esse número não está na lista de episódios. Vamos tentar por pistas.");
        }

        StringBuilder pistas = new StringBuilder(pistasIniciais);
        String pergunta = ServicoAnime.PERGUNTA_EPISODIO;
        for (int rodada = 0; rodada < MAX_RODADAS; rodada++) {
            // se a pessoa já deu pistas ao escolher a temporada, usa elas antes de perguntar de novo
            if (!(rodada == 0 && !pistasIniciais.isEmpty())) {
                String resposta = perguntar(in, pergunta + " ");
                pistas.append("Pergunta do bot: ").append(pergunta).append("\nResposta da pessoa: ").append(resposta).append("\n");
            }
            RespostaEpisodio resp = servico.acharEpisodio(anime, episodios, pistas.toString());
            if (resp.episodio() != 0) {
                String ok = perguntar(in, "Foi o episódio " + resp.episodio()
                        + tituloDoEpisodio(episodios, resp.episodio()) + "? (s/n) ");
                if (ok.toLowerCase().startsWith("s")) return resp.episodio();
                pistas.append("(Não é o episódio ").append(resp.episodio()).append(".)\n");
            }
            pergunta = resp.pergunta().isBlank() ? ServicoAnime.PERGUNTA_EPISODIO : resp.pergunta();
        }
        return 0;
    }

    private static String tituloDoEpisodio(List<String> episodios, int numero) {
        String prefixo = numero + ": ";
        for (String e : episodios) {
            if (e.startsWith(prefixo) && e.length() > prefixo.length()) {
                return " (" + e.substring(prefixo.length()) + ")";
            }
        }
        return "";
    }

    /** 1) baixa a página; 2) abre no Chrome/Edge sem janela; 3) lê crunchyroll.html salvo; 4) crunchyroll.txt; senão null. */
    private static DubStatusCrunchyroll resolverDublagem(Catalogo catalogo, Anime anime, String[] fonte, int total) {
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
                    DubStatusCrunchyroll melhor = DubStatusCrunchyroll.doHtml(CrunchyrollPagina.baixarRolando(url.get(), total));
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

    private static String descricao(String status, boolean manual) {
        return switch (status) {
            case "sim" -> "dublado em português";
            case "nao" -> "ainda só legendado";
            default -> manual ? "você não soube dizer" : "a página não mostrou esse episódio (não dá para afirmar)";
        };
    }

    private static String perguntarDublagem(Scanner in, int episodio) {
        String r = perguntar(in, "O episódio " + episodio + " aparece com \"Dub\" (dublado)? (s = sim, n = só legendado, Enter = não sei) ").toLowerCase();
        if (r.startsWith("s")) return "sim";
        if (r.startsWith("n")) return "nao";
        return "incerto";
    }

    private static void responder(Anime anime, String plataforma, int ultimo, String status,
                                  Catalogo catalogo) throws Exception {
        int proximo = ultimo + 1;
        System.out.println();
        switch (status) {
            case "sim" -> System.out.println("O episódio " + proximo + " de " + anime.titulo() + " já aparece como dublado na Crunchyroll.");
            case "nao" -> System.out.println("O episódio " + proximo + " de " + anime.titulo() + " aparece só legendado na Crunchyroll (ainda sem dublagem).");
            default -> System.out.println("Ainda não tenho uma fonte confiável sobre a dublagem do episódio "
                    + proximo + ". Confira o catálogo oficial de " + plataforma + ".");
        }

        List<String> onde = catalogo.plataformas(anime.id());
        if (!onde.isEmpty()) {
            System.out.println("\nOnde assistir (lista do catálogo; pode não incluir dublagem em português):");
            onde.forEach(p -> System.out.println("  - " + p));
        }
    }

    private static String perguntar(Scanner in, String texto) {
        System.out.print(texto);
        return in.nextLine().trim();
    }
}
