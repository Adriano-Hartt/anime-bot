package bot;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TvmazeClientTest {
    private final ObjectMapper m = new ObjectMapper();

    private String ep(int temp, int n, String nome) {
        return "{\"season\":" + temp + ",\"number\":" + n + ",\"name\":\"" + nome + "\",\"summary\":\"<p>Sinopse   de " + nome + "</p>\"}";
    }

    @Test
    void recortaSoOsEpisodiosDaTemporadaEReNumera() throws Exception {
        var eps = m.readTree("[" + ep(2, 1, "C") + "," + ep(1, 2, "B") + "," + ep(1, 1, "A") + "," + ep(2, 2, "D") + "]");
        List<String> r = TvmazeClient.recortar(eps, 2, 2);
        assertEquals(List.of("1: C - Sinopse de C", "2: D - Sinopse de D"), r);
    }

    @Test
    void serieCurtaDemaisViraVazio() throws Exception {
        var eps = m.readTree("[" + ep(1, 1, "A") + "]");
        assertEquals(List.of(), TvmazeClient.recortar(eps, 0, 3));
        assertEquals(List.of(), TvmazeClient.recortar(eps, -1, 1));
    }

    @Test
    void tiraONumeroDaTemporadaDoNome() {
        assertEquals("Re:ZERO -Starting Life in Another World-", Main.nomeDaSerie("Re:ZERO -Starting Life in Another World- Season 4"));
        assertEquals("Foo", Main.nomeDaSerie("Foo Season 2 Part 2"));
    }
}
