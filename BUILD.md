# Build And Install

## Prerequisites

- JDK 17
- Android SDK with platform/build tools installed
- Gradle
- ADB with USB debugging enabled on the phone

This project was built with:

```text
compileSdk 35
minSdk 26
targetSdk 35
Gradle 9.5.0
Android Gradle Plugin 8.7.3
```

## Build

From the repo root:

```powershell
cd android_app
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-17.0.17.10-hotspot'
$env:ANDROID_HOME='C:\Android\android-sdk'
$env:ANDROID_SDK_ROOT='C:\Android\android-sdk'
$env:PATH="$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:PATH"
gradle assembleDebug
```

The debug APK is created at:

```text
android_app/app/build/outputs/apk/debug/app-debug.apk
```

## Signed Release Build

Release builds are signed with the private keystore referenced by:

```text
android_app/keystore.properties
```

That file and the keystore under `android_app/release/` are intentionally ignored by git.
Use `android_app/keystore.properties.example` as the template if the local signing files need to be recreated.

Build a signed release APK locally:

```powershell
cd android_app
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-17.0.17.10-hotspot'
$env:ANDROID_HOME='C:\Android\android-sdk'
$env:ANDROID_SDK_ROOT='C:\Android\android-sdk'
$env:PATH="$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:PATH"
gradle assembleRelease
```

Gradle creates the signed release APK at:

```text
android_app/app/build/outputs/apk/release/app-release.apk
```

GitHub Releases publish the APK with a descriptive filename:

```text
ExpenseCapture-v<major>.<minor>.<patch>.apk
```

## GitHub Actions Releases

`.github/workflows/android-release.yml` builds a signed release APK on pushed version tags matching:

```text
v<major>.<minor>.<patch>
```

The workflow uses the same signing key as local release builds via these GitHub Actions secrets:

```text
ANDROID_RELEASE_KEYSTORE_BASE64
ANDROID_RELEASE_STORE_PASSWORD
ANDROID_RELEASE_KEY_ALIAS
ANDROID_RELEASE_KEY_PASSWORD
```

On a tag push, the workflow uploads the APK as a run artifact and attaches it to a GitHub Release with a filename such as `ExpenseCapture-v1.0.0.apk`.

## Release Script

The repo includes a release script:

```text
bash scripts/release.sh <patch|minor|major> --push
```

The script bumps `versionName` and `versionCode`, updates `CHANGELOG.md`, creates an annotated tag, and optionally pushes the release commit and tag.

Before running it, update docs for any unreleased feature, bug fix, behavior, setup, or release-process changes. After pushing the release tag, check the `Android Release` GitHub Actions workflow and the generated GitHub Release.

## Install

With the phone connected:

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

Launch:

```powershell
adb shell monkey -p dev.fanis.expensenotification 1
```

### Switching between debug and release builds

Debug builds are signed with the local Android debug keystore; release builds (local and GitHub Actions) are signed with the private release keystore. Android refuses to upgrade between mismatched signatures and the package installer reports `App not installed` (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`).

When swapping a debug install for a release APK, or vice versa, uninstall first:

```powershell
adb uninstall dev.fanis.expensenotification
```

Releases from GitHub all share the same signing key, so an existing GitHub-installed copy upgrades cleanly to the next release without uninstalling.

## Enable Services With ADB

These commands are useful during development. On a normal phone, the same settings can be enabled from the app's setup buttons.

```powershell
adb shell cmd notification allow_listener dev.fanis.expensenotification/dev.fanis.expensenotification.ExpenseNotificationListener
```

For Accessibility, preserve existing enabled services and append the helper:

```powershell
$svc='dev.fanis.expensenotification/dev.fanis.expensenotification.ExpenseEntryAccessibilityService'
$existing=adb shell settings get secure enabled_accessibility_services
if($existing -eq 'null' -or [string]::IsNullOrWhiteSpace($existing)){
  $new=$svc
} elseif($existing -like "*$svc*"){
  $new=$existing
} else {
  $new="$existing`:$svc"
}
adb shell settings put secure enabled_accessibility_services $new
adb shell settings put secure accessibility_enabled 1
```

## Clean Rebuildable Files

Safe to delete:

```text
android_app/.gradle/
android_app/app/build/
```

They are recreated by Gradle.
