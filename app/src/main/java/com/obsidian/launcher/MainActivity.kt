@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.obsidian.launcher

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas as ACanvas
import android.graphics.LinearGradient
import android.graphics.Paint as APaint
import android.graphics.Path as APath
import android.graphics.Shader
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlin.math.absoluteValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

val Blue = Color(0xFF0A84FF)
val Gray = Color(0xFF8E8E93)

data class AppItem(val label: String, val pkg: String, val icon: ImageBitmap)

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("obsidian", Context.MODE_PRIVATE)
    fun hidden(): Set<String> = HashSet(sp.getStringSet("hidden", emptySet()) ?: emptySet())
    fun saveHidden(s: Set<String>) { sp.edit().putStringSet("hidden", HashSet(s)).apply() }
    var showLabels: Boolean
        get() = sp.getBoolean("labels", true)
        set(v) { sp.edit().putBoolean("labels", v).apply() }
    var lockHidden: Boolean
        get() = sp.getBoolean("lock", true)
        set(v) { sp.edit().putBoolean("lock", v).apply() }
}

fun squirclePath(size: Float): APath {
    val p = APath()
    val r = size / 2f
    val steps = 120
    for (i in 0..steps) {
        val t = 2.0 * Math.PI * i / steps
        val c = Math.cos(t)
        val s = Math.sin(t)
        val x = r + r * Math.signum(c) * Math.pow(Math.abs(c), 0.4)
        val y = r + r * Math.signum(s) * Math.pow(Math.abs(s), 0.4)
        if (i == 0) p.moveTo(x.toFloat(), y.toFloat()) else p.lineTo(x.toFloat(), y.toFloat())
    }
    p.close()
    return p
}

fun iosIcon(d: Drawable, size: Int = 160): ImageBitmap {
    val src = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val c = ACanvas(src)
    if (d is AdaptiveIconDrawable) {
        val ext = (size * 1.5f).toInt()
        val off = (size - ext) / 2
        d.background?.let { it.setBounds(off, off, off + ext, off + ext); it.draw(c) }
        d.foreground?.let { it.setBounds(off, off, off + ext, off + ext); it.draw(c) }
    } else {
        c.drawColor(android.graphics.Color.WHITE)
        val pad = (size * 0.16f).toInt()
        d.setBounds(pad, pad, size - pad, size - pad)
        d.draw(c)
    }
    val gloss = APaint(APaint.ANTI_ALIAS_FLAG)
    gloss.shader = LinearGradient(
        0f, 0f, 0f, size.toFloat(),
        intArrayOf(0x33FFFFFF, 0x00FFFFFF, 0x14000000),
        floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
    )
    c.drawRect(0f, 0f, size.toFloat(), size.toFloat(), gloss)
    val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val mask = APaint(APaint.ANTI_ALIAS_FLAG)
    mask.shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    ACanvas(out).drawPath(squirclePath(size.toFloat()), mask)
    return out.asImageBitmap()
}

private val iconCache = java.util.concurrent.ConcurrentHashMap<String, ImageBitmap>()

fun loadApps(ctx: Context): List<AppItem> {
    val pm = ctx.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(intent, 0)
        .filter { it.activityInfo.packageName != ctx.packageName }
        .distinctBy { it.activityInfo.packageName }
        .map { ri ->
            val pkg = ri.activityInfo.packageName
            AppItem(ri.loadLabel(pm).toString(), pkg, iconCache.getOrPut(pkg) { iosIcon(ri.loadIcon(pm)) })
        }
        .sortedBy { it.label.lowercase() }
}

fun openApp(ctx: Context, pkg: String) {
    ctx.packageManager.getLaunchIntentForPackage(pkg)?.let { ctx.startActivity(it) }
}

fun authenticate(activity: FragmentActivity, onOk: () -> Unit) {
    val info = BiometricPrompt.PromptInfo.Builder()
        .setTitle("Hidden Apps")
        .setAllowedAuthenticators(
            BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        )
        .build()
    BiometricPrompt(
        activity, ContextCompat.getMainExecutor(activity),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { onOk() }
        }
    ).authenticate(info)
}

fun Modifier.glass(radius: Dp): Modifier {
    val shape = RoundedCornerShape(radius)
    return this.clip(shape)
        .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.34f), Color.White.copy(alpha = 0.12f))))
        .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.7f), Color.White.copy(alpha = 0.08f))), shape)
}

@Composable
fun Modifier.tap(onClick: () -> Unit): Modifier =
    this.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)

@Composable
fun Modifier.bouncy(onLongClick: () -> Unit = {}, onClick: () -> Unit): Modifier {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        if (pressed) 0.82f else 1f, spring(dampingRatio = 0.4f, stiffness = 600f), label = "press"
    )
    val click by rememberUpdatedState(onClick)
    val long by rememberUpdatedState(onLongClick)
    return this.graphicsLayer { scaleX = scale; scaleY = scale }
        .pointerInput(Unit) {
            detectTapGestures(
                onPress = { pressed = true; tryAwaitRelease(); pressed = false },
                onTap = { click() },
                onLongPress = { long() }
            )
        }
}

@Composable
fun AppIcon(app: AppItem, showLabel: Boolean = true, onLongClick: () -> Unit = {}, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.bouncy(onLongClick, onClick).padding(4.dp)
    ) {
        Image(bitmap = app.icon, contentDescription = app.label, modifier = Modifier.size(60.dp))
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
fun AppPage(apps: List<AppItem>, showLabels: Boolean, onMenu: (AppItem) -> Unit, onOpen: (AppItem) -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        apps.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { app ->
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        AppIcon(app, showLabels, { onMenu(app) }) { onOpen(app) }
                    }
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
fun MenuItem(text: String, color: Color = Color.White, onClick: () -> Unit) {
    Text(
        text, color = color, fontSize = 17.sp,
        modifier = Modifier.fillMaxWidth().tap(onClick).padding(horizontal = 18.dp, vertical = 14.dp)
    )
}

@Composable
fun Hairline() {
    Box(Modifier.fillMaxWidth().padding(start = 16.dp).height(0.5.dp).background(Color(0xFF38383A)))
}

@Composable
fun AppMenu(app: AppItem, onHide: () -> Unit, onInfo: () -> Unit, onSettings: () -> Unit, onDismiss: () -> Unit) {
    val scale = remember { Animatable(0.6f) }
    LaunchedEffect(Unit) { scale.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 400f)) }
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.25f)).tap(onDismiss),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier.graphicsLayer { scaleX = scale.value; scaleY = scale.value }
                .width(250.dp).glass(24.dp).background(Color(0x66202022)).tap { }
        ) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Image(app.icon, app.label, Modifier.size(40.dp))
                Text(app.label, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 12.dp))
            }
            Hairline()
            MenuItem("App Info", onClick = onInfo)
            Hairline()
            MenuItem("Obsidian Settings", onClick = onSettings)
            Hairline()
            MenuItem("Hide App", Color(0xFFFF453A), onHide)
        }
    }
}

@Composable
fun IosSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    val x by animateDpAsState(if (checked) 20.dp else 0.dp, spring(dampingRatio = 0.55f, stiffness = 500f), label = "thumb")
    val track by animateColorAsState(if (checked) Color(0xFF34C759) else Color(0xFF39393D), label = "track")
    Box(Modifier.size(51.dp, 31.dp).clip(CircleShape).background(track).tap { onChange(!checked) }.padding(2.dp)) {
        Box(Modifier.offset(x = x).size(27.dp).clip(CircleShape).background(Color.White))
    }
}

@Composable
fun Group(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(14.dp)).background(Color(0xFF1C1C1E)),
        content = content
    )
}

@Composable
fun Cell(title: String, onClick: (() -> Unit)? = null, trailing: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.tap(onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(title, color = Color.White, fontSize = 17.sp)
        trailing()
    }
}

@Composable
fun Footer(text: String) {
    Text(text, color = Gray, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 32.dp, vertical = 6.dp))
}

@Composable
fun SettingsScreen(
    activity: FragmentActivity,
    allApps: List<AppItem>,
    hidden: Set<String>,
    showLabels: Boolean,
    lock: Boolean,
    onLabels: (Boolean) -> Unit,
    onLock: (Boolean) -> Unit,
    onUnhide: (String) -> Unit,
    onClose: () -> Unit
) {
    var page by remember { mutableStateOf("main") }
    BackHandler(enabled = page == "hidden") { page = "main" }
    Box(Modifier.fillMaxSize().background(Color.Black).systemBarsPadding().tap { }) {
        AnimatedContent(
            page,
            transitionSpec = {
                val fwd = targetState == "hidden"
                val sp = spring<IntOffset>(dampingRatio = 0.85f, stiffness = 350f)
                (slideInHorizontally(sp) { if (fwd) it else -it / 3 } + fadeIn()) togetherWith
                    (slideOutHorizontally(sp) { if (fwd) -it / 3 else it } + fadeOut())
            },
            label = "settings"
        ) { p ->
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                if (p == "main") {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Settings", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                        Text("Done", color = Blue, fontSize = 17.sp, modifier = Modifier.tap(onClose).padding(8.dp))
                    }
                    Group {
                        Cell("Show App Names") { IosSwitch(showLabels, onLabels) }
                    }
                    Group {
                        Cell("Require Authentication") {
                            IosSwitch(lock) { want ->
                                if (want) onLock(true) else authenticate(activity) { onLock(false) }
                            }
                        }
                        Hairline()
                        Cell("Hidden Apps", onClick = {
                            if (lock) authenticate(activity) { page = "hidden" } else page = "hidden"
                        }) { Text("${hidden.size}  ›", color = Gray, fontSize = 17.sp) }
                    }
                    Footer("Hidden apps stay off the Home Screen and out of Search. Viewing them needs your fingerprint, face or PIN.")
                } else {
                    Text(
                        "‹ Settings", color = Blue, fontSize = 17.sp,
                        modifier = Modifier.tap { page = "main" }.padding(20.dp)
                    )
                    Text(
                        "Hidden Apps", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                    )
                    val list = allApps.filter { it.pkg in hidden }
                    if (list.isEmpty()) {
                        Footer("No hidden apps. Long-press an app on the Home Screen and choose Hide App.")
                    } else {
                        Group {
                            list.forEachIndexed { i, a ->
                                if (i > 0) Hairline()
                                Row(
                                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Image(a.icon, a.label, Modifier.size(40.dp))
                                    Text(a.label, color = Color.White, fontSize = 17.sp,
                                        modifier = Modifier.weight(1f).padding(start = 12.dp))
                                    Text("Unhide", color = Blue, fontSize = 17.sp,
                                        modifier = Modifier.tap { onUnhide(a.pkg) }.padding(8.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun Launcher(refresh: Int, homeTick: Int) {
    val activity = LocalContext.current as FragmentActivity
    val focus = LocalFocusManager.current
    val density = LocalDensity.current
    val prefs = remember { Prefs(activity) }
    val allApps by produceState<List<AppItem>>(emptyList(), refresh) {
        value = withContext(Dispatchers.Default) { loadApps(activity) }
    }
    var hidden by remember { mutableStateOf(prefs.hidden()) }
    var showLabels by remember { mutableStateOf(prefs.showLabels) }
    var lock by remember { mutableStateOf(prefs.lockHidden) }
    var query by remember { mutableStateOf("") }
    var focused by remember { mutableStateOf(false) }
    var menuApp by remember { mutableStateOf<AppItem?>(null) }
    var settingsOpen by remember { mutableStateOf(false) }

    val apps = remember(allApps, hidden) { allApps.filter { it.pkg !in hidden } }
    val dockApps = apps.take(4)
    val pages = remember(apps) { apps.drop(4).chunked(24) }
    val pagerState = rememberPagerState(pageCount = { pages.size.coerceAtLeast(1) })
    val searching = focused || query.isNotEmpty()

    val open: (AppItem) -> Unit = { query = ""; focus.clearFocus(); openApp(activity, it.pkg) }

    LaunchedEffect(homeTick) {
        query = ""; focus.clearFocus(); settingsOpen = false; menuApp = null
        pagerState.animateScrollToPage(0)
    }
    BackHandler(enabled = settingsOpen || menuApp != null || searching) {
        when {
            menuApp != null -> menuApp = null
            settingsOpen -> settingsOpen = false
            else -> { query = ""; focus.clearFocus() }
        }
    }

    val blur by animateDpAsState(
        if (settingsOpen || menuApp != null) 36.dp else if (searching) 22.dp else 0.dp,
        spring(stiffness = Spring.StiffnessLow), label = "blur"
    )
    SideEffect {
        if (Build.VERSION.SDK_INT >= 31) {
            try { activity.window.setBackgroundBlurRadius(with(density) { blur.roundToPx() }) } catch (_: Throwable) {}
        }
    }

    val pillFrac by animateFloatAsState(
        if (searching) 0.92f else 0.5f, spring(dampingRatio = 0.6f, stiffness = 300f), label = "pill"
    )

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
            Box(Modifier.weight(1f)) {
                if (query.isBlank()) {
                    HorizontalPager(
                        state = pagerState,
                        flingBehavior = PagerDefaults.flingBehavior(
                            pagerState, snapAnimationSpec = spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessLow)
                        )
                    ) { i ->
                        Box(Modifier.graphicsLayer {
                            val off = ((pagerState.currentPage - i) + pagerState.currentPageOffsetFraction)
                                .absoluteValue.coerceIn(0f, 1f)
                            alpha = 1f - 0.6f * off
                            scaleX = 1f - 0.1f * off
                            scaleY = 1f - 0.1f * off
                        }) {
                            AppPage(pages.getOrElse(i) { emptyList() }, showLabels, { menuApp = it }, open)
                        }
                    }
                } else {
                    val results = remember(query, apps) { apps.filter { it.label.contains(query, ignoreCase = true) } }
                    LazyVerticalGrid(
                        GridCells.Fixed(4),
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        items(results, key = { it.pkg }) { app ->
                            Box(contentAlignment = Alignment.Center) {
                                AppIcon(app, showLabels, { menuApp = app }) { open(app) }
                            }
                        }
                    }
                }
            }
            AnimatedVisibility(!searching) {
                Row(
                    Modifier.fillMaxWidth().padding(4.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    repeat(pages.size) { i ->
                        val active = i == pagerState.currentPage
                        val w by animateDpAsState(
                            if (active) 20.dp else 7.dp, spring(dampingRatio = 0.45f, stiffness = 400f), label = "dot"
                        )
                        Box(
                            Modifier.padding(3.dp).height(7.dp).width(w.coerceAtLeast(4.dp)).clip(CircleShape)
                                .background(Color.White.copy(alpha = if (active) 0.95f else 0.4f))
                        )
                    }
                }
            }
            Box(
                Modifier.align(Alignment.CenterHorizontally).fillMaxWidth(pillFrac)
                    .glass(22.dp).padding(horizontal = 14.dp, vertical = 9.dp),
                contentAlignment = Alignment.Center
            ) {
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = TextStyle(color = Color.White, fontSize = 14.sp, textAlign = TextAlign.Center),
                    cursorBrush = SolidColor(Color.White),
                    modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
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
            AnimatedVisibility(
                !searching,
                enter = slideInVertically(spring(dampingRatio = 0.6f, stiffness = 400f)) { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut()
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)
                        .glass(32.dp)
                        .pointerInput(Unit) { detectTapGestures(onLongPress = { settingsOpen = true }) }
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    dockApps.forEach { app ->
                        AppIcon(app, showLabel = false, onLongClick = { menuApp = app }) { open(app) }
                    }
                }
            }
        }

        menuApp?.let { app ->
            AppMenu(
                app,
                onHide = {
                    hidden = hidden + app.pkg
                    prefs.saveHidden(hidden)
                    menuApp = null
                },
                onInfo = {
                    menuApp = null
                    activity.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + app.pkg))
                    )
                },
                onSettings = { menuApp = null; settingsOpen = true },
                onDismiss = { menuApp = null }
            )
        }

        AnimatedVisibility(
            settingsOpen,
            enter = slideInVertically(spring(dampingRatio = 0.8f, stiffness = 300f)) { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut()
        ) {
            SettingsScreen(
                activity, allApps, hidden, showLabels, lock,
                onLabels = { showLabels = it; prefs.showLabels = it },
                onLock = { lock = it; prefs.lockHidden = it },
                onUnhide = { hidden = hidden - it; prefs.saveHidden(hidden) },
                onClose = { settingsOpen = false }
            )
        }
    }
}

class MainActivity : FragmentActivity() {
    private var refresh by mutableIntStateOf(0)
    private var homeTick by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { Launcher(refresh, homeTick) }
    }

    override fun onResume() {
        super.onResume()
        refresh++
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        homeTick++
    }
}
