package nz.fishingnz.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.google.firebase.analytics.FirebaseAnalytics
import nz.fishingnz.app.ui.CatchCheckApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FirebaseAnalytics.getInstance(this).logEvent("app_opened", Bundle().apply { putString("app_version", "0.1") })
        setContent { CatchCheckApp() }
    }
}
