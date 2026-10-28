# FastScanner - ProGuard rules
# Copyright (C) 2026 - License: GPL-3.0
# Author: https://github.com/Leproide

# OkHttp / Okio
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# WorkManager workers are instantiated by reflection
-keep class * extends androidx.work.ListenableWorker { <init>(...); }

# smbj (SMB2/3) + dependencies: reflection on packets / event bus handlers
-keep class com.hierynomus.** { *; }
-keep class net.engio.mbassy.** { *; }
-keep class org.bouncycastle.** { *; }
-dontwarn com.hierynomus.**
-dontwarn net.engio.mbassy.**
-dontwarn org.ietf.jgss.**
-dontwarn javax.security.auth.kerberos.**
-dontwarn javax.el.**
-dontwarn org.slf4j.**
-dontwarn javax.naming.**
