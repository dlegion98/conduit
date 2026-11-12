package dev.conduit.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.conduit.feature.RingHandler

class RingActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { dev.conduit.ui.theme.ConduitTheme(dark = true) {
            Column(
                Modifier.fillMaxSize().background(dev.conduit.ui.theme.Conduit.colors.background),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Found it?", color = Color.White, fontSize = 34.sp)
                Spacer(Modifier.height(8.dp))
                Text("Your Mac is ringing this phone", color = Color(0xFF9AA4B2), fontSize = 16.sp)
                Spacer(Modifier.height(40.dp))
                Button(onClick = { RingHandler.stop(this@RingActivity); finish() }, modifier = Modifier.size(200.dp, 64.dp)) {
                    Text("Stop", fontSize = 20.sp)
                }
            }
        } }
    }
}
