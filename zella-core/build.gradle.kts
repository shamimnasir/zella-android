plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin { jvmToolchain(17) }

dependencies {
    api(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}

tasks.test {
    // The repo-wide text scans read files from the project root.
    systemProperty("repo.root", rootProject.projectDir.absolutePath)
    // Declare the scanned files as inputs, or Gradle serves a cached pass after a dash is added.
    inputs.files(
        fileTree(rootProject.projectDir) {
            include("**/*.kt", "**/*.kts", "**/*.xml", "**/*.md", "**/*.toml", "**/*.yml", "**/*.properties")
            exclude("**/build/**", ".gradle/**", ".git/**", ".idea/**")
        },
    )
}
