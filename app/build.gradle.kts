/*
 * FastScanner - app/build.gradle.kts
 * Copyright (C) 2026
 * Author: https://github.com/Leproide
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "it.leproide.webdavscanner"
    compileSdk = 35

    defaultConfig {
        applicationId = "it.leproide.webdavscanner"
        minSdk = 24
        targetSdk = 35
        versionCode = 7
        versionName = "1.2.4"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Debug signing so the release APK is installable without a keystore.
            // Replace with your own signingConfig for distribution.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            // Duplicate metadata shipped by BouncyCastle / smbj dependencies
            excludes += setOf(
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/INDEX.LIST"
            )
        }
    }
}

// APK file name: FastScanner-<version>-<buildType>.apk
base {
    archivesName.set("FastScanner-${android.defaultConfig.versionName}")
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Same document scanner used by the original app: edge detection, crop,
    // perspective correction, filters, shadow/stain/finger removal.
    implementation("com.google.android.gms:play-services-mlkit-document-scanner:16.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // SMB2/SMB3 client (NTLMv2 auth, signing, SMB 3.x AES encryption). Pure Java, no SMB1.
    implementation("com.hierynomus:smbj:0.13.0")
    // Newer BouncyCastle than the one smbj pulls (1.75, has known CVEs): crypto for NTLM (MD4) and SMB3 AES-CMAC/GCM/CCM.
    implementation("org.bouncycastle:bcprov-jdk18on:1.80")
}
