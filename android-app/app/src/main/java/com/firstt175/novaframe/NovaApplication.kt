package com.firstt175.novaframe

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.memory.MemoryCache
import com.firstt175.novaframe.session.diagnostics.AppIntegrity
import com.firstt175.novaframe.session.diagnostics.CrashReporter
import com.firstt175.novaframe.session.diagnostics.GamepadInputManager
import com.firstt175.novaframe.session.NovaLog
import com.firstt175.novaframe.prefs.AppLanguagePrefs

class NovaApplication : Application(), ImageLoaderFactory {

    // Wrap the application context so background components also honor the selected language.
    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(AppLanguagePrefs.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        // Install the crash reporter as early as possible so even a crash during
        // the first JNI load attempt gets captured (NativeBridge static init
        // already loads the .so; the call below only configures the handler).
        CrashReporter.install(this)

        // Android 13's hidden-API blocklist hides SurfaceControl.Transaction.setSkipScreenshot
        // and ViewRootImpl internals. Without them the frame-gen overlay stays inside the
        // MediaProjection capture and feeds itself back (endless stacked image). Exempt only
        // the android.view classes the overlay needs.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            runCatching {
                org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions("Landroid/view/")
            }.onFailure { android.util.Log.w("HiddenApi", "exemption failed: ${it.message}") }
        }
        NovaLog.init(this)

        // App-wide controller connect/disconnect detection — registered once
        // here so both the settings UI and the in-game overlays can observe
        // GamepadInputManager.connected without each running their own
        // InputManager listener.
        GamepadInputManager.init(this)

        // Informational only — see AppIntegrity's kdoc. Recorded to the local
        // log so it's visible in an exported diagnostics report if a user
        // ever needs support with a copy that turns out not to be official;
        // this never alters app behavior.
        when (AppIntegrity.check(this)) {
            AppIntegrity.Result.UNOFFICIAL ->
                NovaLog.w("AppIntegrity", "Running build's signature does not match the official release key")
            AppIntegrity.Result.OFFICIAL ->
                NovaLog.i("AppIntegrity", "Build signature verified as official")
            AppIntegrity.Result.NOT_CONFIGURED -> Unit
        }
    }

    /**
     * Coil's default ImageLoader sizes its in-memory bitmap cache to 25% of
     * available app RAM, which is unnecessary here — the app only ever
     * loads locally-decoded app icons (already downsampled, see
     * GameLauncherScreen) and one small bundled GIF avatar on the Credits
     * screen. Capping it well below the default keeps that cache from
     * quietly growing into a large, mostly-unused RAM reservation. Nothing
     * here touches the frame-generation pipeline, which does its own
     * memory management separately in native code.
     */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .components {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                add(ImageDecoderDecoder.Factory())
            } else {
                add(GifDecoder.Factory())
            }
        }
        .memoryCache {
            MemoryCache.Builder(this)
                .maxSizePercent(0.08)
                .build()
        }
        .build()
}
