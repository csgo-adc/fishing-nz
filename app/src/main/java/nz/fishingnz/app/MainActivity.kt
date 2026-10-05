package nz.fishingnz.app

import android.os.Bundle
import android.content.Intent
import androidx.activity.viewModels
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.google.firebase.analytics.FirebaseAnalytics
import nz.fishingnz.app.ui.CatchCheckApp
import nz.fishingnz.app.data.AccountSessionStore
import nz.fishingnz.app.data.SearchPreferencesStore
import nz.fishingnz.app.data.PrivacyPreferences
import nz.fishingnz.app.viewmodel.FishingViewModel

class MainActivity : ComponentActivity() {
    private val fishingViewModel: FishingViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AccountSessionStore.initialize(applicationContext)
        SearchPreferencesStore.initialize(applicationContext)
        PrivacyPreferences.initialize(applicationContext)
        if (PrivacyPreferences.analyticsEnabled()) FirebaseAnalytics.getInstance(this).logEvent("app_opened", Bundle().apply { putString("app_version", BuildConfig.VERSION_NAME) })
        fishingViewModel.completeSocialSignIn(intent.data)
        setContent { CatchCheckApp(fishingViewModel) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        fishingViewModel.completeSocialSignIn(intent.data)
    }
}
