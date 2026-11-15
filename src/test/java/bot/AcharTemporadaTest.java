package bot;

import static org.junit.jupiter.api.Assertions.assertEquals;

import bot.Modelos.Anime;
import bot.Modelos.Temporada;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Testa a escolha da temporada pelas pistas, com um modelo de mentira e sem internet. */
class AcharTemporadaTest {

    private static final Anime ANIME = new Anime(1, "Anime X", 0.9);
    private static final List<Temporada> TEMPORADAS = List.of(
            new Temporada(1, "Anime X", 12, "FINISHED"),
            new Temporada(2, "Anime X 2", 12, "FINISHED"),
            new Temporada(3, "Anime X 3", null, "RELEASING"));

    private static int rodar(String respostaDoModelo) throws Exception {
        ServicoAnime servico = new ServicoAnime((sistema, usuario) -> respostaDoModelo, new JikanClient());
        return servico.acharTemporada(ANIME, TEMPORADAS, "pistas");
    }

    @Test
    void aceitaTemporadaDaListaComConfiancaSuficiente() throws Exception {
        assertEquals(2, rodar("{\"temporada\":2,\"confianca\":0.9}"));
    }

    @Test
    void confiancaBaixaViraZero() throws Exception {
        assertEquals(0, rodar("{\"temporada\":2,\"confianca\":0.3}"));
    }

    @Test
    void numeroForaDaListaViraZero() throws Exception {
        assertEquals(0, rodar("{\"temporada\":7,\"confianca\":0.95}"));
    }

    @Test
    void respostaQueNaoEJsonViraZero() throws Exception {
        assertEquals(0, rodar("não sei dizer"));
    }
}
