package dev.esxiclient.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.esxiclient.app.ui.navigation.AppNavGraph
import dev.esxiclient.app.ui.theme.ESXiClientTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ESXiClientTheme {
                AppNavGraph()
            }
        }
    }
}
