import java.net.URI
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// The bundled caption model: whisper base, multilingual, 5-bit (about 60 MB). Downloaded at build time
// from the whisper.cpp model repository, checked against a pinned SHA-256, never committed to git.
abstract class FetchCaptionModel : DefaultTask() {
    @get:Input abstract val fileName: Property<String>

    @get:Input abstract val sha256: Property<String>

    @get:Internal abstract val cache: RegularFileProperty

    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun fetch() {
        fun sha(f: File) = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
        val cached = cache.get().asFile
        if (!cached.isFile || sha(cached) != sha256.get()) {
            cached.parentFile.mkdirs()
            URI("https://huggingface.co/ggerganov/whisper.cpp/resolve/main/${fileName.get()}").toURL().openStream().use { i ->
                cached.outputStream().use { i.copyTo(it) }
            }
            check(sha(cached) == sha256.get()) { "caption model checksum mismatch" }
        }
        cached.copyTo(outputDir.get().file("models/${fileName.get()}").asFile, overwrite = true)
    }
}

val fetchCaptionModel by tasks.registering(FetchCaptionModel::class) {
    fileName = "ggml-base-q5_1.bin"
    sha256 = "422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898"
    cache = rootProject.layout.projectDirectory.file(".models/ggml-base-q5_1.bin")
}

androidComponents {
    onVariants { v -> v.sources.assets?.addGeneratedSourceDirectory(fetchCaptionModel, FetchCaptionModel::outputDir) }
}

android {
    namespace = "com.zellahq.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.zellahq.app"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.0.1"
        ndk { abiFilters += "arm64-v8a" }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            // Signing config is a placeholder: supply keystore.properties locally, never commit it.
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    androidResources { noCompress += "bin" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":zella-core"))
    implementation(project(":zella-ui"))
    implementation(project(":zella-render"))
    implementation(project(":zella-ai"))
    implementation(project(":zella-whisper"))
    implementation(platform(libs.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.media3.transformer)
    implementation(libs.media3.effect)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.rules)
}

tasks.withType<Test>().configureEach {
    systemProperty("res.dir", file("src/main/res").absolutePath)
}
