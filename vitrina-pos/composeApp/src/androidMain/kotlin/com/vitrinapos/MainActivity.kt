package com.vitrinapos

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.vitrinapos.data.AppGraph
import com.vitrinapos.platform.AndroidPlatform

class VitrinaApp : Application() {
    val graph: AppGraph by lazy { AppGraph(AndroidPlatform(this)) }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val graph = (application as VitrinaApp).graph
        setContent { App(graph) }
    }
}
