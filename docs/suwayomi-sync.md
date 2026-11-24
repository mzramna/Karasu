# Sincronização com o servidor Suwayomi

O Karasu pode sincronizar com um [Suwayomi-Server](https://github.com/Suwayomi/Suwayomi-Server)
seu: categorias, progresso de leitura, fontes mescladas, downloads e backups. A sincronização é
**100% iniciada pelo app**: o servidor guarda fatos e responde perguntas, nunca empurra nada e não
sabe que o aparelho existe.

Configuração em **Configurações → Servidor Suwayomi**. Nada roda enquanto o interruptor
*Sincronizar com o servidor* estiver desligado.

## Configuração

| Campo | Para quê |
| --- | --- |
| Endereço do servidor | Tentado primeiro. Normalmente o IP da rede local (`http://192.168.1.10:4567`). Sem esquema, assume `http`. Um subcaminho (`https://exemplo.com/suwayomi`) é respeitado. |
| Endereço alternativo | Usado quando o principal não responde (Tailscale, VPN, domínio público). Opcional. |
| Usuário / senha | Basic Auth (`Authorization: Basic ...`). Deixe em branco se o servidor não tem autenticação. A senha fica em preferência privada, fora dos backups. |
| Testar conexão | Mostra latência, endereço usado (principal/alternativo), versão, tipo de banco (H2/PostgreSQL) e armazenamento (local/NFS, espaço livre). |
| O que sincronizar | Categorias, progresso, fontes mescladas, exclusões feitas no servidor. |
| Preferir baixar do servidor | Ver "Downloads". |
| Backups | Enviar os automáticos ao servidor; marcar por padrão o envio dos manuais. |
| Sincronizar automaticamente | Manual, 6 h, 12 h, diário, semanal. |

### Dois endereços

O app tenta o endereço principal com timeout de conexão de 3 s. Se não conectar (timeout, recusado,
host desconhecido), tenta o alternativo (10 s). Lembra qual respondeu por último e continua nele por
2 minutos antes de testar o principal de novo, para voltar sozinho à rede local sem pagar o timeout em
toda requisição. Um erro HTTP (401, 404, 500) é resposta de um servidor de verdade e **não** troca de
endereço. O estado online/offline é só uma crença alimentada pelas próprias requisições e é zerado a
cada mudança de rede.

## Quando sincroniza

- Ao abrir o app.
- Ao fechar o leitor (com 10 s de atraso para o progresso da última página chegar ao banco).
- Periodicamente (WorkManager), conforme o intervalo escolhido.
- Botão *Sincronizar agora*, e ao sair da tela de configurações.

Tudo, exceto o botão, é silencioso quando a sincronização está desligada ou o intervalo é *Manual*.

## O que cada parte faz

Obras são sempre casadas por **(source, url)**, nunca por id (os ids são diferentes de cada lado).

- **Categorias.** Os nomes são unidos nos dois sentidos (cria no servidor as que faltam e vice-versa;
  nomes comparados sem diferenciar maiúsculas/espaços). Os vínculos mangá↔categoria usam merge de
  três vias contra o estado do último sync, então uma remoção de um lado se propaga em vez de ser
  desfeita. A categoria *Default* do servidor e as categorias por regra do Karasu ficam de fora.
  Apagar uma categoria nunca se propaga.
- **Progresso.** Por capítulo (casado pela url do capítulo): lido em qualquer lado vira lido nos dois;
  vale a maior página lida. Só avança — marcar como *não lido* num aparelho não se espalha.
- **Fontes mescladas.** Tabela local `merged_manga` ↔ `GET/PUT /merged`. Merge de três vias, com a
  ordem (prioridade) e o flag de reserva. O servidor identifica a obra por `{sourceId, url}`; se ele
  não tem a obra, o `PUT` devolve 404 e o app pula aquela obra.
- **Exclusões.** `GET /deletions?afterId=` e apaga localmente o capítulo baixado. O primeiro sync só
  descobre o fim do histórico, sem apagar nada.
- **Política (`GET /policy`).** Guardada a cada sync. Com `SERVER_ONLY`, o app sempre consulta o
  servidor antes de baixar de uma fonte (como se *Preferir baixar do servidor* estivesse ligado);
  ele não proíbe o download da fonte, para que a leitura offline continue funcionando.

Limitações conhecidas: como não há timestamp por alteração, o progresso só avança; categorias e mangás
precisam existir na biblioteca do servidor (`inLibrary`) para entrar no sync; fontes mescladas novas
só trazem capítulos na próxima atualização da biblioteca.

## Downloads

Com *Preferir baixar do servidor* (ou política `SERVER_ONLY`) e o servidor alcançável, antes de baixar
de uma fonte o app chama `GET /chapter/lookup`. Se `downloaded`, faz stream de
`/chapter/{id}/archive` direto para a pasta de downloads, no formato escolhido (CBZ ou pasta de
páginas). Qualquer falha — servidor fora, 404, transferência cortada, arquivo incompleto — cai de volta
no download normal, sem mensagem. Se o servidor acabou de falhar, ele é ignorado por 30 s para a fila
não pagar o timeout a cada capítulo.

## Backups

- **Criar backup** tem a opção *Enviar também uma cópia ao servidor Suwayomi* (aparece só com a
  sincronização ligada). Os automáticos seguem o interruptor da tela de configurações. Se o envio
  falha, o backup local continua valendo.
- **Restaurar → Do servidor Suwayomi** lista os backups do servidor, baixa o escolhido para o cache e
  segue o fluxo normal de restauração.

## Contrato REST usado (v1)

Base: `{servidor}/api/v1/karasu`. Corpos JSON em camelCase; `sourceId` sempre string. Erros:
`{"error":"..."}`.

| Endpoint | Uso no app |
| --- | --- |
| `GET /health` | Testar conexão (versão, banco, armazenamento). |
| `GET\|HEAD /chapter/{id}/archive` | CBZ de capítulo baixado; 404 se não está baixado. |
| `GET /chapter/lookup?sourceId&mangaUrl&chapterUrl` | Saber se o servidor tem o capítulo. |
| `GET /manga/lookup?sourceId&url` | Disponível no cliente (`ServerSyncApi.lookupManga`). |
| `POST /backups/upload?device=` · `GET /backups` · `GET /backups/{id}/download` · `DELETE /backups/{id}` | Backups na nuvem. |
| `GET /merged` · `GET\|PUT /manga/{id}/merged` · `PUT /merged` | Fontes mescladas (o app usa `GET /merged` e `PUT /merged`). |
| `GET /policy` · `PUT /policy` | Política de download. |
| `POST /deletions` · `GET /deletions?afterId=` | Exclusão remota (o app só lê). |

O que não está no contrato REST usa o GraphQL nativo do Suwayomi (`POST {servidor}/api/graphql`),
com consultas escritas à mão (sem Apollo), baseadas no Suwayomi-Bunko: `categories`, `createCategory`,
`updateMangaCategories`, `mangas(filter: inLibrary)` com `categories` e `chapters`, e `updateChapter`
(`isRead`, `lastPageRead`).

## Onde está no código

- `karasu/domain/suwayomi/` — preferências, modelos, regras de merge puras (`SyncMath`) e o
  interactor `SyncSuwayomiServer`.
- `karasu/data/suwayomi/` — `ServerTransport` (dois endereços + auth), `ServerSyncApi` (REST +
  GraphQL), `ServerReachability`, `ServerChapterDownloader`, `ServerBackupTransfer`.
- `eu/kanade/tachiyomi/data/suwayomi/ServerSyncJob.kt` — WorkManager.
- `eu/kanade/tachiyomi/ui/setting/controllers/SettingsSyncController.kt` — tela de configurações.

## Fork: sincronização com o upstream e releases

`.github/workflows/upstream_sync_and_release.yml` roda toda segunda-feira (e sob demanda):

1. Faz merge de `clevim/Karasu@master` em `master` (commit de merge, sem force-push). Em conflito, a
   execução falha com a lista de arquivos no resumo, abre/atualiza uma issue e **não altera** o `master`.
2. Roda `./gradlew assembleStandardRelease`, assina e anexa os APKs a uma GitHub Release do fork.

Secrets: usa os mesmos do `build_push.yml` (`SIGNING_KEY`, `ALIAS`, `KEY_STORE_PASSWORD`,
`KEY_PASSWORD`). Sem eles, o build roda mas os APKs ficam só como artefato do workflow, sem Release.
Opcional: `SYNC_PAT` (token com escopos `repo` e `workflow`), necessário apenas se o upstream alterar
arquivos em `.github/workflows`, que o `GITHUB_TOKEN` não pode enviar.

## Progresso de leitura: lido e não lido

O estado lido é comparado com o último estado combinado entre os dois lados (merge de 3 vias), então **marcar como não
lido propaga** nos dois sentidos e o capítulo não volta a ficar lido na sincronização seguinte. O "não lido" leva a
página junto (volta ao início), a não ser que o outro lado tenha lido mais desde a última sincronização: leitura nova
nunca é jogada fora por um "não lido" antigo. A **página continua só avançando**: o servidor arredonda a página que
guarda para as que ele conhece, e seguir esse recuo comeria o ponto em que você parou.

## Histórico e exclusões (nos dois sentidos)

* **Histórico.** "Quando cada capítulo foi lido" sincroniza por capítulo, comparando com o último valor combinado
  (merge de 3 vias). Remover um capítulo do histórico no app remove no servidor, e vice-versa; se um lado remove e o
  outro leu de novo, a leitura vence. O app guarda milissegundos e o servidor segundos. O servidor só consegue gravar
  esse valor pelo `PUT /api/v1/karasu/history` do fork (o GraphQL só grava "agora" ao mudar a página lida). Com um
  servidor sem essa rota o histórico é pulado e o resto da biblioteca sincroniza normalmente.
* **Exclusões do servidor → app.** Capítulo apagado pela WebUI com "Deletar também dos dispositivos Karasu" é apagado
  do aparelho. A primeira sincronização só aprende onde o log termina, para não reaplicar um histórico antigo.
* **Exclusões do app → servidor.** Opcional (*Compartilhar exclusões feitas neste aparelho*, desligado por padrão):
  capítulo baixado que você apaga aqui é anunciado ao servidor e os outros aparelhos o apagam. A cópia do servidor é
  mantida. Um aparelho que apagou por ordem do servidor não anuncia de volta, e um capítulo baixado de novo depois
  não é apagado pelo próprio anúncio antigo.

## Testes ao vivo (app ↔ servidor, H2 e PostgreSQL)

`SyncLiveBidirectionalTest` e `ServerSyncApiLiveTest` rodam o app de verdade (o `SyncSuwayomiServer` real, sobre um
banco local em memória) contra um servidor de verdade, mudando um lado e conferindo o outro. Cobrem categorias
(criar, mover, remover, apagar), progresso, histórico (incluindo remover), fontes mescladas (adicionar, remover,
reserva, ordem), downloads e exclusões, backups entre dois aparelhos, política e a convergência de dois aparelhos.

```bash
SERVER_JAR=/caminho/Suwayomi-Server-vX.jar scripts/live-sync-test.sh h2
SERVER_JAR=... PGHOST=localhost PGUSER=postgres PGPASSWORD=... scripts/live-sync-test.sh postgres
```

O script sobe o servidor, cria o esquema, semeia obras e CBZs (`scripts/live/seed.py`) e roda os testes. Sem
`KARASU_LIVE_URL` eles são ignorados, então `./gradlew testStandardDebugUnitTest` continua sem precisar de servidor.
No GitHub, o workflow `CI Integration` faz isso em H2 e em PostgreSQL.

## Log de operações e conflitos decididos pelo horário

Cada ação do usuário que a sincronização acompanha (progresso, histórico, categorias, vínculos obra↔categoria, fontes
mescladas) é registrada no app com o **horário em que foi feita** (`OperationLog`, gravado pelos próprios interactors).
A sincronização publica esse log no servidor (`POST /api/v1/karasu/operations`, com o relógio corrigido pela hora do
servidor, e `X-Karasu-Device` identificando o aparelho) e lê o log do servidor, onde a WebUI e os outros aparelhos
registram o que fizeram. O estado continua sendo comparado com o último combinado; o log só decide os **conflitos
reais** (os dois lados mudaram a mesma coisa): **vale a operação mais recente**, empate para o servidor. O que a própria
sincronização grava não é registrado como ação do usuário. Sem registro de um lado, ou com um servidor sem log, valem
as regras anteriores.

## Repositórios de extensões

O app e o servidor mantêm a **união** dos repositórios de extensões (GraphQL `extensionStores`, `addExtensionStore`,
`removeExtensionStore`). As URLs são comparadas normalizadas (sem `index.min.json`, `index.json`, `index.pb`, `repo.json`
nem barra final), então `.../index.min.json` no app e `.../repo.json` no servidor são o mesmo repositório. Um repositório
novo de um lado é criado no outro; se o app não consegue ler o repositório do servidor (ex.: só v2), registra um aviso e
tenta de novo na próxima vez. A **remoção** só propaga quando o último estado combinado (`suwayomi_extension_repo_snapshot`)
prova que estava nos dois lados (a primeira execução nunca remove) e segue as opções já existentes: removido no app só
sai do servidor com "enviar exclusões locais" (`pushLocalDeletions`); removido no servidor só sai do app com "aplicar
exclusões remotas" (`applyRemoteDeletions`). Se o outro lado fez algo mais novo (log de operações, entidade
`EXTENSION_REPO`, chave `repo:<indexUrl>`), o horário decide e o repositório é recriado. O backup não muda.

Testes: `SyncExtensionReposLiveTest` (12 cenários nos dois sentidos: adicionar, remover com e sem as opções, readicionar
depois da remoção, dois aparelhos, repositório que o servidor não consegue ler). Roda no mock a cada build e contra o
servidor real (H2 e PostgreSQL) em `scripts/live-sync-test.sh`; os repositórios dos testes vêm de um `RepoHost` local, que o
servidor consulta na mesma máquina.

## Renomear categoria

O app lembra qual categoria local é qual categoria do servidor e o nome combinado. Renomeou num lado: o outro
acompanha, sem criar uma segunda categoria. Renomeou nos dois: vale o nome da operação mais recente.

## Três níveis de teste

| Nível | O que roda | Onde |
| --- | --- | --- |
| **Mock** (rápido) | `SyncLiveBidirectionalTest`, `ServerSyncApiLiveTest`, `SyncOldServerTest` contra o `MockSuwayomiServer`, um servidor em memória (REST + GraphQL, com as manhas do real) dentro da JVM de teste | `CI Test`, a cada build |
| **Servidor real** | os mesmos cenários contra um Suwayomi-Server de verdade, em H2 e PostgreSQL (`KARASU_LIVE_URL`, `scripts/live-sync-test.sh`) | `CI Integration`; é o que mantém o mock honesto |
| **Pilha completa** | `FullStackTest`: servidor real + **WebUI real clicada no Chromium** + código de sincronização do app, em H2 e PostgreSQL | `scripts/full-stack/run.sh`, **fora das pipelines** |

```bash
SERVER_JAR=/caminho/Suwayomi-Server-vX.jar WEBUI_DIR=/caminho/Suwayomi-WebUI/build \
  scripts/full-stack/run.sh h2          # ou: postgres  (PGHOST/PGUSER/PGPASSWORD)
```

Precisa de Java 21 (servidor), do JDK e do Android SDK do app, de `python3`, e de `node` com o módulo `playwright`
(`WEBUI_REPO=/caminho/Suwayomi-WebUI` expõe o `node_modules`; `CHROMIUM=/caminho` se o navegador não for o padrão).
`ONLY_STACK=1` sobe a pilha sem testes (para desenvolver o driver); `KEEP_UP=1` a deixa de pé. O driver do navegador é
`scripts/full-stack/web-driver.mjs` (uma ação por chamada: criar/renomear/apagar categoria, vincular obra, marcar
lido/não lido, apagar capítulo com o checkbox do Karasu, histórico, fontes mescladas, backups, política).
**O "app" aqui é o código real de sincronização (interactor + cliente) na JVM, não o APK**: não há emulador Android.
