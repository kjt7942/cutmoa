package com.kjt.cutmoa.result

import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

@Composable
fun ResultScreen(finalVideoUri: String, onRestart: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("완성! 갤러리에 저장됨", style = MaterialTheme.typography.headlineSmall)
        // Platform VideoView: plays the MediaStore content:// URI without adding an ExoPlayer dependency.
        AndroidView(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            factory = { context ->
                VideoView(context).apply {
                    setMediaController(MediaController(context))
                    setVideoURI(Uri.parse(finalVideoUri))
                    setOnPreparedListener { player ->
                        player.isLooping = true
                        start()
                    }
                }
            },
            onRelease = { it.stopPlayback() },
        )
        Button(onClick = onRestart) { Text("새로 촬영하기") }
    }
}
