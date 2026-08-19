# Build & Optimize Guide

How to build a fast, optimized, installable APK of each app and get it onto your
ROG Phone 6. This is for your own sideloading, no Play Store / F-Droid involved.

Applies to all seven apps, same steps for each:
- `BasicSms/`      → `dev.montb.basicsms`
- `BasicPhone/`    → `dev.montb.basicphone`
- `BasicContacts/` → `dev.montb.basiccontacts`
- `BasicKeyboard/` → `dev.montb.basickeyboard`
- `BasicClock/`    → `dev.montb.basicclock`
- `BasicMonitor/`  → `dev.montb.basicmonitor`
- `BasicCamera/`   → `dev.montb.basiccamera`

---

## 0. The one toolchain rule

Build with **JDK 17 or 21**. Your JDK 22/23/24/25/26 are **too new** for the Android
Gradle Plugin and will fail the build.

- **Easiest:** open the project in **Android Studio Panda**: it uses its own bundled
  JDK and ignores your system Java entirely.
- **Command line:** point `JAVA_HOME` at JDK 21 first:
  ```bash
  export JAVA_HOME=/usr/lib/jvm/jdk-21-oracle-x64
  ```

---

## 1. Which build do I want?

| Build | Command | Optimized? | Signing | Use for |
|-------|---------|-----------|---------|---------|
| **Debug** | `./gradlew assembleDebug` | ❌ No R8 | auto (debug key) | quick testing |
| **Release** | `./gradlew assembleRelease` | ✅ R8 + resource shrink | you must sign it | daily use |

R8 (minify + shrink) only runs on the **release** build; that's the "optimized
version." It needs a signing key, covered in step 3.

---

## 2. Quick test build (no signing needed)

From inside a project folder (e.g. `BasicSms/`):
```bash
export JAVA_HOME=/usr/lib/jvm/jdk-21-oracle-x64
./gradlew assembleDebug
```
Output: `app/build/outputs/apk/debug/app-debug.apk`

> First run downloads Gradle + dependencies (a few minutes). If `./gradlew` is
> missing, open the project once in Android Studio (it generates the wrapper), or
> install Gradle and run `gradle wrapper --gradle-version 8.11.1`.

Install it (see step 5).

---

## 3. Optimized RELEASE build (R8) with signing

### 3a. Create a signing key (once, reuse for both apps)
`keytool` ships with the JDK:
```bash
keytool -genkeypair -v \
  -keystore ~/basicapps.jks \
  -alias basic -keyalg RSA -keysize 2048 -validity 10000
```
It asks for a password and a name/org (anything is fine for personal use).
**Keep this file + passwords**: you need the same key to install updates later.

### 3b. Tell Gradle about the key
Create `keystore.properties` in the project root (next to `settings.gradle.kts`):
```properties
storeFile=/home/you/basicapps.jks
storePassword=YOUR_STORE_PASSWORD
keyAlias=basic
keyPassword=YOUR_KEY_PASSWORD
```
Then in `app/build.gradle.kts`, inside `android { }`, add a signing config and point
the release build at it:
```kotlin
    // near the top of android { }
    val keystoreProps = java.util.Properties().apply {
        val f = rootProject.file("keystore.properties")
        if (f.exists()) load(f.inputStream())
    }

    signingConfigs {
        create("release") {
            if (keystoreProps.isNotEmpty()) {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")   // <-- add this line
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
```
> Don't commit `keystore.properties` or the `.jks` to git.

### 3c. Build it
```bash
export JAVA_HOME=/usr/lib/jvm/jdk-21-oracle-x64
./gradlew assembleRelease
```
Output: `app/build/outputs/apk/release/app-release.apk` ← optimized + signed.

**GUI alternative:** Android Studio → **Build → Generate Signed App Bundle / APK →
APK** → create/select the keystore → variant **release**. Does 3a-3c for you.

---

## 4. Build the apps
Repeat from each folder:
```bash
cd "BasicSms"      && ./gradlew assembleRelease && cd ..
cd "BasicPhone"    && ./gradlew assembleRelease && cd ..
cd "BasicContacts" && ./gradlew assembleRelease && cd ..
```

---

## 5. Install on the phone

**Over wireless ADB** (you already use this for Shizuku):
```bash
adb install -r "BasicSms/app/build/outputs/apk/release/app-release.apk"
adb install -r "BasicPhone/app/build/outputs/apk/release/app-release.apk"
adb install -r "BasicContacts/app/build/outputs/apk/release/app-release.apk"
```
**Or** copy each APK to the phone and tap it (enable "install unknown apps" for your
file manager when prompted).

---

## 6. The real "compile it optimized" step (AOT) ⭐

This is the legit version of what you originally asked about. After installing, force
the runtime to **ahead-of-time compile the whole app to native ARM code** for your
CPU. This removes JIT warm-up and is what kills scroll jank:

```bash
adb shell cmd package compile -m speed -f dev.montb.basicsms
adb shell cmd package compile -m speed -f dev.montb.basicphone
adb shell cmd package compile -m speed -f dev.montb.basiccontacts
```

- `-m speed` = full AOT compile (best for a sideloaded app on a non-Play ROM, since
  the Play Store isn't there to install a Baseline Profile for you).
- `-f` = force re-compile now.
- Undo with: `adb shell cmd package compile --reset <package>`

> This is the right answer for your CN ROM (no Google Play), it gives you the AOT
> speedup manually. There is **no** "ARMv9/assembly" switch beyond this; ART already
> targets your exact CPU.

**Optional / advanced:** the `androidx.baselineprofile` Gradle plugin can bake a
profile into the APK so hot paths are pre-compiled automatically. It needs a separate
benchmark module + a connected device to generate the profile; that's more setup for little
extra gain over `-m speed` on a personal sideload. Ask if you want it wired up.

---

## 7. After install, device setup (do this once)

**BasicSms (reliability on the aggressive CN ROM):**
1. Open it → tap **Set as default SMS app** → grant the permission prompts
   (incl. **Phone** for SIM info).
2. Settings → Apps → Basic SMS → **Battery → Unrestricted**.
3. ASUS **Mobile Manager → Auto-start manager** → allow Basic SMS.
4. Lock it in **Recents** (pull the card down → padlock).

**BasicPhone (for the in-call screen / SIM dialing):**
1. Open it → ⋮ menu → **Set as default phone app** (needed for the custom in-call
   screen with mute/speaker/Bluetooth).
2. Grant Phone/Call-log permissions.
3. ⋮ → **Voicemail number…** → enter your carrier's voicemail number.

**BasicContacts (no default-app role needed, it just edits system contacts):**
1. Open it → **Allow contacts access** (READ + WRITE_CONTACTS).
2. That's it. Edits sync to BasicSms/BasicPhone since all three share the system
   contacts provider. Import/export vCard via the ⋮ menu (uses the file picker).

---

## 8. Updating later
Rebuild with the **same keystore**, `adb install -r …`, then re-run the step-6
`compile -m speed` commands. Done.
