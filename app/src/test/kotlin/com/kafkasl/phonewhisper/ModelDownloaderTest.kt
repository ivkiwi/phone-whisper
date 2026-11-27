package com.kafkasl.phonewhisper

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files

class ModelDownloaderTest {

    @Test fun `extracts tar bz2 with nested files`() {
        withTempDir { tmp ->
            val archive = File(tmp, "test.tar.bz2")
            val outDir = File(tmp, "out")

            writeTarBz2(archive, mapOf(
                "mymodel/tokens.txt" to "hello\nworld",
                "mymodel/encoder.onnx" to "fake-onnx-data",
            ))

            ModelDownloader.extractTarBz2(archive, outDir)

            assertTrue(File(outDir, "mymodel").isDirectory)
            assertEquals("hello\nworld", File(outDir, "mymodel/tokens.txt").readText())
            assertEquals("fake-onnx-data", File(outDir, "mymodel/encoder.onnx").readText())
        }
    }

    @Test fun `rejects path traversal`() {
        withTempDir { tmp ->
            val archive = File(tmp, "evil.tar.bz2")
            writeTarBz2(archive, mapOf("../evil.txt" to "gotcha"))

            assertThrows(IllegalArgumentException::class.java) {
                ModelDownloader.extractTarBz2(archive, File(tmp, "out"))
            }
        }
    }

    @Test fun `catalog has expected structure`() {
        assertEquals(10, MODEL_CATALOG.size)
        assertTrue(MODEL_CATALOG.any { it.recommended })
        assertTrue(MODEL_CATALOG.all { it.sizeMb > 0 })
        assertTrue(MODEL_CATALOG.all { it.archive.isNotBlank() })
        // archive names are used as on-disk dir names, so they must be unique
        assertEquals(MODEL_CATALOG.size, MODEL_CATALOG.map { it.archive }.toSet().size)
        // every model has a source: a Hugging Face source, an explicit url, or a
        // default sherpa-onnx tar archive resolved from BASE_URL
        assertTrue(MODEL_CATALOG.all {
            it.hf != null || it.url != null || it.archive.startsWith("sherpa-onnx-")
        })
        val v4 = MODEL_CATALOG.first { it.archive == "gigaam-v4-e2e-rnnt-ru-en" }.hf!!
        assertEquals(
            "https://github.com/ivkiwi/phone-whisper/releases/download/models-gigaam-v4/gigaam_v4_e2e_rnnt_tokens.txt",
            v4.url(v4.files.last()),
        )
        val v3 = MODEL_CATALOG.first { it.archive == "gigaam-v3-e2e-rnnt-ru" }.hf!!
        assertEquals(
            "https://huggingface.co/Smirnov75/GigaAM-v3-sherpa-onnx/resolve/main/gigaam_v3_e2e_rnnt_tokens.txt",
            v3.url(v3.files.last()),
        )
    }

    @Test fun `rejects incomplete Hugging Face model dirs`() {
        withTempDir { tmp ->
            val hf = HfSource("test/repo", files = listOf(HfFile("remote.onnx", "model.onnx", 4)))
            File(tmp, "model.onnx").writeText("abc")

            assertFalse(ModelDownloader.isCompleteHfModelDir(tmp, hf))

            File(tmp, "model.onnx").writeText("abcd")
            assertTrue(ModelDownloader.isCompleteHfModelDir(tmp, hf))
        }
    }

    // -- helpers --

    private fun withTempDir(block: (File) -> Unit) {
        val tmp = Files.createTempDirectory("model-test").toFile()
        try { block(tmp) } finally { tmp.deleteRecursively() }
    }

    private fun writeTarBz2(file: File, entries: Map<String, String>) {
        TarArchiveOutputStream(BZip2CompressorOutputStream(FileOutputStream(file))).use { tar ->
            for ((name, content) in entries) {
                val bytes = content.toByteArray()
                tar.putArchiveEntry(TarArchiveEntry(name).apply { size = bytes.size.toLong() })
                tar.write(bytes)
                tar.closeArchiveEntry()
            }
        }
    }
}
