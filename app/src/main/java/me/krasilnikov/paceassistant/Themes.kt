package me.krasilnikov.paceassistant

import androidx.compose.material.darkColors
import androidx.compose.material.lightColors
import androidx.compose.ui.graphics.Color

private val Purple200 = Color(0xFFBB86FC)
private val Purple500 = Color(0xFF6200EE)
private val Purple700 = Color(0xFF3700B3)

private val Teal200 = Color(0xFF03DAC5)
private val Teal700 = Color(0xFF018786)

val darkPalette = darkColors(
    primary = Purple200,
    primaryVariant = Purple700,
    onPrimary = Color.Black,
    secondary = Teal200,
    onSecondary = Color.Black,
    onBackground = Color.White,
)

val lightPalette = lightColors(
    primary = Purple200,
    primaryVariant = Purple700,
    onPrimary = Color.White,
    secondary = Teal200,
    onSecondary = Color.Black,
)
