package com.milega.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.milega.app.ui.AppNavigation
import com.milega.app.ui.theme.MilegaTheme

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            MilegaTheme {
                AppNavigation(container)
            }
        }
    }
}
