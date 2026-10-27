package cn.zxy.timetable

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(
                primary = Color(0xFF405E9B), onPrimary = Color.White,
                background = Color(0xFFF8F9FC), surface = Color(0xFFF8F9FC),
                onBackground = Color(0xFF202631), onSurface = Color(0xFF202631),
                surfaceVariant = Color(0xFFECF0F6), onSurfaceVariant = Color(0xFF6A7485),
                outlineVariant = Color(0xFFE3E8F0),
            )) { TimetableApp() }
        }
    }
}
