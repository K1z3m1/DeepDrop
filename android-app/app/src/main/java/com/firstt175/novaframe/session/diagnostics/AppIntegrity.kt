package com.firstt175.novaframe.session.diagnostics

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

/**
 * Lightweight, non-blocking build-signature check.
 *
 * This is *not* DRM and it never disables the app: the project is GPL v3.0,
 * so anyone is free to fork, modify, and run their own build. What this
 * exists for is purely informational — helping a user who downloaded an APK
 * from an untrusted third party (a reseller, a shady "modded" mirror, a
 * random Telegram channel) notice that what's running on their phone isn't
 * the build the developer actually published, before they trust it with
 * root/Shizuku access or a MediaProjection grant.
 *
 * How it works: at release-signing time the maintainer fills in
 * [OFFICIAL_SIGNATURE_SHA256] with the SHA-256 hash of their release signing
 * certificate (see the comment below for how to compute it). At runtime we
 * hash whatever certificate the currently-running APK was actually signed
 * with and compare. If the maintainer never filled the constant in, the
 * check is skipped entirely — it fails open, never closed, and no UI is
 * shown either way.
 */
object AppIntegrity {

    // Fill this in after generating your release keystore, then keep it in
    // source control — it's a public hash of your certificate, not a secret.
    //
    // Compute it with either of:
    //   keytool -list -v -keystore your.keystore -alias your_alias
    //     (take the "SHA256:" fingerprint, remove the colons, lowercase it)
    // or, on an already-signed release APK:
    //   apksigner verify --print-certs app-release.apk
    //
    // Left blank means "not configured" — the check below no-ops.
    private const val OFFICIAL_SIGNATURE_SHA256 = ""

    enum class Result { OFFICIAL, UNOFFICIAL, NOT_CONFIGURED }

    /**
     * Compares the running APK's signing certificate against
     * [OFFICIAL_SIGNATURE_SHA256]. Safe to call on any build type — never
     * throws, never blocks, never affects app behavior. Purely a read.
     */
    fun check(ctx: Context): Result {
        if (OFFICIAL_SIGNATURE_SHA256.isBlank()) return Result.NOT_CONFIGURED
        val actual = currentSignatureSha256(ctx) ?: return Result.NOT_CONFIGURED
        return if (actual.equals(OFFICIAL_SIGNATURE_SHA256, ignoreCase = true)) {
            Result.OFFICIAL
        } else {
            Result.UNOFFICIAL
        }
    }

    private fun currentSignatureSha256(ctx: Context): String? = try {
        val pm = ctx.packageManager
        // Always use apkContentsSigners: it reflects exactly which
        // certificate(s) actually signed *this* APK. signingCertificateHistory
        // was deliberately not used here — it returns the full past+present
        // key-rotation lineage ordered oldest-first, so naively taking its
        // first entry would compare against a retired certificate instead of
        // the one that actually signed the running build.
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            info.signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNATURES).signatures
        }
        val cert = signatures?.firstOrNull() ?: return null
        val digest = MessageDigest.getInstance("SHA-256").digest(cert.toByteArray())
        digest.joinToString("") { "%02x".format(it) }
    } catch (_: Throwable) {
        null
    }
}
