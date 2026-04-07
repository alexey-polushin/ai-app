package me.lekseg.aiapp

import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import me.lekseg.aiapp.mlcapi.MlcUiProvider
import java.util.ServiceLoader

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val tCreate = SystemClock.elapsedRealtime()
        setContent {
            MaterialTheme {
                val mlcUi = remember {
                    val t0 = SystemClock.elapsedRealtime()
                    val p = ServiceLoader.load(MlcUiProvider::class.java, classLoader)
                        .iterator()
                        .asSequence()
                        .firstOrNull()
                    Log.i(
                        "LeksegTest",
                        "[ui main=${Looper.myLooper() == Looper.getMainLooper()}] ServiceLoader ms=${SystemClock.elapsedRealtime() - t0} mlc=${p != null}",
                    )
                    p
                }
                Column(
                    Modifier
                        .fillMaxSize()
                        .then(
                            if (mlcUi != null) {
                                Modifier.windowInsetsPadding(
                                    WindowInsets.safeDrawing.exclude(WindowInsets.statusBars),
                                )
                            } else {
                                Modifier.safeDrawingPadding()
                            },
                        ),
                ) {
                    MlcIntegrationBanner()
                    if (mlcUi != null) {
                        mlcUi.Chat(Modifier.weight(1f))
                    } else {
                        App(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
        Log.i(
            "LeksegTest",
            "[ui] onCreate setContent called msSinceCreate=${SystemClock.elapsedRealtime() - tCreate}",
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun AppAndroidPreview() {
    App(Modifier.fillMaxSize().safeDrawingPadding())
}
