package bot;

import java.util.List;

/** Tipos de dados simples que circulam entre as classes do bot. */
public final class Modelos {
    private Modelos() {}

    /** Resultado de uma busca no catálogo: id do catálogo e título oficial. */
    public record Achado(int id, String titulo) {}

    /** Anime já confirmado no catálogo. A confiança é a que o modelo deu ao proporí-lo. */
    public record Anime(int id, String titulo, double confianca) {}

    /** Uma temporada de um anime no catálogo; episodios é null quando ainda não se sabe o total. */
    public record Temporada(int id, String titulo, Integer episodios, String status) {}

    /** Resposta da etapa 1: animes válidos (pode ser vazio) e a próxima pergunta ao usuário. */
    public record Identificacao(List<Anime> animes, String pergunta) {}

    /** Resposta da etapa 2: episódio proposto (0 = ainda não sabe) e a próxima pergunta. */
    public record RespostaEpisodio(int episodio, double confianca, String pergunta) {}
}
