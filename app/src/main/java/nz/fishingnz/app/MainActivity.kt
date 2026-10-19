package nz.fishingnz.app

import android.os.Bundle
import android.content.Intent
import androidx.activity.viewModels
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.google.firebase.analytics.FirebaseAnalytics
import nz.fishingnz.app.ui.CatchCheckApp
import nz.fishingnz.app.data.AccountSessionStore
import nz.fishingnz.app.data.CatchLogStore
import nz.fishingnz.app.data.OfflineCache
import nz.fishingnz.app.data.TripReminders
import nz.fishingnz.app.data.TripStore
import nz.fishingnz.app.data.Analytics
import nz.fishingnz.app.data.SearchPreferencesStore
import nz.fishingnz.app.data.PrivacyPreferences
import nz.fishingnz.app.viewmodel.FishingViewModel

class MainActivity : ComponentActivity() {
    private val fishingViewModel: FishingViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AccountSessionStore.initialize(applicationContext)
        SearchPreferencesStore.initialize(applicationContext)
        CatchLogStore.initialize(applicationContext)
        OfflineCache.initialize(applicationContext)
        TripStore.initialize(applicationContext)
        TripReminders.initialize(applicationContext)
        PrivacyPreferences.initialize(applicationContext)
        if (PrivacyPreferences.firebaseEnabled()) FirebaseAnalytics.getInstance(this).logEvent("app_opened", Bundle().apply { putString("app_version", BuildConfig.VERSION_NAME) })
        Analytics.track("app_open")
        fishingViewModel.completeSocialSignIn(intent.data)
        // Only on a fresh start: after a rotation the same intent is delivered again and must not reopen the window.
        if (savedInstanceState == null) fishingViewModel.openSharedWindow(intent.data)
        if (savedInstanceState == null && intent.action == TripReminders.OPEN_TRIPS) fishingViewModel.selectTab(3)
        setContent { CatchCheckApp(fishingViewModel) }
    }

    override fun onStop() {
        super.onStop()
        Analytics.flush()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        fishingViewModel.completeSocialSignIn(intent.data)
        fishingViewModel.openSharedWindow(intent.data)
        if (intent.action == TripReminders.OPEN_TRIPS) fishingViewModel.selectTab(3)
    }
}
