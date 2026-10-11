
package com.sonic.assistant

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SonicScreen()
        }
    }
}

@Composable
fun SonicScreen() {
    var darkMode by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf("") }

    val background = if (darkMode) Color(0xFF050816) else Color(0xFFF3F7FF)
    val foreground = if (darkMode) Color.White else Color(0xFF10182B)
    val blue = Color(0xFF168BFF)

    val transition = rememberInfiniteTransition(label = "orb")
    val pulse by transition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    MaterialTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "SONIC",
                        color = foreground,
                        fontSize = 25.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 5.sp
                    )
                    TextButton(onClick = { darkMode = !darkMode }) {
                        Text(
                            if (darkMode) "☀ Light" else "☾ Dark",
                            color = foreground
                        )
                    }
                }

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(280.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(250.dp)
                                .scale(pulse)
                                .alpha(0.18f)
                                .background(blue, CircleShape)
                                .blur(30.dp)
                        )
                        Box(
                            modifier = Modifier
                                .size(205.dp)
                                .scale(pulse)
                                .background(
                                    Brush.radialGradient(
                                        colors = listOf(
                                            Color(0xFFB8E5FF),
                                            Color(0xFF168BFF),
                                            Color(0xFF1834C8),
                                            Color(0xFF080F36)
                                        )
                                    ),
                                    CircleShape
                                )
                        )
                        Box(
                            modifier = Modifier
                                .size(165.dp)
                                .background(
                                    Brush.radialGradient(
                                        colors = listOf(
                                            Color(0xFF64C9FF),
                                            Color(0xFF176CFF),
                                            Color(0xFF102A96)
                                        )
                                    ),
                                    CircleShape
                                )
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    Text(
                        text = "Your world. One assistant.",
                        color = foreground,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Sonic is ready when you are.",
                        color = foreground.copy(alpha = 0.65f),
                        fontSize = 14.sp
                    )
                }

                OutlinedTextField(
                    value = message,
                    onValueChange = { message = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Ask Sonic anything...") },
                    singleLine = true,
                    trailingIcon = {
                        TextButton(onClick = {
                            message = ""
                        }) {
                            Text("➤", color = blue, fontSize = 22.sp)
                        }
                    }
                )

                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "VOICE • INTELLIGENCE • ACTION",
                    color = foreground.copy(alpha = 0.5f),
                    fontSize = 10.sp,
                    letterSpacing = 2.sp
                )
            }
        }
    }
}
