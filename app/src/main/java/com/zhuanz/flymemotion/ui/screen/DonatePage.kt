package com.zhuanz.flymemotion.ui.screen

import android.content.Context
import android.graphics.RuntimeShader
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhuanz.flymemotion.R
import com.zhuanz.flymemotion.ui.Author
import com.zhuanz.flymemotion.ui.component.Footer
import com.zhuanz.flymemotion.ui.component.PageScaffold
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TabRowWithContour
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.abs
import kotlin.math.sin

private enum class Pay(val label: String, @param:DrawableRes val code: Int, val light: Color, val deep: Color, val brand: String, val hint: String) {
    Wechat("微信", R.drawable.donate_wechat, Color(0xFF17B05E), Color(0xFF04522F), "微信赞赏", "微信扫一扫"),
    Alipay("支付宝", R.drawable.donate_alipay, Color(0xFF2B7BFF), Color(0xFF0A2F8F), "支付宝", "支付宝扫一扫"),
}

@Composable
fun DonatePage(blur: Boolean, onBack: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    var index by remember { mutableIntStateOf(0) }
    val pay = Pay.entries[index]
    val save = {
        val ok = Author.saveCode(context, pay.code, "Liftlock-${if (pay == Pay.Wechat) "wechat" else "alipay"}")
        if (ok) view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        toast(context, if (ok) "已保存到相册" else "保存失败，可以截屏保存")
    }

    PageScaffold(title = "赞赏", blur = blur, onBack = onBack) {
        item(key = "intro") {
            Text(
                "Liftlock 免费开源。如果它让你的锁屏更顺手，\n可以请作者喝杯咖啡。",
                style = MiuixTheme.textStyles.body2, textAlign = TextAlign.Center,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 8.dp),
            )
        }
        item(key = "tabs") {
            TabRowWithContour(
                tabs = Pay.entries.map { it.label },
                selectedTabIndex = index,
                onTabSelected = { index = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 48.dp, vertical = 8.dp).testTag("donate_tabs"),
            )
        }
        item(key = "card") {
            PayCard(index = index, onFlip = { index = it }, onLongPress = save)
        }
        item(key = "actions") {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 40.dp).padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                AnimatedContent(pay, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "primary") { p ->
                    Button(
                        onClick = {
                            if (p == Pay.Alipay) {
                                if (!Author.openAlipay(context)) toast(context, "没有找到支付宝，可以保存收款码后扫码")
                            } else {
                                val saved = Author.saveCode(context, p.code, "Liftlock-wechat")
                                if (!Author.openWechatScanner(context)) toast(context, if (saved) "已保存到相册，没有找到微信" else "没有找到微信")
                                else toast(context, if (saved) "已保存到相册，在扫一扫右上角选择相册" else "在扫一扫右上角选择相册")
                            }
                        },
                        colors = ButtonDefaults.buttonColors(color = p.light),
                        modifier = Modifier.fillMaxWidth().testTag("donate_primary"),
                    ) {
                        Text(if (p == Pay.Alipay) "打开支付宝" else "保存并打开微信扫一扫",
                            color = Color.White, fontWeight = FontWeight.SemiBold)
                    }
                }
                Button(onClick = save, modifier = Modifier.fillMaxWidth().testTag("donate_save")) {
                    Text("保存收款码", color = MiuixTheme.colorScheme.onSurface)
                }
            }
        }
        item(key = "note") {
            Footer("左右拨动卡片可以切换，长按卡片保存。赞赏完全自愿，不影响任何功能。",
                Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
        }
    }
}

private fun toast(context: Context, text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

/**
 * The code as a physical card: it leans toward the finger, drifts with the phone (gravity), and
 * a flick turns it over to the other payment. The surface is lit per pixel (see [CARD_SHADER]).
 */
@Composable
private fun PayCard(index: Int, onFlip: (Int) -> Unit, onLongPress: () -> Unit) {
    val view = LocalView.current
    val screen = LocalDensity.current
    val scope = rememberCoroutineScope()
    // Even turns show WeChat; the direction of a turn follows the gesture that caused it.
    var turns by remember { mutableIntStateOf(index) }
    val flip = remember { Animatable(index * 180f) }
    val leanX = remember { Animatable(0f) }
    val leanY = remember { Animatable(0f) }
    val press = remember { Animatable(1f) }
    val gravity by rememberGravityTilt()

    LaunchedEffect(index) { if (Math.floorMod(turns, 2) != index) turns += 1 }
    LaunchedEffect(turns) {
        if (flip.targetValue != turns * 180f) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        flip.animateTo(turns * 180f, spring(dampingRatio = .7f, stiffness = 190f))
    }

    val springBack = spring<Float>(dampingRatio = .55f, stiffness = 260f)
    // A driver that rejects the shader still gets the card, flat-shaded.
    val shader = remember { runCatching { RuntimeShader(CARD_SHADER) }.getOrNull() }
    val shape = RoundedCornerShape(28.dp)

    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 44.dp, vertical = 16.dp), contentAlignment = Alignment.Center) {
        val width = maxWidth.coerceAtMost(340.dp)
        val height = width / .68f
        val widthPx = with(screen) { width.toPx() }
        val rotationY = flip.value + leanX.value + gravity.x
        val rotationX = leanY.value + gravity.y
        val normalized = ((rotationY % 360f) + 360f) % 360f
        val back = normalized > 90f && normalized < 270f
        val pay = if (back) Pay.Alipay else Pay.Wechat

        Box(
            Modifier
                .size(width, height)
                .graphicsLayer {
                    this.rotationY = rotationY
                    this.rotationX = rotationX
                    cameraDistance = 12f * density
                    scaleX = press.value; scaleY = press.value
                    shadowElevation = 28.dp.toPx()
                    this.shape = shape
                    clip = true
                    ambientShadowColor = pay.deep
                    spotShadowColor = pay.deep
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        scope.launch { press.animateTo(.97f, spring(stiffness = 900f)) }
                        do {
                            val event = awaitPointerEvent()
                        } while (event.changes.any { it.pressed })
                        scope.launch { press.animateTo(1f, spring(dampingRatio = .5f, stiffness = 500f)) }
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(onLongPress = { onLongPress() })
                }
                .pointerInput(widthPx) {
                    var dx = 0f
                    var dy = 0f
                    detectDragGestures(
                        onDragStart = { dx = 0f; dy = 0f },
                        onDragEnd = {
                            if (abs(dx) > widthPx * .28f) {
                                turns += if (dx > 0) 1 else -1
                                onFlip(Math.floorMod(turns, 2))
                            }
                            scope.launch { leanX.animateTo(0f, springBack) }
                            scope.launch { leanY.animateTo(0f, springBack) }
                        },
                        onDragCancel = {
                            scope.launch { leanX.animateTo(0f, springBack) }
                            scope.launch { leanY.animateTo(0f, springBack) }
                        },
                    ) { change, amount ->
                        change.consume()
                        dx += amount.x; dy += amount.y
                        // Soft limit: the card leans freely at first and resists further out.
                        scope.launch {
                            leanX.snapTo(soft(dx / widthPx * 70f, 38f))
                            leanY.snapTo(soft(-dy / widthPx * 50f, 22f))
                        }
                    }
                },
        ) {
            // The far face is drawn mirrored back, so its text reads correctly once turned.
            Box(Modifier.fillMaxSize().graphicsLayer { if (back) scaleX = -1f }) {
                val tiltY = if (back) -(rotationY - 180f) else rotationY
                CardFace(pay, shader, tiltX = rotationX, tiltY = tiltY - Math.round(tiltY / 360f) * 360f)
            }
        }
    }
}

/** Rubber-band limit: linear near zero, approaching [max] asymptotically. */
private fun soft(v: Float, max: Float): Float = max * v / (max + abs(v)) * 1.6f

@Composable
private fun CardFace(pay: Pay, shader: RuntimeShader?, tiltX: Float, tiltY: Float) {
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .drawBehind {
                if (shader == null) {
                    drawRect(Brush.linearGradient(listOf(pay.light, pay.deep), start = Offset(size.width, 0f), end = Offset(0f, size.height)))
                    return@drawBehind
                }
                val side = size.width * .72f
                val left = (size.width - side) / 2f
                val top = size.height * .2f
                val radians = Math.PI / 180.0
                shader.setFloatUniform("size", size.width, size.height)
                shader.setFloatUniform("radius", 28.dp.toPx())
                shader.setFloatUniform("panel", left, top, left + side, top + side)
                shader.setFloatUniform("panelRadius", 20.dp.toPx())
                shader.setFloatUniform("base", pay.light.red, pay.light.green, pay.light.blue)
                shader.setFloatUniform("deep", pay.deep.red, pay.deep.green, pay.deep.blue)
                // A fixed light above-left of the screen, seen from the card's own frame.
                shader.setFloatUniform(
                    "light",
                    (-.18f + 1.5f * sin(tiltY * radians)).toFloat(),
                    (-.22f - 1.5f * sin(tiltX * radians)).toFloat(),
                    1f,
                )
                drawRect(ShaderBrush(shader))
            },
    ) {
        val side = maxWidth * .72f
        val top = maxHeight * .2f
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(top = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(pay.brand, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text("Liftlock", color = Color.White.copy(alpha = .7f), fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
        // The code's panel: matte white, only a faint gloss so it always scans.
        Box(
            Modifier
                .offset(x = (maxWidth - side) / 2, y = top)
                .size(side)
                .clip(RoundedCornerShape(20.dp))
                .background(Color.White)
                .drawWithContent {
                    drawContent()
                    val x = (.5f + sin(tiltY * Math.PI / 180.0).toFloat() * 1.8f) * size.width
                    val y = (.3f - sin(tiltX * Math.PI / 180.0).toFloat() * 1.8f) * size.height
                    drawRect(Brush.radialGradient(listOf(Color.White.copy(alpha = .16f), Color.Transparent),
                        center = Offset(x, y), radius = size.maxDimension * .7f))
                }
                .padding(14.dp),
        ) {
            Image(painterResource(pay.code), contentDescription = "${pay.label}收款码", modifier = Modifier.fillMaxSize())
        }
        Column(
            Modifier.fillMaxWidth().offset(y = top + side).padding(top = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(Author.NAME, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text("${pay.hint}，请作者喝杯咖啡", color = Color.White.copy(alpha = .75f), fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 16.dp), textAlign = TextAlign.Center)
        }
    }
}

/**
 * Lean from gravity relative to how the phone is being held: the baseline follows slowly, so the
 * card answers to movement and settles flat whenever the hand is still. Zero without a sensor.
 */
@Composable
private fun rememberGravityTilt(): State<Offset> {
    val context = LocalContext.current
    val tilt = remember { mutableStateOf(Offset.Zero) }
    DisposableEffect(Unit) {
        val manager = context.getSystemService(SensorManager::class.java)
        val sensor = manager?.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (manager == null || sensor == null) return@DisposableEffect onDispose { }
        var baseX = Float.NaN
        var baseY = 0f
        var x = 0f
        var y = 0f
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val gx = e.values[0] / SensorManager.GRAVITY_EARTH
                val gy = e.values[1] / SensorManager.GRAVITY_EARTH
                if (baseX.isNaN()) { baseX = gx; baseY = gy }
                baseX += (gx - baseX) * .02f
                baseY += (gy - baseY) * .02f
                x += ((-(gx - baseX) * 40f).coerceIn(-12f, 12f) - x) * .25f
                y += (((gy - baseY) * 40f).coerceIn(-12f, 12f) - y) * .25f
                tilt.value = Offset(x, y)
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        onDispose { manager.unregisterListener(listener) }
    }
    return tilt
}

/**
 * A pressed-metal card, lit physically: a height field (rounded bevel, the well the code sits in,
 * a fine guilloché and brushed grain) gives the normals; GGX specular with Schlick Fresnel plus a
 * tighter clear-coat lobe, viewed in perspective so the highlight travels as the card turns.
 */
private const val CARD_SHADER = """
uniform float2 size;
uniform float radius;
uniform float4 panel;
uniform float panelRadius;
uniform float3 base;
uniform float3 deep;
uniform float3 light;

float sdBox(float2 p, float2 b, float r) {
    float2 q = abs(p) - b + r;
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}
float hash(float2 p) {
    p = fract(p * float2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}
float vnoise(float2 p) {
    float2 i = floor(p);
    float2 f = fract(p);
    float2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + float2(1.0, 0.0)), u.x), mix(hash(i + float2(0.0, 1.0)), hash(i + float2(1.0, 1.0)), u.x), u.y);
}
float heightAt(float2 p) {
    float2 c = size * 0.5;
    float h = smoothstep(0.0, 22.0, -sdBox(p - c, c, radius));
    float2 pc = (panel.xy + panel.zw) * 0.5;
    float well = sdBox(p - pc, (panel.zw - panel.xy) * 0.5, panelRadius);
    h -= 0.6 * (1.0 - smoothstep(0.0, 10.0, well));
    float2 q = (p - c) / size.y;
    h += 0.010 * sin(length(q) * 300.0 + 2.2 * sin(atan(q.y, q.x) * 9.0));
    h += 0.012 * (vnoise(p * float2(0.9, 0.035)) - 0.5);
    return h;
}
float ggx(float ndh, float rough) {
    float a2 = rough * rough * rough * rough;
    float d = ndh * ndh * (a2 - 1.0) + 1.0;
    return a2 / (3.14159 * d * d);
}
half4 main(float2 p) {
    float hc = heightAt(p);
    float hx = heightAt(p + float2(1.0, 0.0)) - hc;
    float hy = heightAt(p + float2(0.0, 1.0)) - hc;
    float3 n = normalize(float3(-hx * 10.0, -hy * 10.0, 1.0));
    float3 pos = float3((p - size * 0.5) / size.y, 0.0);
    float3 v = normalize(float3(0.0, 0.0, 1.6) - pos);
    float3 l = normalize(light);
    float3 h = normalize(l + v);
    float ndl = max(dot(n, l), 0.0);
    float ndh = max(dot(n, h), 0.0);
    float vdh = max(dot(v, h), 0.0);
    float fresnel = 0.06 + 0.94 * pow(1.0 - vdh, 5.0);
    float spec = ggx(ndh, 0.42) * fresnel * 0.55 + ggx(ndh, 0.16) * fresnel * 0.012;
    float g = clamp(p.x / size.x * 0.4 + (1.0 - p.y / size.y) * 0.6, 0.0, 1.0);
    float3 albedo = mix(deep, base, g);
    float3 film = 0.5 + 0.5 * cos(6.2831 * (vdh * 2.0 + float3(0.0, 0.33, 0.67)));
    float3 tint = 0.55 + 0.45 * albedo;
    float3 color = albedo * (0.30 + 0.70 * ndl) + spec * tint * (0.8 + 0.2 * film);
    return half4(half3(clamp(color, 0.0, 1.0)), 1.0);
}
"""
