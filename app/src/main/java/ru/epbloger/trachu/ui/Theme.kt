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

val Lavender = Color(0xFFE8E2FC)
val Graphite = Color(0xFF27243E)
private val Light = lightColorScheme(
    primary=Color(0xFF6143CC), onPrimary=Color.White,
    primaryContainer=Lavender, onPrimaryContainer=Color(0xFF422B99),
    secondary=Color(0xFF555675), secondaryContainer=Color(0xFFE5E5F5),
    onSecondaryContainer=Color(0xFF30304D),
    tertiary=Color(0xFF995041), background=Color(0xFFF5F5F8),
    surface=Color.White, onSurface=Color(0xFF242338),
    surfaceVariant=Color(0xFFE8E7EF), onSurfaceVariant=Color(0xFF545166),
    outline=Color(0xFF77738B), error=Color(0xFFB23D52)
)
private val Dark = darkColorScheme(
    primary=Color(0xFFC7B9FF), onPrimary=Color(0xFF2F206C),
    primaryContainer=Color(0xFF49358C), onPrimaryContainer=Color(0xFFE8E2FC),
    secondary=Color(0xFFC5C5E1), secondaryContainer=Color(0xFF383851),
    onSecondaryContainer=Color(0xFFE5E5F5),
    tertiary=Color(0xFFFFB8AA), background=Color(0xFF161522),
    surface=Color(0xFF222131), onSurface=Color(0xFFF1EFFA),
    surfaceVariant=Color(0xFF39364A), onSurfaceVariant=Color(0xFFCDC8DE),
    outline=Color(0xFF9A95AF), error=Color(0xFFFFAAB9)
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
