# Device Policy Controller

A minimal native Android Device Policy Controller (DPC) for organization-owned devices. The app locks its policy controls behind a locally stored controller password and lets an authorized administrator block or allow configuration of:

- Private DNS (`no_config_private_dns`)
- Wi-Fi settings (`no_config_wifi`)
- Factory reset (`no_factory_reset`)
- Physical external storage media, including USB drives and SD cards (`no_physical_media`)

## Provisioning

Restrictions are enforceable only when the app is a **device owner**. For a test-only, freshly reset device/emulator with USB debugging enabled:

```bash
adb shell dpm set-device-owner com.example.devicepolicycontroller/.PolicyAdminReceiver
```

Install the debug APK first, then open the app and create the controller password. Production deployments should use Android Enterprise provisioning (QR, zero-touch, or an EMM), not the test command above.

## Build

```bash
./gradlew clean assembleDebug
```

If Android Studio had previously generated template `values-night` resources, use **Build > Clean Project** after syncing. The project includes the Material resources those templates reference, plus a native night theme.

The app targets Android 15 (API 35), supports Android 8.0+ (API 26), compiles both Java and Kotlin sources for JVM 17, and enables AndroidX for its Material Components dependency. Policy behavior can vary with Android version, management mode, and OEM implementation.

## Approved external APK installers

Turn on **External APK installs** to prevent users from enabling arbitrary unknown sources. To allow a trusted distribution app, unlock the controller, enter that app's package name under **Approved APK installer apps**, and select **Allow**. The trusted app must send an **explicit** broadcast to `ApprovedApkInstallReceiver`, set the APK `content://` URI as its data, and include `FLAG_GRANT_READ_URI_PERMISSION`:

```kotlin
val request = Intent("com.example.devicepolicycontroller.REQUEST_APPROVED_APK_INSTALL")
    .setComponent(ComponentName(
        "com.example.devicepolicycontroller",
        "com.example.devicepolicycontroller.ApprovedApkInstallReceiver"
    ))
    .setData(apkContentUri)
    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
sendBroadcast(request)
```

The DPC checks the Android-authenticated sending package against its allowlist before opening the URI and creating a `PackageInstaller` session. Apps not on the list cannot use this installation path. APKs must still satisfy normal Android package-signature and compatibility checks; Android versions or OEMs may require a confirmation UI for some installs.

## External web links

The DPC blocks external `http` and `https` links by default once it is unlocked as device owner. An administrator can enter the package name of one installed browser or link-handler app and select **Allow**. The DPC sets that app as the persistent preferred handler for external web links; selecting **Block all external links** routes those intents to a blocking activity instead. This controls Android external-link intents. It cannot prevent an app from rendering network content inside its own in-app WebView.
