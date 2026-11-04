import java.net.URI
import java.security.MessageDigest
import java.util.Properties

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

/** The other pinned model files, fetched once and never committed. */
abstract class FetchModel : DefaultTask() {
    @get:Input abstract val url: Property<String>

    @get:Input abstract val assetPath: Property<String>

    @get:Input abstract val sha256: Property<String>

    @get:Internal abstract val cache: RegularFileProperty

    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun fetch() {
        fun sha(f: File) = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
        val cached = cache.get().asFile
        if (!cached.isFile || sha(cached) != sha256.get()) {
            cached.parentFile.mkdirs()
            URI(url.get()).toURL().openStream().use { i -> cached.outputStream().use { i.copyTo(it) } }
            check(sha(cached) == sha256.get()) { "checksum mismatch for ${url.get()}: ${sha(cached)}" }
        }
        cached.copyTo(outputDir.get().file(assetPath.get()).asFile, overwrite = true)
    }
}

val fetchReaderData by tasks.registering(FetchModel::class) {
    url = "https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/4.1.0/eng.traineddata"
    assetPath = "ocr/eng.traineddata"
    sha256 = "7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2"
    cache = rootProject.layout.projectDirectory.file(".models/eng.traineddata")
}

val fetchCutoutModel by tasks.registering(FetchModel::class) {
    url = "https://storage.googleapis.com/mediapipe-models/image_segmenter/selfie_multiclass_256x256/float32/1/" +
        "selfie_multiclass_256x256.tflite"
    assetPath = "cutout/selfie_multiclass_256x256.tflite"
    sha256 = "c6748b1253a99067ef71f7e26ca71096cd449baefa8f101900ea23016507e0e0"
    cache = rootProject.layout.projectDirectory.file(".models/selfie_multiclass_256x256.tflite")
}

val fetchCaptionModel by tasks.registering(FetchCaptionModel::class) {
    fileName = "ggml-base-q5_1.bin"
    sha256 = "422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898"
    cache = rootProject.layout.projectDirectory.file(".models/ggml-base-q5_1.bin")
}

androidComponents {
    onVariants { v ->
        v.sources.assets?.addGeneratedSourceDirectory(fetchCaptionModel, FetchCaptionModel::outputDir)
        v.sources.assets?.addGeneratedSourceDirectory(fetchReaderData, FetchModel::outputDir)
        v.sources.assets?.addGeneratedSourceDirectory(fetchCutoutModel, FetchModel::outputDir)
    }
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
        // The public key from Play Console, for checking purchases on the phone. Not a secret.
        // Set zella.playKey in ~/.gradle/gradle.properties or local.properties before a release.
        val localProps = Properties().apply { rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) } }
        val playKey = (findProperty("zella.playKey") as String?) ?: localProps.getProperty("zella.playKey") ?: ""
        buildConfigField("String", "PLAY_KEY", "\"$playKey\"")
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // keystore.properties stays on your machine and is never committed. Without it the build
            // is unsigned, which is fine for checking R8 and useless for Play.
            val keys = rootProject.file("keystore.properties")
            if (keys.isFile) {
                val props = Properties().apply { keys.inputStream().use { load(it) } }
                signingConfig =
                    signingConfigs.create("release") {
                        storeFile = rootProject.file(props.getProperty("storeFile"))
                        storePassword = props.getProperty("storePassword")
                        keyAlias = props.getProperty("keyAlias")
                        keyPassword = props.getProperty("keyPassword")
                    }
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    androidResources { noCompress += listOf("bin", "tflite", "traineddata") }
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
    implementation(libs.play.billing)
    implementation(libs.tesseract4android)
    implementation(libs.litert)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.rules)
}

tasks.withType<Test>().configureEach {
    systemProperty("res.dir", file("src/main/res").absolutePath)
}
