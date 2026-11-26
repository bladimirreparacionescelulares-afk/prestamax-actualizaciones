package com.vitrinapos

import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.vitrinapos.data.AppGraph
import com.vitrinapos.platform.DesktopPlatform

fun main() {
    val graph = AppGraph(DesktopPlatform())
    application {
        Window(
            onCloseRequest = { graph.repo.close(); exitApplication() },
            title = "Vitrina POS",
            icon = remember { BitmapPainter(Thread.currentThread().contextClassLoader.getResourceAsStream("icono.png")!!.use { it.readAllBytes() }.decodeToImageBitmap()) },
            state = rememberWindowState(width = 1280.dp, height = 820.dp),
        ) {
            window.minimumSize = java.awt.Dimension(420, 640)
            App(graph)
        }
    }
}

private fun ByteArray.decodeToImageBitmap() = org.jetbrains.skia.Image.makeFromEncoded(this).toComposeImageBitmap()
