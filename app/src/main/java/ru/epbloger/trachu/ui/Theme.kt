package ru.epbloger.trachu.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val Mint = Color(0xFFB9F3D5)
val Deep = Color(0xFF173B35)
private val Light = lightColorScheme(
    primary=Color(0xFF176C54), onPrimary=Color.White,
    primaryContainer=Color(0xFFCEF2DF), onPrimaryContainer=Color(0xFF123F31),
    secondary=Color(0xFF566860), secondaryContainer=Color(0xFFE1EBE5),
    tertiary=Color(0xFF6C5C9E), background=Color(0xFFF4F6F3),
    surface=Color(0xFFFFFFFF), onSurface=Color(0xFF172B25),
    surfaceVariant=Color(0xFFE4ECE7), onSurfaceVariant=Color(0xFF465B52),
    outline=Color(0xFF7C8B83), error=Color(0xFFB3424B)
)
private val Dark = darkColorScheme(
    primary=Color(0xFF9AE5C3), onPrimary=Color(0xFF103C2E),
    primaryContainer=Color(0xFF214F40), onPrimaryContainer=Color(0xFFC6F4DE),
    secondary=Color(0xFFB5C9BD), secondaryContainer=Color(0xFF34463E),
    tertiary=Color(0xFFC8B9F3), background=Color(0xFF101B19),
    surface=Color(0xFF182824), onSurface=Color(0xFFE4F0E9),
    surfaceVariant=Color(0xFF2C4038), onSurfaceVariant=Color(0xFFB9C9C0),
    outline=Color(0xFF81968A), error=Color(0xFFFFAAB1)
)
private val Type = Typography(
    displaySmall=TextStyle(fontFamily=FontFamily.SansSerif,fontWeight=FontWeight.Bold,fontSize=34.sp,lineHeight=40.sp,letterSpacing=(-1).sp),
    headlineLarge=TextStyle(fontWeight=FontWeight.Bold,fontSize=30.sp,lineHeight=36.sp,letterSpacing=(-0.7).sp),
    headlineSmall=TextStyle(fontWeight=FontWeight.SemiBold,fontSize=23.sp,lineHeight=29.sp),
    titleLarge=TextStyle(fontWeight=FontWeight.SemiBold,fontSize=20.sp,lineHeight=26.sp),
    titleMedium=TextStyle(fontWeight=FontWeight.SemiBold,fontSize=16.sp,lineHeight=22.sp),
    bodyLarge=TextStyle(fontSize=16.sp,lineHeight=23.sp),
    bodyMedium=TextStyle(fontSize=14.sp,lineHeight=20.sp),
    labelLarge=TextStyle(fontWeight=FontWeight.Medium,fontSize=14.sp,lineHeight=20.sp)
)
@Composable fun TrachuTheme(theme: String, content: @Composable ()->Unit) {
    val dark=when(theme) { "dark"->true; "light"->false; else->isSystemInDarkTheme() }
    MaterialTheme(colorScheme=if(dark) Dark else Light,typography=Type,
        shapes=Shapes(extraSmall=RoundedCornerShape(8.dp),small=RoundedCornerShape(12.dp),
            medium=RoundedCornerShape(18.dp),large=RoundedCornerShape(24.dp),extraLarge=RoundedCornerShape(32.dp)),content=content)
}
