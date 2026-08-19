# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this repo produces

A [Morphe](https://morphe.software) patch bundle (`patches/build/libs/patches-<version>.mpp`) that patches the X/Twitter and Instagram Android apps. Morphe Manager consumes it by reading `patches-bundle.json` from the repository's raw `main` branch (`dev` when a source has pre-releases enabled), then downloading the `.mpp` asset named in `download_url`.

This is a fork of `crimera/piko`. Releases are published to `Alpha3288/piko`; `origin` uses the `github-personal` SSH host alias, and `upstream` points at `crimera/piko` with `tagOpt = --no-tags`.

## Commands

```sh
./gradlew buildAndroid          # build the .mpp bundle (the only real verification loop)
./gradlew checkStringResources  # validate printf-style formatting in addresources strings
./gradlew generatePatchesList   # regenerate patches-list.json (release.yml does this; rarely run by hand)
```

There is no test source set anywhere in the project. A change is verified by building the bundle and applying it to a real APKM with [Morphe Desktop](https://github.com/MorpheApp/morphe-desktop) or the Morphe CLI.

### Local build prerequisites

The `app.morphe.patches` Gradle plugin and the patcher libraries come from GitHub Packages (`maven.pkg.github.com/MorpheApp/registry`), which requires authentication even though it is public. Credentials resolve from the `gpr.user` / `gpr.key` Gradle properties, falling back to `GITHUB_ACTOR` / `GITHUB_TOKEN`. `.envrc` exports those two from the active `gh` account, but a default `gh` token lacks the `read:packages` scope and the build then fails with `Plugin [id: 'app.morphe.patches'] was not found`. Fix it with `gh auth refresh -s read:packages`, or put a classic PAT in `~/.gradle/gradle.properties`.

`flake.nix` pins a working JDK and Android SDK, but nix and direnv are not installed everywhere, in which case `.envrc` never runs and the toolchain has to be supplied per invocation:

```sh
JAVA_HOME=<jdk17> GITHUB_ACTOR=$(gh api user --jq .login) GITHUB_TOKEN=$(gh auth token) ./gradlew buildAndroid
```

- **Build with JDK 17.** On JDK 22 the build dies in AGP's `JdkImageTransform` because `jlink` rejects the arguments AGP passes. CI uses temurin 17.
- **The Android SDK needs `build-tools;36.0.0` and `platforms;android-36`** with licences accepted. An `ANDROID_HOME` pointing at a root-owned stub cannot be self-installed into, so `sdk.dir` in `local.properties` (gitignored, and it takes precedence over the environment) is the way to point at a usable SDK. Once one is writable, AGP pulls in the extra platforms it needs, such as `android-34`, by itself.

## Architecture

Two halves that are compiled separately and only meet at runtime inside the patched app:

- **`patches/`** - Kotlin patches run by the patcher. They match code in the target APK with `Fingerprint`s and inject smali.
- **`extensions/{twitter,instagram,shared}/`** - Java code compiled into the patched APK. This is where UI, preferences and business logic live. Each has a `stub/` sibling holding `compileOnly` declarations of target-app classes so extension code can reference them without the real APK.

Neither `settings.gradle.kts` nor `build.gradle.kts` lists these modules: the `app.morphe.patches` settings plugin discovers `patches` and every `extensions/**` directory, and applies `defaultNamespace = "app.morphe.extension"`.

### Anatomy of a patch

```kotlin
@Suppress("unused")                       // patches are found by reflection; the suppression is load-bearing
val disableAdsPatch = bytecodePatch(name = "Disable ads", default = true) {
    compatibleWith(COMPATIBILITY_INSTAGRAM)
    dependsOn(settingsPatch, hookFlagsPatch)
    execute { /* Fingerprint.method.addInstructions(...) */ }
}
```

- **Supported app versions live in `patches/src/main/kotlin/app/crimera/patches/{twitter,instagram}/utils/Constants.kt`** (`COMPATIBILITY_X`, `COMPATIBILITY_INSTAGRAM`). A version bump is an edit to the `AppTarget` list there, nothing else. Instagram targets also pin per-ABI `versionCodes`.
- The same `Constants.kt` holds the class descriptors patches inject calls to (`INTEGRATIONS_PACKAGE`, `PREF_DESCRIPTOR`, `UTILS_DESCRIPTOR`, ...). **These descriptors are strings: nothing checks that an injected call still matches the extension's Java signature.** Renaming or re-signaturing an extension method silently breaks every patch that calls it, and the failure only shows up at patch time or in the patched app.
- Injected smali is applied via `app.crimera.utils` helpers (`changeFirstString`, `classNameToExtension`, `methodExtractor`, ...) plus the patcher's `InstructionExtensions`.

### Adding a user-facing toggle

Three places, all required:

1. The patch calls `enableSettings("<fn>")` and, on Instagram, `addFlags("<fn>")`. These inject a call into the extension's `SettingsStatus` / `HookFlags` loader so the app knows the patch was applied.
2. The extension's `SettingsStatus` / `HookFlags` Java class gets the matching method, and the preference screen code gets the entry.
3. Strings go in `patches/src/main/resources/addresources/values/<app>/strings.xml`, applied by `AddResourcesPatch`.

`values-<locale>/` string files are owned by Crowdin. Do not hand-edit them; `crowdin.yml` maps source files to the Crowdin project, and both Crowdin workflows are manual-only in this fork (no Crowdin project or secrets here).

### Package naming

Kotlin patches live under `app.crimera.patches.*`, extensions under `app.morphe.extension.*` (including `app.morphe.extension.crimera.*` in the shared library), and the Instagram/X package rename patches keep `piko` in their defaults. These names are upstream identity, kept deliberately: do not mass-rename them when touching fork configuration.

## Release pipeline

Work happens on `dev`; `release.yml` publishes only from `main`.

- Commit types drive releases (`.releaserc`): `fix:` → patch, `feat:` → minor, `bump:`/`perf:` → patch, `chore:` → no release. `build(Needs bump):` is the deliberate escape hatch to force a patch release with no user-facing changelog entry.
- Pushing `dev` opens/updates a draft PR to `main` (`open_pull_request.yml`) and runs `buildAndroid` (`build_pull_request.yml`). Merge that PR with a **merge commit, never a squash**, or semantic-release loses the commit history it analyses.
- `patches-bundle.json`, `patches-list.json`, `CHANGELOG.md` and the `<!-- PATCHES_START -->` block in `README.md` are all generated during release. Never hand-edit them, and never force-push a `chore: Release ...` commit.
- Nothing signs the bundle: no `signing` plugin is applied, so no `.mpp.asc` is produced even though `patches-bundle.json` advertises a `signature_download_url`. Morphe treats that field as metadata and does not verify it.
- Tag hygiene: only push tags reachable from `main`. Upstream's in-flight `v*-dev.N` tags would collide with the tags this fork's own `dev` pre-releases need to create.
