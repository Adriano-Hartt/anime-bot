package bot;

/** Escolhe o catálogo de animes: AniList (padrão) ou Jikan, pela variável CATALOGO. */
public final class CatalogoFactory {
    private CatalogoFactory() {
    }

    public static Catalogo criar() {
        String escolhido = System.getenv().getOrDefault("CATALOGO", "anilist").trim().toLowerCase();
        return switch (escolhido) {
            case "anilist" -> new AniListClient();
            case "jikan" -> new JikanClient();
            default -> throw new IllegalStateException("CATALOGO inválido: use anilist ou jikan.");
        };
    }
}
