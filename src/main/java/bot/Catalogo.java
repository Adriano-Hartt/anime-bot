package bot;

import bot.Modelos.Achado;
import bot.Modelos.Temporada;
import java.util.List;

/** Fonte de dados sobre animes (títulos, episódios e onde assistir). Hoje: AniList ou Jikan. */
public interface Catalogo {
    /** Busca pelo título; devolve o id e o título oficial, ou null se não achar. */
    Achado buscar(String titulo) throws Exception;

    /** Temporadas da mesma série, da mais antiga à mais recente. Vazio se o catálogo não souber. */
    default List<Temporada> temporadas(int id) throws Exception {
        return List.of();
    }

    /** Lista os episódios no formato "N: título" (o título pode vir vazio). */
    List<String> listarEpisodios(int id) throws Exception;

    /** Lista onde o anime está disponível, no formato "plataforma - link". */
    List<String> plataformas(int id) throws Exception;
}
