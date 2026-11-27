package app.pocket.local

import java.io.ByteArrayOutputStream
import java.net.URI
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class UpdateInstallerTest {
    private val data = "abc".encodeToByteArray()
    private val apk = UpdateApk("https://github.com/owner/runlocal/releases/download/v1/runlocal-1-arm64.apk", 3,
        "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")

    @Test fun copiesACompleteUpdateAndReportsProgress() {
        val output = ByteArrayOutputStream()
        val progress = mutableListOf<Int>()
        copyVerifiedUpdate(data.inputStream(), output, apk, { false }) { progress.add(it) }
        assertArrayEquals(data, output.toByteArray())
        assertEquals(listOf(100), progress)
    }

    @Test fun rejectsAnOversizedTruncatedOrAlteredDownload() {
        for (input in listOf("abcd", "ab", "abd")) {
            assertThrows(IllegalStateException::class.java) {
                copyVerifiedUpdate(input.byteInputStream(), ByteArrayOutputStream(), apk, { false }) {}
            }
        }
    }

    @Test fun cancellationStopsTheDownloadBeforeWriting() {
        val output = ByteArrayOutputStream()
        assertThrows(CancellationException::class.java) {
            copyVerifiedUpdate(data.inputStream(), output, apk, { true }) {}
        }
        assertEquals(0, output.size())
    }

    @Test fun followsOnlySecureGitHubAssetRedirects() {
        fun allowed(value: String) = updateRedirectAllowed(URI(value).toURL())
        assertTrue(allowed("https://release-assets.githubusercontent.com/github-production-release-asset/123"))
        assertTrue(allowed("https://objects.githubusercontent.com:443/github-production-release-asset-2e65be/123"))
        assertFalse(allowed("http://release-assets.githubusercontent.com/a"))
        assertFalse(allowed("https://release-assets.githubusercontent.com.example.com/a"))
        assertFalse(allowed("https://user@release-assets.githubusercontent.com/a"))
        assertFalse(allowed("https://release-assets.githubusercontent.com:444/a"))
    }
}
