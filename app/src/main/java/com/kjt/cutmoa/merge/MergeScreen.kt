package com.kjt.cutmoa.merge

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.kjt.cutmoa.util.VideoFrameUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MergeScreen(onMerged: (String) -> Unit) {
    val context = LocalContext.current
    val workManager = remember { WorkManager.getInstance(context) }

    var selectedClips by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var activeWorkId by remember { mutableStateOf<UUID?>(null) }

    val pickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(20)
    ) { uris -> if (uris.isNotEmpty()) selectedClips = uris }

    val workInfoFlow = remember(activeWorkId) {
        activeWorkId?.let { workManager.getWorkInfoByIdFlow(it) } ?: flowOf(null)
    }
    val workInfo by workInfoFlow.collectAsState(initial = null)

    LaunchedEffect(workInfo?.state) {
        val info = workInfo ?: return@LaunchedEffect
        if (info.state == WorkInfo.State.SUCCEEDED) {
            val outputPath = info.outputData.getString(MergeWorker.KEY_OUTPUT_PATH)
            if (outputPath != null) onMerged(outputPath)
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("클립 병합") }) }) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Button(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                onClick = {
                    pickerLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                    )
                }
            ) {
                Text("촬영본 / 갤러리에서 클립 선택")
            }

            if (selectedClips.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("선택된 클립 없음", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(modifier = Modifier.weight(1f).padding(horizontal = 16.dp)) {
                    items(selectedClips, key = { it.toString() }) { uri ->
                        val index = selectedClips.indexOf(uri)
                        ClipRow(
                            order = index + 1,
                            uri = uri,
                            canMoveUp = index > 0,
                            canMoveDown = index < selectedClips.size - 1,
                            onMoveUp = { selectedClips = selectedClips.swap(index, index - 1) },
                            onMoveDown = { selectedClips = selectedClips.swap(index, index + 1) },
                            onRemove = { selectedClips = selectedClips.toMutableList().apply { removeAt(index) } },
                        )
                    }
                }

                val progress = workInfo?.progress?.getInt(MergeWorker.KEY_PROGRESS, 0) ?: 0
                val isRunning = workInfo?.state == WorkInfo.State.RUNNING || workInfo?.state == WorkInfo.State.ENQUEUED

                if (isRunning) {
                    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                        LinearProgressIndicator(
                            progress = { progress / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text("병합 중… $progress%")
                    }
                } else {
                    Button(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        onClick = {
                            val request = MergeWorker.buildRequest(selectedClips)
                            activeWorkId = request.id
                            workManager.enqueue(request)
                        }
                    ) {
                        Text("${selectedClips.size}개 클립 병합 시작")
                    }
                }

                if (workInfo?.state == WorkInfo.State.FAILED) {
                    Text(
                        "병합 실패: ${workInfo?.outputData?.getString(MergeWorker.KEY_ERROR)}",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ClipRow(
    order: Int,
    uri: Uri,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
) {
    val context = LocalContext.current
    var thumbnail by remember(uri) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(uri) {
        thumbnail = withContext(Dispatchers.IO) { VideoFrameUtil.frameAt(context, uri) }
    }

    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.DarkGray),
            ) {
                thumbnail?.let { bitmap ->
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
            }
            Text(
                "$order.  ${uri.lastPathSegment ?: uri}",
                modifier = Modifier.weight(1f).padding(start = 8.dp),
            )
            IconButton(onClick = onMoveUp, enabled = canMoveUp) {
                Icon(Icons.Filled.ArrowUpward, contentDescription = "위로")
            }
            IconButton(onClick = onMoveDown, enabled = canMoveDown) {
                Icon(Icons.Filled.ArrowDownward, contentDescription = "아래로")
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Filled.Close, contentDescription = "제거")
            }
        }
    }
}

private fun <T> List<T>.swap(a: Int, b: Int): List<T> =
    toMutableList().apply { val tmp = this[a]; this[a] = this[b]; this[b] = tmp }
