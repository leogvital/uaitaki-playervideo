# CLAUDE.md — NeonPlayer

> Contexto de projeto para o Claude Code. Mantenha este arquivo enxuto e específico — atualize conforme decisões arquiteturais mudarem.

## Visão geral

- **Nome**: NeonPlayer
- **Package**: `com.example.neonplayer` (ajustar para o package final antes de publicar)
- **Tipo**: App Android nativo, standalone, player de **vídeo** — **sem banco relacional** (sem Room, sem SQLite, sem backend remoto)
- **Persistência permitida**: `DataStore` (Preferences ou Proto) guardando dados estruturados em JSON/protobuf — servidores configurados, favoritos, credenciais. Não é um schema relacional, mas é persistência local real (não é só "preferências simples").
- **UI**: Jetpack Compose (Material 3), tema customizado `NeonPlayerTheme`
- **Estado**: `ViewModel` + `StateFlow` para estado de UI/playback em memória; `DataStore` para o que precisa sobreviver ao fechar o app (favoritos, servidores, credenciais, últimas posições assistidas se aplicável).

## Funcionalidades (spec do usuário)

### Player
- Reproduz vídeos via **Media3/ExoPlayer**
- Controles: play/pause, skip (próximo/anterior da lista)
- **Duplo toque na lateral esquerda da tela**: retrocede 10s
- **Duplo toque na lateral direita da tela**: avança 10s

### Fontes de vídeo
- Armazenamento local do celular (MediaStore API, com runtime permissions `READ_MEDIA_VIDEO`)
- **SMB** (compartilhamento de rede)
- **SFTP**
- **FTP**
- Cada fonte remota é configurada pelo usuário (host, porta, usuário, senha, path)

### Gerenciamento de vídeos
- Listar vídeos por fonte (local, SMB, SFTP, FTP — cada uma tratada como "local de armazenamento" separado)
- **Excluir vídeo**: local sempre; em SMB/SFTP/FTP, **somente se a conta tiver permissão de escrita** no servidor (tratar erro de permissão negada com feedback claro ao usuário, não crash)
- **Favoritar vídeo** (ícone de estrela): favoritos são **separados por local de armazenamento de origem** (não é uma lista única global — um favorito "pertence" à fonte de onde veio)

### Credenciais (SMB/SFTP/FTP)
- Senha **gravada de forma persistente e criptografada** via `EncryptedSharedPreferences` (`androidx.security.crypto`), chave gerenciada pelo **Android Keystore**
- **Nunca** salvar senha em texto puro, em `DataStore` sem criptografia, em log, ou em `SharedPreferences` comum
- Ao editar/remover um servidor, remover a credencial associada também

## Bibliotecas sugeridas (validar disponibilidade/licença antes de adicionar)
- `androidx.media3:media3-exoplayer` + `media3-ui` — player de vídeo
- `androidx.security:security-crypto` — credenciais criptografadas
- Cliente SMB: `hierynomus/smbj` (Java, JCIFS é legado)
- Cliente SFTP: `com.hierynomus:sshj` ou `JSch` (mantido: SSHJ é preferível)
- Cliente FTP: `commons-net` (Apache Commons Net)

## Stack técnica

- Kotlin, Jetpack Compose, Material 3
- Gradle Kotlin DSL (`build.gradle.kts`)
- Min/target SDK: definir em `app/build.gradle.kts` (checar antes de assumir)
- Arquitetura: MVVM simples — `Composable` → `ViewModel` → (opcional) `UseCase` → fonte de dados local (arquivos de mídia no dispositivo, não banco)

## Convenções de código

- Composables públicos em PascalCase, sempre com `modifier: Modifier = Modifier` como último parâmetro
- Evitar lógica de negócio dentro de Composables — delegar para ViewModel
- Preferir `remember`/`rememberSaveable` para estado local de UI; `ViewModel` para estado que sobrevive a recomposição/rotação
- Nomes de arquivos = nome do Composable/Classe principal
- Sem hardcode de strings visíveis ao usuário — usar `strings.xml`

## Restrições explícitas

- **Não introduzir Room/SQLite nem backend próprio da Anthropic/terceiros para sync** — persistência é local via DataStore/EncryptedSharedPreferences apenas.
- Sem autenticação de usuário do app em si (não é multi-usuário) — as "credenciais" são apenas para os servidores SMB/SFTP/FTP configurados.
- Qualquer leitura de mídia local deve usar MediaStore API — não assumir backend.
- Permissões de mídia (`READ_MEDIA_VIDEO` ou `READ_EXTERNAL_STORAGE` conforme API level) tratadas com runtime permissions, com fallback de UI caso negadas.
- Operações de rede (SMB/SFTP/FTP) sempre fora da main thread (coroutines + `Dispatchers.IO`), com tratamento de timeout e erro de conexão visível ao usuário.
- Exclusão de arquivo remoto: sempre verificar/capturar erro de permissão negada antes de assumir sucesso.

## Build & Run

```bash
./gradlew assembleDebug
./gradlew installDebug
```

- Sync do Gradle às vezes trava — se acontecer, `File > Invalidate Caches / Restart` no Android Studio, ou `./gradlew --stop` no terminal.

## O que ainda falta decidir (preencher conforme o projeto evolui)

- [ ] Estrutura exata de telas (ex: tela de "Fontes" → lista de vídeos → player fullscreen → tela de favoritos por fonte → tela de config de servidor)
- [ ] min/target SDK definitivo
- [ ] Comportamento quando a conexão remota cai no meio da reprodução (retry automático? aviso?)
- [ ] Miniaturas de vídeo na lista: gerar localmente ou pular por enquanto?
