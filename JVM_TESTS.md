# JVM test runtime

Install a JDK 21 before running JVM tests. The test tasks select that JDK through Gradle toolchains, even when Gradle itself runs on JDK 17. A missing JDK is a configuration error; it must not turn into skipped tests. Application bytecode targets are unchanged.

If Gradle does not discover an installed JDK (Homebrew JDKs without a macOS registration are one example), set `TUVORA_TEST_JAVA_HOME` to its home, for example `export TUVORA_TEST_JAVA_HOME=/opt/homebrew/opt/openjdk@21`. This is a discovery hint for tests; `JAVA_HOME` may stay on JDK 17 for native builds. Gradle does not silently download a JDK.

Test workers use a 4 GiB maximum heap and one fork. Gradle and Kotlin daemon heap settings do not set the heap of these separate worker processes. Failed and skipped tests are printed in the console; inspect the XML/HTML reports for totals.

Run builds serially on memory-constrained machines. In the parent workspace use `scripts/gradle-lock.sh <label> <command...>`.

Run from the repository root:

```bash
./gradlew :composeApp:desktopTest
```

The QuickJS engine tests deliberately bypass the production search-pause admission gate. Their fixture starts with discovery paused, reproducing the state left by player tests; each execution must still return real scraper results. JUnit bounds each test to 30 seconds. Production search pausing and plugin engine code are unchanged.
