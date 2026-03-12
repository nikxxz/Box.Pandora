package com.example.boxpandora

import android.os.Bundle
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.boxpandora.ui.main.MainScreen
import com.example.boxpandora.ui.theme.BoxPandoraTheme
import kotlin.math.abs

class MainActivity : ComponentActivity() {

    // Edge-swipe back gesture state
    private var edgeGestureActive = false
    private var edgeStartX = 0f
    private var edgeStartY = 0f

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (handleEdgeBackGesture(event)) return true
        return super.dispatchTouchEvent(event)
    }

    /**
     * Intercepts touches that start within the edge zone (24 dp on each side)
     * and triggers back navigation on an inward horizontal swipe.
     * This replaces the system back gesture that immersive mode blocks.
     */
    private fun handleEdgeBackGesture(event: MotionEvent): Boolean {
        val edgeZone = 24 * resources.displayMetrics.density
        val screenWidth = window.decorView.width

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (event.x < edgeZone || event.x > screenWidth - edgeZone) {
                    edgeGestureActive = true
                    edgeStartX = event.x
                    edgeStartY = event.y
                    return true
                }
                edgeGestureActive = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (edgeGestureActive) {
                    val dx = event.x - edgeStartX
                    val dy = event.y - edgeStartY
                    val isInward = if (edgeStartX < edgeZone) dx > 0 else dx < 0
                    val minSwipe = 24 * resources.displayMetrics.density
                    if (isInward && abs(dx) > minSwipe && abs(dx) > abs(dy)) {
                        edgeGestureActive = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                    return true
                }
            }
            MotionEvent.ACTION_UP -> {
                if (edgeGestureActive) {
                    edgeGestureActive = false
                    return true
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                edgeGestureActive = false
            }
        }
        return false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // enableEdgeToEdge() is the modern way to achieve a fullscreen look.
        // It makes the status and navigation bars transparent and allows the app to draw behind them.
        enableEdgeToEdge()

        // Hide the gesture pill / navigation bar app-wide; keep the status bar.
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.navigationBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        // Re-hide the navigation bar immediately whenever the system tries to show it.
        ViewCompat.setOnApplyWindowInsetsListener(window.decorView) { view, insets ->
            if (insets.isVisible(WindowInsetsCompat.Type.navigationBars())) {
                controller.hide(WindowInsetsCompat.Type.navigationBars())
            }
            ViewCompat.onApplyWindowInsets(view, insets)
        }

        setContent {
            BoxPandoraTheme {
                MainScreen()
            }
        }
    }
}
