package com.ris.imagedistributor.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.ris.imagedistributor.ui.theme.ImageDropTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as ImageDropApplication).container
        setContent {
            ImageDropTheme {
                ImageDropApp(container)
            }
        }
    }
}
