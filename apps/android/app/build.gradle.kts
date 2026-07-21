// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Release signing material is loaded from `apps/android/keystore.properties`
// (gitignored) or, failing that, from environment variables — so the keystore
// and its passwords never live in the source tree or in this build file. When
// none are provided (debug builds, CI unit tests, contributor checkouts) the
// release build is left UNSIGNED and must be signed out-of-band with apksigner.
// See docs/RELEASING_ANDROID.md.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}
fun signingParam(propKey: String, envKey: String): String? =
    keystoreProperties.getProperty(propKey)
        ?: providers.environmentVariable(envKey).orNull
val releaseStoreFilePath = signingParam("storeFile", "ANDROID_KEYSTORE_FILE")
val releaseStorePassword = signingParam("storePassword", "ANDROID_KEYSTORE_PASSWORD")
val releaseKeyAlias = signingParam("keyAlias", "ANDROID_KEY_ALIAS")
val releaseKeyPassword = signingParam("keyPassword", "ANDROID_KEY_PASSWORD")
val releaseSigningParams = listOf(
    releaseStoreFilePath, releaseStorePassword, releaseKeyAlias, releaseKeyPassword,
)
// Sign only when ALL four values are present — gating on the whole set, not just
// storeFile: a keystore path with a missing password/alias would otherwise reach
// the signing config as nulls and fail the build obscurely.
val hasReleaseSigning = releaseSigningParams.all { !it.isNullOrBlank() }
// A partial config is almost always a mistake — fail loudly rather than silently
// falling back to an unsigned build the operator believed was signed.
if (!hasReleaseSigning && releaseSigningParams.any { !it.isNullOrBlank() }) {
    throw GradleException(
        "Incomplete release signing config: provide all of storeFile, storePassword, keyAlias, " +
            "keyPassword (via keystore.properties or the ANDROID_KEYSTORE_FILE / " +
            "ANDROID_KEYSTORE_PASSWORD / ANDROID_KEY_ALIAS / ANDROID_KEY_PASSWORD env vars), or none. " +
            "See docs/RELEASING_ANDROID.md.",
    )
}

android {
    namespace = "com.zitrone.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.zitrone.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 14
        versionName = "0.8.5-beta"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Relay onion address — NEVER published or committed. Injected from the
        // build environment so the relay hidden service stays out of the source
        // tree. Empty string when unset (clearnet/dev builds).
        buildConfigField(
            "String",
            "RELAY_ONION_ADDRESS",
            // providers.environmentVariable (not System.getenv) so Gradle tracks
            // the env var as a build input and the configuration cache stays valid.
            "\"${providers.environmentVariable("RELAY_ONION_ADDRESS").orNull ?: ""}\""
        )

        // Relay I2P destination (the .b32.i2p address) — same rationale as the
        // onion address above: NEVER published or committed, injected from the
        // build environment via providers.environmentVariable so Gradle tracks it
        // as a build input. Empty string when unset, in which case I2P routing is
        // impossible and the transport chain falls through to Tor/clearnet (see
        // net/TransportResolver.kt — mirrors the desktop i2p.rs RELAY_I2P_DEST).
        buildConfigField(
            "String",
            "RELAY_I2P_DEST",
            "\"${providers.environmentVariable("RELAY_I2P_DEST").orNull ?: ""}\""
        )

        // Host of the local I2P router's HTTP proxy (the official I2P app's default
        // 127.0.0.1:4444). Env-overridable dev/emulator escape hatch: an emulator
        // reaches a host-side router at 10.0.2.2 rather than 127.0.0.1. The port
        // (4444) is fixed in i2p/I2pIntegration.kt.
        buildConfigField(
            "String",
            "I2P_PROXY_HOST",
            "\"${providers.environmentVariable("I2P_PROXY_HOST").orNull ?: "127.0.0.1"}\""
        )
    }

    signingConfigs {
        // Declared only when a full keystore config was provided; otherwise no
        // signing config exists and assembleRelease yields an unsigned apk.
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFilePath!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Sign only when a keystore was supplied (local keystore.properties
            // or CI secrets). A keyless checkout still configures and builds —
            // the release apk is just unsigned. See docs/RELEASING_ANDROID.md.
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            // No special debug behavior: FLAG_SECURE, no-logging and all other
            // security rules apply identically in debug builds.
        }
    }

    compileOptions {
        // Required by org.signal:libsignal-android, which uses APIs that must be
        // desugared to run on minSdk 26.
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        // Required for the RELAY_ONION_ADDRESS buildConfigField above.
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = libs.versions.composeCompiler.get()
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Core library desugaring runtime (required by libsignal-android).
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    // Signal Protocol (Double Ratchet + X3DH)
    implementation(libs.libsignal.android)
    implementation(libs.libsignal.client)

    // libsodium binding for the lemon-drop one-shot responder ONLY (sealed
    // box, raw X25519, Ed25519→Curve25519) — see crypto/LemonDropSodiumOps.kt.
    // Prebuilt .so per ABI via JNA; no NDK build step. The JNA dependency must
    // be the @aar (the jar variant lazysodium-android declares transitively
    // has no Android natives).
    implementation(libs.lazysodium.android) {
        // lazysodium's POM pulls the JAR variant of JNA (desktop natives only);
        // the @aar below replaces it — both at once is a duplicate-class error.
        exclude(group = "net.java.dev.jna", module = "jna")
    }
    implementation("${libs.jna.get()}@aar")

    // Networking — WebSocket + certificate pinning
    implementation(libs.okhttp)

    // Encrypted local storage + biometrics
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.biometric)
    // biometric 1.1.0 pulls fragment 1.2.5, which predates ActivityResult support
    // for FragmentActivity; pin a current fragment so registerForActivityResult
    // works correctly (and satisfies lintVitalRelease).
    implementation(libs.androidx.fragment)

    // QR codes for key verification + contact exchange (pure-Java, offline)
    implementation(libs.zxing.core)
    // In-app QR scanner (camera). FOSS, no Play Services — F-Droid-friendly.
    // Keeps the explicit zxing-core pin above (this pulls an older core).
    implementation(libs.zxing.android.embedded)

    // Unit tests (pure JVM logic only)
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    testImplementation(libs.kotlinx.coroutines.test)
    // Same libsodium C functions as lazysodium-android, bound for the host
    // JVM — lets the cross-stack lemon-drop round-trip run as a plain unit
    // test through the production LemonDropSodiumOps adapter.
    testImplementation(libs.lazysodium.java)
}
