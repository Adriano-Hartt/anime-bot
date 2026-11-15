package bot;

/** Qualquer modelo de linguagem que receba um prompt de sistema e uma mensagem e devolva texto. */
public interface Llm {
    String perguntar(String sistema, String usuario) throws Exception;
}
