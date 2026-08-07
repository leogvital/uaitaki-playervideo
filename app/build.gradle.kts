import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Credenciais de assinatura de release: nunca versionadas (ver .gitignore) — carregadas de
// keystore.properties na raiz do projeto, gerado uma única vez ao publicar. Sem esse arquivo
// (ex: checkout novo, CI sem os segredos), o build de release simplesmente sai sem assinatura em
// vez de falhar, para não travar builds de debug.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

android {
    namespace = "com.example.neonplayer"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.uaitaki.playervideo"
        minSdk = 29
        targetSdk = 36
        versionCode = 2
        versionName = "1.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // Player (Media3/ExoPlayer)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)

    // Credenciais criptografadas (EncryptedSharedPreferences)
    implementation(libs.androidx.security.crypto)

    // Favoritos e outras preferências estruturadas persistentes
    implementation(libs.androidx.datastore.preferences)

    // Fontes remotas: SMB / SFTP / FTP
    implementation(libs.smbj)
    implementation(libs.sshj)
    // BouncyCastle completo: o provider "BC" embutido no Android é uma versão reduzida sem
    // suporte a X25519/curve25519, exigido pela negociação de key exchange de servidores SSH/SFTP
    // modernos (ver SftpClients.kt) — sem esta dependência a conexão falha com
    // "no such algorithm: X25519 for provider BC".
    implementation(libs.bouncycastle)
    implementation(libs.commons.net)
    // Listagem de compartilhamentos SMB (NetrShareEnum/MS-SRVS) via smbj-rpc, em vez de montar/
    // interpretar o PDU de DCE/RPC na mão — a lib foi publicada contra smbj 0.12.2, mas só usa a
    // API estável de alto nível (Session/PipeShare), então funciona normalmente com o smbj 0.14.0
    // deste projeto (Gradle resolve o conflito de versão transitiva para a mais nova).
    implementation(libs.dcerpc)

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}