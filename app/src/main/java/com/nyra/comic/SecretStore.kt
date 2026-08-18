package com.nyra.comic

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Penyimpanan kunci API terenkripsi.
 *
 * MASALAH YANG DIPERBAIKI: sebelumnya kunci API ditulis apa adanya ke
 * SharedPreferences. Berkas itu memang hanya bisa dibaca oleh aplikasi ini,
 * tapi ia ikut tersalin ke cadangan otomatis Android, terbaca lewat `adb
 * backup` / `run-as` di perangkat yang bisa di-debug, dan terlihat gamblang di
 * perangkat yang sudah di-root. Kunci Gemini berbayar yang bocor artinya
 * tagihan orang lain.
 *
 * CARA KERJA: kunci AES-256-GCM dibuat di dalam Android Keystore (AndroidKeyStore
 * provider). Material kuncinya tidak pernah keluar dari sana - kita hanya bisa
 * meminta operasi enkripsi/dekripsi. Yang tersimpan di SharedPreferences
 * hanyalah ciphertext base64 dengan IV 12 byte di depannya. Kunci itu juga
 * terikat pada instalasi ini, jadi berkas prefs yang disalin ke perangkat lain
 * tidak bisa dibuka.
 *
 * KOMPATIBILITAS: KeyGenParameterSpec baru ada di API 23. minSdk proyek ini 24,
 * jadi jalur itu selalu tersedia di perangkat; cabang cadangan hanya melayani
 * lingkungan uji (Robolectric tanpa provider AndroidKeyStore). Bila apa pun
 * gagal, nilainya disimpan dengan penanda plaintext supaya aplikasi tetap jalan
 * dan pengguna tidak kehilangan kunci yang sudah diketiknya.
 */
object SecretStore {

    private const val ALIAS = "nyra_api_key_v1"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val PANJANG_IV = 12
    private const val PANJANG_TAG = 128

    /** Awalan penanda format, supaya nilai lama tanpa awalan tetap terbaca. */
    private const val TANDA_SANDI = "enc1:"
    private const val TANDA_POLOS = "raw1:"

    /**
     * Enkripsi [nilai]. Mengembalikan string siap simpan.
     *
     * String kosong dikembalikan apa adanya: tidak ada gunanya mengenkripsi
     * "tidak ada kunci", dan hasil kosong memudahkan pemeriksaan isNotBlank().
     */
    fun sandi(nilai: String): String {
        if (nilai.isEmpty()) return ""
        return try {
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.ENCRYPT_MODE, kunci())
            val iv = cipher.iv
            require(iv.size == PANJANG_IV) { "IV tidak sesuai: ${iv.size}" }
            val ct = cipher.doFinal(nilai.toByteArray(Charsets.UTF_8))
            TANDA_SANDI + Base64.encodeToString(iv + ct, Base64.NO_WRAP)
        } catch (_: Throwable) {
            // Perangkat/lingkungan tanpa AndroidKeyStore yang berfungsi.
            // Lebih baik menyimpan apa adanya daripada membuang kunci pengguna,
            // tapi tandai supaya bukaSandi() tidak salah tafsir.
            TANDA_POLOS + nilai
        }
    }

    /**
     * Kebalikan [sandi]. Nilai tanpa penanda dianggap peninggalan versi lama
     * (plaintext) dan dikembalikan apa adanya, sehingga pengguna yang naik versi
     * tidak perlu mengetik ulang kunci.
     */
    fun bukaSandi(tersimpan: String): String {
        if (tersimpan.isEmpty()) return ""
        if (tersimpan.startsWith(TANDA_POLOS)) return tersimpan.removePrefix(TANDA_POLOS)
        if (!tersimpan.startsWith(TANDA_SANDI)) return tersimpan
        val payload = tersimpan.removePrefix(TANDA_SANDI)
        return try {
            val blob = Base64.decode(payload, Base64.NO_WRAP)
            if (blob.size <= PANJANG_IV) return ""
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(
                Cipher.DECRYPT_MODE, kunci(),
                GCMParameterSpec(PANJANG_TAG, blob, 0, PANJANG_IV)
            )
            String(
                cipher.doFinal(blob, PANJANG_IV, blob.size - PANJANG_IV),
                Charsets.UTF_8
            )
        } catch (_: Throwable) {
            // Kunci keystore hilang (mis. pengguna menghapus kredensial layar
            // kunci, atau data dipulihkan ke perangkat lain). Ciphertext-nya
            // tidak akan pernah bisa dibuka lagi; kembalikan kosong supaya UI
            // meminta kunci baru alih-alih mengirim sampah ke API.
            ""
        }
    }

    /** True bila [tersimpan] benar-benar terenkripsi (bukan warisan plaintext). */
    fun terenkripsi(tersimpan: String): Boolean = tersimpan.startsWith(TANDA_SANDI)

    private fun kunci(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val spec = KeyGenParameterSpec.Builder(
            ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            // Tidak menuntut layar terkunci: penerjemahan berjalan di layanan
            // latar depan yang bisa hidup melewati layar mati, dan menuntut
            // autentikasi akan mematikannya di tengah bab.
            .setUserAuthenticationRequired(false)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    setRandomizedEncryptionRequired(true)
                }
            }
            .build()
        gen.init(spec)
        return gen.generateKey()
    }
}
