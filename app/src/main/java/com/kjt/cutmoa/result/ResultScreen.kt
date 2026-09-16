package com.kjt.cutmoa.result

import android.content.Intent
import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.kjt.cutmoa.util.SystemBarsFor

// Gray, not translucent black: the pills straddle the black letterbox and the video, and
// black-on-black made their top half vanish.
private val PILL_COLOR = Color(0xFF3A3A3A).copy(alpha = 0.75f)

@Composable
fun ResultScreen(finalVideoUri: String, onRestart: () -> Unit) {
    SystemBarsFor(darkContent = true)
    val context = LocalContext.current

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // Platform VideoView: plays the MediaStore content:// URI without adding an ExoPlayer dependency.
        AndroidView(
            modifier = Modifier.fillMaxSize(),
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

        // Top row, not bottom: MediaController's seek bar pops up along the bottom edge.
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "완성! 갤러리에 저장됨",
                color = Color.White,
                modifier = Modifier
                    .background(PILL_COLOR, RoundedCornerShape(50))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    onClick = {
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "video/mp4"
                            putExtra(Intent.EXTRA_STREAM, Uri.parse(finalVideoUri))
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(shareIntent, null))
                    },
                    modifier = Modifier.background(PILL_COLOR, RoundedCornerShape(50)),
                ) {
                    Text("공유", color = Color.White)
                }
                TextButton(
                    onClick = onRestart,
                    modifier = Modifier.background(PILL_COLOR, RoundedCornerShape(50)),
                ) {
                    Text("새로 촬영하기", color = Color.White)
                }
            }
        }
    }
}
