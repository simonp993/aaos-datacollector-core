---
applyTo: "**/*.kt,**/*.kts,**/build.gradle.kts"
---

# Kotlin & Android Conventions

## Build Toolchain

- Java 21 toolchain (`jvmToolchain(21)`)
- compileSdk 36, minSdk 33 (Android 13+), targetSdk 35
- Build flavors: `platform` (mib4, nextgen) × `datasource` (mock, real)
- Convention plugins: `datacollector.android-app`, `sportapps.android-library`, `sportapps.kotlin-library`

## Code Style

- Formatting enforced by **Spotless + ktlint** — run `./gradlew spotlessCheck`
- **Trailing commas required** on both call sites and declaration sites
- Max line length: **120 characters**
- Wildcard imports disallowed
- Max return statements per function: **4**

## Static Analysis

- **Detekt** with zero-tolerance (`maxIssues = 0`), config at `config/detekt/detekt.yml`
- Run `./gradlew detekt` to check

## Module Architecture

- Core modules (`core-*`) provide shared functionality
- `vehicle-*` modules abstract vehicle platform interaction
- Vehicle modules must expose only interfaces publicly

## DI Patterns

- **Hilt** — `@HiltAndroidApp`, `@AndroidEntryPoint`, `@Inject`
- Flavor-specific modules in `app/src/mock/` and `app/src/real/`
- Bindings identical across flavors in `app/src/main/`
- Prefer `@Binds` for interface-to-implementation; `@Provides` only when construction logic needed

## Testing

- **JUnit 5** is default for all tests
- Use `mockk` for mocking, `turbine` for Flow testing, `runTest {}` for coroutines
- **JaCoCo ≥80% line coverage** per module
- Run: `./gradlew testMib4MockDebugUnitTest`
