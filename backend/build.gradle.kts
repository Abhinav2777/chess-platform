plugins {
    java
    alias(libs.plugins.springBoot)
    alias(libs.plugins.springDepMgmt)
}

group = "com.chessplatform"
version = "0.1.0-SNAPSHOT"

java {
    // A toolchain, not `sourceCompatibility`. Gradle will download and use JDK 25
    // regardless of which JDK is running Gradle itself, so CI and every developer
    // machine compile against exactly the same JDK. Without this, "works on my
    // machine" is a build-level problem, not just a runtime one.
    toolchain {
        languageVersion = JavaLanguageVersion.of(libs.versions.java.get().toInt())
    }
}

repositories {
    mavenCentral()

    // chesslib is distributed through JitPack, not Maven Central. JitPack builds
    // artifacts on demand from GitHub tags, so it is a build service rather than a
    // curated registry — a weaker supply-chain position than Central, and a deliberate
    // trade-off recorded in ADR-012.
    //
    // `exclusiveContent` is the mitigation and is not optional:
    //   - JitPack is consulted for `com.github.bhlangonijr` and NOTHING else. An
    //     unfiltered JitPack entry means every dependency miss gets retried against it,
    //     which is both slow and a dependency-confusion vector: anyone can create a
    //     GitHub repo whose coordinate shadows an internal artifact name.
    //   - Conversely, `com.github.bhlangonijr` is fetched ONLY from JitPack, so a
    //     same-named artifact appearing on another repository cannot substitute itself.
    exclusiveContent {
        forRepository {
            maven {
                name = "jitpack"
                url = uri("https://jitpack.io")
            }
        }
        filter {
            includeGroup("com.github.bhlangonijr")
        }
    }
}

dependencies {
    implementation(libs.spring.web)
    implementation(libs.spring.websocket)
    implementation(libs.spring.jpa)
    implementation(libs.spring.redis)
    implementation(libs.spring.security)
    implementation(libs.spring.security.jose)
    implementation(libs.spring.validation)
    implementation(libs.spring.actuator)

    implementation(libs.flyway.starter)    // the STARTER — see libs.versions.toml
    implementation(libs.flyway.postgres)   // Flyway 10+ needs the per-database module
    runtimeOnly(libs.postgresql)

    implementation(libs.bundles.observability)
    implementation(libs.chesslib)

    testImplementation(libs.spring.test)
    testImplementation(libs.spring.webmvc.test)
    testImplementation(libs.spring.security.test)
    testImplementation(libs.archunit)
    testImplementation(libs.assertj)
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgres)
}

// ---------------------------------------------------------------------------
// Test task split.
//
// `test` is fast and hermetic — unit tests and ArchUnit, no Docker. It runs on every
// save and every push. `integrationTest` starts Testcontainers and is slow.
//
// Splitting them is not tidiness. A test suite that takes four minutes stops being run
// locally, which is how a fast feedback loop dies. `check` depends on both, so CI still
// runs everything.
// ---------------------------------------------------------------------------
testing {
    suites {
        val test by getting(JvmTestSuite::class) {
            useJUnitJupiter()
            targets.all {
                testTask.configure {
                    // Anything under src/test/java/**/integration/ belongs to the other suite
                    exclude("**/integration/**")
                }
            }
        }
    }
}

val integrationTest by tasks.registering(Test::class) {
    description = "Runs integration tests (requires Docker)."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    include("**/integration/**")
    shouldRunAfter(tasks.test)
    // Testcontainers reuse cuts repeated container startup. Requires
    // `testcontainers.reuse.enable=true` in ~/.testcontainers.properties.
    systemProperty("testcontainers.reuse.enable", "true")
}

tasks.check {
    dependsOn(integrationTest)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")

        // FULL, not the SHORT default. SHORT prints
        //   "Caused by: SchemaManagementException at AbstractSchemaValidator.java:128"
        // and throws away the message — which is the only part that identifies the
        // problem. Hibernate's validation errors name the exact column and both types;
        // without FULL + showCauses you get a class name and a line number and have to
        // go digging in the HTML report.
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showExceptions = true
        showCauses = true
        showStackTraces = true
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.addAll(
        listOf(
            // `-serial` is excluded deliberately. RuntimeException is Serializable, so
            // every exception class without a serialVersionUID trips this lint — and
            // under -Werror that fails the build. Our exceptions are never serialised
            // (no RMI, no HTTP session replication, no distributed cache holding them),
            // so a version UID would be four lines of ceremony guarding nothing.
            //
            // `-processing` is excluded because it warns when no annotation processor
            // claims an annotation, which is noise in a project that has none.
            "-Xlint:all,-serial,-processing",
            "-Werror",       // warnings fail the build; they are only ever ignored otherwise
            "-parameters"    // Spring needs parameter names for constructor binding
        )
    )
}

tasks.bootJar {
    archiveFileName = "chess-platform.jar"
}
