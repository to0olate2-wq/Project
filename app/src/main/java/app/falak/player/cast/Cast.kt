package app.falak.player.cast

import android.content.Context
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.framework.media.CastMediaOptions
import com.google.android.gms.cast.framework.media.NotificationOptions

/**
 * Chromecast (8.28; the owner picked it from a list of what was missing). Google's standard
 * receiver plays the link on the Chromecast itself (see CastLinks for what can go); the phone
 * keeps a notification to pause or stop it. Named in the manifest
 * (com.google.android.gms.cast.framework.OPTIONS_PROVIDER_CLASS_NAME) and kept by R8.
 */
class CastSetup : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions = CastOptions.Builder()
        .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
        .setCastMediaOptions(CastMediaOptions.Builder().setNotificationOptions(NotificationOptions.Builder().build()).build())
        .setStopReceiverApplicationWhenEndingSession(true)
        .build()

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}

object Cast {
    /**
     * Google's cast library, or null where it can't run: no Google Play services (many TV boxes,
     * some phones), or the screen tests. Asked on the main thread.
     */
    fun context(ctx: Context): CastContext? = try {
        val ok = com.google.android.gms.common.GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(ctx)
        if (ok != com.google.android.gms.common.ConnectionResult.SUCCESS) {
            null
        } else {
            @Suppress("DEPRECATION")
            CastContext.getSharedInstance(ctx)
        }
    } catch (e: Throwable) {
        null
    }
}
