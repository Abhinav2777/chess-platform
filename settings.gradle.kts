// Toolchain resolver. Without this, `java { toolchain { languageVersion = 25 } }` in
// backend/build.gradle.kts is a requirement Gradle can only *check*, never *satisfy* —
// on a machine with no JDK 25 installed the build fails with
// "No matching toolchains found ... Toolchain auto-provisioning is not enabled."
//
// With it, Gradle downloads the JDK itself, so `git clone && ./gradlew build` works on
// any machine with Java 17+ and nothing else. For a portfolio repository that a stranger
// might clone, that property is worth the one plugin.
//
// This is a SETTINGS plugin, not a project plugin — it cannot go in build.gradle.kts.
//
// Version is inline rather than in libs.versions.toml because the version catalog is
// itself defined by this file; it is not available in the settings plugins block.
// Versions before 1.0.0 only support Gradle up to 8.14.x, so on Gradle 9.7.1 the 1.x
// line is required, not merely preferred.  (verified 2026-09-06)
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

// Single Gradle build rooted at the repo, with the backend as a subproject.
// The frontend stays outside Gradle — it has its own npm toolchain and coupling the
// two builds buys nothing.
rootProject.name = "chess-platform"

include("backend")
