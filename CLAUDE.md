# CLAUDE.md — U.Ai.TAKi Player Vídeo

> Contexto de projeto para o Claude Code. Mantenha este arquivo enxuto e específico — atualize conforme decisões arquiteturais mudarem.

## Visão geral

- **Nome**: U.Ai.TAKi Player Vídeo (nome interno de código/pastas ainda `neonplayer`, ver nota abaixo)
- **applicationId (Play Store, definitivo)**: `com.uaitaki.playervideo`
- **namespace (pacote Kotlin interno)**: `com.example.neonplayer` — mantido de propósito diferente do applicationId. Play Store só enxerga o `applicationId`; renomear o pacote Kotlin em ~50 arquivos não trazia nenhum benefício e só aumentava o risco de erro. Não "corrigir" isso sem necessidade real.
- **Desenvolvedor**: UAiTAKi Soluções Corporativas
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

- [x] min/target SDK definitivo — minSdk 29, targetSdk 36 (`app/build.gradle.kts`)
- [x] Miniaturas de vídeo — geradas e cacheadas em disco+memória (`player/ThumbnailStore.kt`), local e remoto (SMB/SFTP com leitura posicional, FTP com download parcial)
- [x] Feature graphic da Play Store (1024×500) — `store/feature_graphic.png`
- [ ] Comportamento quando a conexão remota cai no meio da reprodução (retry automático? aviso?)
- [ ] Tornar o repositório público e ativar GitHub Pages manualmente (ver seção de publicação abaixo)
- [ ] Criar/configurar a conta no Google Play Console e submeter a primeira versão para revisão

## Publicação na Google Play Store

### Status atual (o que já foi feito)

- **applicationId definitivo**: `com.uaitaki.playervideo`, configurado em `app/build.gradle.kts`.
- **Ícone**: adaptativo (fundo ciano `#6DCFF6` + primeiro plano com a marca do rocket/chevron em laranja/marinho, extraída de `Logomarca/uaitakivideoplayer.svg`), gerado em todas as densidades (`app/src/main/res/mipmap-*`), mais ícone de alta resolução 512×512 em `store/ic_launcher_512.png`.
- **Chave de assinatura de release**: gerada (`release/uaitaki-playervideo-release.jks`, RSA 4096, alias `uaitaki-playervideo`, válida até 2056). **Nunca versionada** — está em `.gitignore` junto com `keystore.properties` (que guarda as senhas em texto puro localmente, também ignorado).
- **Build assinado**: `app/build.gradle.kts` tem `signingConfigs.release` lendo de `keystore.properties`; `./gradlew bundleRelease` gera e assina `app/build/outputs/bundle/release/app-release.aab` automaticamente (verificado com `jarsigner -verify`).
- **Repositório**: https://github.com/leogvital/uaitaki-playervideo (branch `main`), **atualmente privado** (confirmado via `api.github.com/repos/...` sem autenticação → 404). `ExemploTela/` (screenshots de referência do VLC) e `.idea/` ficam fora do repositório de propósito.
- **Política de privacidade**: conteúdo em `docs/index.html`, já commitado — falta tornar o repositório público e ativar o GitHub Pages (passos manuais, ver abaixo). URL final será `https://leogvital.github.io/uaitaki-playervideo/`.
- **Ficha da loja**: descrição curta/completa e categoria em `store/listing-pt-BR.md`; ícone 512×512 e gráfico de destaque 1024×500 em `store/`; 4 screenshots reais do app em `store/screenshots/` (proporção 2:1, dentro do limite do Play Console).

### ⚠️ Backup da chave de assinatura — leia antes de fazer qualquer coisa

O arquivo `release/uaitaki-playervideo-release.jks` e as senhas em `keystore.properties` **não estão em nenhum backup automático** (estão fora do git de propósito). Se esse arquivo se perder:

- Você **nunca mais** conseguirá publicar uma atualização do app já publicado — o Google Play exige a mesma chave (ou a mesma linhagem, se você usar upload key rotation) para toda atualização.
- A única saída seria publicar como um app novo, perdendo todas as avaliações, instalações e histórico.

**Faça isso agora**: copie a pasta `release/` inteira e o arquivo `keystore.properties` para pelo menos dois lugares fora deste computador (ex: um gerenciador de senhas com anexo de arquivo, um cofre na nuvem da empresa). As senhas dentro de `keystore.properties` também deveriam ir para um gerenciador de senhas, não só ficar em disco.

### Passo a passo: ativar o GitHub Pages (necessário para a política de privacidade)

O repositório está privado hoje. No plano GitHub Free, Pages só publica a partir de repositório **público** — e mesmo em planos pagos, o site publicado normalmente fica acessível sem login, que é justamente o que precisamos aqui (o revisor do Google e qualquer usuário do app têm que conseguir abrir a política sem autenticação). Não há nada sensível versionado (chave de assinatura e senhas ficam fora do git), então tornar público é seguro.

1. Acesse https://github.com/leogvital/uaitaki-playervideo/settings, role até "Danger Zone" → **Change visibility** → **Change to public**.
2. Acesse https://github.com/leogvital/uaitaki-playervideo/settings/pages
3. Em "Build and deployment" → "Source", escolha **Deploy from a branch**.
4. Em "Branch", escolha **main** e a pasta **/docs**, depois **Save**.
5. Espere alguns minutos; a URL `https://leogvital.github.io/uaitaki-playervideo/` deve ficar no ar. Confirme abrindo no navegador antes de colar no Play Console.

### Passo a passo: criar a conta no Google Play Console

1. Acesse https://play.google.com/console/signup com a conta Google que vai administrar o app (recomendado: uma conta da empresa, não pessoal — dá pra adicionar mais gente depois via Play Console → Usuários e permissões).
2. Pague a taxa única de registro (US$25, cobrada uma vez, sem mensalidade).
3. Escolha o tipo de conta:
   - **Conta de organização** (recomendado, já que é a UAiTAKi Soluções Corporativas publicando): pede CNPJ, endereço da empresa e passa por uma verificação de identidade da organização (D-U-N-S number — se a empresa não tiver um, o próprio fluxo do Google ajuda a solicitar um grátis via Dun & Bradstreet, pode levar alguns dias).
   - **Conta individual**: mais rápida de abrir (só verificação de identidade pessoal), mas o nome exibido como desenvolvedor é o da pessoa física, não da empresa.
4. Preencha os dados de contato (e-mail de suporte visível na ficha da loja — sugestão: usar o mesmo e-mail de contato da política de privacidade).
5. Complete a verificação de identidade quando solicitada (documento oficial + eventualmente uma videochamada rápida — o Google avisa na tela quando é necessário).

### Passo a passo: criar o app e submeter a primeira versão

1. No Play Console, **Criar app** → nome "U.Ai.TAKi Player Vídeo", idioma padrão Português (Brasil), tipo **App**, gratuito.
2. **Política do app** (menu lateral) → declarar:
   - Política de privacidade: cole a URL do GitHub Pages.
   - Segurança dos dados (*Data safety*): com base no que o app realmente faz (ver `docs/index.html`) — não coleta nem compartilha dados do usuário; as únicas informações armazenadas (servidores, senhas, favoritos) ficam só no dispositivo, então o formulário deve refletir "nenhum dado coletado/compartilhado".
   - Classificação indicativa: responder o questionário — o app não hospeda nem produz conteúdo próprio, só reproduz o que já está no aparelho do usuário ou em servidor configurado por ele.
   - Público-alvo: adultos/geral, não direcionado a crianças.
   - Anúncios: declarar que o app não tem anúncios.
3. **Presença na loja** → **Ficha da loja principal**: usar o conteúdo de `store/listing-pt-BR.md` (descrição curta/completa, categoria "Vídeo Players e Editores"), subir `store/ic_launcher_512.png` como ícone, `store/feature_graphic.png` como gráfico de destaque, e os arquivos de `store/screenshots/` como capturas de tela.
4. **Versão** → **Produção** (ou comece por **Teste interno/fechado** para validar antes de ir a público, recomendado para a primeira publicação) → **Criar nova versão** → subir `app/build/outputs/bundle/release/app-release.aab` (gerar com `./gradlew bundleRelease` se não existir ou estiver desatualizado).
5. Revisar os avisos do Play Console (ele aponta o que ainda falta preencher) e enviar para revisão. A primeira revisão do Google costuma levar de algumas horas a poucos dias.

### Gerando uma nova versão de release

```bash
# 1. Suba versionCode e versionName em app/build.gradle.kts
# 2. Gere o bundle assinado:
./gradlew bundleRelease
# Saída: app/build/outputs/bundle/release/app-release.aab
```

### Botão de doação (planejado para depois da aprovação)

Combinado com o usuário: só entra depois que o app for aprovado e testado em produção. Ao implementar, ter em mente:

- Doação pura (sem "comprar" nenhum conteúdo/funcionalidade dentro do app) normalmente é tratada como fora do escopo do Play Billing — o caminho mais simples costuma ser um botão que abre um link externo (Pix, PayPal, etc.) no navegador, não uma compra dentro do app.
- A política do Google Play sobre pagamentos/doações pode mudar — **revisar a política vigente em https://support.google.com/googleplay/android-developer/answer/9858738 no momento de implementar**, antes de assumir que o comportamento acima ainda é válido.
- Não usar `androidx.security.crypto`/DataStore para nada relacionado a pagamento — isso é responsabilidade de um provedor de pagamento externo, não do app.
