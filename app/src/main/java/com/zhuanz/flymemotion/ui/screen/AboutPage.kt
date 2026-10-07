package com.zhuanz.flymemotion.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import com.zhuanz.flymemotion.ui.component.ClawdTyping
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import com.zhuanz.flymemotion.ui.Author
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhuanz.flymemotion.R
import com.zhuanz.flymemotion.TargetBuild
import com.zhuanz.flymemotion.core.ReloadPlan
import com.zhuanz.flymemotion.ui.FRAMEWORK
import com.zhuanz.flymemotion.ui.ManagerViewModel
import com.zhuanz.flymemotion.ui.Route
import com.zhuanz.flymemotion.ui.Tone
import com.zhuanz.flymemotion.ui.component.Glyphs
import com.zhuanz.flymemotion.ui.component.PageScaffold
import com.zhuanz.flymemotion.ui.component.Section
import com.zhuanz.flymemotion.ui.component.ValueRow
import com.zhuanz.flymemotion.ui.theme.ToneColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Who made it, in one card: the app, the author, and two ways to reach them. */
@Composable
fun AuthorCard(onGithub: () -> Unit, onDonate: () -> Unit) {
    val context = LocalContext.current
    // "0.3.5-miuix18" reads as "0.3.5"; the build suffix is in Diagnostics.
    val version = remember { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty().substringBefore('-') }
    Card(Modifier.padding(horizontal = 12.dp).padding(top = 12.dp)) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.ic_launcher), contentDescription = null,
                    modifier = Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Liftlock $version", style = MiuixTheme.textStyles.headline1, fontWeight = FontWeight.SemiBold)
                    Text("把 Flyme 的锁屏上滑手感带到澎湃 OS", style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
            }
            Spacer(Modifier.height(16.dp))
            // The developer, drawn as Clawd at the keyboard: this module was written with Claude.
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                    .background(MiuixTheme.colorScheme.surface.copy(alpha = .7f)).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(68.dp).clip(RoundedCornerShape(18.dp)).background(Color(0xFF1E1B18))) {
                    ClawdTyping(Modifier.fillMaxSize().padding(6.dp))
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("开发者", style = MiuixTheme.textStyles.footnote2, color = Color(0xFFD97757), fontWeight = FontWeight.Bold)
                    Text(Author.NAME, style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.Bold,
                        modifier = Modifier.testTag("author_line"))
                    Text("和 Claude 一起敲出来的", style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onGithub, modifier = Modifier.weight(1f).testTag("open_github")) {
                    Text("GitHub", fontWeight = FontWeight.Medium)
                }
                Button(onClick = onDonate, colors = ButtonDefaults.buttonColorsPrimary(),
                    modifier = Modifier.weight(1f).testTag("open_donate_more")) {
                    Text("赞赏作者", color = Color.White, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

private data class Step(val title: String, val summary: String?, val done: Boolean)

@Composable
fun GuidePage(vm: ManagerViewModel, blur: Boolean, onBack: () -> Unit) {
    val link by vm.link.collectAsState()
    val receipts by vm.receipts.collectAsState()
    val config by vm.config.collectAsState()
    val steps = listOf(
        Step("在 LSPosed 中启用本模块", null, link.connected),
        Step("勾选系统界面", null, link.hasScope(ReloadPlan.SYSTEMUI)),
        Step("勾选系统设置", null, link.hasScope(ReloadPlan.SETTINGS)),
        Step("勾选系统框架", "勾选后需要重启手机", link.hasScope(FRAMEWORK)),
        Step("勾选壁纸", null, link.hasScope(ReloadPlan.WALLPAPER)),
        Step("重启手机", null, receipts.framework.currentModule()),
        Step("锁屏后上滑一次", if (config.enabled) null else "先在主页打开开关", receipts.ui.currentModule() && receipts.ui.motion > 0),
    )
    PageScaffold(title = "首次启用", blur = blur, onBack = onBack) {
        item(key = "steps") {
            Section(footer = "只需要这四个作用域，不用勾选桌面或其他应用。") {
                steps.forEach { step ->
                    BasicComponent(
                        title = step.title,
                        summary = step.summary,
                        startAction = {
                            Icon(if (step.done) Glyphs.Ok else Glyphs.Wait, contentDescription = if (step.done) "已完成" else "未完成",
                                tint = if (step.done) ToneColors.accent(Tone.Good) else MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = .5f),
                                modifier = Modifier.padding(end = 14.dp).size(22.dp))
                        },
                    )
                }
            }
        }
    }
}

private data class License(val name: String, val detail: String, val asset: String?)

private val licenses = listOf(
    License("HyperMusicCover v0.7.10", "AGPL-3.0", "licenses/HyperMusicCover-AGPL-3.0.txt"),
    License("HyperMusicCover 声明", "版权与改编说明", "licenses/HyperMusicCover-NOTICE.txt"),
    License("miuix", "Apache-2.0", "licenses/Apache-2.0.txt"),
    License("HyperNavBar", "Apache-2.0", "licenses/Apache-2.0.txt"),
    License("AndroidX 与 Jetpack Compose", "Apache-2.0", "licenses/Apache-2.0.txt"),
    License("libxposed service", "Apache-2.0", "licenses/Apache-2.0.txt"),
    License("Android NDK 工具链", "声明", "third_party_licenses/NOTICE.toolchain"),
    License("Android NDK sysroot", "声明", "third_party_licenses/NOTICE.sysroot"),
)

@Composable
fun LicensesPage(blur: Boolean, onBack: () -> Unit, open: (Route) -> Unit) {
    PageScaffold(title = "开源许可", blur = blur, onBack = onBack) {
        item(key = "intro") {
            Section(footer = "Liftlock 按 AGPL-3.0 发布，源码见 ${Author.GITHUB.removePrefix("https://")}。壁纸引擎适配改编自 HyperMusicCover（github.com/zyl6932/HyperMusicCover），界面参考了它的设计。") {
                licenses.forEach { l ->
                    ValueRow(l.name, l.detail, onClick = { l.asset?.let { open(Route.LicenseText(l.name, it)) } })
                }
            }
        }
    }
}

@Composable
fun LicenseTextPage(route: Route.LicenseText, blur: Boolean, onBack: () -> Unit) {
    val context = LocalContext.current
    var chunks by remember(route) { mutableStateOf<List<String>?>(null) }
    LaunchedEffect(route) {
        chunks = withContext(Dispatchers.IO) {
            runCatching {
                context.assets.open(route.asset).bufferedReader().use { it.readLines() }.chunked(40) { it.joinToString("\n") }
            }.getOrElse { listOf("许可证读取失败：${it.javaClass.simpleName}") }
        }
    }
    PageScaffold(title = route.title, blur = blur, onBack = onBack) {
        items(chunks.orEmpty()) { text ->
            Text(text, fontSize = 11.sp, lineHeight = 15.sp, fontFamily = FontFamily.Monospace,
                color = MiuixTheme.colorScheme.onSurface, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        }
    }
}
