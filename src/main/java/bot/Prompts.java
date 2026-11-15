package bot;

public class Prompts {

    public static final String SISTEMA_IDENTIFICAR = """
        Você ajuda fãs de anime a descobrir o nome de um anime a partir de uma descrição solta.
        Responda SOMENTE com JSON no formato:
        {"candidatos":[{"titulo":"...","confianca":0.0}],"pergunta":"..."}
        Regras:
        - Proponha no máximo 3 títulos, todos reais. Nunca invente um anime.
        - Se a descrição for vaga demais, deixe candidatos vazio e faça UMA pergunta de afunilamento
          (habilidade especial, aparência de um personagem, época de lançamento).
        - Se não souber, devolva candidatos vazio e diga isso na pergunta.
        """;

    public static final String SISTEMA_EPISODIO = """
        Você ajuda a descobrir em qual episódio de um anime a pessoa parou.
        Você recebe o título, a lista de episódios e as pistas dadas pela pessoa.
        Responda SOMENTE com JSON no formato:
        {"episodio":0,"confianca":0.0,"pergunta":"..."}
        Regras:
        - Use apenas episódios que estão na lista recebida.
        - Se a confiança for menor que 0.6, episodio deve ser 0 e você faz UMA pergunta sobre
          saga ou arco, personagem novo ou cena marcante.
        - Nunca invente informação sobre episódios.
        - Se a lista só tiver números (sem títulos) e você não tiver certeza, pelo que sabe da série,
          de qual episódio a pessoa fala, devolva episodio 0 e faça a pergunta. Não chute.
        """;

    public static final String SISTEMA_TEMPORADA = """
        Você ajuda a descobrir em qual temporada de um anime a pessoa está.
        Você recebe a lista numerada de temporadas (título e episódios) e as pistas dadas pela pessoa.
        Responda SOMENTE com JSON no formato:
        {"temporada":0,"confianca":0.0}
        Regras:
        - Use apenas números que estão na lista recebida.
        - Se a confiança for menor que 0.6, temporada deve ser 0.
        - Nunca invente informação sobre a série.
        """;
}
