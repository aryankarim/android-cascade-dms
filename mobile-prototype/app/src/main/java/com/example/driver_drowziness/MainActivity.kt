package com.example.driver_drowziness

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.driver_drowziness.screens.LiveMonitorScreen
import com.example.driver_drowziness.screens.VideoTestingScreen
import com.example.driver_drowziness.ui.theme.DriverdrowzinessTheme

/**
 * Main application activity responsible for runtime permission handling and setting up the root UI view.
 */
class MainActivity : ComponentActivity() {


    private val cameraPermissionRequest =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                setComposeContent()
            }
        }

    /**
     * Entry point of the activity; verifies camera permissions before launching the UI content.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            setComposeContent()
        } else {
            cameraPermissionRequest.launch(Manifest.permission.CAMERA)
        }
    }

    /**
     * Sets the Jetpack Compose content view wrapped inside the application theme.
     */
    private fun setComposeContent() {
        setContent {
            DriverdrowzinessTheme {
                Layout()
            }
        }
    }
}


/**
 * Main dashboard screen providing tabs to switch between the Live Camera feed and Video Testing evaluation.
 */
@Composable
fun Layout() {
    var selectedTab by remember { mutableIntStateOf(1) } // 0=Live, 1=Video Testing
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Button(
                onClick = { selectedTab = 0 },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (selectedTab == 0) MaterialTheme.colorScheme.primary else Color.DarkGray
                )
            ) { Text("Live Camera", fontSize = 12.sp) }

            Button(
                onClick = { selectedTab = 1 },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (selectedTab == 1) MaterialTheme.colorScheme.primary else Color.DarkGray
                )
            ) { Text("Video Testing", fontSize = 12.sp) }
        }

        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            when (selectedTab) {
                0 -> LiveMonitorScreen()
                1 -> VideoTestingScreen()
            }
        }
    }
}