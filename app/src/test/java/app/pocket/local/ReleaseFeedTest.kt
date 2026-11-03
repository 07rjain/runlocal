package app.pocket.local

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class ReleaseFeedTest {
    private val repository = "owner/RunLocal"
    private fun release() = JsonParser.parseString("""{
        "draft":false,"prerelease":false,"tag_name":"v1.0.0",
        "html_url":"https://github.com/owner/RunLocal/releases/tag/v1.0.0",
        "body":"Changes\n<!-- runlocal-version-code: 20 -->",
        "assets":[{"name":"runlocal-1.0.0-arm64.apk","size":123,
          "browser_download_url":"https://github.com/owner/RunLocal/releases/download/v1.0.0/runlocal-1.0.0-arm64.apk"}]
    }""").asJsonObject
    @Test fun detectsOnlyNewerPublishedStableRelease() {
        val json = release()
        assertEquals(AppUpdate(20, "1.0.0", "https://github.com/owner/RunLocal/releases/tag/v1.0.0"), ReleaseFeed.parse(json.toString(), repository, 9))
        assertNull(ReleaseFeed.parse(json.toString(), repository, 20))
        assertNull(ReleaseFeed.parse(json.toString(), repository, 21))
        json.addProperty("prerelease", true); assertNull(ReleaseFeed.parse(json.toString(), repository, 9))
        json.addProperty("prerelease", false); json.addProperty("draft", true)
        assertNull(ReleaseFeed.parse(json.toString(), repository, 9))
    }
    @Test fun rejectsMissingAmbiguousOrOverflowingVersionMarker() {
        for (body in listOf("No marker", "<!-- runlocal-version-code: 999999999999999999999 -->", "<!-- runlocal-version-code: 20 --> <!-- runlocal-version-code: 21 -->")) {
            val json = release(); json.addProperty("body", body)
            assertNull(ReleaseFeed.parse(json.toString(), repository, 9))
        }
    }
    @Test fun requiresSameRepositoryReleaseAndArm64Asset() {
        val json = release(); json.addProperty("html_url", "https://evil.test/release")
        assertNull(ReleaseFeed.parse(json.toString(), repository, 9))
        val asset = release(); asset.getAsJsonArray("assets")[0].asJsonObject.addProperty("browser_download_url", "https://evil.test/app.apk")
        assertNull(ReleaseFeed.parse(asset.toString(), repository, 9))
        val missing = release(); missing.getAsJsonArray("assets").remove(0)
        assertNull(ReleaseFeed.parse(missing.toString(), repository, 9))
        assertNull(ReleaseFeed.parse(release().toString(), "other/repo", 9))
    }
    @Test fun rejectsUnsafeRepositoryAndTags() {
        for (repo in listOf("", "https://github.com/owner/repo", "../repo", "owner/repo/extra", "owner/repo?x=1")) assertFalse(ReleaseFeed.validRepository(repo))
        val json = release(); json.addProperty("tag_name", "../../escape")
        assertNull(ReleaseFeed.parse(json.toString(), repository, 9))
    }
}
