package nz.fishingnz.app

import android.os.Bundle
import android.content.Intent
import androidx.activity.viewModels
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.launch
import nz.fishingnz.app.data.AppUpdates
import com.google.firebase.analytics.FirebaseAnalytics
import nz.fishingnz.app.ui.CatchCheckApp
import nz.fishingnz.app.data.AccountSessionStore
import nz.fishingnz.app.data.CatchLogStore
import nz.fishingnz.app.data.OfflineCache
import nz.fishingnz.app.data.SyncStore
import nz.fishingnz.app.data.TideWidget
import nz.fishingnz.app.data.TideWidgetStation
import nz.fishingnz.app.data.TripReminders
import nz.fishingnz.app.data.TripStore
import nz.fishingnz.app.data.Analytics
import nz.fishingnz.app.data.SearchPreferencesStore
import nz.fishingnz.app.data.PrivacyPreferences
import nz.fishingnz.app.viewmodel.FishingViewModel

class MainActivity : ComponentActivity() {
    private val fishingViewModel: FishingViewModel by viewModels()
    private val updateLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { AppUpdates.onFlowResult(it.resultCode) }
    private val backgroundInstall = Handler(Looper.getMainLooper())
    // A downloaded update installs after the app has been out of sight for a minute, so a quick trip to the photo
    // picker or share sheet never restarts it.
    private val installWhenAway = Runnable { AppUpdates.installIfDownloaded() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AccountSessionStore.initialize(applicationContext)
        SearchPreferencesStore.initialize(applicationContext)
        CatchLogStore.initialize(applicationContext)
        OfflineCache.initialize(applicationContext)
        nz.fishingnz.app.data.RulesSync.initialize(applicationContext)
        TripStore.initialize(applicationContext)
        TripReminders.initialize(applicationContext)
        SyncStore.initialize(applicationContext)
        TideWidgetStation.initialize(applicationContext)
        PrivacyPreferences.initialize(applicationContext)
        AppUpdates.initialize(applicationContext)
        if (PrivacyPreferences.firebaseEnabled()) FirebaseAnalytics.getInstance(this).logEvent("app_opened", Bundle().apply { putString("app_version", BuildConfig.VERSION_NAME) })
        Analytics.track("app_open")
        fishingViewModel.completeSocialSignIn(intent.data)
        // Only on a fresh start: after a rotation the same intent is delivered again and must not reopen the window.
        if (savedInstanceState == null) fishingViewModel.openSharedWindow(intent.data)
        if (savedInstanceState == null && intent.action == TripReminders.OPEN_TRIPS) fishingViewModel.selectTab(3)
        if (savedInstanceState == null && intent.action == TideWidget.OPEN_TIDE) fishingViewModel.selectTab(2)
        setContent { CatchCheckApp(fishingViewModel, onUpdate = { lifecycleScope.launch { AppUpdates.startUpdate(this@MainActivity, updateLauncher) } }) }
    }

    override fun onStart() {
        super.onStart()
        backgroundInstall.removeCallbacks(installWhenAway)
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            AppUpdates.resume(this@MainActivity, updateLauncher)
            AppUpdates.check(java.util.Locale.getDefault().toLanguageTag())
        }
    }

    override fun onStop() {
        super.onStop()
        Analytics.flush()
        backgroundInstall.postDelayed(installWhenAway, 60_000)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        fishingViewModel.completeSocialSignIn(intent.data)
        fishingViewModel.openSharedWindow(intent.data)
        if (intent.action == TripReminders.OPEN_TRIPS) fishingViewModel.selectTab(3)
        if (intent.action == TideWidget.OPEN_TIDE) fishingViewModel.selectTab(2)
    }
}
