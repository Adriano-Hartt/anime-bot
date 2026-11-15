package bot;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KitsuClientTest {
    private final ObjectMapper m = new ObjectMapper();

    @Test
    void montaLinhasComTituloESinopse() throws Exception {
        var e = m.readTree("{\"attributes\":{\"number\":4,\"canonicalTitle\":\"A White Sky Asterism\",\"synopsis\":\"Subaru   meets Shaula.\"}}");
        assertEquals(List.of("4: A White Sky Asterism - Subaru meets Shaula."), KitsuClient.montarLinhas(List.of(e)));
    }

    @Test
    void ignoraEpisodioSemNumero() throws Exception {
        var e = m.readTree("{\"attributes\":{\"canonicalTitle\":\"x\"}}");
        assertEquals(List.of(), KitsuClient.montarLinhas(List.of(e)));
    }

    @Test
    void prefereSerieComMesmoTotalDeEpisodios() throws Exception {
        var d = m.readTree("[{\"id\":\"1\",\"attributes\":{\"episodeCount\":25}},{\"id\":\"2\",\"attributes\":{\"episodeCount\":19}}]");
        assertEquals("2", KitsuClient.escolherId(d, 19));
        assertEquals("1", KitsuClient.escolherId(d, 7));
    }
}
