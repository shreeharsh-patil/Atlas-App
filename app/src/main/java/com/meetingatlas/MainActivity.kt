package com.meetingatlas

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.meetingatlas.data.MeetingDatabase
import com.meetingatlas.recording.RecordingRecoveryManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch
import com.meetingatlas.ui.MeetingAtlasApp
import com.meetingatlas.ui.theme.MeetingAtlasTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var database: MeetingDatabase
    private var dictationSignal by mutableIntStateOf(0)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(SHOW_DICTATION, false)) dictationSignal++
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getBooleanExtra(SHOW_DICTATION, false)) dictationSignal++
        // Android 15+ draws every app edge-to-edge; opt in explicitly so system-bar icon contrast follows the theme.
        enableEdgeToEdge()
        lifecycleScope.launch {
            try { RecordingRecoveryManager(applicationContext, database).recoverInterruptedSessions() }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { android.widget.Toast.makeText(this@MainActivity, "Audio files were kept, but recovery could not finish. Reopen the app to retry.", android.widget.Toast.LENGTH_LONG).show() }
        }
        setContent {
            MeetingAtlasTheme {
                MeetingAtlasApp(dictationSignal = dictationSignal)
            }
        }
    }

    companion object { const val SHOW_DICTATION = "com.meetingatlas.SHOW_DICTATION" }
}
