package app.pocket.local

import android.content.Context
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.StatFs
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.CancellationException

internal fun updateRedirectAllowed(url: URL): Boolean =
    url.protocol == "https" && url.userInfo == null && url.port in listOf(-1, 443) &&
        url.host.lowercase() in setOf(
            "release-assets.githubusercontent.com", "objects.githubusercontent.com", "github-releases.githubusercontent.com"
        )

internal fun copyVerifiedUpdate(
    input: InputStream,
    output: OutputStream,
    apk: UpdateApk,
    cancelled: () -> Boolean,
    progress: (Int) -> Unit
) {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    var total = 0L
    var percent = 0
    while (true) {
        checkNotCancelled(cancelled)
        val read = input.read(buffer)
        if (read == -1) break
        total += read
        check(total <= apk.bytes) { "The downloaded update is larger than expected." }
        digest.update(buffer, 0, read)
        output.write(buffer, 0, read)
        val next = (total * 100 / apk.bytes).toInt()
        if (next > percent) { percent = next; progress(percent) }
    }
    check(total == apk.bytes) { "The download ended early. Try again." }
    check(digest.digest().joinToString("") { "%02x".format(it) } == apk.sha256) {
        "The download did not match the published update. Try checking for updates again."
    }
}

private fun checkNotCancelled(cancelled: () -> Boolean) {
    if (cancelled()) throw CancellationException()
}

private fun downloadUpdate(apk: UpdateApk, file: File, cancelled: () -> Boolean, progress: (Int) -> Unit) {
    require(apk.bytes in 1..ReleaseFeed.MAX_APK_BYTES)
    val initial = URL(apk.url)
    require(initial.protocol == "https" && initial.host == "github.com" && initial.userInfo == null && initial.port == -1 &&
        initial.path.startsWith("/${BuildConfig.RELEASE_REPOSITORY}/releases/download/"))
    var url = initial
    repeat(6) {
        checkNotCancelled(cancelled)
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("Accept-Encoding", "identity")
            val status = connection.responseCode
            if (status in listOf(301, 302, 303, 307, 308)) {
                val next = URL(url, checkNotNull(connection.getHeaderField("Location")) { "Missing download address." })
                check(updateRedirectAllowed(next)) { "GitHub redirected the download to an unexpected address." }
                url = next
            } else {
                check(status == HttpURLConnection.HTTP_OK) { "GitHub returned HTTP $status. Try again." }
                check(connection.contentLengthLong in listOf(-1L, apk.bytes)) { "The published download size changed." }
                connection.inputStream.use { input ->
                    file.outputStream().use { output -> copyVerifiedUpdate(input, output, apk, cancelled, progress) }
                }
                return
            }
        } finally { connection.disconnect() }
    }
    error("GitHub redirected the download too many times.")
}

/** Creates an uncommitted self-update session, ready to commit from the foreground. */
@Suppress("DEPRECATION") // The int overloads also work on API 31–32.
internal fun stageUpdate(
    context: Context,
    update: AppUpdate,
    cancelled: () -> Boolean,
    progress: (Int) -> Unit
): Int {
    val apk = requireNotNull(update.apk)
    val cache = File(context.cacheDir, "apk-updates")
    check(cache.mkdirs() || cache.isDirectory) { "Could not open temporary storage for the update." }
    check(StatFs(cache.path).availableBytes > apk.bytes * 2) { "Free up storage on this phone to install the update." }
    // Files left by a stopped app process are no longer needed.
    cache.listFiles()?.filter { it.name.startsWith("runlocal-") && it.extension == "apk" }?.forEach { it.delete() }
    val file = File.createTempFile("runlocal-", ".apk", cache)
    try {
        downloadUpdate(apk, file, cancelled, progress)
        checkNotCancelled(cancelled)
        val pm = context.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val archive = checkNotNull(pm.getPackageArchiveInfo(file.path, flags)) { "Android could not read the update APK." }
        val installed = pm.getPackageInfo(context.packageName, flags)
        check(archive.packageName == context.packageName && archive.longVersionCode == update.versionCode.toLong() &&
            archive.longVersionCode > installed.longVersionCode) { "The APK is not the expected RunLocal update." }
        val installedSigners = installed.signingInfo?.apkContentsSigners.orEmpty().toSet()
        check(installedSigners.isNotEmpty() && archive.signingInfo?.apkContentsSigners?.toSet() == installedSigners) {
            "The update was signed with a different key from this installation."
        }
        val installer = pm.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.bytes)
            setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                session.openWrite("base.apk", 0, apk.bytes).use { output ->
                    file.inputStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            checkNotCancelled(cancelled)
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                        }
                    }
                    session.fsync(output)
                }
            }
            checkNotCancelled(cancelled)
            return id
        } catch (error: Exception) {
            runCatching { installer.abandonSession(id) }
            throw error
        }
    } finally { file.delete() }
}
