package bot;

import bot.Modelos.Achado;
import bot.Modelos.Anime;
import bot.Modelos.Identificacao;
import bot.Modelos.RespostaEpisodio;
import bot.Modelos.Temporada;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Regra de negócio do bot. O modelo propõe; este serviço confere tudo antes de aceitar:
 * título tem que existir no catálogo e episódio tem que estar na lista.
 */
public class ServicoAnime {
    public static final double CONFIANCA_MINIMA = 0.6;
    static final String PERGUNTA_ANIME =
            "Não entendi bem. Conta mais: como é o personagem principal ou o que acontece na história?";
    static final String PERGUNTA_EPISODIO =
            "Lembra de alguma saga ou arco, de um personagem novo ou de uma cena marcante?";

    private final Llm llm;
    private final Catalogo catalogo;
    private final ObjectMapper json = new ObjectMapper();

    public ServicoAnime(Llm llm, Catalogo catalogo) {
        this.llm = llm;
        this.catalogo = catalogo;
    }

    /** Etapa 1: transforma uma descrição solta em animes confirmados no catálogo. */
    public Identificacao identificar(String descricao) throws Exception {
        String resposta = llm.perguntar(Prompts.SISTEMA_IDENTIFICAR, descricao); // erros de rede sobem
        JsonNode raiz;
        try {
            raiz = lerJson(resposta);
        } catch (JsonProcessingException e) {
            return new Identificacao(List.of(), PERGUNTA_ANIME); // modelo não devolveu JSON: pergunta de novo
        }

        List<Anime> validos = new ArrayList<>();
        Set<Integer> vistos = new HashSet<>();
        for (JsonNode c : raiz.path("candidatos")) {
            String titulo = c.path("titulo").asText("").trim();
            if (titulo.isEmpty()) continue;
            Achado achado = catalogo.buscar(titulo);
            // descarta o que o catálogo não conhece e títulos repetidos
            if (achado != null && vistos.add(achado.id())) {
                validos.add(new Anime(achado.id(), achado.titulo(), c.path("confianca").asDouble(0)));
            }
        }
        validos.sort(Comparator.comparingDouble(Anime::confianca).reversed());

        String pergunta = raiz.path("pergunta").asText("").trim();
        if (validos.isEmpty() && pergunta.isEmpty()) pergunta = PERGUNTA_ANIME;
        return new Identificacao(validos, pergunta);
    }

    /** Etapa 2: cruza as pistas da pessoa com a lista de episódios ("N: título"). */
    public RespostaEpisodio acharEpisodio(Anime anime, List<String> episodios, String pistas) throws Exception {
        String usuario = "Anime: " + anime.titulo()
                + "\n\nEpisódios:\n" + String.join("\n", episodios)
                + "\n\nPistas da pessoa:\n" + pistas;
        String resposta = llm.perguntar(Prompts.SISTEMA_EPISODIO, usuario);

        JsonNode raiz;
        try {
            raiz = lerJson(resposta);
        } catch (JsonProcessingException e) {
            return new RespostaEpisodio(0, 0, PERGUNTA_EPISODIO);
        }

        int episodio = raiz.path("episodio").asInt(0);
        double confianca = raiz.path("confianca").asDouble(0);
        String pergunta = raiz.path("pergunta").asText("").trim();

        // o modelo citou um episódio que não existe na lista: não confiamos
        if (episodio != 0 && !numeros(episodios).contains(episodio)) {
            episodio = 0;
        }
        // confiança baixa: não chuta, faz outra pergunta
        if (confianca < CONFIANCA_MINIMA) {
            episodio = 0;
        }
        if (episodio == 0 && pergunta.isEmpty()) pergunta = PERGUNTA_EPISODIO;
        return new RespostaEpisodio(episodio, confianca, pergunta);
    }

    /**
     * Descobre em qual temporada a pessoa está a partir das pistas. Devolve o número (1 = primeira da lista)
     * ou 0 se o modelo não tiver confiança, citar um número fora da lista ou não devolver JSON.
     */
    public int acharTemporada(Anime anime, List<Temporada> temporadas, String pistas) throws Exception {
        StringBuilder lista = new StringBuilder();
        for (int i = 0; i < temporadas.size(); i++) {
            Temporada t = temporadas.get(i);
            lista.append(i + 1).append(": ").append(t.titulo());
            if (t.episodios() != null) lista.append(" (").append(t.episodios()).append(" episódios)");
            lista.append("\n");
        }
        String usuario = "Anime: " + anime.titulo() + "\n\nTemporadas:\n" + lista + "\nPistas da pessoa:\n" + pistas;
        String resposta = llm.perguntar(Prompts.SISTEMA_TEMPORADA, usuario);

        JsonNode raiz;
        try {
            raiz = lerJson(resposta);
        } catch (JsonProcessingException e) {
            return 0;
        }
        int numero = raiz.path("temporada").asInt(0);
        double confianca = raiz.path("confianca").asDouble(0);
        if (numero < 1 || numero > temporadas.size() || confianca < CONFIANCA_MINIMA) return 0;
        return numero;
    }

    /** Extrai os números de uma lista no formato "N: título". */
    public static Set<Integer> numeros(List<String> episodios) {
        Set<Integer> numeros = new HashSet<>();
        for (String e : episodios) {
            int doisPontos = e.indexOf(':');
            if (doisPontos > 0) {
                try {
                    numeros.add(Integer.parseInt(e.substring(0, doisPontos).trim()));
                } catch (NumberFormatException ignorado) {
                    // linha fora do formato: ignora
                }
            }
        }
        return numeros;
    }

    /** O modelo às vezes cerca o JSON com texto ou cercas de código; pega só do primeiro { ao último }. */
    private JsonNode lerJson(String texto) throws JsonProcessingException {
        int ini = texto.indexOf('{');
        int fim = texto.lastIndexOf('}');
        return json.readTree(ini >= 0 && fim > ini ? texto.substring(ini, fim + 1) : texto);
    }
}
