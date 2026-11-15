package bot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import bot.Modelos.Achado;
import bot.Modelos.Temporada;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.Test;

class ServidorTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Catálogo de mentira: um anime com duas temporadas. */
    private static final Catalogo CATALOGO = new Catalogo() {
        @Override
        public Achado buscar(String titulo) {
            return "Re:Zero".equals(titulo) ? new Achado(1, "Re:Zero") : null;
        }

        @Override
        public List<Temporada> temporadas(int id) {
            return List.of(new Temporada(1, "Re:Zero", 25, "FINISHED"), new Temporada(2, "Re:Zero 2", 13, "FINISHED"));
        }

        @Override
        public List<String> listarEpisodios(int id) {
            return List.of("1: Um", "2: Dois", "3: Tres");
        }

        @Override
        public List<String> plataformas(int id) {
            return List.of();
        }
    };

    private static Servidor subir(String respostaDoModelo) throws Exception {
        Llm llm = (sistema, usuario) -> respostaDoModelo;
        Servidor s = new Servidor(new ServicoAnime(llm, CATALOGO), CATALOGO, 0);
        s.iniciar();
        return s;
    }

    private static HttpResponse<String> chamar(Servidor s, String metodo, String caminho, String corpo) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + s.porta() + caminho));
        b = corpo == null ? b.GET() : b.method(metodo, HttpRequest.BodyPublishers.ofString(corpo));
        return HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void identificarDevolveSoCandidatosQueExistemNoCatalogo() throws Exception {
        Servidor s = subir("{\"candidatos\":[{\"titulo\":\"Re:Zero\",\"confianca\":0.9},{\"titulo\":\"Inventado\",\"confianca\":0.8}],\"pergunta\":\"\"}");
        try {
            HttpResponse<String> r = chamar(s, "POST", "/api/identificar", "{\"descricao\":\"cara que volta no tempo\"}");
            assertEquals(200, r.statusCode());
            JsonNode j = JSON.readTree(r.body());
            assertEquals(1, j.path("candidatos").size());
            assertEquals("Re:Zero", j.path("candidatos").get(0).path("titulo").asText());
        } finally {
            s.parar();
        }
    }

    @Test
    void temporadasTrazODeslocamentoDeCadaUma() throws Exception {
        Servidor s = subir("{}");
        try {
            JsonNode j = JSON.readTree(chamar(s, "GET", "/api/temporadas?id=1", null).body());
            assertEquals(2, j.size());
            assertEquals(0, j.get(0).path("deslocamento").asInt());
            assertEquals(25, j.get(1).path("deslocamento").asInt());
        } finally {
            s.parar();
        }
    }

    @Test
    void episodioForaDaListaViraPerguntaEDescricaoVaziaDa400() throws Exception {
        Servidor s = subir("{\"episodio\":99,\"confianca\":0.9,\"pergunta\":\"Qual cena?\"}");
        try {
            JsonNode j = JSON.readTree(chamar(s, "POST", "/api/episodio", "{\"id\":2,\"titulo\":\"x\",\"deslocamento\":0,\"pistas\":\"algo\"}").body());
            assertEquals(0, j.path("episodio").asInt());
            assertEquals("Qual cena?", j.path("pergunta").asText());
            assertEquals(400, chamar(s, "POST", "/api/identificar", "{\"descricao\":\"\"}").statusCode());
            assertTrue(chamar(s, "GET", "/api/dublagem?id=abc", null).statusCode() == 400);
        } finally {
            s.parar();
        }
    }

    @Test
    void limitaChamadasPorPessoa() throws Exception {
        Servidor s = new Servidor(new ServicoAnime((a, b) -> "{}", CATALOGO), CATALOGO, 0, 2);
        s.iniciar();
        try {
            assertEquals(200, chamar(s, "GET", "/api/temporadas?id=1", null).statusCode());
            assertEquals(200, chamar(s, "GET", "/api/temporadas?id=1", null).statusCode());
            assertEquals(429, chamar(s, "GET", "/api/temporadas?id=1", null).statusCode());
        } finally {
            s.parar();
        }
    }
}
