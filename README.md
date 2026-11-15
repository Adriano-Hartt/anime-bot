# Bot de Anime Dublado (Java + IA generativa)

Bot de terminal que usa um modelo de linguagem para três tarefas:

1. **Identificar o anime** a partir de uma descrição solta, como "um anime de um cara que volta no tempo, o nome dele é Subaru".
2. **Descobrir em qual episódio a pessoa parou**, mesmo sem lembrar o número, comparando as pistas dela com título e sinopse reais dos episódios.
3. **Dizer se o próximo episódio já está dublado em português**, lendo a página da Crunchyroll.

## Por que existe

Quem assiste dublado perde o fio: não lembra o nome do anime nem o episódio, e ainda precisa abrir o site para ver se a dublagem saiu. Regex e seletores não resolvem a primeira parte, porque o problema é entender linguagem natural. Por isso usei um LLM, e o foco do projeto é usá-lo **com confiabilidade**: o modelo sugere, o código confere.

## Como funciona

~~~mermaid
flowchart LR
  A[Pessoa descreve o anime] --> B[LLM propõe candidatos em JSON]
  B --> C{Existe no catálogo AniList?}
  C -- não --> D[Descartado]
  C -- sim --> E[Pessoa confirma]
  E --> F[AniList lista as temporadas]
  F --> G[TVmaze/Kitsu trazem título e sinopse dos episódios]
  G --> H[LLM cruza as pistas com os episódios]
  H --> I{Episódio existe na lista e confiança >= 0,6?}
  I -- não --> J[Faz outra pergunta]
  I -- sim --> K[Navegador sem janela lê a Crunchyroll]
  K --> L[Resposta: dublado, só legendado ou incerto]
~~~

## Decisões de design

- **O modelo propõe, o código decide.** O modelo responde só em JSON e o código valida cada campo antes de usar.
- **Defesa contra alucinação em camadas.** Título que não existe no catálogo é descartado. Episódio fora da lista é rejeitado. Confiança abaixo de 0,6 vira pergunta, não chute. Resposta que não é JSON vira pergunta, não erro.
- **Dados reais no lugar de memória do modelo.** O AniList só informa o número dos episódios. O bot busca título e sinopse no TVmaze (e na Kitsu como reserva) e só aceita a lista se a quantidade de episódios bater com a da temporada. Foi a correção mais importante: sem isso, o modelo "chutava" o episódio.
- **Nunca afirma dublagem sem fonte.** A checagem fica atrás da interface `DubStatusProvider`. Se a página não mostrar o episódio, o bot diz "não dá para afirmar".
- **Sem API não oficial e sem credencial.** A Crunchyroll não tem API pública para isso. O bot abre a página pública no Chrome/Edge do próprio computador, sem janela, rola a lista até os episódios carregarem e lê o rótulo `Dub | Leg`. Se o navegador falhar, tenta ler um arquivo salvo pela pessoa e, por último, pergunta.
- **Testável sem internet.** O modelo fica atrás da interface `Llm` e o catálogo atrás da interface `Catalogo`, então os testes usam dublês e não gastam API. São 45 testes.
- **Troca de modelo sem mexer na regra de negócio.** Groq, Gemini e Claude implementam a mesma interface. Se o modelo padrão do Groq não estiver liberado na conta, o bot consulta a lista de modelos da chave e escolhe outro.
- **Falhas de rede tratadas.** Erros 429 e 5xx tentam de novo com espera crescente. Os demais sobem com mensagem clara.
- **Segredo fora do código.** A chave da API vem de variável de ambiente.

## Como rodar

Requisitos: Java 17+, Maven 3.8+, Chrome ou Edge instalado (para a dublagem) e uma chave de API gratuita.

~~~bash
# Groq (gratuito, console.groq.com, sem cartão)
# Windows CMD (sem aspas):  set GROQ_API_KEY=sua-chave
# PowerShell:               $env:GROQ_API_KEY="sua-chave"
# Linux/Mac:                export GROQ_API_KEY="sua-chave"

mvn -q compile exec:java
~~~

Alternativas: `GEMINI_API_KEY` (aistudio.google.com/apikey) ou `ANTHROPIC_API_KEY`. Se houver mais de uma, a `LlmFactory` usa Groq, depois Gemini, depois Claude; `LLM_PROVIDER` força a escolha.

Variáveis opcionais: `GROQ_MODEL`, `GEMINI_MODEL`, `ANIME_BOT_MODEL`, `CATALOGO` (`anilist` ou `jikan`) e `CHROME_PATH` (caminho do navegador, se não estiver no lugar padrão).

Testes: `mvn test`.

## API para o front-end

O mesmo fluxo está disponível como API HTTP local, usada pela interface web do repositório `anime-bot-front`. Usa só o servidor embutido do JDK, escuta em `127.0.0.1:8080` (variável `PORT`) e cada chamada é independente.

~~~bash
mvn -q compile exec:java -Dexec.mainClass=bot.Servidor
~~~

| Rota | O que faz |
|---|---|
| `POST /api/identificar` | descrição livre → candidatos confirmados no catálogo |
| `GET /api/temporadas?id=` | temporadas da série |
| `POST /api/temporada` | sugere a temporada pelas pistas |
| `POST /api/episodio` | sugere o episódio pelas pistas |
| `GET /api/dublagem?id=&episodio=` | dublagem do próximo episódio e do último da temporada |

Variáveis opcionais da API: `PORT`, `HOST` (use `0.0.0.0` em servidor), `LIMITE_HORA` (chamadas por pessoa por hora, padrão 40). Há um `Dockerfile` com Java e Chromium para publicar a API.

## Exemplo de conversa (execução real)

~~~text
Qual anime? Pode descrever do seu jeito: um anime de um cara que volta no tempo o nome dele é subaru
Você quis dizer "Re:ZERO -Starting Life in Another World-"? (s/n) s

Esse anime tem 5 temporadas:
  1) Re:ZERO -Starting Life in Another World- (25 episódios)
  2) Re:ZERO -Starting Life in Another World- Season 2 (13 episódios)
  3) Re:ZERO -Starting Life in Another World- Season 2 Part 2 (12 episódios)
  4) Re:ZERO -Starting Life in Another World- Season 3 (16 episódios)
  5) Re:ZERO -Starting Life in Another World- Season 4 (19 episódios)
Qual temporada você está assistindo? (número, ou Enter se não souber)
Sem problema. Lembra de alguma saga ou arco, de um personagem novo ou de uma cena marcante? ele entra em um castelo no deserto e conhece uma personagem chamada shaula
Pelas suas pistas, parece ser "... Season 2 Part 2". É essa? (s/n) n
Vou usar a temporada mais recente: Re:ZERO -Starting Life in Another World- Season 4.
(Achei títulos e sinopses de 19 episódios.)
Qual foi o último episódio que você viu dublado? (número, ou 'não lembro') não lembro
Foi o episódio 4 (A White Sky Asterism - Subaru and company meet Shaula, keeper of the Pleiades Watchtower...)? (s/n) s

O episódio 5 de Re:ZERO -Starting Life in Another World- Season 4 já aparece como dublado na Crunchyroll.
~~~

Note que o palpite de temporada (S2 Part 2) estava errado e a pessoa recusou: o bot usou a mais recente. O episódio foi achado pela cena descrita (castelo no deserto + Shaula).

## Avaliação

A classe `Avaliacao` testa a identificação com 10 descrições conhecidas, incluindo um anime que não existe (o certo é recusar). Para rodar: `mvn -q compile exec:java -Dexec.mainClass=bot.Avaliacao`.

Resultado da última execução: _a ser adicionado após a execução com o modelo atual (Groq)_.

## Limitações conhecidas

- **Temporada:** a sugestão do modelo parte só dos títulos das temporadas, sem sinopse, e já errou no teste acima. A pessoa sempre confirma, e a escolha manual pelo número é a mais confiável.
- **Dublagem do último episódio:** a página da Crunchyroll carrega os episódios aos poucos. Nos testes o bot leu 13 de 19 episódios da temporada 4, então para os episódios finais ele responde "não dá para afirmar" em vez de chutar. O episódio seguinte ao que a pessoa viu foi lido corretamente.
- **Dublagem é a do catálogo em português da Crunchyroll**, não a data em que saiu. Depende do layout da página, que pode mudar.
- A Crunchyroll pode bloquear programas automáticos. Nesse caso o bot cai para o arquivo `crunchyroll.html` (página salva com Ctrl+S) ou pergunta à pessoa.
- A busca usa o primeiro resultado do catálogo, que pode casar com um anime parecido. Por isso a pessoa sempre confirma o título.
- Os planos gratuitos (Groq, Gemini) têm limite de requisições e podem usar o conteúdo enviado: não envie dados pessoais.
- Funciona só no terminal.

## Próximos passos

- Hospedar a API para a interface web funcionar sem rodar nada local.
- Rolagem mais robusta na Crunchyroll para ler todos os episódios.
- Cache da lista de episódios.
- Aviso automático por Telegram quando o episódio sair dublado.
- Mais casos na avaliação, incluindo descrições em inglês.

## Tecnologias

Java 17, Maven, Jackson, JUnit 5, Groq / Gemini / Claude (LLM), AniList, TVmaze e Kitsu (dados de episódios), Chrome DevTools Protocol (leitura da Crunchyroll).
