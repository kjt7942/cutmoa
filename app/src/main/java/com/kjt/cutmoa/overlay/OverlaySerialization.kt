package com.kjt.cutmoa.overlay

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.json.JSONArray
import org.json.JSONObject

/**
 * WorkManager's Data can only carry flat primitives/arrays, but an overlay list is a
 * variable number of items each with a variable number of keyframes — so it's shipped to
 * the export worker as one JSON string using the platform's built-in org.json (no extra
 * serialization dependency needed for something this small). The editor also uses it to
 * save its layers across rotation/process death.
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
                    .put("rotation", kf.rotation.toDouble())
            )
        }
        array.put(
            JSONObject()
                .put("id", item.id)
                .put("kind", item.kind.name)
                .put("text", item.text)
                .put("color", item.color.toArgb())
                .put("style", item.style.name)
                .put("startMs", item.startMs)
                .put("endMs", item.endMs)
                .put("keyframes", keyframesJson)
                .put("animated", item.animated)
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
                rotation = kf.optDouble("rotation", 0.0).toFloat(),
            )
        }
        OverlayItem(
            id = obj.getLong("id"),
            kind = OverlayKind.valueOf(obj.getString("kind")),
            text = obj.getString("text"),
            color = Color(obj.getInt("color")),
            style = OverlayTextStyle.valueOf(obj.optString("style", OverlayTextStyle.OUTLINE.name)),
            startMs = obj.getLong("startMs"),
            endMs = obj.getLong("endMs"),
            keyframes = keyframes,
            animated = obj.optBoolean("animated", keyframes.size > 1),
        )
    }
}
