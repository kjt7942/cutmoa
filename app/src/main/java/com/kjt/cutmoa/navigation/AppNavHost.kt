package com.kjt.cutmoa.navigation

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.work.WorkManager
import androidx.core.net.toUri
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.kjt.cutmoa.camera.CameraScreen
import com.kjt.cutmoa.merge.MergeScreen
import com.kjt.cutmoa.merge.QueuedClip
import com.kjt.cutmoa.overlay.OverlayEditorScreen
import com.kjt.cutmoa.result.ResultScreen

/** Survives rotation/process-death (e.g. app backgrounded mid-recording) by saving plain strings. */
private val cutsSaver = listSaver<List<QueuedClip>, String>(
    save = { list -> list.map { "${it.fromCamera}|${it.uri}" } },
    restore = { saved ->
        saved.map {
            val (fromCamera, uri) = it.split("|", limit = 2)
            QueuedClip(uri.toUri(), fromCamera.toBoolean())
        }
    },
)

private object Routes {
    const val RECORD = "record"
    const val MERGE = "merge"
    const val OVERLAY = "overlay/{path}"
    const val RESULT = "result/{uri}"

    fun overlay(path: String) = "overlay/${Uri.encode(path)}"
    fun result(uri: String) = "result/${Uri.encode(uri)}"
}

@Composable
fun AppNavHost() {
    val navController = rememberNavController()
    val context = LocalContext.current

    // Hoisted above both screens so cuts recorded on the camera screen are already sitting
    // in the merge screen's list when the user gets there — no manual gallery re-selection.
    var cuts by rememberSaveable(stateSaver = cutsSaver) { mutableStateOf(emptyList<QueuedClip>()) }

    NavHost(navController = navController, startDestination = Routes.RECORD) {
        composable(Routes.RECORD) {
            CameraScreen(
                cutCount = cuts.size,
                // Finalize can, in principle, report a clip already in the list (e.g. a
                // stray duplicate event) — dedupe here rather than trust the caller, since a
                // repeated key would crash the merge screen's LazyColumn outright.
                onCutRecorded = { uri ->
                    if (cuts.none { it.uri == uri }) cuts = cuts + QueuedClip(uri, fromCamera = true)
                },
                onNavigateToMerge = { navController.navigate(Routes.MERGE) },
            )
        }

        composable(Routes.MERGE) {
            MergeScreen(
                clips = cuts,
                onClipsChange = { cuts = it },
                onMerged = { mergedPath -> navController.navigate(Routes.overlay(mergedPath)) },
            )
        }

        composable(
            route = Routes.OVERLAY,
            arguments = listOf(navArgument("path") { type = NavType.StringType }),
        ) { backStackEntry ->
            val path = backStackEntry.arguments?.getString("path").orEmpty()
            OverlayEditorScreen(
                mergedVideoPath = path,
                onExported = { finalUri -> navController.navigate(Routes.result(finalUri)) },
                onBack = { navController.popBackStack() },
            )
        }

        composable(
            route = Routes.RESULT,
            arguments = listOf(navArgument("uri") { type = NavType.StringType }),
        ) { backStackEntry ->
            val uri = backStackEntry.arguments?.getString("uri").orEmpty()
            ResultScreen(
                finalVideoUri = uri,
                onRestart = {
                    // New shoot starts empty — otherwise clips from the finished video would
                    // reappear pre-selected the next time the user reaches the merge screen.
                    cuts = emptyList()
                    // Also clear finished job records (e.g. an old FAILED merge) so the new
                    // shoot's merge screen doesn't show the previous run's error.
                    WorkManager.getInstance(context).pruneWork()
                    navController.navigate(Routes.RECORD) {
                        popUpTo(Routes.RECORD) { inclusive = true }
                    }
                },
            )
        }
    }
}
