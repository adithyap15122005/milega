package com.milega.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.mutableStateOf
import com.milega.app.ui.AppNavigation
import com.milega.app.ui.theme.MilegaTheme

class MainActivity : AppCompatActivity() {
    /** A screen to open once navigation is ready (from a notification tap). */
    private val pendingRoute = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        pendingRoute.value = intent?.getStringExtra(EXTRA_ROUTE)?.ifBlank { null }
        setContent {
            MilegaTheme {
                AppNavigation(container, pendingRoute.value) { pendingRoute.value = null }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(EXTRA_ROUTE)?.ifBlank { null }?.let { pendingRoute.value = it }
    }

    companion object {
        const val EXTRA_ROUTE = "open_route"
    }
}
