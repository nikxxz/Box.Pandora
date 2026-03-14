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
        enableEdgeToEdge()
        setContent {
            BoxPandoraTheme {
                MainScreen()
            }
        }
    }
}
