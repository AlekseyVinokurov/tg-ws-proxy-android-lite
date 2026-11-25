package com.amurcanov.tgwsproxy

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Installs only a published stable APK from this repository.
 * GitHub's SHA-256 digest, package identity, versionCode and installed signer
 * must all match before the standard Android installer is opened.
 */
object AppUpdateInstaller {
    private const val REPO = "AlekseyVinokurov/tg-ws-proxy-android-lite"
    private const val MAX_APK_BYTES = 200L * 1024L * 1024L
    private val tagPattern = Regex("^v?[0-9]+\\.[0-9]+\\.[0-9]+[a-zA-Z0-9._-]*$")

    private data class Asset(
        val url: String,
        val digest: String,
        val size: Long
    )

    suspend fun install(context: Context, release: AppReleaseInfo) {
        val apk = withContext(Dispatchers.IO) { fetchVerifyApk(context, release) }
        withContext(Dispatchers.Main) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                !context.packageManager.canRequestPackageInstalls()) {
                val settingsIntent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(settingsIntent)
                throw IllegalStateException("Allow APK installs for Pixel Eco, then tap update again")
            }
            val uri = FileProvider.getUriForFile(
                context, "${context.packageName}.apkprovider", apk
            )
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(intent)
        }
    }

    private fun fetchVerifyApk(context: Context, release: AppReleaseInfo): File {
        val tag = release.versionTag
        require(tagPattern.matches(tag)) { "Unsupported release tag" }
        require(release.source == RemoteVersionSource.Release) { "Release APK required" }
        val apiUrl = "https://api.github.com/repos/$REPO/releases/tags/$tag"
        val json = JSONObject(getText(apiUrl))
        require(!json.optBoolean("draft") && !json.optBoolean("prerelease")) {
            "Only stable releases are accepted"
        }
        require(json.optString("tag_name") == tag) { "Release tag changed" }
        val array = json.getJSONArray("assets")
        val matches = (0 until array.length()).map { array.getJSONObject(it) }
            .filter { it.optString("name").endsWith(".apk", ignoreCase = true) }
        require(matches.size == 1) { "Expected exactly one release APK" }
        val assetJson = matches.single()
        val asset = Asset(
            url = assetJson.getString("browser_download_url"),
            digest = assetJson.optString("digest"),
            size = assetJson.optLong("size")
        )
        require(asset.url.startsWith("https://github.com/$REPO/releases/download/$tag/")) {
            "Unexpected download location"
        }
        require(asset.size in 1..MAX_APK_BYTES) { "APK size out of range" }
        require(Regex("^sha256:[a-fA-F0-9]{64}$").matches(asset.digest)) {
            "Release has no trusted SHA-256 digest"
        }
        val updateDir = File(context.cacheDir, "verified-apk").apply { mkdirs() }
        val apk = File(updateDir, "pixel-eco-update.apk")
        apk.delete()
        try {
            val md = MessageDigest.getInstance("SHA-256")
            val conn = openHttps(asset.url)
            try {
                conn.inputStream.use { input ->
                    apk.outputStream().use { out ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            total += n
                            require(total <= asset.size && total <= MAX_APK_BYTES) { "APK too large" }
                            md.update(buffer, 0, n)
                            out.write(buffer, 0, n)
                        }
                        require(total == asset.size) { "Truncated APK" }
                    }
                }
            } finally {
                conn.disconnect()
            }
            val checksum = md.digest().joinToString("") { "%02x".format(it) }
            require(checksum.equals(asset.digest.removePrefix("sha256:"), ignoreCase = true)) {
                "APK SHA-256 mismatch"
            }
            verifyPackage(context, apk)
            return apk
        } catch (e: Exception) {
            apk.delete()
            throw e
        }
    }

    private fun getText(address: String): String {
        val conn = openHttps(address)
        return try {
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun openHttps(address: String): HttpURLConnection {
        val conn = URL(address).openConnection() as HttpURLConnection
        conn.connectTimeout = 12_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("User-Agent", "PixelEco/${BuildConfig.VERSION_NAME}")
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            require(conn.responseCode in 200..299) { "HTTP ${conn.responseCode}" }
            require(conn.url.protocol.equals("https", ignoreCase = true)) {
                "Insecure download redirect"
            }
        } catch (e: Exception) {
            conn.disconnect()
            throw e
        }
        return conn
    }

    @Suppress("DEPRECATION")
    private fun verifyPackage(context: Context, apk: File) {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        val archive = pm.getPackageArchiveInfo(apk.absolutePath, flags)
            ?: error("Not a valid Android APK")
        require(archive.packageName == context.packageName) { "APK package mismatch" }
        val installed = pm.getPackageInfo(context.packageName, flags)
        val newVersion = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            archive.longVersionCode
        } else archive.versionCode.toLong()
        val oldVersion = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            installed.longVersionCode
        } else installed.versionCode.toLong()
        require(newVersion > oldVersion) { "APK versionCode is not newer" }
        val newSignatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            archive.signingInfo?.apkContentsSigners?.toList().orEmpty()
        } else archive.signatures?.toList().orEmpty()
        val oldSignatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            installed.signingInfo?.apkContentsSigners?.toList().orEmpty()
        } else installed.signatures?.toList().orEmpty()
        require(newSignatures.isNotEmpty() && oldSignatures.isNotEmpty()) {
            "Missing signing certificate"
        }
        val old = oldSignatures.map { it.toByteArray().toList() }.toSet()
        val newest = newSignatures.map { it.toByteArray().toList() }.toSet()
        require(old == newest) { "Signer differs from installed Pixel Eco" }
    }
}
