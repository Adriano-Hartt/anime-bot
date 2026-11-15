package bot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DubStatusCrunchyrollTest {
    private static final String PAGINA = """
            4ª Temporada
            MAIS ANTIGO
            RE:ZERO -STARTING LIFE IN ANOTHER WORLD- SHOR...
            E1 - O Motivo pelo qual Estou Levando Você Comigo
            Dub | Leg
            RE:ZERO -STARTING LIFE IN ANOTHER WORLD- SHOR...
            E2 - Superando a Hora da Areia
            Dub | Leg
            E3 - A Vigia da Torre
            Leg
            E4 - Asterismo no Céu Branco
            E5 - Brandidor de Bastões
            Dub | Leg
            3ª Temporada
            E1 - Malícia Teatral
            Leg
            """;

    @Test
    void lerDubELeg() {
        var d = new DubStatusCrunchyroll(PAGINA);
        assertEquals("sim", d.proximoEpisodioDublado(0, 1));
        assertEquals("sim", d.proximoEpisodioDublado(0, 2));
        assertEquals("nao", d.proximoEpisodioDublado(0, 3));
        assertEquals("incerto", d.proximoEpisodioDublado(0, 4));
        assertEquals("sim", d.proximoEpisodioDublado(0, 5));
    }

    @Test
    void episodioForaDaListaEIncerto() {
        assertEquals("incerto", new DubStatusCrunchyroll(PAGINA).proximoEpisodioDublado(0, 19));
    }

    @Test
    void paraQuandoANumeracaoRecomeca() {
        assertEquals(5, new DubStatusCrunchyroll(PAGINA).episodiosLidos());
    }

    @Test
    void textoVazioNaoQuebra() {
        assertEquals(0, new DubStatusCrunchyroll("").episodiosLidos());
        assertEquals(0, new DubStatusCrunchyroll(null).episodiosLidos());
    }

    @Test
    void arquivoSemRotulosNaoServe() {
        var d = new DubStatusCrunchyroll("E1 - A\n01/01/2026\nSinopse\nE2 - B\n02/01/2026\nSinopse\n");
        assertEquals(2, d.episodiosLidos());
        assertEquals(false, d.temRotulos());
    }

    private static final String HTML = "<div data-t=\"episode-card \"><h4>E1 - A</h4><div data-t=\"meta-tags\"><div><span>Dub | Leg</span></div></div></div>"
            + "<div data-t=\"episode-card \"><h4>E2 - B</h4><div data-t=\"meta-tags\"><div><span>Legendado</span></div></div></div>"
            + "<div data-t=\"episode-card \"><h4>E3 - C</h4><div data-t=\"meta-tags\"><div><span>Dublado</span></div></div></div>"
            + "<div data-t=\"carousel-card-wrapper\"><div data-t=\"series-card \">E1 - Outro<div data-t=\"meta-tags\"><div><span>Legendado</span></div></div></div></div>";

    @Test
    void lerHtmlDaPagina() {
        var d = DubStatusCrunchyroll.doHtml(HTML);
        assertEquals("sim", d.proximoEpisodioDublado(0, 1));
        assertEquals("nao", d.proximoEpisodioDublado(0, 2));
        assertEquals("sim", d.proximoEpisodioDublado(0, 3));
        assertEquals(3, d.episodiosLidos());
    }

    @Test
    void acharUrlDaSerieNasPlataformas() {
        var u = CrunchyrollPagina.urlPtBr(java.util.List.of("iQ - https://www.iq.com/x", "Crunchyroll - https://www.crunchyroll.com/series/GRGG9798R"));
        assertEquals("https://www.crunchyroll.com/pt-br/series/GRGG9798R", u.get());
    }

    @Test
    void listaDeNavegadoresTemChromeEEdge() {
        var l = CrunchyrollPagina.navegadoresPossiveis();
        assertEquals(true, l.stream().anyMatch(x -> x.contains("chrome.exe") || x.contains("google-chrome")));
        assertEquals(true, l.stream().anyMatch(x -> x.contains("msedge.exe") || x.contains("chromium")));
    }
}
