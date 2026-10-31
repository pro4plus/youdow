package app.localdrop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object Palette {
    val Background=Color(0xFF11141A)
    val Surface=Color(0xFF1B2029)
    val Elevated=Color(0xFF232A36)
    val Border=Color(0xFF2A3140)
    val Outline=Color(0xFF3A4354)
    val Text=Color(0xFFF2F4F7)
    val Muted=Color(0xFFA3ABB8)
    val Accent=Color(0xFFC6F135)
}
private val Plex=FontFamily(
    Font(R.font.plex_arabic_regular,FontWeight.Normal),
    Font(R.font.plex_arabic_medium,FontWeight.Medium),
    Font(R.font.plex_arabic_bold,FontWeight.Bold))
private fun type(size: Int, height: Int, weight: FontWeight=FontWeight.Normal)=
    TextStyle(fontFamily=Plex,fontSize=size.sp,lineHeight=height.sp,fontWeight=weight)

@Composable fun LocalDropTheme(content: @Composable ()->Unit) {
    MaterialTheme(colorScheme=darkColorScheme(
        primary=Palette.Accent,onPrimary=Palette.Background,
        primaryContainer=Palette.Elevated,onPrimaryContainer=Palette.Text,
        secondary=Palette.Accent,onSecondary=Palette.Background,
        background=Palette.Background,onBackground=Palette.Text,
        surface=Palette.Surface,onSurface=Palette.Text,
        surfaceVariant=Palette.Elevated,onSurfaceVariant=Palette.Muted,
        outline=Palette.Outline,outlineVariant=Palette.Border,
        error=Palette.Text,onError=Palette.Background),typography=Typography(
            displayLarge=type(40,52,FontWeight.Bold),
            headlineLarge=type(32,44,FontWeight.Bold),headlineMedium=type(28,38,FontWeight.Bold),
            titleLarge=type(20,30,FontWeight.Bold),titleMedium=type(16,25,FontWeight.Medium),
            titleSmall=type(14,22,FontWeight.Medium),bodyLarge=type(16,26),bodyMedium=type(14,23),
            bodySmall=type(12,20),labelLarge=type(15,24,FontWeight.Medium),
            labelMedium=type(12,20,FontWeight.Medium),labelSmall=type(11,18)),content=content)
}
@Composable fun LineIcon(id: Int,description: String?=null,tint: Color=Palette.Muted,modifier: Modifier=Modifier) {
    Icon(painterResource(id),description,modifier.size(24.dp),tint=tint)
}
@Composable fun PrimaryButton(text: String,onClick: ()->Unit,enabled: Boolean=true,modifier: Modifier=Modifier,icon: Int?=null) {
    Button(onClick=onClick,enabled=enabled,modifier=modifier.fillMaxWidth().heightIn(min=52.dp),
        shape=RoundedCornerShape(16.dp),colors=ButtonDefaults.buttonColors(containerColor=Palette.Accent,
            contentColor=Palette.Background,disabledContainerColor=Palette.Elevated,disabledContentColor=Palette.Muted)) {
        if(icon != null) { LineIcon(icon,tint=Palette.Background); Spacer(Modifier.width(8.dp)) }
        Text(text)
    }
}
@Composable fun Panel(modifier: Modifier=Modifier,content: @Composable ColumnScope.()->Unit) {
    Surface(modifier=modifier.fillMaxWidth(),shape=RoundedCornerShape(20.dp),color=Palette.Surface,
        border=BorderStroke(1.dp,Palette.Border)) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp),content=content)
    }
}
