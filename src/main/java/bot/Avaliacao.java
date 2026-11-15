package bot;

import bot.Modelos.Identificacao;
import java.text.Normalizer;
import java.util.List;

/**
 * Mede a qualidade da etapa 1 com casos conhecidos. Roda de verdade contra a API:
 * mvn -q compile exec:java -Dexec.mainClass=bot.Avaliacao
 */
public class Avaliacao {

    /** Uma descrição e os nomes aceitos como certos. Lista vazia = o bot deve se recusar a chutar. */
    record Caso(String descricao, List<String> aceitos) {}

    private static Caso caso(String descricao, String... aceitos) {
        return new Caso(descricao, List.of(aceitos));
    }

    public static void main(String[] args) throws Exception {
        ServicoAnime servico = new ServicoAnime(LlmFactory.criar(), CatalogoFactory.criar());

        List<Caso> casos = List.of(
                caso("o anime do pirata que estica", "one piece"),
                caso("ninja loiro com uma raposa de nove caudas dentro dele", "naruto"),
                caso("caderno que mata quem tem o nome escrito nele", "death note"),
                caso("humanidade vive atrás de muros por causa de gigantes", "shingeki no kyojin", "attack on titan"),
                caso("dois irmãos alquimistas que perdem o corpo tentando ressuscitar a mãe", "fullmetal alchemist"),
                caso("caçador de demônios cuja irmã virou demônio", "kimetsu no yaiba", "demon slayer"),
                caso("garoto sem poderes numa escola de super-heróis", "boku no hero academia", "my hero academia"),
                caso("guerreiros procuram esferas que realizam desejos", "dragon ball"),
                caso("time de vôlei de escola com um jogador baixinho que pula muito", "haikyuu"),
                caso("anime de gatos astronautas feiticeiros do planeta Zorg") // não existe: deve recusar
        );

        int acertos = 0;
        for (Caso c : casos) {
            Identificacao id = servico.identificar(c.descricao());
            String primeiro = id.animes().isEmpty() ? "" : id.animes().get(0).titulo();
            boolean ok = c.aceitos().isEmpty()
                    ? id.animes().isEmpty()
                    : c.aceitos().stream().anyMatch(a -> normalizar(primeiro).contains(normalizar(a)));
            if (ok) acertos++;
            System.out.printf("%s \"%s\" -> %s%n", ok ? "OK  " : "ERRO", c.descricao(),
                    primeiro.isEmpty() ? "(sem candidato)" : primeiro);
        }
        System.out.printf("%nAcertos: %d/%d (%.0f%%)%n", acertos, casos.size(), 100.0 * acertos / casos.size());
    }

    private static String normalizar(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase()
                .replaceAll("[^a-z0-9]+", " ")
                .trim();
    }
}
