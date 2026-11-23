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
