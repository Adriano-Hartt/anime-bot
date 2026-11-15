package bot;

/** Versão inicial: ainda não há fonte confiável de dublagem. */
public class DubStatusProviderIncerto implements DubStatusProvider {
    @Override
    public String proximoEpisodioDublado(int id, int episodio) {
        return "incerto";
    }
}
