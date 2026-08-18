package com.nyra.comic

/**
 * Pemilihan gambar rujukan (halaman utuh) untuk satu request mosaik.
 *
 * Mosaik hanya memuat potongan balon, jadi model kehilangan ekspresi wajah,
 * arah pandang, dan tata letak panel - padahal justru itu yang menentukan
 * siapa yang bicara dan nada kalimatnya.
 *
 * Kuncinya: satu request bisa memuat balon dari BEBERAPA halaman sekaligus
 * (chunk dipotong per 20 balon, bukan per halaman). Melampirkan salah satu
 * halaman saja dalam keadaan itu justru menyesatkan - model akan mencocokkan
 * balon ke halaman yang salah. Jadi rujukan hanya dikirim bila seluruh
 * potongan dalam request berasal dari satu bagian halaman yang sama.
 */
object PageReference {

    /** Identitas satu bagian halaman: indeks unit + indeks potongan split. */
    data class Kunci(val unitIdx: Int, val partIdx: Int)

    /**
     * Kembalikan bagian halaman yang layak dijadikan rujukan, atau null bila
     * request ini bercampur lebih dari satu halaman (atau kosong).
     */
    fun pilih(sumber: List<Kunci>): Kunci? {
        if (sumber.isEmpty()) return null
        val pertama = sumber[0]
        for (k in sumber) if (k != pertama) return null
        return pertama
    }

    /**
     * Blok prompt untuk gambar rujukan. Dipanggil hanya bila rujukan benar
     * benar dilampirkan, karena menjanjikan gambar kedua yang tidak ada akan
     * membuat model mengarang.
     */
    fun promptSection(): String = buildString {
        append("\nREFERENCE IMAGE:\n")
        append("You are given TWO images. The FIRST image is the numbered mosaic: ")
        append("every bubble you must translate is there, each marked with a large red ID number. ")
        append("The SECOND image is the full manga page those bubbles were cut from.\n")
        append("Use the second image ONLY as visual context - to see who is speaking, ")
        append("their facial expression, gender, age, and the mood of the panel - ")
        append("so pronouns, politeness level, and tone are correct.\n")
        append("Rules for the second image: it has NO id numbers. ")
        append("Do NOT translate any text you see only in the second image. ")
        append("Do NOT invent new ids from it. ")
        append("Return exactly the ids from the FIRST image, nothing else.\n")
    }
}
