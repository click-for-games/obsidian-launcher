package com.obsidian.launcher

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap

data class AppItem(val label: String, val pkg: String, val icon: ImageBitmap)

fun loadApps(ctx: Context): List<AppItem> {
    val pm = ctx.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(intent, 0)
        .filter { it.activityInfo.packageName != ctx.packageName }
        .map {
            AppItem(
                it.loadLabel(pm).toString(),
                it.activityInfo.packageName,
                it.loadIcon(pm).toBitmap(120, 120).asImageBitmap()
            )
        }
        .sortedBy { it.label.lowercase() }
}

fun launch(ctx: Context, pkg: String) {
    ctx.packageManager.getLaunchIntentForPackage(pkg)?.let { ctx.startActivity(it) }
}

fun Modifier.glass(radius: Dp): Modifier {
    val shape = RoundedCornerShape(radius)
    return this.clip(shape)
        .background(Color.White.copy(alpha = 0.22f))
        .border(1.dp, Color.White.copy(alpha = 0.3f), shape)
}

@Composable
fun AppIcon(app: AppItem, showLabel: Boolean = true, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick).padding(4.dp)
    ) {
        Image(
            bitmap = app.icon,
            contentDescription = app.label,
            modifier = Modifier.size(58.dp).clip(RoundedCornerShape(14.dp))
        )
        if (showLabel) {
            Text(
                app.label, color = Color.White, fontSize = 11.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
fun AppPage(apps: List<AppItem>, onOpen: (AppItem) -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        apps.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { app ->
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        AppIcon(app) { onOpen(app) }
                    }
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
fun Launcher() {
    val ctx = LocalContext.current
    val apps = remember { loadApps(ctx) }
    var query by remember { mutableStateOf("") }
    val dockApps = apps.take(4)
    val pages = apps.drop(4).chunked(24)
    val pagerState = rememberPagerState(pageCount = { pages.size.coerceAtLeast(1) })
    val open: (AppItem) -> Unit = { query = ""; launch(ctx, it.pkg) }

    Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
        Box(Modifier.weight(1f)) {
            if (query.isBlank()) {
                HorizontalPager(state = pagerState) { i ->
                    AppPage(pages.getOrElse(i) { emptyList() }, open)
                }
            } else {
                val results = apps.filter { it.label.contains(query, ignoreCase = true) }
                LazyVerticalGrid(
                    GridCells.Fixed(4),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    items(results) { app ->
                        Box(contentAlignment = Alignment.Center) { AppIcon(app) { open(app) } }
                    }
                }
            }
        }
        if (query.isBlank()) {
            Row(Modifier.fillMaxWidth().padding(4.dp), horizontalArrangement = Arrangement.Center) {
                repeat(pages.size) { i ->
                    Box(
                        Modifier.padding(3.dp).size(7.dp).clip(CircleShape).background(
                            Color.White.copy(alpha = if (i == pagerState.currentPage) 0.95f else 0.4f)
                        )
                    )
                }
            }
        }
        Box(
            Modifier.align(Alignment.CenterHorizontally).fillMaxWidth(0.55f)
                .glass(20.dp).padding(horizontal = 14.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
                cursorBrush = SolidColor(Color.White),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.Center) {
                        if (query.isEmpty()) {
                            Text("Search", color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp)
                        }
                        inner()
                    }
                }
            )
        }
        if (query.isBlank()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)
                    .glass(30.dp).padding(12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                dockApps.forEach { AppIcon(it, showLabel = false) { open(it) } }
            }
        }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { Launcher() }
    }
}
