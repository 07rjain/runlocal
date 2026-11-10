package app.pocket.local

import com.google.gson.JsonParser
import com.google.gson.JsonArray
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

    private fun candidate(code: Int = 22, tag: String = "v1.0.0-rc8") = release().apply {
        addProperty("prerelease", true); addProperty("tag_name", tag)
        addProperty("body", "Candidate\n<!-- runlocal-version-code: $code -->")
        addProperty("html_url", "https://github.com/$repository/releases/tag/$tag")
        getAsJsonArray("assets")[0].asJsonObject.apply {
            val name = "runlocal-${tag.removePrefix("v")}-arm64.apk"
            addProperty("name", name)
            addProperty("browser_download_url", "https://github.com/$repository/releases/download/$tag/$name")
        }
    }

    @Test fun candidatesRequireExplicitOptInAndNeverExposeDrafts() {
        val json = candidate()
        assertNull(ReleaseFeed.parse(json.toString(), repository, 9))
        assertEquals(AppUpdate(22, "1.0.0-rc8", "https://github.com/$repository/releases/tag/v1.0.0-rc8", true),
            ReleaseFeed.parse(json.toString(), repository, 9, true))
        assertNull(ReleaseFeed.parse(json.toString(), repository, 22, true))
        json.addProperty("draft", true)
        assertNull(ReleaseFeed.parse(json.toString(), repository, 9, true))
    }

    @Test fun feedUsesHighestValidVersionCodeRatherThanListOrderOrVersionName() {
        val feed = JsonArray().apply {
            add(candidate(21, "v1.0.0-rc99"))
            add(release())
            add(candidate(23, "v1.0.0-rc8"))
            add(candidate(25).apply { addProperty("draft", true) })
            add(candidate(24).apply { getAsJsonArray("assets").remove(0) })
        }
        assertEquals(23, ReleaseFeed.parseFeed(feed.toString(), repository, 9, true)?.versionCode)
        assertEquals(20, ReleaseFeed.parseFeed(feed.toString(), repository, 9)?.versionCode)
        assertNull(ReleaseFeed.parseFeed(feed.toString(), repository, 23, true))
    }

    @Test fun invalidMetadataCannotHideAnotherValidRelease() {
        val feed = JsonArray().apply {
            add(JsonParser.parseString("null")); add(42)
            add(candidate(25).apply { addProperty("body", "<!-- runlocal-version-code: 999999999999999999 -->") })
            add(candidate(24).apply { addProperty("html_url", "https://evil.test/release") })
            add(candidate(23).apply { getAsJsonArray("assets")[0].asJsonObject.addProperty("browser_download_url", "https://evil.test/app.apk") })
            add(candidate(22).apply { addProperty("body", "<!-- runlocal-version-code: 22 --> <!-- runlocal-version-code: 23 -->") })
            add(candidate(21).apply { remove("prerelease") })
            add(release())
        }
        assertEquals(20, ReleaseFeed.parseFeed(feed.toString(), repository, 9, true)?.versionCode)
        assertNull(ReleaseFeed.parseFeed("[]", repository, 9, true))
    }
}
