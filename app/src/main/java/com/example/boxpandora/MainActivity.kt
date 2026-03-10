package com.example.boxpandora

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.boxpandora.ui.main.MainScreen
import com.example.boxpandora.ui.theme.BoxPandoraTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // enableEdgeToEdge() is the modern way to achieve a fullscreen look.
        // It makes the status and navigation bars transparent and allows the app to draw behind them.
        enableEdgeToEdge()

        setContent {
            BoxPandoraTheme {
                MainScreen()
            }
        }
    }
}
