package ru.epbloger.trachu.ads

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.yandex.mobile.ads.common.AdRequest
import com.yandex.mobile.ads.common.YandexAds
import com.yandex.mobile.ads.compose.Banner
import com.yandex.mobile.ads.compose.BannerEvents
import com.yandex.mobile.ads.compose.BannerSize
import com.yandex.mobile.ads.compose.rememberBannerAdState
import kotlinx.coroutines.delay
import ru.epbloger.trachu.BuildConfig

/** SDK 8 is explicitly initialized at most once per process, on the main thread. */
private object AdsRuntime {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var requested = false
    var ready by mutableStateOf(false)
        private set

    fun runOnMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action()
        else mainHandler.post(action)
    }

    fun initializeIfNeeded(context: Context) {
        if (requested || ready) return
        requested = true
        runCatching {
            // The app has no personalization consent flow. Do not imply users are children.
            YandexAds.setUserConsent(false)
            YandexAds.setLocationTracking(false)
            YandexAds.initialize(context.applicationContext) {
                mainHandler.post { ready = true }
            }
        }.onFailure {
            requested = false
            Log.w("TrachuAds", "Could not initialize Yandex Mobile Ads", it)
        }
    }
}

/** Rate-limit new requests when a user returns to the overview repeatedly. */
private object BannerRequestGate {
    private var lastRequestAt: Long? = null

    suspend fun awaitNext() {
        while (true) {
            val now = SystemClock.elapsedRealtime()
            val previous = lastRequestAt
            val remaining = if (previous == null) 0L else
                (30_000L - (now - previous).coerceAtLeast(0L)).coerceAtLeast(0L)
            if (remaining == 0L) {
                lastRequestAt = now
                return
            }
            delay(remaining)
        }
    }
}

/**
 * A single small sticky banner under the overview content. The caller never composes
 * this on the lock screen or while any financial editor/details overlay is open.
 */
@Composable
fun OverviewAdBanner() {
    val adUnitId = BuildConfig.YANDEX_BANNER_ID
    // No SDK call in release until a production ID AND enabling flag are supplied.
    if (adUnitId.isEmpty()) return

    val context = LocalContext.current.applicationContext
    LaunchedEffect(context) { AdsRuntime.initializeIfNeeded(context) }

    if (!AdsRuntime.ready) return
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val bannerWidth = maxWidth.coerceAtMost(480.dp)
        if (bannerWidth < 120.dp) return@BoxWithConstraints
        key(bannerWidth) {
            var loaded by remember { mutableStateOf(false) }
            var failed by remember { mutableStateOf(false) }
            val bannerState = rememberBannerAdState(
                adSize = BannerSize.Sticky(width = bannerWidth),
                events = BannerEvents(
                    onAdLoaded = { AdsRuntime.runOnMain { loaded = true } },
                    onAdFailedToLoad = { error ->
                        AdsRuntime.runOnMain {
                            failed = true
                            loaded = false
                        }
                        Log.w("TrachuAds", "Banner unavailable: ${error.description}")
                    },
                ),
            )
            LaunchedEffect(bannerState, adUnitId) {
                BannerRequestGate.awaitNext()
                if (!failed) bannerState.loadAd(AdRequest.Builder(adUnitId).build())
            }
            if (!failed) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (loaded) Text(
                        if (BuildConfig.DEBUG) "Тестовая реклама" else "Реклама",
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Banner(
                        state = bannerState,
                        modifier = Modifier.width(bannerWidth).testTag("overview-ad-banner"),
                    )
                }
            }
        }
    }
}
