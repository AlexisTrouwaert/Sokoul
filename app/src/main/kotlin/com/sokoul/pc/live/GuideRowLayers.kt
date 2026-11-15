package com.sokoul.pc.live

import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.unit.IntSize

/** A fixed pool of display lists, released by rememberGraphicsLayer with the guide. */
internal class GuideRowLayers(private val layers: List<GraphicsLayer>) {
    private val keys = arrayOfNulls<Any>(layers.size)

    fun draw(scope: DrawScope, row: Int, key: Any, size: IntSize, content: DrawScope.() -> Unit) {
        val slot = row % layers.size
        val layer = layers[slot]
        if (keys[slot] != key) {
            layer.record(scope, scope.layoutDirection, size, content)
            keys[slot] = key
        }
        with(scope) { drawLayer(layer) }
    }
}
