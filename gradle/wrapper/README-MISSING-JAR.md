# gradle-wrapper.jar is not in this directory

`gradlew` and `gradle-wrapper.properties` are here. **`gradle-wrapper.jar` is not**, and
it cannot be — it is a ~60 KB binary that can only come from a real Gradle distribution,
and fabricating or scavenging one would hand you an executable of unverified provenance
in a project that checksum-validates this exact file in CI.

Get it once, from your own machine:

```bash
./gradlew wrapper          # regenerates all four files at the pinned version
```

Or copy it from any Gradle project you already have.

Then commit all four:

```bash
git add gradlew gradlew.bat gradle/wrapper/gradle-wrapper.jar gradle/wrapper/gradle-wrapper.properties
git update-index --chmod=+x gradlew    # git does not always preserve the executable bit
git rm --cached gradle/wrapper/README-MISSING-JAR.md   # delete this file once done
```

After that the wrapper lives in your git history and never has to travel again.

`gradlew.bat` is also absent — it is only needed on Windows, and writing it from memory
is the failure mode this project has already hit three times with version numbers.
`./gradlew wrapper` generates it.
