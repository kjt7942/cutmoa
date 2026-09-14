package com.kjt.cutmoa.overlay

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.json.JSONArray
import org.json.JSONObject

/**
 * WorkManager's Data can only carry flat primitives/arrays, but an overlay list is a
 * variable number of items each with a variable number of keyframes — so it's shipped to
 * the export worker as one JSON string using the platform's built-in org.json (no extra
 * serialization dependency needed for something this small).
 */
fun List<OverlayItem>.toJson(): String {
    val array = JSONArray()
    forEach { item ->
        val keyframesJson = JSONArray()
        item.keyframes.forEach { kf ->
            keyframesJson.put(
                JSONObject()
                    .put("timeMs", kf.timeMs)
                    .put("x", kf.xFraction.toDouble())
                    .put("y", kf.yFraction.toDouble())
                    .put("scale", kf.scale.toDouble())
            )
        }
        array.put(
            JSONObject()
                .put("id", item.id)
                .put("kind", item.kind.name)
                .put("text", item.text)
                .put("color", item.color.toArgb())
                .put("startMs", item.startMs)
                .put("endMs", item.endMs)
                .put("keyframes", keyframesJson)
        )
    }
    return array.toString()
}

fun parseOverlayItems(json: String): List<OverlayItem> {
    val array = JSONArray(json)
    return (0 until array.length()).map { i ->
        val obj = array.getJSONObject(i)
        val keyframesJson = obj.getJSONArray("keyframes")
        val keyframes = (0 until keyframesJson.length()).map { j ->
            val kf = keyframesJson.getJSONObject(j)
            Keyframe(
                timeMs = kf.getLong("timeMs"),
                xFraction = kf.getDouble("x").toFloat(),
                yFraction = kf.getDouble("y").toFloat(),
                scale = kf.getDouble("scale").toFloat(),
            )
        }
        OverlayItem(
            id = obj.getLong("id"),
            kind = OverlayKind.valueOf(obj.getString("kind")),
            text = obj.getString("text"),
            color = Color(obj.getInt("color")),
            startMs = obj.getLong("startMs"),
            endMs = obj.getLong("endMs"),
            keyframes = keyframes,
        )
    }
}
