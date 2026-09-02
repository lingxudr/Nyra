package com.nyra.comic

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config as RoboConfig

/**
 * Kontrak prompt ronde 41: aturan FIDELITY AND REGISTER ditambahkan untuk
 * menguatkan mutu terjemahan tanpa menghapus aturan lama yang sudah terbukti.
 */
@RunWith(RobolectricTestRunner::class)
@RoboConfig(sdk = [33], manifest = RoboConfig.NONE)
class Ronde41PromptTest {

    @Test
    fun promptMemuatAturanFidelityDanRegister() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val cfg = Config(ctx)
        cfg.konteksHalaman = false
        val p = Pipeline(ctx, cfg, log = {}, progress = { _, _ -> })
        try {
            val prompt = p.buildPrompt("indonesian")

            assertTrue(prompt.contains("FIDELITY AND REGISTER RULE"))
            assertTrue(prompt.contains("Match the register of the speaker"))
            assertTrue(prompt.contains("Preserve the length and punctuation"))
            assertTrue(prompt.contains("Do NOT add quotation marks, honorifics"))
            assertTrue(prompt.contains("proper nouns exactly as written in the original"))
            // Aturan lama yang sudah terbukti tidak boleh hilang.
            assertTrue(prompt.contains("HONORIFICS RULE"))
            assertTrue(prompt.contains("SFX AND VOICE RULE"))
            assertTrue(prompt.contains("contains ANY readable text must be translated"))
        } finally {
            p.close()
        }
    }
}
