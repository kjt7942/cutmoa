package com.kjt.cutmoa.merge

import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.sp
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.kjt.cutmoa.util.SystemBarsFor
import com.kjt.cutmoa.util.VideoFrameUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** [fromCamera] just tells the list apart visually — the merge itself only needs the [uri]. */
data class QueuedClip(val uri: Uri, val fromCamera: Boolean)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MergeScreen(clips: List<QueuedClip>, onClipsChange: (List<QueuedClip>) -> Unit, onMerged: (String) -> Unit) {
    SystemBarsFor(darkContent = false)
    val context = LocalContext.current
    val workManager = remember { WorkManager.getInstance(context) }

    val selectedClips = clips

    // A queued clip can be deleted from the gallery before merging; merging it would fail the
    // whole job, so unreadable ones are dropped (on entry and again right before starting).
    fun dropMissing(): List<QueuedClip> {
        val readable = selectedClips.filter {
            runCatching { context.contentResolver.openFileDescriptor(it.uri, "r")!!.close() }.isSuccess
        }
        if (readable.size < selectedClips.size) {
            onClipsChange(readable)
            Toast.makeText(context, "삭제된 클립 ${selectedClips.size - readable.size}개를 목록에서 뺐어요", Toast.LENGTH_SHORT).show()
        }
        return readable
    }
    LaunchedEffect(Unit) { dropMissing() }

    val pickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(20)
    ) { uris ->
        if (uris.isNotEmpty()) {
            val existing = selectedClips.map { it.uri }.toSet()
            onClipsChange(selectedClips + uris.filterNot { it in existing }.map { QueuedClip(it, fromCamera = false) })
        }
    }

    // Watched by the request's stable name, not by an id kept in `remember`: an id is lost on
    // rotation or on leaving and returning to this screen, which orphans the running job (it
    // keeps merging with no one watching, and the start button reappears inviting a second,
    // overlapping run on the same clips).
    val workInfo by remember {
        workManager.getWorkInfosForUniqueWorkFlow(MergeWorker.UNIQUE_WORK_NAME).map { it.firstOrNull() }
    }.collectAsState(initial = null)

    LaunchedEffect(workInfo?.state) {
        val info = workInfo ?: return@LaunchedEffect
        if (info.state == WorkInfo.State.SUCCEEDED) {
            val outputPath = info.outputData.getString(MergeWorker.KEY_OUTPUT_PATH)
            if (outputPath != null) {
                // Finished records outlive the job; without pruning, coming back to this screen
                // would replay this SUCCEEDED state and jump straight to the old merged video.
                workManager.pruneWork()
                onMerged(outputPath)
            }
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
                Text(if (selectedClips.isEmpty()) "촬영본 / 갤러리에서 클립 선택" else "클립 더 추가")
            }

            if (selectedClips.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("선택된 클립 없음", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                var pendingRemoveIndex by remember { mutableStateOf<Int?>(null) }

                LazyColumn(modifier = Modifier.weight(1f).padding(horizontal = 16.dp)) {
                    items(selectedClips, key = { it.uri.toString() }) { clip ->
                        val index = selectedClips.indexOf(clip)
                        ClipRow(
                            order = index + 1,
                            clip = clip,
                            canMoveUp = index > 0,
                            canMoveDown = index < selectedClips.size - 1,
                            onMoveUp = { onClipsChange(selectedClips.swap(index, index - 1)) },
                            onMoveDown = { onClipsChange(selectedClips.swap(index, index + 1)) },
                            onRemove = { pendingRemoveIndex = index },
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
                            val readable = dropMissing()
                            if (readable.isEmpty()) return@Button
                            val request = MergeWorker.buildRequest(readable.map { it.uri })
                            workManager.enqueueUniqueWork(
                                MergeWorker.UNIQUE_WORK_NAME,
                                ExistingWorkPolicy.KEEP,
                                request,
                            )
                        }
                    ) {
                        Text("${selectedClips.size}개 클립 병합 시작")
                    }
                }

                pendingRemoveIndex?.let { index ->
                    AlertDialog(
                        onDismissRequest = { pendingRemoveIndex = null },
                        title = { Text("클립 제거") },
                        text = { Text("${index + 1}번 클립을 목록에서 뺄까요? 촬영본 자체는 갤러리에 그대로 남아요.") },
                        confirmButton = {
                            TextButton(onClick = {
                                onClipsChange(selectedClips.toMutableList().apply { removeAt(index) })
                                pendingRemoveIndex = null
                            }) { Text("제거") }
                        },
                        dismissButton = { TextButton(onClick = { pendingRemoveIndex = null }) { Text("취소") } },
                    )
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
    clip: QueuedClip,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
) {
    val context = LocalContext.current
    val uri = clip.uri
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
            Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                Text(
                    // A recorded clip and a gallery pick look identical otherwise — without
                    // this, there's no way to tell "I just shot this" from "I picked this
                    // from my camera roll" once both sit in the same list.
                    if (clip.fromCamera) "촬영본" else "갤러리",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("$order.  ${uri.lastPathSegment ?: uri}")
            }
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
