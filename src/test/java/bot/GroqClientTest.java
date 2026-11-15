package bot;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GroqClientTest {
    @Test
    void montaCorpoComSistemaEUsuario() throws Exception {
        var r = new ObjectMapper().readTree(GroqClient.montarCorpo("m1", "sis", "usr"));
        assertEquals("m1", r.path("model").asText());
        assertEquals("system", r.path("messages").path(0).path("role").asText());
        assertEquals("usr", r.path("messages").path(1).path("content").asText());
        assertEquals("json_object", r.path("response_format").path("type").asText());
    }

    @Test
    void extraiTextoDaResposta() throws Exception {
        assertEquals("{\"a\":1}", GroqClient.extrairTexto("{\"choices\":[{\"message\":{\"content\":\"{\\\"a\\\":1}\"}}]}"));
        assertEquals("", GroqClient.extrairTexto("{}"));
    }

    @Test
    void escolheOPrimeiroModeloPreferidoDisponivel() throws Exception {
        var r = new ObjectMapper().readTree("{\"data\":[{\"id\":\"openai/gpt-oss-20b\"},{\"id\":\"openai/gpt-oss-120b\"}]}");
        assertEquals("openai/gpt-oss-120b", GroqClient.escolherModelo(r, java.util.List.of("llama-3.3-70b-versatile", "openai/gpt-oss-120b", "openai/gpt-oss-20b")));
        assertEquals(null, GroqClient.escolherModelo(r, java.util.List.of("x")));
    }
}
