package bot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import bot.Modelos.Achado;
import bot.Modelos.Anime;
import bot.Modelos.Identificacao;
import bot.Modelos.RespostaEpisodio;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ServicoAnimeTest {

    private static final Anime ONE_PIECE = new Anime(21, "One Piece", 0.9);
    private static final List<String> EPISODIOS = List.of("1: Eu sou o Luffy", "2: Zoro", "3: Morgan");

    /** Modelo de mentira: devolve sempre o texto combinado, sem chamar API nenhuma. */
    private static Llm llmQueResponde(String resposta) {
        return (sistema, usuario) -> resposta;
    }

    /** Catálogo de mentira: conhece só os títulos do mapa. */
    private static JikanClient catalogoCom(Map<String, Achado> animes) {
        return new JikanClient() {
            @Override
            public Achado buscar(String titulo) {
                return animes.get(titulo);
            }
        };
    }

    private static final Map<String, Achado> SO_ONE_PIECE = Map.of("One Piece", new Achado(21, "One Piece"));

    @Test
    void descartaTituloInventadoPeloModelo() throws Exception {
        Llm llm = llmQueResponde("{\"candidatos\":[{\"titulo\":\"One Piece\",\"confianca\":0.9},"
                + "{\"titulo\":\"Anime Que Nao Existe\",\"confianca\":0.8}],\"pergunta\":\"\"}");
        Identificacao id = new ServicoAnime(llm, catalogoCom(SO_ONE_PIECE)).identificar("pirata que estica");

        assertEquals(1, id.animes().size());
        assertEquals("One Piece", id.animes().get(0).titulo());
    }

    @Test
    void aceitaJsonCercadoPorTextoOuCercas() throws Exception {
        Llm llm = llmQueResponde("Claro!\n```json\n{\"candidatos\":[{\"titulo\":\"One Piece\","
                + "\"confianca\":0.9}],\"pergunta\":\"\"}\n```");
        Identificacao id = new ServicoAnime(llm, catalogoCom(SO_ONE_PIECE)).identificar("pirata que estica");

        assertEquals(1, id.animes().size());
    }

    @Test
    void respostaQueNaoEJsonViraPerguntaEmVezDeErro() throws Exception {
        Llm llm = llmQueResponde("desculpa, não entendi");
        Identificacao id = new ServicoAnime(llm, catalogoCom(SO_ONE_PIECE)).identificar("algo");

        assertTrue(id.animes().isEmpty());
        assertFalse(id.pergunta().isBlank());
    }

    @Test
    void doisTitulosQueApontamParaOMesmoAnimeViramUm() throws Exception {
        Map<String, Achado> catalogo = Map.of(
                "One Piece", new Achado(21, "One Piece"),
                "Wan Pisu", new Achado(21, "One Piece"));
        Llm llm = llmQueResponde("{\"candidatos\":[{\"titulo\":\"One Piece\",\"confianca\":0.9},"
                + "{\"titulo\":\"Wan Pisu\",\"confianca\":0.5}],\"pergunta\":\"\"}");

        assertEquals(1, new ServicoAnime(llm, catalogoCom(catalogo)).identificar("x").animes().size());
    }

    @Test
    void errosDeRedeSobemParaQuemChamou() {
        Llm llm = (sistema, usuario) -> {
            throw new RuntimeException("sem internet");
        };
        ServicoAnime servico = new ServicoAnime(llm, catalogoCom(SO_ONE_PIECE));

        assertThrows(RuntimeException.class, () -> servico.identificar("x"));
    }

    @Test
    void rejeitaEpisodioQueNaoEstaNaLista() throws Exception {
        Llm llm = llmQueResponde("{\"episodio\":99,\"confianca\":0.95,\"pergunta\":\"\"}");
        RespostaEpisodio r = new ServicoAnime(llm, catalogoCom(SO_ONE_PIECE))
                .acharEpisodio(ONE_PIECE, EPISODIOS, "luta contra o Morgan");

        assertEquals(0, r.episodio());
        assertFalse(r.pergunta().isBlank());
    }

    @Test
    void aceitaEpisodioDaListaComConfiancaSuficiente() throws Exception {
        Llm llm = llmQueResponde("{\"episodio\":3,\"confianca\":0.9,\"pergunta\":\"\"}");
        RespostaEpisodio r = new ServicoAnime(llm, catalogoCom(SO_ONE_PIECE))
                .acharEpisodio(ONE_PIECE, EPISODIOS, "luta contra o Morgan");

        assertEquals(3, r.episodio());
    }

    @Test
    void confiancaBaixaNaoChutaEFazPergunta() throws Exception {
        Llm llm = llmQueResponde("{\"episodio\":2,\"confianca\":0.3,\"pergunta\":\"Lembra de algum arco?\"}");
        RespostaEpisodio r = new ServicoAnime(llm, catalogoCom(SO_ONE_PIECE))
                .acharEpisodio(ONE_PIECE, EPISODIOS, "não sei");

        assertEquals(0, r.episodio());
        assertEquals("Lembra de algum arco?", r.pergunta());
    }

    @Test
    void numerosIgnoraLinhasForaDoFormato() {
        assertEquals(java.util.Set.of(1, 2), ServicoAnime.numeros(List.of("1: a", "2: b", "lixo", "x: y")));
    }
}
