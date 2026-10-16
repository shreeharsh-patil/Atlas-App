package com.meetingatlas

import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import com.meetingatlas.data.MeetingDatabase
import com.meetingatlas.recording.RecordingRecoveryManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch
import com.meetingatlas.ui.MeetingAtlasApp
import com.meetingatlas.ui.theme.MeetingAtlasTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var database: MeetingDatabase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            try { RecordingRecoveryManager(applicationContext, database).recoverInterruptedSessions() }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { android.widget.Toast.makeText(this@MainActivity, "Audio files were kept, but recovery could not finish. Reopen the app to retry.", android.widget.Toast.LENGTH_LONG).show() }
        }
        setContent {
            MeetingAtlasTheme {
                MeetingAtlasApp()
            }
        }
    }
}
