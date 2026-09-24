package com.example.floodapptest

import PvrDestination
import RouteData
import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.webkit.WebView
import android.webkit.WebViewClient
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import android.Manifest
import android.content.pm.PackageManager
import android.os.Looper
import com.google.android.gms.location.*
import com.google.gson.Gson

// Вспомогательный класс — держит WebView и флаг готовности
private class MapWebView(context: android.content.Context) {
    val webView = WebView(context).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        @Suppress("SetJavaScriptEnabled")
        settings.allowFileAccessFromFileURLs = true
        settings.allowUniversalAccessFromFileURLs = true
    }
    var ready = false
    var pendingIdx: Int? = null
}

// ─────────────────────────────────────────────────────────────────────────────
// RouteMessageItem
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun RouteMessageItem(message: ChatMessage.Route) {
    var selectedIndex by remember { mutableStateOf<Int?>(null) }
    var mapVisible    by remember { mutableStateOf(false) }
    var fullscreen    by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val gson    = remember { Gson() }

    // Создаём ДВА независимых WebView — один для превью, один для полноэкрана.
    // Каждый монтируется только в один родитель одновременно → нет краша.
    val previewMap  = remember { MapWebView(context) }
    val fullMap     = remember { MapWebView(context) }

    // Настраиваем WebViewClient для каждого
    fun setupClient(holder: MapWebView) {
        holder.webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                holder.ready = true
                val json    = gson.toJson(message.routeData)
                val escaped = json.replace("\\", "\\\\").replace("'", "\\'")
                view?.evaluateJavascript("initRoute('$escaped');", null)
                holder.pendingIdx?.let { idx ->
                    view?.evaluateJavascript("selectPvr($idx);", null)
                    holder.pendingIdx = null
                }
            }
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                if (url != null && url.startsWith("tel:")) {
                    context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse(url)))
                    return true
                }
                return false
            }
        }
        holder.webView.loadUrl("file:///android_asset/route_map.html")
    }

    LaunchedEffect(Unit) {
        setupClient(previewMap)
        setupClient(fullMap)
    }

    // GPS-трекинг: обновляем оба WebView одновременно
    val hasPermission = remember {
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    DisposableEffect(Unit) {
        if (!hasPermission) return@DisposableEffect onDispose {}
        val client  = LocationServices.getFusedLocationProviderClient(context)
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000L)
            .setMinUpdateDistanceMeters(5f).build()

        fun sendToMap(holder: MapWebView, lat: Double, lon: Double, heading: Float?, acc: Float?) {
            val h = if (heading != null) heading.toString() else "null"
            val a = if (acc    != null) acc.toString()     else "null"
            holder.webView.post {
                holder.webView.evaluateJavascript(
                    "updateUserLocation($lat,$lon,$h,$a);", null
                )
            }
        }

        val cb = object : LocationCallback() {
            override fun onLocationResult(r: LocationResult) {
                val loc = r.lastLocation ?: return
                val heading  = if (loc.hasBearing())  loc.bearing  else null
                val accuracy = if (loc.hasAccuracy()) loc.accuracy else null
                sendToMap(previewMap, loc.latitude, loc.longitude, heading, accuracy)
                sendToMap(fullMap,    loc.latitude, loc.longitude, heading, accuracy)
            }
        }
        client.requestLocationUpdates(request, cb, Looper.getMainLooper())
        onDispose { client.removeLocationUpdates(cb) }
    }

    // Когда пользователь выбирает ПВР — говорим обоим картам
    fun onPvrSelected(idx: Int) {
        selectedIndex = idx
        mapVisible    = true

        fun callSelect(holder: MapWebView) {
            if (holder.ready) {
                holder.webView.evaluateJavascript("selectPvr($idx);", null)
            } else {
                holder.pendingIdx = idx
            }
        }
        callSelect(previewMap)
        callSelect(fullMap)
    }

    // ── UI ───────────────────────────────────────────────────────────────────
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Box(
            modifier = Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).background(BotIconBg),
            contentAlignment = Alignment.Center
        ) { Icon(Icons.Default.LocationOn, null, tint = Color.White) }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                "Я нашёл 3 ближайших ПВР. Нажмите на карточку — построю маршрут:",
                fontSize = 14.sp, color = TextDarkGray,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            message.routeData.destinations.forEachIndexed { idx, pvr ->
                PvrCard(
                    pvr = pvr, index = idx, isSelected = selectedIndex == idx,
                    onClick = { onPvrSelected(idx) }
                )
                if (idx < message.routeData.destinations.size - 1)
                    Spacer(Modifier.height(8.dp))
            }

            // Превью карта
            AnimatedVisibility(visible = mapVisible, enter = expandVertically() + fadeIn()) {
                Column {
                    Spacer(Modifier.height(12.dp))
                    Box(modifier = Modifier.fillMaxWidth()) {
                        AndroidView(
                            factory = { previewMap.webView },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(260.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .border(1.dp, Color(0xFFE9ECEF), RoundedCornerShape(16.dp))
                        )
                        Surface(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(8.dp)
                                .size(36.dp)
                                .clickable { fullscreen = true },
                            shape = CircleShape, color = Color.White, shadowElevation = 4.dp
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Fullscreen, "Открыть карту",
                                    tint = Color(0xFF212529), modifier = Modifier.size(22.dp))
                            }
                        }
                    }
                    Text(
                        "Нажмите ⛶ чтобы открыть карту на весь экран",
                        fontSize = 11.sp, color = Color(0xFFADB5BD),
                        modifier = Modifier.padding(top = 4.dp, start = 4.dp)
                    )
                }
            }
        }
    }

    // Полноэкранный диалог — использует fullMap (отдельный WebView)
    if (fullscreen) {
        FullscreenRouteDialog(
            webView = fullMap.webView,
            pvr     = selectedIndex?.let { message.routeData.destinations[it] },
            onDismiss = { fullscreen = false }
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Полноэкранный диалог
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun FullscreenRouteDialog(
    webView: WebView,
    pvr: PvrDestination?,
    onDismiss: () -> Unit
) {
    BackHandler { onDismiss() }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth  = false,
            dismissOnBackPress       = true,
            dismissOnClickOutside    = false
        )
    ) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(
                factory  = { webView },
                modifier = Modifier.fillMaxSize()
            )

            // Кнопка "Назад"
            Surface(
                modifier = Modifier
                    .padding(top = 48.dp, start = 16.dp)
                    .size(44.dp)
                    .align(Alignment.TopStart)
                    .clickable { onDismiss() },
                shape = CircleShape, color = Color.White, shadowElevation = 8.dp
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад",
                        tint = Color.Black, modifier = Modifier.size(24.dp))
                }
            }

            // Плашка с названием ПВР
            if (pvr != null) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 48.dp)
                        .padding(horizontal = 72.dp),
                    shape = RoundedCornerShape(20.dp),
                    color = Color.White, shadowElevation = 4.dp
                ) {
                    Text(
                        pvr.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF212529), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// PvrCard
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun PvrCard(pvr: PvrDestination, index: Int, isSelected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .border(1.5.dp,
                if (isSelected) Color(0xFF3B82F6) else Color(0xFFE9ECEF),
                RoundedCornerShape(14.dp)),
        shape = RoundedCornerShape(14.dp),
        color = if (isSelected) Color(0xFFEFF6FF) else Color(0xFFF8F9FA)
    ) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            Box(
                modifier = Modifier.size(32.dp).clip(CircleShape)
                    .background(if (isSelected) Color(0xFF3B82F6) else Color(0xFFDEE2E6)),
                contentAlignment = Alignment.Center
            ) {
                Text("${index+1}", fontSize = 15.sp, fontWeight = FontWeight.Bold,
                    color = if (isSelected) Color.White else Color(0xFF495057))
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(pvr.name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                    color = Color(0xFF212529), maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.LocationOn, null, tint = Color(0xFF6C757D),
                        modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(3.dp))
                    Text(pvr.address, fontSize = 12.sp, color = Color(0xFF6C757D),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    InfoChip("${pvr.distance_km} км", "📏")
                    if (pvr.capacity > 0) InfoChip("${pvr.capacity} чел.", "👥")
                }
                if (pvr.phone.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Phone, null, tint = Color(0xFF007AFF),
                            modifier = Modifier.size(13.dp))
                        Spacer(Modifier.width(3.dp))
                        Text(pvr.phone, fontSize = 12.sp, color = Color(0xFF007AFF),
                            fontWeight = FontWeight.Medium)
                    }
                }
            }
            if (isSelected) {
                Spacer(Modifier.width(6.dp))
                Text("✓", fontSize = 18.sp, color = Color(0xFF3B82F6), fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun InfoChip(text: String, emoji: String) {
    Row(
        modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(Color.White)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(emoji, fontSize = 11.sp)
        Spacer(Modifier.width(3.dp))
        Text(text, fontSize = 11.sp, color = Color(0xFF495057))
    }
}