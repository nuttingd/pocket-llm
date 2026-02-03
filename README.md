# Template Android App

Minimal, runnable Jetpack Compose app with Material 3, CI/CD, and semantic-release. Clone and build to get a working app immediately.

## Quick start

```sh
./gradlew assembleDebug
```

The Gradle wrapper will automatically download Gradle 9.1.0 and JDK 21 (via toolchains) if they aren't already installed. Any JDK 17+ on `JAVA_HOME` is sufficient to launch Gradle.

## What's included

- Jetpack Compose with Material 3 and dynamic color (light/dark)
- Edge-to-edge display
- About dialog (app icon, version from `BuildConfig`, description, GitHub link)
- Adaptive launcher icon with monochrome layer
- Release signing via `keystore.properties` or environment variables
- GitHub Actions CI (PR checks) and release (semantic-release on main)
- Placeholder unit test

## Customizing for a new app

After cloning this template, replace the placeholder values below with your own.

### 1. Package name

Replace `dev.nutting.template` with your package name (e.g. `com.example.myapp`).

**Files:**

| File | What to change |
|------|---------------|
| `app/build.gradle.kts` | `namespace` and `applicationId` |
| `app/src/main/java/dev/nutting/template/MainActivity.kt` | `package` declaration and `import` |
| `app/src/main/java/dev/nutting/template/ui/theme/Theme.kt` | `package` declaration |
| `app/src/test/java/dev/nutting/template/ExampleUnitTest.kt` | `package` declaration |
| `CLAUDE.md` | Package reference in docs |

**Directories** — rename to match your package:

```
app/src/main/java/dev/nutting/template/  →  app/src/main/java/com/example/myapp/
app/src/test/java/dev/nutting/template/  →  app/src/test/java/com/example/myapp/
```

### 2. App name

Replace `Template App` with your app's display name.

| File | What to change |
|------|---------------|
| `app/src/main/res/values/strings.xml` | `app_name` |
| `.releaserc.json` | `label` field |

### 3. Project name

Replace `template-app` with your project name (used in Gradle and release artifacts).

| File | What to change |
|------|---------------|
| `settings.gradle.kts` | `rootProject.name` |
| `.releaserc.json` | `name` field (APK filename) |
| `.github/workflows/release.yml` | Keystore filename (`template-app.jks`) on lines with `base64 -d >` and `RELEASE_STORE_FILE` |

### 4. Theme name

Replace `Template` with your app name in theme identifiers.

| File | What to change |
|------|---------------|
| `app/src/main/res/values/themes.xml` | Style name `Theme.Template` |
| `app/src/main/AndroidManifest.xml` | `android:theme` reference |
| `app/src/main/java/dev/nutting/template/ui/theme/Theme.kt` | `TemplateTheme` function name |
| `app/src/main/java/dev/nutting/template/MainActivity.kt` | `TemplateTheme` call site |

### 5. About dialog content

| File | What to change |
|------|---------------|
| `app/src/main/res/values/strings.xml` | `app_description` and `app_github_url` |

### 6. Launcher icon

Replace the placeholder vector drawables with your own:

| File | Purpose |
|------|---------|
| `app/src/main/res/drawable/ic_launcher_background.xml` | Adaptive icon background |
| `app/src/main/res/drawable/ic_launcher_foreground.xml` | Adaptive icon foreground + monochrome |

### 7. CLAUDE.md

Update `CLAUDE.md` with your project's actual description, architecture, and conventions.

## Release signing

### Local development

Create `keystore.properties` in the project root (gitignored):

```properties
storeFile=release.jks
storePassword=your-store-password
keyAlias=your-key-alias
keyPassword=your-key-password
```

Without this file, release builds fall back to the debug signing key.

### CI (GitHub Actions)

Set these repository secrets:

| Secret | Value |
|--------|-------|
| `RELEASE_KEYSTORE_BASE64` | Base64-encoded keystore (`base64 < release.jks`) |
| `RELEASE_KEYSTORE_PASSWORD` | Keystore password |
| `RELEASE_KEY_ALIAS` | Key alias |
| `RELEASE_KEY_PASSWORD` | Key password |

CI will fail if release signing secrets are missing (intentional — prevents unsigned release builds).

## Build commands

```sh
./gradlew assembleDebug          # debug APK
./gradlew assembleRelease        # signed release APK
./gradlew test                   # unit tests
./gradlew lintDebug              # lint checks
./gradlew connectedAndroidTest   # instrumented tests (needs device/emulator)
```
