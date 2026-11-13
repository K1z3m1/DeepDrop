package com.prirai.android.lr.imagereader

import android.content.Context
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.memory.MemoryCache
import coil.request.ImageRequest
import coil.size.Dimension
import coil.size.Precision
import coil.size.Scale
import coil.size.Size
import com.prirai.android.lr.R
import com.prirai.android.lr.ui.theme.LRTheme
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Full-screen reader for every image of a web page.
 *
 * - Images are requested at their original resolution (capped at
 *   4096 x 8192 px so a huge image cannot exhaust memory).
 * - Three reading modes: vertical scroll, horizontal pages (LTR) and
 *   horizontal pages (RTL, manga).
 * - Thumbnail strip + slider for jumping around, tap to hide the chrome.
 */
class ImageReaderActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val payload = ImageReaderSession.payload.value
        if (payload == null || payload.images.isEmpty()) {
            Toast.makeText(this, R.string.image_reader_none_found, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            // Draw under the camera cutout too: truly edge-to-edge.
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        setContent {
            LRTheme {
                ReaderScreen(
                    payload = payload,
                    onClose = { finish() },
                    onChromeVisibilityChanged = { visible -> setSystemBarsVisible(visible) },
                )
            }
        }
    }

    override fun onDestroy() {
        if (isFinishing) ImageReaderSession.clear()
        super.onDestroy()
    }

    private fun setSystemBarsVisible(visible: Boolean) {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (visible) {
            controller.show(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
    }
}

private enum class ReadMode(val labelRes: Int) {
    VERTICAL(R.string.image_reader_mode_vertical),
    PAGED_LTR(R.string.image_reader_mode_ltr),
    PAGED_RTL(R.string.image_reader_mode_rtl);

    fun next(): ReadMode = entries[(ordinal + 1) % entries.size]
}

private const val PREFS = "lr_image_reader"
private const val KEY_MODE = "mode"
private const val KEY_HIDE_BANNERS = "hide_banners"

/** Largest decode size: original resolution, bounded for memory safety. */
private val ORIGINAL_SIZE = Size(Dimension.Pixels(4096), Dimension.Pixels(8192))
private val PREVIEW_SIZE = Size(Dimension.Pixels(768), Dimension.Pixels(1024))
private val THUMB_SIZE = Size(Dimension.Pixels(240), Dimension.Pixels(340))

/** Zoom limits shared by the long-strip and single-page readers. */
private const val MAX_ZOOM = 5f
private const val DOUBLE_TAP_ZOOM = 2.5f

/**
 * Drops obvious non-content images (ads, banners, icons): anything much
 * narrower than the page's dominant image width, or extremely wide and flat.
 * Images whose size is unknown (not yet loaded by the page) are kept.
 */
private fun filterImages(images: List<ReaderImage>, hideBanners: Boolean): List<ReaderImage> {
    if (!hideBanners) return images
    val widths = images.map { it.width }.filter { it >= 300 }
    if (widths.isEmpty()) return images.filter { isNotBanner(it) }
    val dominant = widths
        .groupingBy { (it / 50) * 50 }
        .eachCount()
        .maxByOrNull { it.value }!!.key + 25
    return images.filter { img ->
        if (img.width == 0 || img.height == 0) return@filter true
        isNotBanner(img) && img.width >= dominant * 0.5f
    }
}

private fun isNotBanner(img: ReaderImage): Boolean {
    if (img.width == 0 || img.height == 0) return true
    return img.width.toFloat() / img.height < 2.8f && img.width >= 250
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ReaderScreen(
    payload: ReaderPayload,
    onClose: () -> Unit,
    onChromeVisibilityChanged: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()

    var mode by remember {
        mutableStateOf(ReadMode.entries.getOrElse(prefs.getInt(KEY_MODE, 0)) { ReadMode.VERTICAL })
    }
    var hideBanners by remember { mutableStateOf(prefs.getBoolean(KEY_HIDE_BANNERS, true)) }
    // Starts hidden: the image owns the whole screen, tap the centre for controls.
    var chromeVisible by remember { mutableStateOf(false) }

    val images = remember(payload, hideBanners) { filterImages(payload.images, hideBanners) }
    val loader = remember {
        ImageLoader.Builder(context)
            .memoryCache { MemoryCache.Builder(context).maxSizePercent(0.2).build() }
            .crossfade(false)
            .build()
    }

    val listState = rememberLazyListState()
    val pagerState = rememberPagerState(pageCount = { images.size })
    val thumbState = rememberLazyListState()

    val currentIndex by remember(images, mode) {
        derivedStateOf {
            val raw = if (mode == ReadMode.VERTICAL) listState.firstVisibleItemIndex else pagerState.currentPage
            raw.coerceIn(0, (images.size - 1).coerceAtLeast(0))
        }
    }

    // Keep the reading position when switching mode or filter: the toggle
    // handlers store the current page in restoreTo just before changing state.
    var restoreTo by remember { mutableIntStateOf(0) }
    LaunchedEffect(mode, images) {
        val target = restoreTo.coerceIn(0, (images.size - 1).coerceAtLeast(0))
        if (mode == ReadMode.VERTICAL) listState.scrollToItem(target) else pagerState.scrollToPage(target)
    }
    LaunchedEffect(currentIndex, chromeVisible) {
        if (chromeVisible && images.isNotEmpty()) {
            thumbState.animateScrollToItem((currentIndex - 2).coerceAtLeast(0))
        }
    }
    LaunchedEffect(chromeVisible) { onChromeVisibilityChanged(chromeVisible) }

    fun jump(index: Int) {
        val target = index.coerceIn(0, (images.size - 1).coerceAtLeast(0))
        scope.launch {
            if (mode == ReadMode.VERTICAL) listState.scrollToItem(target) else pagerState.scrollToPage(target)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (images.isEmpty()) {
            Column(
                Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stringResource(R.string.image_reader_none_found), color = Color.White)
                TextButton(onClick = {
                    hideBanners = false
                    prefs.edit().putBoolean(KEY_HIDE_BANNERS, false).apply()
                }) { Text(stringResource(R.string.image_reader_show_all)) }
            }
        } else if (mode == ReadMode.VERTICAL) {
            VerticalZoomHost(
                listState = listState,
                onTap = { chromeVisible = !chromeVisible },
            ) { zoomModifier ->
                LazyColumn(
                    state = listState,
                    modifier = zoomModifier,
                ) {
                    itemsIndexed(images, key = { index, _ -> index }) { _, image ->
                        ReaderImageView(
                            image = image,
                            loader = loader,
                            referer = payload.pageUrl,
                            userAgent = payload.userAgent,
                            targetSize = ORIGINAL_SIZE,
                            contentScale = ContentScale.FillWidth,
                            flowLayout = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        } else {
            HorizontalPager(
                state = pagerState,
                reverseLayout = mode == ReadMode.PAGED_RTL,
                beyondViewportPageCount = 1,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                ZoomableBox(onTap = { fraction ->
                    // Edge taps turn the page, centre tap toggles controls.
                    val rtl = mode == ReadMode.PAGED_RTL
                    val step = when {
                        fraction < 0.28f -> if (rtl) 1 else -1
                        fraction > 0.72f -> if (rtl) -1 else 1
                        else -> 0
                    }
                    if (step == 0) {
                        chromeVisible = !chromeVisible
                    } else {
                        scope.launch {
                            val t = (pagerState.currentPage + step).coerceIn(0, images.size - 1)
                            pagerState.animateScrollToPage(t)
                        }
                    }
                }) {
                    ReaderImageView(
                        image = images[page],
                        loader = loader,
                        referer = payload.pageUrl,
                        userAgent = payload.userAgent,
                        targetSize = ORIGINAL_SIZE,
                        contentScale = ContentScale.Fit,
                        flowLayout = false,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            TopBar(
                title = payload.title.ifBlank { payload.pageUrl },
                counter = if (images.isEmpty()) "" else "${currentIndex + 1}/${images.size}",
                onBack = onClose,
            )
        }

        AnimatedVisibility(
            visible = chromeVisible && images.isNotEmpty(),
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            BottomPanel(
                images = images,
                currentIndex = currentIndex,
                loader = loader,
                referer = payload.pageUrl,
                userAgent = payload.userAgent,
                thumbState = thumbState,
                mode = mode,
                hideBanners = hideBanners,
                onJump = { jump(it) },
                onSetMode = { newMode ->
                    restoreTo = currentIndex
                    mode = newMode
                    prefs.edit().putInt(KEY_MODE, mode.ordinal).apply()
                },
                onToggleBanners = {
                    restoreTo = currentIndex
                    hideBanners = !hideBanners
                    prefs.edit().putBoolean(KEY_HIDE_BANNERS, hideBanners).apply()
                },
            )
        }
    }
}

@Composable
private fun TopBar(title: String, counter: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.72f))
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.image_reader_close),
                tint = Color.White,
            )
        }
        Text(
            text = title,
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = counter,
            color = Color.White.copy(alpha = 0.85f),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
    }
}

@Composable
private fun BottomPanel(
    images: List<ReaderImage>,
    currentIndex: Int,
    loader: ImageLoader,
    referer: String,
    userAgent: String,
    thumbState: androidx.compose.foundation.lazy.LazyListState,
    mode: ReadMode,
    hideBanners: Boolean,
    onJump: (Int) -> Unit,
    onSetMode: (ReadMode) -> Unit,
    onToggleBanners: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.78f))
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(top = 8.dp, bottom = 4.dp),
    ) {
        // Thumbnail strip
        LazyRow(
            state = thumbState,
            contentPadding = PaddingValues(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            itemsIndexed(images, key = { index, _ -> index }) { index, image ->
                val selected = index == currentIndex
                Box(
                    modifier = Modifier
                        .width(64.dp)
                        .height(92.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF2A2A2A))
                        .then(
                            if (selected) Modifier.border(
                                BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
                                RoundedCornerShape(6.dp),
                            ) else Modifier
                        )
                        .clickable { onJump(index) },
                ) {
                    ReaderImageView(
                        image = image,
                        loader = loader,
                        referer = referer,
                        userAgent = userAgent,
                        targetSize = THUMB_SIZE,
                        contentScale = ContentScale.Crop,
                        flowLayout = false,
                        preferPreview = true,
                        modifier = Modifier.fillMaxSize(),
                    )
                    Text(
                        text = "${index + 1}",
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .background(
                                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.9f)
                                else Color.Black.copy(alpha = 0.6f)
                            )
                            .padding(vertical = 2.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        }

        // Slider with page numbers
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("${currentIndex + 1}", color = Color.White, style = MaterialTheme.typography.labelLarge)
            Slider(
                value = currentIndex.toFloat(),
                onValueChange = { onJump(it.roundToInt()) },
                valueRange = 0f..(images.size - 1).coerceAtLeast(1).toFloat(),
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
            )
            Text("${images.size}", color = Color.White, style = MaterialTheme.typography.labelLarge)
        }

        // Controls
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FilterChip(
                    selected = mode == ReadMode.VERTICAL,
                    onClick = { onSetMode(ReadMode.VERTICAL) },
                    label = { Text(stringResource(R.string.image_reader_long_strip)) },
                )
                FilterChip(
                    selected = mode != ReadMode.VERTICAL,
                    onClick = {
                        if (mode == ReadMode.VERTICAL) onSetMode(ReadMode.PAGED_LTR)
                    },
                    label = { Text(stringResource(R.string.image_reader_single_page)) },
                )
                if (mode != ReadMode.VERTICAL) {
                    FilterChip(
                        selected = mode == ReadMode.PAGED_RTL,
                        onClick = {
                            onSetMode(
                                if (mode == ReadMode.PAGED_RTL) ReadMode.PAGED_LTR else ReadMode.PAGED_RTL
                            )
                        },
                        label = {
                            Text(
                                stringResource(
                                    if (mode == ReadMode.PAGED_RTL) R.string.image_reader_dir_rtl
                                    else R.string.image_reader_dir_ltr
                                )
                            )
                        },
                    )
                }
            }
            FilterChip(
                selected = hideBanners,
                onClick = onToggleBanners,
                label = { Text(stringResource(R.string.image_reader_hide_banners)) },
            )
        }
    }
}

/**
 * One image with: an optional displayed-size preview while the original
 * request loads, Referer/User-Agent headers (many image hosts block
 * hot-linking), candidate fallback on error, and tap-to-retry after failure.
 *
 * With [flowLayout] the box takes the image's aspect ratio (known from the
 * page, or from the first successful decode) so the vertical list does not
 * jump while images load.
 */
@Composable
private fun ReaderImageView(
    image: ReaderImage,
    loader: ImageLoader,
    referer: String,
    userAgent: String,
    targetSize: Size,
    contentScale: ContentScale,
    flowLayout: Boolean,
    preferPreview: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val candidates = remember(image, preferPreview) {
        if (preferPreview && image.previewUrl != null) {
            listOf(image.previewUrl) + image.candidates.filterNot { it == image.previewUrl }
        } else {
            image.candidates
        }
    }
    val previewUrl = image.previewUrl?.takeIf { !preferPreview && it != candidates.firstOrNull() }
    var candidateIndex by remember(image, preferPreview) { mutableIntStateOf(0) }
    var failed by remember(image) { mutableStateOf(false) }
    var loading by remember(image) { mutableStateOf(true) }
    var previewVisible by remember(image, preferPreview) { mutableStateOf(previewUrl != null) }
    var retry by remember(image) { mutableIntStateOf(0) }
    var ratio by remember(image) {
        mutableFloatStateOf(
            if (image.width > 0 && image.height > 0) image.width.toFloat() / image.height else 0f
        )
    }

    val url = candidates[candidateIndex.coerceIn(0, candidates.lastIndex)]
    val previewRequest = remember(previewUrl, referer, userAgent) {
        previewUrl?.let { preview ->
            ImageRequest.Builder(context)
                .data(preview)
                .memoryCacheKey(preview)
                .apply {
                    if (referer.isNotBlank()) addHeader("Referer", referer)
                    if (userAgent.isNotBlank()) addHeader("User-Agent", userAgent)
                }
                .size(PREVIEW_SIZE)
                .scale(Scale.FIT)
                .precision(Precision.INEXACT)
                .build()
        }
    }
    val request = remember(url, previewUrl, retry, targetSize) {
        ImageRequest.Builder(context)
            .data(url)
            .apply {
                if (referer.isNotBlank()) addHeader("Referer", referer)
                if (userAgent.isNotBlank()) addHeader("User-Agent", userAgent)
                if (previewUrl != null) placeholderMemoryCacheKey(previewUrl)
            }
            .size(targetSize)
            .scale(Scale.FIT)
            .precision(Precision.INEXACT)
            .build()
    }

    val boxModifier = if (flowLayout) {
        if (ratio > 0f) modifier.aspectRatio(ratio) else modifier.heightIn(min = 320.dp)
    } else {
        modifier
    }

    Box(boxModifier, contentAlignment = Alignment.Center) {
        if (failed) {
            Text(
                text = stringResource(R.string.image_reader_tap_retry),
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .clickable {
                        failed = false
                        loading = true
                        candidateIndex = 0
                        retry++
                    }
                    .padding(16.dp),
            )
        } else {
            AsyncImage(
                model = request,
                imageLoader = loader,
                contentDescription = null,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
                onLoading = { loading = true },
                onSuccess = { state ->
                    loading = false
                    previewVisible = false
                    val size = state.painter.intrinsicSize
                    if (size.isSpecified && size.height > 0f && size.width > 0f) {
                        ratio = size.width / size.height
                    }
                },
                onError = {
                    if (candidateIndex < candidates.lastIndex) {
                        candidateIndex++
                    } else {
                        loading = false
                        failed = true
                    }
                },
            )
            if (previewVisible && previewRequest != null) {
                AsyncImage(
                    model = previewRequest,
                    imageLoader = loader,
                    contentDescription = null,
                    contentScale = contentScale,
                    modifier = Modifier.fillMaxSize(),
                    onError = { previewVisible = false },
                )
            }
            if (loading && targetSize == ORIGINAL_SIZE) {
                CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    strokeWidth = 2.dp,
                    color = Color.White.copy(alpha = 0.7f),
                )
            }
        }
    }
}

/**
 * Pinch-to-zoom for the long-strip reader.
 *
 * Instead of scaling a bitmap layer (which would blur), the list is laid out
 * [scale] times wider than the screen, so every image is re-measured and
 * decoded at the larger size. Horizontal panning moves the wide list;
 * vertical movement is left to the LazyColumn's own scrolling.
 *
 * - two fingers: pinch around the fingers' centre (and drag)
 * - double tap: toggle fit-width <-> [DOUBLE_TAP_ZOOM]x around the tap point
 * - one finger while zoomed: horizontal pan, vertical scroll as usual
 */
@Composable
private fun VerticalZoomHost(
    listState: LazyListState,
    onTap: () -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var panX by remember { mutableFloatStateOf(0f) }
    var viewWidth by remember { mutableIntStateOf(0) }

    fun clampPan(x: Float, s: Float): Float = x.coerceIn(-(viewWidth * (s - 1f)), 0f)

    /** Zoom to [target] keeping the content under [focus] where it is. */
    fun zoomAround(focus: Offset, target: Float) {
        val old = scale
        val s = target.coerceIn(1f, MAX_ZOOM)
        if (s == old || viewWidth == 0) return
        val ratio = s / old
        // Horizontal: keep the content point under the fingers fixed.
        panX = clampPan((panX - focus.x) * ratio + focus.x, s)
        // Vertical: every item grows by [ratio], so the distance from the
        // first visible item's top to the focus point grows by [ratio] too.
        val newOffset = ((listState.firstVisibleItemScrollOffset + focus.y) * ratio - focus.y)
            .roundToInt().coerceAtLeast(0)
        listState.requestScrollToItem(listState.firstVisibleItemIndex, newOffset)
        scale = s
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged { viewWidth = it.width }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { pos ->
                        zoomAround(pos, if (scale > 1.05f) 1f else DOUBLE_TAP_ZOOM)
                    },
                )
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    do {
                        // Initial pass: we see the touches before the LazyColumn,
                        // so a pinch is never mistaken for a scroll.
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val fingers = event.changes.count { it.pressed }
                        if (fingers >= 2) {
                            val centroid = event.calculateCentroid(useCurrent = true)
                            val pan = event.calculatePan()
                            val zoom = event.calculateZoom()
                            if (centroid.isSpecified) {
                                zoomAround(centroid, scale * zoom)
                                panX = clampPan(panX + pan.x, scale)
                                listState.dispatchRawDelta(-pan.y)
                            }
                            event.changes.forEach { it.consume() }
                        } else if (scale > 1f) {
                            // Do not consume: vertical drags still scroll the list.
                            val pan = event.calculatePan()
                            if (pan.x != 0f) panX = clampPan(panX + pan.x, scale)
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
    ) {
        content(
            Modifier.layout { measurable, constraints ->
                val width = (constraints.maxWidth * scale).roundToInt()
                val placeable = measurable.measure(
                    constraints.copy(minWidth = width, maxWidth = width)
                )
                layout(constraints.maxWidth, placeable.height) {
                    placeable.placeRelative(panX.roundToInt(), 0)
                }
            }
        )
    }
}

/**
 * Pinch-to-zoom + pan + double-tap container for paged mode.
 *
 * - pinch zooms around the fingers, double tap around the tap point
 * - gestures are only consumed while pinching or while the image can still
 *   move, so a swipe at 1x (or against an image edge) turns the page
 */
@Composable
private fun ZoomableBox(onTap: (Float) -> Unit, content: @Composable () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }

    fun clamp(o: Offset, s: Float): Offset {
        val maxX = size.width * (s - 1f) / 2f
        val maxY = size.height * (s - 1f) / 2f
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }

    /** Zoom to [target] keeping the point under [focus] fixed, then pan by [pan]. */
    fun transform(target: Float, focus: Offset, pan: Offset) {
        val s = target.coerceIn(1f, MAX_ZOOM)
        if (s <= 1f) {
            scale = 1f
            offset = Offset.Zero
            return
        }
        val centre = Offset(size.width / 2f, size.height / 2f)
        val ratio = s / scale
        offset = clamp((offset - (focus - centre)) * ratio + (focus - centre) + pan, s)
        scale = s
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { size = it }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { pos -> onTap(if (size.width > 0) pos.x / size.width else 0.5f) },
                    onDoubleTap = { pos ->
                        transform(if (scale > 1.05f) 1f else DOUBLE_TAP_ZOOM, pos, Offset.Zero)
                    },
                )
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var handedToPager = false
                    do {
                        val event = awaitPointerEvent()
                        val multi = event.changes.count { it.pressed } >= 2
                        if ((multi || scale > 1f) && !handedToPager) {
                            val before = offset
                            val centroid = event.calculateCentroid(useCurrent = true)
                            transform(
                                target = scale * event.calculateZoom(),
                                focus = if (centroid.isSpecified) centroid
                                else Offset(size.width / 2f, size.height / 2f),
                                pan = event.calculatePan(),
                            )
                            val pan = event.calculatePan()
                            val movedX = offset.x != before.x
                            val movedY = offset.y != before.y
                            val horizontalDrag = abs(pan.x) > abs(pan.y)
                            if (!multi && horizontalDrag && !movedX) {
                                // Image is against its edge: let the pager take over.
                                handedToPager = true
                            } else if (multi || movedX || movedY) {
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            },
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}
