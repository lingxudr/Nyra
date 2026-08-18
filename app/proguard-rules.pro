# Aturan R8 untuk NYRA.
#
# Yang perlu dijaga hanyalah hal-hal yang dijangkau lewat refleksi atau JNI;
# sisanya boleh dipangkas dan diaburkan.

# --- ONNX Runtime ---
# Kelas-kelas ini dipanggil balik dari kode native (JNI mencari nama persis).
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# --- OkHttp / Okio ---
# OkHttp menyebut kelas platform opsional (Conscrypt, BouncyCastle, OpenJSSE)
# yang memang tidak ada di APK ini; itu bukan kesalahan.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn okio.**

# --- commons-compress / XZ ---
# commons-compress memuat implementasi kompresi lewat refleksi, dan menyebut
# sejumlah API Java desktop (Files, Path, Memory-mapped) yang absen di Android.
-keep class org.apache.commons.compress.compressors.** { *; }
-keep class org.apache.commons.compress.archivers.** { *; }
-keep class org.tukaani.xz.** { *; }
-dontwarn org.apache.commons.compress.**
-dontwarn org.tukaani.xz.**
-dontwarn java.nio.file.**
-dontwarn java.lang.management.**
-dontwarn javax.annotation.**

# --- junrar ---
-keep class com.github.junrar.** { *; }
-dontwarn com.github.junrar.**

# --- org.json bawaan proyek ---
# Android sudah menyediakan org.json; salinan yang ikut dari dependensi
# boleh hilang, tapi jangan sampai R8 mengeluh.
-dontwarn org.json.**

# --- Aplikasi ---
# View custom di-inflate dari XML lewat nama kelas, jadi namanya harus utuh.
-keep class com.nyra.comic.PageView { *; }
# Kelas yang namanya muncul di manifest tetap dijaga otomatis oleh AGP.

# Baris nomor tetap disimpan supaya laporan crash masih bisa dibaca.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# --- slf4j ---
# junrar memakai slf4j sebagai fasad log, tetapi tidak ada implementasi
# (binding) yang dibundel - slf4j akan diam-diam memakai NOPLogger. Kelas
# binding yang tidak ada itu memang tidak diperlukan.
-dontwarn org.slf4j.**
