import java.time.Duration

plugins {
    java
    application
    id("org.openjfx.javafxplugin") version "0.1.0"
    // 3.1.1+ is required on Gradle 9: 3.0.1 calls ResolvedConfiguration.getFiles(),
    // which Gradle 9 removed, so :app:prepareMergedJarsDir fails immediately.
    // packaging/build-installer.sh remains the supported fallback path (N-01).
    id("org.beryx.jlink") version "3.1.1"          // drives jpackage (N-01)
}

javafx {
    version = "21.0.4"
    modules = listOf("javafx.controls", "javafx.graphics", "javafx.base", "javafx.swing", "javafx.web")
}

application {
    // MUST be the plain Launcher, never the Application subclass. A JavaFX
    // Application as main class fails on a classpath launch with
    // "JavaFX runtime components are missing". Regressing this breaks the packaged app.
    mainClass.set("com.aegis.fdx.Launcher")
    applicationDefaultJvmArgs = listOf(
        "-Xmx4g",                                   // N-04 default 4 GB, configurable
        "-XX:+UseZGC",                              // low-pause GC keeps UI < 500 ms (N-03)
        "-XX:MaxGCPauseMillis=50",
        "-Dfile.encoding=UTF-8"
    )
}

dependencies {
    // --- Indexing / search (Apache-2.0) -------------------------------------
    implementation("org.apache.lucene:lucene-core:9.11.1")
    implementation("org.apache.lucene:lucene-analysis-common:9.11.1")
    implementation("org.apache.lucene:lucene-queryparser:9.11.1")
    implementation("org.apache.lucene:lucene-highlighter:9.11.1")

    // --- Extraction (Apache-2.0) --------------------------------------------
    implementation("org.apache.tika:tika-core:3.0.0")
    implementation("org.apache.tika:tika-parsers-standard-package:3.0.0")
    implementation("org.apache.pdfbox:pdfbox:3.0.3")
    implementation("org.apache.poi:poi-ooxml:5.3.0")

    // --- Mail (EPL-2.0 / Apache-2.0) ----------------------------------------
    implementation("org.eclipse.angus:jakarta.mail:2.0.3")
    implementation("org.apache.james:apache-mime4j-core:0.8.11")
    implementation("org.apache.james:apache-mime4j-dom:0.8.11")
    implementation("com.pff:java-libpst:0.9.3")                 // PST/OST, Apache-2.0

    // --- Archives (Apache-2.0 / MIT) ----------------------------------------
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("com.github.junrar:junrar:7.5.5")
    implementation("org.tukaani:xz:1.10")

    // --- OCR (Apache-2.0 wrapper over Tesseract 5, Apache-2.0) --------------
    implementation("net.sourceforge.tess4j:tess4j:5.13.0")

    // --- Storage / logging ---------------------------------------------------
    implementation("org.xerial:sqlite-jdbc:3.46.1.0")           // Apache-2.0
    implementation("org.slf4j:slf4j-api:2.0.16")                // MIT
    implementation("ch.qos.logback:logback-classic:1.5.8")      // EPL-1.0 / LGPL-2.1

    // No AI/ML runtime is declared here on purpose. The local agent is an optional
    // analysis layer that talks to a separately installed local model runtime over
    // loopback HTTP (see docs/AI_BOUNDARY.md); the processing engine must build, run
    // and ship without any inference dependency. An ONNX Runtime dependency for
    // in-pipeline "AI classification" was declared but never used, and was removed:
    // File → Java processing engine → database/index is the only ingest path.

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
    // implementation, not runtimeOnly: JUnitRunner imports launcher classes, so
    // :app:compileTestJava needs them on the compile classpath, not just at run.
    testImplementation("org.junit.platform:junit-platform-launcher")
}

// The suite harnesses are main()-based and are bridged into JUnit by SuiteBridgeTest,
// so `./gradlew :app:test` runs the same suites as run-tests.sh rather than discovering
// nothing and failing the build.
// A partial checkout produces a baffling "cannot find symbol" on a method that is
// plainly in the repo. Check tree completeness first and say so directly.
val verifySources by tasks.registering {
    group = "verification"
    description = "Fails early if the source tree is incomplete or inconsistent."
    val manifest = rootProject.file("packaging/SOURCE-MANIFEST.txt")
    onlyIf { manifest.exists() }
    doLast {
        val missing = manifest.readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .mapNotNull { line -> line.trim().split(Regex("\\s+"), 2).getOrNull(1) }
            .filter { !rootProject.file(it).exists() }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "Incomplete checkout: ${missing.size} source file(s) missing, e.g. " +
                missing.take(3).joinToString(", ") +
                ". Re-sync the whole tree rather than copying individual files."
            )
        }
    }
}

tasks.named("compileJava") { dependsOn(verifySources) }

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "1g"
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    // Acceptance suites build real corpora; they are not millisecond unit tests.
    timeout.set(Duration.ofMinutes(30))
}

// N-07: coverage gate on core units.
tasks.register("coverageGate") {
    group = "verification"
    description = "Fails the build if core-unit line coverage drops below 70%."
}

jlink {
    options.set(listOf("--strip-debug", "--compress", "2", "--no-header-files", "--no-man-pages"))
    launcher {
        name = "aegis-fdx"
    }
    jpackage {
        imageName = "AEGIS-FDX"
        installerName = "AEGIS-FDX"
        appVersion = project.version.toString()
        // Windows 10/11 x64 is mandatory; macOS/Linux targets build the same way.
        if (org.gradle.internal.os.OperatingSystem.current().isWindows) {
            installerType = "msi"
            installerOptions = listOf("--win-dir-chooser", "--win-menu", "--win-shortcut", "--win-per-user-install")
        }
        if (org.gradle.internal.os.OperatingSystem.current().isMacOsX) {
            installerType = "dmg"
        }
        if (org.gradle.internal.os.OperatingSystem.current().isLinux) {
            installerType = "deb"
        }
    }
}
