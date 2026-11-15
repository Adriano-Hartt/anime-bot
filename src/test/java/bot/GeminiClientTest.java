package bot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/** Testa a montagem do pedido e a leitura da resposta do Gemini, sem chamar a internet. */
class GeminiClientTest {

    @Test
    void montaCorpoComPromptDeSistemaEMensagemDaPessoa() throws Exception {
        JsonNode corpo = new ObjectMapper().readTree(GeminiClient.montarCorpo("seja breve", "oi"));

        assertEquals("seja breve", corpo.path("systemInstruction").path("parts").path(0).path("text").asText());
        assertEquals("user", corpo.path("contents").path(0).path("role").asText());
        assertEquals("oi", corpo.path("contents").path(0).path("parts").path(0).path("text").asText());
        assertEquals("application/json", corpo.path("generationConfig").path("responseMimeType").asText());
    }

    @Test
    void leOTextoDaResposta() throws Exception {
        String resposta = "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"{\\\"ok\\\":true}\"}]}}]}";

        assertEquals("{\"ok\":true}", GeminiClient.extrairTexto(resposta));
    }

    @Test
    void ignoraPartesDeRaciocinio() throws Exception {
        String resposta = "{\"candidates\":[{\"content\":{\"parts\":["
                + "{\"thought\":true,\"text\":\"pensando...\"},"
                + "{\"text\":\"resposta final\"}]}}]}";

        assertEquals("resposta final", GeminiClient.extrairTexto(resposta));
    }

    @Test
    void devolveVazioQuandoNaoHaCandidatos() throws Exception {
        assertEquals("", GeminiClient.extrairTexto("{\"promptFeedback\":{\"blockReason\":\"OTHER\"}}"));
    }

    @Test
    void recusaChaveVazia() {
        assertThrows(IllegalStateException.class, () -> new GeminiClient(" ", "gemini-3.8-flash"));
    }
}
