import java.net.URI
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

/**
 * Downloads a file once and refuses to use it unless its SHA-256 fingerprint
 * matches. If anyone tampers with the file on the server, the build fails.
 */
abstract class DownloadVerified : DefaultTask() {
    @get:Input abstract val url: Property<String>
    @get:Input abstract val sha256: Property<String>
    @get:OutputFile abstract val output: RegularFileProperty

    @TaskAction
    fun download() {
        val target = output.get().asFile
        if (target.exists() && sha256Of(target) == sha256.get()) return
        target.parentFile.mkdirs()
        val temp = File(target.parentFile, target.name + ".part")
        URI(url.get()).toURL().openStream().use { input -> temp.outputStream().use { input.copyTo(it) } }
        val actual = sha256Of(temp)
        if (actual != sha256.get()) {
            temp.delete()
            throw GradleException("Checksum mismatch for ${url.get()}: expected ${sha256.get()}, got $actual")
        }
        if (!temp.renameTo(target)) throw GradleException("Could not move ${temp.name} into place")
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

/** Unpacks just the files the wake-word engine needs into a generated assets folder. */
abstract class PrepareVoiceAssets : DefaultTask() {
    @get:InputFile abstract val kwsArchive: RegularFileProperty
    @get:OutputDirectory abstract val outputDir: DirectoryProperty
    @get:Inject abstract val archives: ArchiveOperations
    @get:Inject abstract val fileSystem: FileSystemOperations

    @TaskAction
    fun unpack() {
        fileSystem.sync {
            from(archives.tarTree(archives.bzip2(kwsArchive.get().asFile))) {
                include("**/tokens.txt", "**/encoder-*.int8.onnx", "**/decoder-*.int8.onnx", "**/joiner-*.int8.onnx")
                includeEmptyDirs = false
                eachFile {
                    val renamed = when {
                        name.startsWith("encoder") -> "encoder.int8.onnx"
                        name.startsWith("decoder") -> "decoder.int8.onnx"
                        name.startsWith("joiner") -> "joiner.int8.onnx"
                        else -> name
                    }
                    relativePath = RelativePath(true, "kws", renamed)
                }
            }
            into(outputDir)
        }
    }
}

// sherpa-onnx: offline wake word + speaker recognition (https://github.com/k2-fsa/sherpa-onnx, Apache-2.0)
val downloadSherpaOnnx = tasks.register<DownloadVerified>("downloadSherpaOnnx") {
    url.set("https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-1.13.8.aar")
    sha256.set("633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96")
    output.set(layout.buildDirectory.file("downloads/sherpa-onnx-1.13.8.aar"))
}
val downloadKwsModel = tasks.register<DownloadVerified>("downloadKwsModel") {
    url.set("https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01.tar.bz2")
    sha256.set("f170013b4716e41b62b9bfd809687c207cef798ef9bc6534d524e17af9b6561a")
    output.set(layout.buildDirectory.file("downloads/kws-gigaspeech-3.3M.tar.bz2"))
}
val prepareVoiceAssets = tasks.register<PrepareVoiceAssets>("prepareVoiceAssets") {
    kwsArchive.set(downloadKwsModel.flatMap { it.output })
    outputDir.set(layout.buildDirectory.dir("generated/voiceAssets"))
}

// Every CI build gets a higher version number, so the phone accepts it as an update.
val buildNumber = providers.environmentVariable("GITHUB_RUN_NUMBER").orNull?.toIntOrNull() ?: 1

android {
    namespace = "com.jarvis.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.jarvis.assistant"
        minSdk = 31
        targetSdk = 36
        versionCode = buildNumber
        versionName = "0.1.$buildNumber"
        // Modern phones are all 64-bit ARM; skipping other CPU types keeps the APK small.
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        create("jarvis") {
            // CI uses your private key from GitHub secrets when you add one (see docs/lessons/phase-1.md).
            // Otherwise it falls back to the shared development key, so each new APK installs over the old one.
            val storePath = providers.environmentVariable("JARVIS_KEYSTORE_FILE").orNull
            storeFile = if (storePath != null) file(storePath) else file("jarvis-dev.keystore")
            storePassword = providers.environmentVariable("JARVIS_KEYSTORE_PASSWORD").orNull ?: "jarvis-dev"
            keyAlias = providers.environmentVariable("JARVIS_KEY_ALIAS").orNull ?: "jarvis"
            keyPassword = providers.environmentVariable("JARVIS_KEY_PASSWORD").orNull ?: "jarvis-dev"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("jarvis")
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("jarvis")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/LICENSE*",
                "/META-INF/NOTICE*",
                "/META-INF/INDEX.LIST",
                "/META-INF/versions/9/OSGI-INF/MANIFEST.MF",
            )
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

// Packs the wake-word model into the APK's assets for every build variant.
androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(prepareVoiceAssets, PrepareVoiceAssets::outputDir)
    }
}

dependencies {
    implementation(project(":shared"))

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Google's on-device LLM runtime (offline brain).
    implementation(libs.litertlm.android)

    // Wake word + voice lock engine (official release AAR, checksum-verified above).
    implementation(files(downloadSherpaOnnx.flatMap { it.output }))
}
