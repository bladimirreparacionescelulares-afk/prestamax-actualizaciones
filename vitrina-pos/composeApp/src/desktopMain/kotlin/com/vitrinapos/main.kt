package com.vitrinapos

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
            state = rememberWindowState(width = 1280.dp, height = 820.dp),
        ) {
            window.minimumSize = java.awt.Dimension(420, 640)
            App(graph)
        }
    }
}
