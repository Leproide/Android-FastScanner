# WebDAV Scanner - ProGuard rules
# Copyright (C) 2026 - License: GPL-3.0
# Author: https://github.com/Leproide

# OkHttp / Okio
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# WorkManager workers are instantiated by reflection
-keep class * extends androidx.work.ListenableWorker { <init>(...); }
