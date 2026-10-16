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
            RecordingRecoveryManager(this@MainActivity, database.meetingDao(), database.audioSegmentDao()).recoverInterruptedSessions()
        }
        setContent {
            MeetingAtlasTheme {
                MeetingAtlasApp()
            }
        }
    }
}
