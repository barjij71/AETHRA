package org.aethra.assistant

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.runtime.CompositionLocalProvider

private val Night = Color(0xFF070B14)
private val Panel = Color(0xFF111A2A)
private val Cyan = Color(0xFF67E8F9)
private val Violet = Color(0xFF9B8CFF)
private val Muted = Color(0xFF9AAAC0)

private val AethraColors = darkColorScheme(
    primary = Cyan,
    secondary = Violet,
    background = Night,
    surface = Panel,
    onPrimary = Color(0xFF061018),
    onBackground = Color(0xFFEAF2FF),
    onSurface = Color(0xFFEAF2FF)
)

private data class Destination(val title: String, val symbol: String)
private val destinations = listOf(
    Destination("الرئيسية", "⌂"),
    Destination("المهام", "◷"),
    Destination("الأدوات", "◇"),
    Destination("الإعدادات", "⚙")
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = AethraColors) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    AethraApp()
                }
            }
        }
    }
}

@Composable
private fun AethraApp() {
    var selectedPage by remember { mutableIntStateOf(0) }
    val pagerState = rememberPagerState(pageCount = { destinations.size })

    LaunchedEffect(pagerState.currentPage) {
        selectedPage = pagerState.currentPage
    }
    LaunchedEffect(selectedPage) {
        if (pagerState.currentPage != selectedPage) {
            pagerState.animateScrollToPage(selectedPage)
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.navigationBars,
        containerColor = Night,
        bottomBar = {
            NavigationDock(selectedPage = selectedPage, onSelect = { selectedPage = it })
        }
    ) { innerPadding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            beyondViewportPageCount = 1
        ) { page ->
            when (page) {
                0 -> HomePage(
                    onTasks = { selectedPage = 1 },
                    onTools = { selectedPage = 2 },
                    onSettings = { selectedPage = 3 }
                )
                1 -> TasksPage()
                2 -> ToolsPage()
                else -> SettingsPage()
            }
        }
    }
}

@Composable
private fun NavigationDock(selectedPage: Int, onSelect: (Int) -> Unit) {
    Surface(color = Color(0xF20D1422), tonalElevation = 8.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            destinations.forEachIndexed { index, destination ->
                val selected = index == selectedPage
                Column(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { onSelect(index) }
                        .background(if (selected) Cyan.copy(alpha = 0.12f) else Color.Transparent)
                        .padding(horizontal = 13.dp, vertical = 7.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Text(
                        text = destination.symbol,
                        color = if (selected) Cyan else Muted,
                        fontSize = 21.sp,
                        lineHeight = 23.sp
                    )
                    Text(
                        text = destination.title,
                        color = if (selected) Color.White else Muted,
                        fontSize = 11.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                    )
                }
            }
        }
    }
}

@Composable
private fun PageFrame(title: String, subtitle: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF0C1423), Night)))
            .padding(horizontal = 20.dp, vertical = 18.dp)
    ) {
        Text(title, color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
        Text(
            subtitle,
            modifier = Modifier.padding(top = 5.dp, bottom = 18.dp),
            color = Muted,
            fontSize = 13.sp
        )
        content()
    }
}

@Composable
private fun HomePage(onTasks: () -> Unit, onTools: () -> Unit, onSettings: () -> Unit) {
    PageFrame("مرحبًا، أنا إيثرا", "مساعدتك المستقلة — من مكان واحد") {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(2.dp))
            HologramOrb()
            Text(
                "أنا هنا لمساعدتك",
                modifier = Modifier.padding(top = 2.dp),
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "النواة قيد التأسيس • الواجهة تجريبية",
                modifier = Modifier.padding(top = 7.dp, bottom = 18.dp),
                color = Muted,
                fontSize = 12.sp,
                textAlign = TextAlign.Center
            )
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = Panel),
                border = androidx.compose.foundation.BorderStroke(1.dp, Cyan.copy(alpha = 0.20f))
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("ماذا تريد أن نفعل؟", color = Color.White, fontWeight = FontWeight.SemiBold)
                    Text(
                        "اختر مساحة العمل. ستُربط الإجراءات بالنواة عند تنفيذها.",
                        modifier = Modifier.padding(top = 5.dp, bottom = 13.dp),
                        color = Muted,
                        fontSize = 12.sp
                    )
                    ActionRow("متابعة المهام", "عرض التنفيذ والحالة", "←", onTasks)
                    ActionRow("المهارات والأدوات", "إدارة التكاملات والصلاحيات", "◇", onTools)
                    ActionRow("الإعدادات", "المظهر والأداء والخصوصية", "⚙", onSettings)
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "اسحب أفقيًا للتنقل، أو استخدم الشريط السفلي.",
                color = Muted,
                fontSize = 11.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun HologramOrb() {
    val transition = rememberInfiniteTransition(label = "hologram")
    val pulse by transition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "orb-pulse"
    )
    Box(
        modifier = Modifier
            .size(174.dp)
            .clip(CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val base = size.minDimension * 0.30f * pulse
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Cyan.copy(alpha = 0.25f), Violet.copy(alpha = 0.11f), Color.Transparent),
                    center = center,
                    radius = size.minDimension * 0.49f
                ),
                radius = size.minDimension * 0.49f,
                center = center
            )
            drawCircle(Cyan.copy(alpha = 0.65f), radius = base * 1.14f, center = center, style = Stroke(width = 1.5.dp.toPx()))
            drawCircle(Violet.copy(alpha = 0.75f), radius = base * 0.86f, center = center, style = Stroke(width = 2.dp.toPx()))
            drawCircle(
                brush = Brush.radialGradient(listOf(Color(0xFFB6FBFF), Color(0xFF38BDF8), Color(0xFF5B4BC4))),
                radius = base * 0.62f,
                center = center
            )
            drawCircle(Color.White.copy(alpha = 0.75f), radius = base * 0.13f, center = Offset(center.x - base * 0.20f, center.y - base * 0.25f))
            drawArc(
                color = Cyan.copy(alpha = 0.65f),
                startAngle = 205f,
                sweepAngle = 130f,
                useCenter = false,
                topLeft = Offset(center.x - base * 1.38f, center.y - base * 1.38f),
                size = androidx.compose.ui.geometry.Size(base * 2.76f, base * 2.76f),
                style = Stroke(width = 1.4.dp.toPx(), cap = StrokeCap.Round)
            )
        }
    }
}

@Composable
private fun ActionRow(title: String, description: String, symbol: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 11.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Cyan.copy(alpha = 0.10f))
                .border(1.dp, Cyan.copy(alpha = 0.15f), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(symbol, color = Cyan, fontSize = 19.sp)
        }
        Column(Modifier.weight(1f).padding(horizontal = 11.dp)) {
            Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(description, color = Muted, fontSize = 11.sp)
        }
        Text("‹", color = Cyan, fontSize = 25.sp)
    }
}

@Composable
private fun SectionCard(title: String, detail: String, status: String, accent: Color = Cyan) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 11.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Panel),
        border = androidx.compose.foundation.BorderStroke(1.dp, accent.copy(alpha = 0.18f))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(15.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(9.dp).clip(CircleShape).background(accent)
            )
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(detail, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            }
            Text(status, color = accent, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun TasksPage() {
    PageFrame("مركز المهام", "حالات واضحة بدل مؤشرات تقدم وهمية") {
        SectionCard("محرك المهام", "سيُدار التنفيذ والاستئناف من النواة", "قيد البناء")
        SectionCard("نقاط الحفظ والاستعادة", "حفظ الحالة قبل استئناف المهام الطويلة", "مخطط", Violet)
        SectionCard("التحقق من النتائج", "كل نتيجة تحتاج إلى دليل قابل للفحص", "مخطط")
        Text(
            "لا توجد مهام فعلية بعد. ستظهر هنا عند ربط محرك المهام.",
            color = Muted,
            fontSize = 12.sp,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun ToolsPage() {
    PageFrame("الأدوات والمهارات", "مكونات موحدة مع صلاحيات قابلة للمراجعة") {
        SectionCard("المهارات والإضافات", "المصدر والإصدار والأذونات ومراجعة أول تشغيل", "مخطط")
        SectionCard("بيئة Linux", "تشغيل معزول أولًا، والصلاحيات الممتازة منفصلة", "لاحقًا", Violet)
        SectionCard("مختبر REA", "تحليل التطبيقات مع حفظ التقارير والأدلة", "لاحقًا")
        SectionCard("ربط الحاسوب", "تكامل اختياري بعد استقرار نواة Android", "لاحقًا", Violet)
        Text(
            "لن تُشغّل هذه الأدوات من الواجهة قبل تنفيذ طبقة الصلاحيات والتحقق.",
            color = Muted,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 4.dp),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun SettingsPage() {
    PageFrame("الإعدادات", "تحكم واضح في المظهر والأداء والخصوصية") {
        SectionCard("المظهر والحركة", "مستويات اقتصادي ومتوازن وسينمائي", "قريبًا")
        SectionCard("اللغة واتجاه العرض", "العربية وRTL مع دعم الإنجليزية", "أساسي")
        SectionCard("الذاكرة والخصوصية", "المراجعة والتصدير والحذف محليًا", "مخطط", Violet)
        SectionCard("النماذج والمزودون", "اختيارات محلية وسحابية وسياسة بدائل", "مخطط")
        SectionCard("الأمان والصلاحيات", "موافقة صريحة على العمليات الحساسة", "أساسي", Violet)
    }
}
