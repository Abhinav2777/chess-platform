// Root build. Deliberately near-empty — all application code lives in :backend.
//
// Its one job is to make the Gradle version itself a pinned, verified dependency rather
// than an ambient property of whoever last ran `gradle wrapper`.

tasks.wrapper {
    // Sourced from gradle/libs.versions.toml so the version lives in exactly one place.
    // Running `./gradlew wrapper` regenerates the wrapper at the pinned version without
    // anyone having to remember a --gradle-version flag.
    gradleVersion = libs.versions.gradle.get()

    // BIN over ALL: the `all` distribution bundles Gradle sources and docs for IDE
    // completion, which modern IntelliJ resolves without them. BIN is a much smaller
    // CI cache for no practical loss.
    distributionType = Wrapper.DistributionType.BIN
}

// ---------------------------------------------------------------------------
// Drift guard.
//
// The catalog and gradle-wrapper.properties can disagree — someone runs
// `./gradlew wrapper --gradle-version X` with an explicit flag, or hand-edits the
// properties file. When they disagree the *wrapper* wins silently, so the pinned
// version in the catalog quietly becomes a comment rather than a constraint.
//
// This is the same failure this project already suffered once: a version that was
// documented but never actually enforced. Ten lines to make the pin real.
// ---------------------------------------------------------------------------
tasks.register("verifyGradleVersion") {
    group = "verification"
    description = "Fails if the committed wrapper does not match the version pinned in libs.versions.toml."

    val expected = libs.versions.gradle.get()
    val wrapperProps = layout.projectDirectory.file("gradle/wrapper/gradle-wrapper.properties")

    doLast {
        val file = wrapperProps.asFile
        if (!file.exists()) {
            throw GradleException(
                "gradle/wrapper/gradle-wrapper.properties is missing. " +
                "Bootstrap the wrapper first — see SETUP.md."
            )
        }

        val distributionUrl = java.util.Properties()
            .apply { file.inputStream().use { load(it) } }
            .getProperty("distributionUrl")
            ?: throw GradleException("distributionUrl not set in gradle-wrapper.properties")

        if (!distributionUrl.contains("gradle-$expected-")) {
            throw GradleException(
                """
                Gradle version drift.
                  pinned in libs.versions.toml : $expected
                  wrapper distributionUrl      : $distributionUrl

                Fix by regenerating the wrapper from the pin:
                  ./gradlew wrapper
                Or, if the new version is intended, update `gradle` in
                gradle/libs.versions.toml (with today's verification date) first.
                """.trimIndent()
            )
        }

        logger.lifecycle("Gradle wrapper matches pinned version $expected")
    }
}
