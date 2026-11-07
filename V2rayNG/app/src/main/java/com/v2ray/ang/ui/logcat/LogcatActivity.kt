package com.v2ray.ang.ui.logcat

import android.content.ClipData
import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import com.v2ray.ang.ui.compose.OmniIconButton as IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import com.v2ray.ang.ui.compose.omniLogClearSheen
import com.v2ray.ang.ui.compose.OmniButton
import com.v2ray.ang.ui.compose.LocalOmniAppearance
import com.v2ray.ang.ui.compose.OmniEffects
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.v2ray.ang.ui.compose.omniTerminalAccent
import com.v2ray.ang.ui.compose.OmniLogSurface
import com.v2ray.ang.ui.compose.omniNewLogCount
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.ui.compose.AppTopBar
import com.v2ray.ang.ui.compose.ItemDivider
import com.v2ray.ang.ui.compose.NavigationBarsBottomPadding
import com.v2ray.ang.ui.compose.verticalScrollbar
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LogcatActivity : BaseComponentActivity() {
    private val viewModel: LogcatViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    @Composable
    override fun ScreenContent() {
        LogcatScreen(
            viewModel = viewModel,
            onBackClick = { finish() },
            onShareLogcat = { shareLogcat() }
        )
    }

    private fun shareLogcat() {
        lifecycleScope.launch(Dispatchers.IO) {
            val logText = viewModel.filteredLogs.value.joinToString("\n")

            val result = try {
                val shareDir = File(cacheDir, "shared_logs").apply {
                    mkdirs()
                }

                shareDir.listFiles()?.forEach { it.delete() }

                val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
                val logFile = File(shareDir, "Omni_logcat_$timestamp.txt")
                logFile.writeText(logText, Charsets.UTF_8)

                val uri = FileProvider.getUriForFile(
                    this@LogcatActivity,
                    "${packageName}.cache",
                    logFile
                )

                uri to logFile.name
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Failed to share Logcat", e)
                withContext(Dispatchers.Main) {
                    toastError(R.string.toast_failure)
                }
                return@launch
            }

            withContext(Dispatchers.Main) {
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, result.first)
                    putExtra(Intent.EXTRA_SUBJECT, result.second)
                    putExtra(Intent.EXTRA_TITLE, result.second)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    clipData = ClipData.newUri(contentResolver, result.second, result.first)
                }

                startActivity(
                    Intent.createChooser(
                        shareIntent,
                        getString(R.string.logcat_share)
                    )
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LogcatScreen(
    viewModel: LogcatViewModel,
    onBackClick: () -> Unit,
    onShareLogcat: () -> Unit
) {
    val context = LocalContext.current
    val logs by viewModel.logEntries.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()

    var searchQuery by rememberSaveable { mutableStateOf("") }
    var showSearch by rememberSaveable { mutableStateOf(false) }

    val listState = rememberLazyListState()
    var live by rememberSaveable { mutableStateOf(true) }
    var arrivalRate by remember { mutableStateOf<Double?>(null) }
    val motion=LocalOmniAppearance.current.effects!=OmniEffects.OFF && android.animation.ValueAnimator.areAnimatorsEnabled()
    var wipeNonce by remember { mutableIntStateOf(0) }
    var wiping by remember { mutableStateOf(false) }
    var wipeEntries by remember { mutableStateOf(logs) }
    val wipe=remember { Animatable(0f) }
    val clearLog:()->Unit={
        // Real clear starts immediately; an optional outgoing snapshot is only presentation.
        viewModel.clearLogcat()
        arrivalRate=null
        if(motion){wipeEntries=logs;wiping=true;wipeNonce++}
    }
    LaunchedEffect(wipeNonce,motion) {
        wipe.snapTo(0f)
        if(wipeNonce>0 && motion)wipe.animateTo(1f,tween(320,easing=CubicBezierEasing(.42f,0f,1f,1f)))
        wiping=false;wipe.snapTo(0f)
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(live, searchQuery, lifecycle) {
        arrivalRate = null
        viewModel.filter(searchQuery)
        if (!live) { viewModel.pauseLogRefresh(); return@LaunchedEffect }
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var previous: Map<String,Int>? = null
            var at = android.os.SystemClock.elapsedRealtime()
            try {
                while (true) {
                    viewModel.loadLogcat()
                    viewModel.isLoading.first { !it }
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (!viewModel.readSucceeded.value) {
                        arrivalRate=null;previous=null;at=now
                        delay(2000L);continue
                    }
                    val current = viewModel.filteredLogs.value.groupingBy { it }.eachCount()
                    val old = previous
                    arrivalRate = if (old != null && now > at) omniNewLogCount(old,current) * 1000.0 / (now-at) else null
                    previous = current; at = now
                    delay(2000L)
                }
            } finally { viewModel.pauseLogRefresh() }
        }
    }


    LaunchedEffect(logs.firstOrNull()?.key, live) {
        if(live && listState.firstVisibleItemIndex < 3) listState.scrollToItem(0)
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            AppTopBar(
                title = stringResource(R.string.title_logcat),
                onBackClick = onBackClick,
                isLoading = isLoading && !live,
                isSearchActive = showSearch,
                searchQuery = searchQuery,
                onSearchQueryChange = {
                    searchQuery = it
                    viewModel.filter(it)
                },
                onSearchClose = {
                    searchQuery = ""
                    viewModel.filter("")
                    showSearch = false
                },
                searchPlaceholder = stringResource(R.string.menu_item_search),
                actions = {
                    if (!showSearch) {
                        IconButton(onClick = { showSearch = true }) {
                            Icon(
                                painterResource(R.drawable.ic_search_24dp),
                                contentDescription = stringResource(R.string.acc_search)
                            )
                        }
                    }
                    IconButton(onClick = { viewModel.copyLogcat() }) {
                        Icon(
                            painterResource(R.drawable.ic_copy),
                            contentDescription = stringResource(R.string.acc_copy_log)
                        )
                    }
                    IconButton(onClick = { onShareLogcat() }) {
                        Icon(
                            painterResource(R.drawable.ic_share_24dp),
                            contentDescription = stringResource(R.string.acc_share_log)
                        )
                    }
                    IconButton(onClick = clearLog) {
                        Icon(
                            painterResource(R.drawable.ic_delete_24dp),
                            contentDescription = stringResource(R.string.acc_clear_log)
                        )
                    }
                }
            )
        },
    ) { innerPadding ->
        Column(Modifier.fillMaxSize().padding(innerPadding).navigationBarsPadding().padding(horizontal=14.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        OmniLogSurface(live=live,onLive={ live=it },onRefresh=viewModel::loadLogcat,count=logs.size,rate=arrivalRate,wipeProgress=wipe.value,modifier=Modifier.weight(1f)) {
            LazyColumn(
                state = listState,
                reverseLayout = true,
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScrollbar(listState),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom=24.dp)
            ) {
                items(items = if(wiping)wipeEntries else logs, key = { it.key }) { log ->
                    LogcatItem(log = log.text, animate = live && listState.firstVisibleItemIndex < 3, onLongClick = { Utils.setClipboard(context, log.text) })
                }
            }
        }
        OmniButton(onClick=clearLog,modifier=Modifier.fillMaxWidth().height(54.dp).clip(RoundedCornerShape(99.dp)).omniLogClearSheen(),shape=RoundedCornerShape(99.dp)) {
            Text(stringResource(R.string.acc_clear_log),fontSize=16.sp,fontWeight=FontWeight.ExtraBold)
        }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LogcatItem(log: String, animate: Boolean, onLongClick: () -> Unit) {
    val fx=LocalOmniAppearance.current.effects
    val effects=fx!=OmniEffects.OFF && android.animation.ValueAnimator.areAnimatorsEnabled()
    var entered by remember { mutableStateOf(!animate || !effects) }
    LaunchedEffect(Unit) { entered=true }
    val amount by animateFloatAsState(if(entered)1f else 0f,animationSpec=tween(if(fx==OmniEffects.FULL)550 else 150),label="real-log-arrival")
    val level=remember(log) { LOG_LEVEL.find(log)?.groupValues?.get(1) }
    val color=when(level) { "W"->Color(0xFFFFA42B);"E","F"->Color(0xFFFF6B81);"I"->omniTerminalAccent(LocalOmniAppearance.current.skin.accent);"D","V"->Color(0xFFB3B3B3);else->Color.White }
    Text(log,modifier=Modifier.fillMaxWidth().graphicsLayer {
        alpha=if(effects)amount else 1f
        if(effects && fx==OmniEffects.FULL) { rotationX=-65f*(1f-amount);scaleX=.78f+.22f*amount;scaleY=scaleX;cameraDistance=640f*density }
    }.combinedClickable(onClick={},onLongClick=onLongClick).padding(horizontal=18.dp,vertical=1.dp),color=color,
        style=MaterialTheme.typography.bodySmall.copy(fontFamily=FontFamily.Monospace,fontSize=13.sp,lineHeight=20.8.sp,fontWeight=FontWeight.Medium))
}

private val LOG_LEVEL = Regex("\\s([VDIWEF])[/\\s]")
