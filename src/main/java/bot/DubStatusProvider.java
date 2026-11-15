package bot;

public interface DubStatusProvider {
    /** Devolve "sim", "nao" ou "incerto". */
    String proximoEpisodioDublado(int id, int episodio);
}
