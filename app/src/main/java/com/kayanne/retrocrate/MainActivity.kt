package com.kayanne.retrocrate

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.kayanne.retrocrate.core.designsystem.RetroCrateTheme
import com.kayanne.retrocrate.navigation.RetroCrateApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RetroCrateTheme {
                RetroCrateApp()
            }
        }
    }
}
