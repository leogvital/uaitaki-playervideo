<div align="center">
  <img src="Logomarca/uaitakivideoplayer.png" alt="U.Ai.TAKi Player Vídeo" width="420">

  <p>
    Player de vídeo Android nativo — local, SMB, SFTP e FTP.<br>
    Sem conta, sem anúncios, sem analytics, sem backend próprio.
  </p>
</div>

## Screenshots

<p align="center">
  <img src="store/screenshots/01_lista_de_videos.png" width="200">
  <img src="store/screenshots/02_player.png" width="200">
  <img src="store/screenshots/03_favoritos.png" width="200">
  <img src="store/screenshots/04_procurar.png" width="200">
</p>

## Sobre

**U.Ai.TAKi Player Vídeo** reproduz os vídeos do seu aparelho e também os que estão guardados em
servidores de rede (SMB, SFTP ou FTP), sem exigir conta, sem anúncios e sem coletar dados de uso.
Cada servidor remoto é configurado uma vez (host, porta, usuário, senha, caminho) e fica salvo
localmente, com a senha criptografada — nada sai do aparelho.

## Funcionalidades

**Fontes de vídeo**
- Armazenamento local do celular (MediaStore)
- Servidores SMB, SFTP e FTP configurados pelo usuário

**Navegação**
- Navegação por pastas e subpastas em todas as fontes
- Miniaturas geradas e cacheadas no aparelho (local e remoto)
- Ordenação por nome, data ou tamanho — lista ou grade

**Player**
- Barra de progresso com tempo decorrido/total
- Duplo toque na lateral: avança/retrocede 10s
- Arraste horizontal: scrub proporcional à posição
- Arraste vertical: brilho (esquerda) e volume (direita)
- Zoom com pinça de dois dedos + arraste para mover a imagem ampliada
- Ajuste de proporção (tamanho original / preencher tela)
- Travamento de orientação (retrato / paisagem / automático)
- Reprodução em ordem aleatória
- Controles somem sozinhos durante a reprodução, reaparecem com um toque
- Tela não escurece durante a reprodução

**Organização**
- Favoritos separados por fonte de origem
- Exclusão de vídeo local (com confirmação) ou remoto (quando a conta tiver permissão de escrita)

**Privacidade**
- Senhas de servidores remotos criptografadas com `EncryptedSharedPreferences` (Android Keystore)
- Nenhum servidor próprio — tudo fica no dispositivo
- Ver [política de privacidade completa](https://leogvital.github.io/uaitaki-playervideo/)

## Stack técnica

- Kotlin + Jetpack Compose (Material 3)
- MVVM: `Composable` → `ViewModel` → repositório de fonte de dados
- [Media3/ExoPlayer](https://developer.android.com/media/media3) para reprodução
- `DataStore` (Preferences) para servidores/favoritos, `EncryptedSharedPreferences` para senhas — sem Room/SQLite, sem backend
- Clientes de rede: [smbj](https://github.com/hierynomus/smbj) (SMB), [sshj](https://github.com/hierynomus/sshj) (SFTP), [Apache Commons Net](https://commons.apache.org/proper/commons-net/) (FTP)

Detalhes de arquitetura e convenções: ver [`CLAUDE.md`](CLAUDE.md).

## Build

```bash
./gradlew assembleDebug     # APK de debug
./gradlew installDebug      # instala num dispositivo/emulador conectado
./gradlew bundleRelease     # Android App Bundle assinado (exige keystore.properties local — ver CLAUDE.md)
```

Requer Android Studio / JDK compatível com o AGP do projeto (ver `gradle/libs.versions.toml`), minSdk 29, targetSdk 36.

## Publicação na Play Store

**Feito:**
- [x] `applicationId` definitivo (`com.uaitaki.playervideo`) e ícone adaptativo
- [x] Chave de assinatura de release gerada e configurada (`./gradlew bundleRelease` já gera o `.aab` assinado)
- [x] Repositório no GitHub (público)
- [x] Ficha da loja: descrição, categoria, screenshots e [gráfico de destaque](store/feature_graphic.png) ([`store/`](store/))
- [x] Política de privacidade publicada: **https://leogvital.github.io/uaitaki-playervideo/**

**Pendente (depende de ação manual do mantenedor, fora do que dá pra automatizar):**
- [ ] Criar a conta de desenvolvedor no [Google Play Console](https://play.google.com/console/signup) (taxa única de US$25 + verificação de identidade)
- [ ] Submeter a primeira versão para revisão

O passo a passo detalhado de cada item (inclusive backup da chave de assinatura, que é crítico) está em [`CLAUDE.md`](CLAUDE.md#publicação-na-google-play-store).

### Gerando um build de release local

Requer um `keystore.properties` na raiz do projeto (não versionado — cada máquina/pessoa que assina uma release precisa do seu próprio, com `storePassword`, `keyPassword`, `keyAlias` e `storeFile`):

```bash
./gradlew bundleRelease
# saída: app/build/outputs/bundle/release/app-release.aab
```

## Desenvolvedor

UAiTAKi Soluções Corporativas
