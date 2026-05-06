package vip.mystery0.pixel.text

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import vip.mystery0.pixel.text.ui.AppNavigation
import vip.mystery0.pixel.text.ui.theme.PixelTextTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PixelTextTheme {
                AppNavigation()
            }
        }
    }
}