package bot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import bot.Modelos.Temporada;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Testa a montagem da lista de episódios e a escolha do título, sem chamar a internet. */
class AniListClientTest {

    @Test
    void listaTodosOsEpisodiosQuandoOTotalEConhecido() {
        List<String> lista = AniListClient.montarLista(3, null, List.of());

        assertEquals(3, lista.size());
        assertEquals(Set.of(1, 2, 3), ServicoAnime.numeros(lista));
    }

    @Test
    void usaOTituloDoEpisodioQuandoOAniListInforma() {
        List<String> lista = AniListClient.montarLista(3, null, List.of("Episode 2 - O reencontro"));

        assertEquals("2: O reencontro", lista.get(1));
        assertEquals("1: ", lista.get(0));
    }

    @Test
    void emExibicaoUsaOProximoEpisodioMenosUm() {
        List<String> lista = AniListClient.montarLista(null, 6, List.of());

        assertEquals(5, lista.size());
    }

    @Test
    void semTotalNemProximoUsaOMaiorNumeroDosTitulos() {
        List<String> lista = AniListClient.montarLista(null, null, List.of("Episode 1 - A", "Episode 4 - B"));

        assertEquals(4, lista.size());
        assertTrue(lista.contains("4: B"));
    }

    @Test
    void preferTituloEmInglesEUsaRomajiSeFaltar() throws Exception {
        ObjectMapper json = new ObjectMapper();

        assertEquals("Re:ZERO", AniListClient.tituloDe(json.readTree("{\"english\":\"Re:ZERO\",\"romaji\":\"Re:Zero kara\"}"), "x"));
        assertEquals("Re:Zero kara", AniListClient.tituloDe(json.readTree("{\"english\":null,\"romaji\":\"Re:Zero kara\"}"), "x"));
        assertEquals("x", AniListClient.tituloDe(json.readTree("{}"), "x"));
    }

    @Test
    void ordenaTemporadasPorDataEIgnoraAsQueAindaNaoEstrearam() throws Exception {
        ObjectMapper json = new ObjectMapper();
        List<JsonNode> medias = List.of(
                json.readTree("{\"id\":3,\"title\":{\"english\":\"S3\"},\"status\":\"FINISHED\",\"episodes\":16,"
                        + "\"startDate\":{\"year\":2024,\"month\":10,\"day\":2}}"),
                json.readTree("{\"id\":1,\"title\":{\"english\":\"S1\"},\"status\":\"FINISHED\",\"episodes\":25,"
                        + "\"startDate\":{\"year\":2016,\"month\":4,\"day\":4}}"),
                json.readTree("{\"id\":9,\"title\":{\"english\":\"S9\"},\"status\":\"NOT_YET_RELEASED\","
                        + "\"episodes\":null,\"startDate\":{\"year\":2099}}"),
                json.readTree("{\"id\":2,\"title\":{\"english\":\"S2\"},\"status\":\"FINISHED\",\"episodes\":25,"
                        + "\"startDate\":{\"year\":2020,\"month\":7,\"day\":8}}"));

        List<Temporada> temporadas = AniListClient.montarTemporadas(medias);

        assertEquals(3, temporadas.size());
        assertEquals(1, temporadas.get(0).id());
        assertEquals(3, temporadas.get(2).id());
        assertEquals("S3", temporadas.get(2).titulo());
        assertEquals(Integer.valueOf(16), temporadas.get(2).episodios());
    }
}
