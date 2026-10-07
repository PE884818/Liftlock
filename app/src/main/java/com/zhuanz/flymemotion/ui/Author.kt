package com.zhuanz.flymemotion.ui

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.DrawableRes

/** Who made this and how to support them. Only the public nickname appears anywhere. */
object Author {
    const val NAME = "稽品飞车"
    const val GITHUB = "https://github.com/feice11"
    /** The payee link inside the Alipay code; opening it skips the scan. */
    private const val ALIPAY = "https://qr.alipay.com/fkx17723flixrzpmlywdld3"

    fun openGithub(context: Context): Boolean = start(context, Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB)))

    /** Alipay's own scheme first; a browser opening the same link is the fallback. */
    fun openAlipay(context: Context): Boolean =
        start(context, Intent(Intent.ACTION_VIEW, Uri.parse("alipays://platformapi/startapp?saId=10000007&qrcode=" + Uri.encode(ALIPAY))))
            || start(context, Intent(Intent.ACTION_VIEW, Uri.parse(ALIPAY)))

    /** WeChat's scanner, the way its home-screen shortcut opens it; the album is one tap away there. */
    fun openWechatScanner(context: Context): Boolean = start(context, Intent(Intent.ACTION_VIEW)
        .setComponent(ComponentName("com.tencent.mm", "com.tencent.mm.ui.LauncherUI"))
        .putExtra("LauncherUI.From.Scaner.Shortcut", true))

    /** The code on a white quiet zone, so any scanner reads it from the album. */
    fun saveCode(context: Context, @DrawableRes code: Int, name: String): Boolean = runCatching {
        val source = BitmapFactory.decodeResource(context.resources, code)
        val side = 1080
        val margin = 120
        val out = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            drawColor(Color.WHITE)
            drawBitmap(source, null, Rect(margin, margin, side - margin, side - margin), null)
        }
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$name.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Liftlock")
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        resolver.openOutputStream(uri)?.use { out.compress(Bitmap.CompressFormat.PNG, 100, it) } ?: return false
        true
    }.getOrDefault(false)

    private fun start(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    } catch (_: ActivityNotFoundException) { false } catch (_: SecurityException) { false }
}
