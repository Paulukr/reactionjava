plugins {
    application
}

// Repositories are declared in settings.gradle.kts (dependencyResolutionManagement).

// Pick the webrtc-java native jar for the machine running the build/app. For a cross-platform
// distribution, add the other classifiers (windows-x86_64, macos-x86_64, macos-aarch64,
// linux-aarch64) as runtimeOnly too — each bundles that platform's native library.
val webrtcVersion = "0.18.0"
val nativeClassifier = run {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    val a = if (arch.contains("aarch64") || arch.contains("arm64")) "aarch64" else "x86_64"
    when {
        os.contains("win") -> "windows-x86_64"
        os.contains("mac") || os.contains("darwin") -> "macos-$a"
        else -> "linux-$a"
    }
}

dependencies {
    implementation("dev.onvoid.webrtc:webrtc-java:$webrtcVersion")
    runtimeOnly("dev.onvoid.webrtc:webrtc-java:$webrtcVersion:$nativeClassifier")
    implementation("org.java-websocket:Java-WebSocket:1.6.0")
    implementation("org.json:json:20240303")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.13")
    testImplementation("junit:junit:4.13.2")
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
}

application {
    mainClass.set("com.reaction.desktop.Main")
}

tasks.test {
    useJUnit()
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = System.getenv("REACTION_SIGNALING") != null
    }
}
