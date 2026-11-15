# Bot de Anime Dublado (Java + IA generativa)

Bot de terminal que usa um modelo de linguagem (Gemini, no plano gratuito, ou Claude) para duas tarefas:

1. **Identificar o anime** a partir de uma descrição solta, como "o anime do pirata que estica".
2. **Descobrir em qual episódio a pessoa parou**, mesmo sem lembrar o número, e avisar se o próximo já saiu dublado em português.

## Por que existe

Quem assiste dublado perde o fio: não lembra o nome do anime nem o episódio. Regex e seletores não resolvem isso, porque o problema é entender linguagem natural. Foi por isso que usei um LLM, e o foco do projeto é usar o modelo **com confiabilidade**.

## Como funciona

~~~mermaid
flowchart LR
  A[Pessoa descreve o anime] --> B[LLM propõe até 3 candidatos em JSON]
  B --> C{Existe no catálogo (AniList)?}
  C -- não --> D[Descartado]
  C -- sim --> E[Pessoa confirma]
  E --> F[LLM cruza pistas com a lista de episódios]
  F --> G{Episódio está na lista e confiança >= 0.6?}
  G -- não --> H[Faz outra pergunta]
  G -- sim --> I[Checa dublagem: sim, nao ou incerto]
~~~

## Decisões de design

- **O modelo propõe, o código decide.** O modelo responde só em JSON e o código valida cada campo antes de usar.
- **Defesa contra alucinação em camadas.** Título que não existe no catálogo é descartado. Episódio fora da lista é rejeitado. Confiança abaixo de 0,6 vira pergunta, não chute. Resposta que não é JSON vira pergunta, não erro.
- **Nunca afirma dublagem sem fonte.** A checagem fica atrás da interface `DubStatusProvider`. A implementação atual responde sempre "incerto", e o bot diz isso claramente.
- **Testável sem internet.** O modelo fica atrás da interface `Llm` e o catálogo fica atrás da interface `Catalogo` (AniList por padrão, Jikan/MyAnimeList como opção pela variável `CATALOGO`), então os testes usam dublês e não gastam API.
- **Falhas de rede tratadas.** Erros 429 e 5xx tentam de novo com espera crescente. Os demais sobem com mensagem clara.
- **Temporadas.** Depois de confirmar o anime, o bot segue as ligações de prequel e sequência no AniList, lista as temporadas e pergunta em qual a pessoa está. Se ela não souber, o modelo sugere a temporada a partir das pistas e a pessoa confirma. Sem isso, a lista de episódios seria sempre a da primeira temporada.
- **O modelo sempre vê a pergunta junto com a resposta.** Um "sim" sozinho não diz nada; "Pergunta: é a segunda temporada? Resposta: sim" diz.
- **Troca de modelo sem mexer na regra de negócio.** Gemini e Claude implementam a mesma interface `Llm`, e a `LlmFactory` escolhe qual usar por variável de ambiente.
- **Segredo fora do código.** A chave da API vem de variável de ambiente.

## Avaliação

A classe `Avaliacao` testa a identificação com 10 descrições conhecidas, incluindo um anime que não existe (o certo é recusar).

- Resultado: **[PREENCHER] de 10** (modelo `claude-haiku-5-5`, data da execução: **[PREENCHER]**)
- Casos que erraram e o que mudei nos prompts por causa disso: **[PREENCHER]**

## Como rodar

Requisitos: Java 17+, Maven 3.8+ e uma chave de API. O caminho gratuito usa o Gemini (chave em aistudio.google.com/apikey, sem cartão). Também funciona com a API do Claude.

~~~bash
# Linux/Mac: Gemini (gratuito)
export GEMINI_API_KEY="sua-chave"
# Windows PowerShell: $env:GEMINI_API_KEY="sua-chave"

# Opcional: usar o Claude no lugar
# export ANTHROPIC_API_KEY="sua-chave" e LLM_PROVIDER=claude

mvn -q compile exec:java
~~~

Variáveis opcionais: `GEMINI_MODEL` (padrão `gemini-3.8-flash`), `ANIME_BOT_MODEL` (modelo do Claude) e `LLM_PROVIDER` (`gemini` ou `claude`).

Testes: `mvn test`. Avaliação: `mvn -q compile exec:java -Dexec.mainClass=bot.Avaliacao`.

## Exemplo de conversa

**[PREENCHER com uma conversa real copiada do terminal]**

## Limitações conhecidas

- Ainda não há fonte pública confiável de dublagem integrada, então a resposta sobre dublagem é "incerto".
- O AniList tem limite de requisições por minuto e nem sempre informa o título de cada episódio: quando falta, a lista traz só o número. A Jikan (não oficial) pode ficar fora do ar ou inacessível em algumas redes, por isso não é o padrão.
- A busca usa o primeiro resultado do catálogo, que pode casar com um anime parecido. Por isso a pessoa sempre confirma o título.
- O plano gratuito do Gemini tem limite de requisições por minuto e por dia, então o cliente espera entre as chamadas e a avaliação leva alguns minutos. O Google pode usar o conteúdo enviado no plano gratuito para melhorar seus produtos: não envie dados pessoais.
- Animes muito longos enviam uma lista grande de episódios ao modelo, o que aumenta custo e tempo.
- Funciona só no terminal.

## Próximos passos

- Implementar um `DubStatusProvider` com fonte real de dublagem.
- Cache da lista de episódios.
- API REST e aviso automático por Telegram.
- Mais casos na avaliação, incluindo descrições em inglês.

## Tecnologias

Java 17, Maven, Jackson, JUnit 5, API do Gemini (Google) e do Claude (Anthropic) e API do AniList (com Jikan/MyAnimeList como alternativa).


## IA gratuita: Groq
Defina `GROQ_API_KEY` (console.groq.com, sem cartão). Modelo padrão `llama-3.3-70b-versatile` (troque com `GROQ_MODEL`). Se houver `GROQ_API_KEY`, ela tem prioridade sobre as outras.

## Títulos e sinopses dos episódios (Kitsu)
O AniList só devolve números de episódio. Quando não há títulos, o bot busca título + sinopse na Kitsu (API gratuita, sem chave) e o modelo compara com suas pistas (ex.: "castelo no deserto", "Shaula" → Re:Zero S4, ep. 3-4). Se a Kitsu falhar, o bot segue só com os números e pergunta mais.

## Dublagem (Crunchyroll)
Cada cartão de episódio da página em português traz um rótulo (`Dub | Leg`, `Dublado` ou `Legendado`). O bot tenta, em ordem: (1) baixar a página pública da série (o link vem do AniList); (2) abrir a página no seu Chrome/Edge sem janela (`--headless --dump-dom`) e ler o resultado depois que o JavaScript monta a página; (3) ler `crunchyroll.html`, salvo pelo navegador com Ctrl+S ("Página da Web, completa"); (4) ler `crunchyroll.txt`; (5) perguntar à pessoa. Não usa API não oficial nem credenciais. Limitação: a Crunchyroll pode bloquear o download automático (passo 1) e o rótulo mostra a dublagem do catálogo em português, não a data de estreia.
