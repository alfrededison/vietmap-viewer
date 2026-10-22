package com.hieu.vmviewer.map

import android.graphics.drawable.BitmapDrawable
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.hieu.vmviewer.cam.GpsPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.math.floor
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt

/** Vị trí xe trên bản đồ; [bearing] null = không biết hướng (đứng yên) -> vẽ chấm, không vẽ mũi tên. */
data class Car(val lat: Double, val lon: Double, val bearing: Float?)

private data class Tile(val z: Int, val x: Int, val y: Int)
private class Placed(val tile: Tile, val left: Float, val top: Float, val size: Float)

private const val MIN_ZOOM = 3f
private const val TILE_PX = 256
private val ACCENT = Color(0xFF4FB3FF)
private val START = Color(0xFF39D98A)
private val END = Color(0xFFFF6B6B)

/**
 * Bản đồ OSM với lộ trình của clip, giống VFTools (Leaflet): đường xanh, chấm đầu xanh lá /
 * cuối đỏ, mũi tên xe xoay theo hướng. Mặc định bám theo xe (zoom vẫn bám); kéo tay thì
 * thôi bám, nút định vị để bám lại. Mỗi clip một TrackMap mới (gọi trong key(uri)).
 *
 * Lộ trình đến từng đoạn [track] (chỗ thiếu GPS để hở, không nối đoán).
 * Chấm cuối chỉ vẽ khi [complete] — khi chưa đọc hết, điểm cuối đã có chưa phải cuối clip.
 */
@Composable
fun TrackMap(
    track: List<List<GpsPoint>>,
    complete: Boolean,
    car: Car?,
    tiles: Tiles,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    DisposableEffect(tiles) {
        tiles.acquire()
        onDispose { tiles.release() }
    }
    val network by tiles.network.collectAsState()
    val density = LocalDensity.current
    val tilePx = with(density) { TILE_PX.dp.toPx() }

    var zoom by rememberSaveable { mutableFloatStateOf(16f) }
    var manual by remember { mutableStateOf<World?>(null) }   // null = bám theo xe
    var viewSize by remember { mutableStateOf(IntSize.Zero) }

    val path = remember(track) { track.map { seg -> seg.map { world(it.lat, it.lon) } } }
    val carWorld = car?.let { world(it.lat, it.lon) }
    val view by rememberUpdatedState(manual ?: carWorld ?: path.firstOrNull()?.firstOrNull())

    // Tile: tối đa 2 request cùng lúc (chính sách OSM), tile ra khỏi màn hình thì huỷ.
    // Tất cả chạy trên main thread nên các map dưới đây không cần khoá.
    val bitmaps = remember { mutableStateMapOf<Tile, ImageBitmap>() }
    var retryTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(network) {
        val net = network
        val jobs = mutableMapOf<Tile, Job>()
        val retryAt = mutableMapOf<Tile, Long>()
        val tries = mutableMapOf<Tile, Int>()
        val slots = Semaphore(2)
        var want = emptySet<Tile>()
        snapshotFlow { retryTick to visibleTiles(view, zoom, viewSize, tilePx).mapTo(HashSet()) { it.tile } }
            .distinctUntilChanged()
            .collect { (_, tilesNow) ->
                want = tilesNow
                jobs.keys.filter { it !in tilesNow }.forEach { jobs.remove(it)?.cancel() }
                val now = SystemClock.uptimeMillis()
                for (t in tilesNow) {
                    if (t in bitmaps || t in jobs || (retryAt[t] ?: 0) > now) continue
                    jobs[t] = launch {
                        val req = ImageRequest.Builder(ctx)
                            .data(Tiles.url(t.z, t.x, t.y))
                            .size(TILE_PX)
                            .networkCachePolicy(if (net != null) CachePolicy.ENABLED else CachePolicy.DISABLED)
                            .build()
                        val result = slots.withPermit { tiles.loader.execute(req) }
                        jobs.remove(t)
                        val bmp = ((result as? SuccessResult)?.drawable as? BitmapDrawable)?.bitmap
                        if (bmp != null) {
                            bitmaps[t] = bmp.asImageBitmap()
                            retryAt.remove(t)
                            tries.remove(t)
                            if (bitmaps.size > 160) bitmaps.keys.retainAll(want)
                        } else if (net == null) {
                            retryAt[t] = Long.MAX_VALUE   // chỉ có cache: không có thì thôi, tới khi có mạng
                        } else {
                            // 4G yếu / OSM từ chối: thử lại sau 5, 10, 20… tối đa 60 s
                            val n = (tries[t] ?: 0) + 1
                            tries[t] = n
                            val wait = minOf(60_000L, 5_000L shl (n - 1).coerceAtMost(4))
                            retryAt[t] = SystemClock.uptimeMillis() + wait
                            delay(wait)
                            retryTick++
                        }
                    }
                }
            }
    }

    // Báo không có Internet sau một lúc (mạng 4G cần vài trăm ms mới báo về)
    var offline by remember { mutableStateOf(false) }
    LaunchedEffect(network) {
        offline = false
        if (network == null) {
            delay(2500)
            offline = true
        }
    }

    fun zoomAround(focus: Offset, factor: Float, pan: Offset = Offset.Zero) {
        val v = view ?: return
        val w0 = tilePx * 2.0.pow(zoom.toDouble())
        val z1 = (zoom + log2(factor)).coerceIn(MIN_ZOOM, Tiles.MAX_ZOOM.toFloat())
        val w1 = tilePx * 2.0.pow(z1.toDouble())
        val dx = focus.x - viewSize.width / 2.0
        val dy = focus.y - viewSize.height / 2.0
        // Điểm dưới ngón tay giữ nguyên chỗ (cộng thêm phần kéo)
        val px = v.x + dx / w0
        val py = v.y + dy / w0
        manual = World(
            (px - (dx + pan.x) / w1).mod(1.0),
            (py - (dy + pan.y) / w1).coerceIn(0.0, 1.0),
        )
        zoom = z1
    }

    // Đang bám xe: zoom quanh xe (giữa màn hình) và vẫn bám; chỉ kéo tay mới thôi bám
    fun zoomAt(focus: Offset, factor: Float) {
        if (manual == null) zoom = (zoom + log2(factor)).coerceIn(MIN_ZOOM, Tiles.MAX_ZOOM.toFloat())
        else zoomAround(focus, factor)
    }

    Box(modifier.clipToBounds().background(Color(0xFFDDDDDD))) {
        Canvas(
            Modifier.fillMaxSize()
                .onSizeChanged { viewSize = it }
                .pointerInput(Unit) {
                    detectTransformGestures { centroid, pan, factor, _ ->
                        if (factor != 1f) zoomAt(centroid, factor)
                        if (pan != Offset.Zero && (factor == 1f || manual != null)) zoomAround(centroid, 1f, pan)
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = { zoomAt(it, 2f) })
                },
        ) {
            val v = view ?: return@Canvas
            for (p in visibleTiles(v, zoom, viewSize, tilePx)) drawTile(p, bitmaps)

            val w = tilePx * 2.0.pow(zoom.toDouble())
            fun screen(o: World): Offset {
                var dx = o.x - v.x
                if (dx > 0.5) dx -= 1.0 else if (dx < -0.5) dx += 1.0
                return Offset((dx * w + viewSize.width / 2.0).toFloat(), ((o.y - v.y) * w + viewSize.height / 2.0).toFloat())
            }
            for (seg in path) {
                if (seg.size < 2) continue
                val line = Path()
                seg.forEachIndexed { i, o -> screen(o).let { if (i == 0) line.moveTo(it.x, it.y) else line.lineTo(it.x, it.y) } }
                drawPath(
                    line, ACCENT.copy(alpha = 0.85f),
                    style = Stroke(4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }
            // Đầu / cuối: lộ trình vòng về chỗ cũ thì không biết đi chiều nào
            path.firstOrNull()?.firstOrNull()?.let { drawEnd(screen(it), START) }
            if (complete) path.lastOrNull()?.lastOrNull()?.let { drawEnd(screen(it), END) }

            if (car != null && carWorld != null) drawCar(screen(carWorld), car.bearing)
        }

        Column(
            Modifier.align(Alignment.TopEnd).padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val center = Offset(viewSize.width / 2f, viewSize.height / 2f)
            MapButton(onClick = { zoomAt(center, 2f) }) { Icon(Icons.Filled.Add, "Phóng to") }
            MapButton(onClick = { zoomAt(center, 0.5f) }) { Icon(Icons.Filled.Remove, "Thu nhỏ") }
            if (manual != null && car != null) {
                MapButton(onClick = { manual = null }) { Icon(Icons.Filled.MyLocation, "Theo xe") }
            }
        }
        if (offline) {
            Text(
                "Không có Internet · chỉ hiện bản đồ đã lưu",
                color = Color.White, fontSize = 11.sp,
                modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
                    .background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        // Bắt buộc theo giấy phép OSM
        Text(
            Tiles.ATTRIBUTION,
            color = Color(0xFF333333), fontSize = 10.sp,
            modifier = Modifier.align(Alignment.BottomEnd)
                .background(Color.White.copy(alpha = 0.7f)).padding(horizontal = 4.dp, vertical = 1.dp),
        )
    }
}

@Composable
private fun MapButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    FilledTonalIconButton(onClick = onClick, modifier = Modifier.size(36.dp), content = content)
}

/** Các tile phủ khung nhìn ở mức zoom nguyên gần nhất (dưới), kèm chỗ vẽ trên màn hình. */
private fun visibleTiles(v: World?, zoom: Float, size: IntSize, tilePx: Float): List<Placed> {
    if (v == null || size.width == 0 || size.height == 0) return emptyList()
    val z = floor(zoom).toInt().coerceIn(0, Tiles.MAX_ZOOM)
    val n = 1 shl z
    val ts = tilePx * 2.0.pow((zoom - z).toDouble())
    val ox = v.x * n * ts - size.width / 2.0
    val oy = v.y * n * ts - size.height / 2.0
    val out = ArrayList<Placed>()
    for (y in floor(oy / ts).toInt().coerceAtLeast(0)..floor((oy + size.height) / ts).toInt().coerceAtMost(n - 1)) {
        for (x in floor(ox / ts).toInt()..floor((ox + size.width) / ts).toInt()) {
            out += Placed(Tile(z, x.mod(n), y), (x * ts - ox).toFloat(), (y * ts - oy).toFloat(), ts.toFloat())
        }
    }
    return out
}

/** Tile chưa có thì vẽ tạm phần tương ứng của tile cha (phóng to cho mờ còn hơn ô xám). */
private fun DrawScope.drawTile(p: Placed, bitmaps: Map<Tile, ImageBitmap>) {
    val left = p.left.roundToInt()
    val top = p.top.roundToInt()
    val dst = IntSize((p.left + p.size).roundToInt() - left, (p.top + p.size).roundToInt() - top)
    for (k in 0..4) {
        if (p.tile.z - k < 0) break
        val img = bitmaps[Tile(p.tile.z - k, p.tile.x shr k, p.tile.y shr k)] ?: continue
        val sub = img.width shr k
        if (sub == 0) break
        val mask = (1 shl k) - 1
        drawImage(
            img,
            srcOffset = IntOffset((p.tile.x and mask) * sub, (p.tile.y and mask) * sub),
            srcSize = IntSize(sub, sub),
            dstOffset = IntOffset(left, top),
            dstSize = dst,
        )
        return
    }
}

private fun DrawScope.drawEnd(at: Offset, color: Color) {
    drawCircle(color.copy(alpha = 0.9f), radius = 6.dp.toPx(), center = at)
    drawCircle(color, radius = 6.dp.toPx(), center = at, style = Stroke(2.dp.toPx()))
}

/** Mũi tên chỉ hướng đi; không có hướng thì chấm tròn — vẽ mũi tên chỉ bắc là nói dối. */
private fun DrawScope.drawCar(at: Offset, bearing: Float?) {
    if (bearing == null) {
        drawCircle(ACCENT.copy(alpha = 0.28f), radius = 9.dp.toPx(), center = at)
        drawCircle(ACCENT, radius = 6.dp.toPx(), center = at)
        return
    }
    val u = 1.dp.toPx()
    val arrow = Path().apply {
        moveTo(at.x, at.y - 12 * u)
        lineTo(at.x + 9 * u, at.y + 10 * u)
        lineTo(at.x, at.y + 5 * u)
        lineTo(at.x - 9 * u, at.y + 10 * u)
        close()
    }
    rotate(bearing, pivot = at) {
        drawPath(arrow, ACCENT)
        drawPath(arrow, Color.White, style = Stroke(2 * u, join = StrokeJoin.Round))
    }
}
