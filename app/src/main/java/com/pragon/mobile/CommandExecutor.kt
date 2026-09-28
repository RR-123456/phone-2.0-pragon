package com.pragon.mobile

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.provider.MediaStore
import android.provider.Settings
import android.view.KeyEvent
import org.json.JSONObject

/** Runs one command from the PC. Same action names as the PC's phone_control tool. */
object CommandExecutor {

    data class Res(val ok: Boolean, val msg: String, val unsupported: Boolean = false)

    private const val YT = "com.google.android.youtube"

    private val ALIASES = mapOf(
        "youtube" to YT,
        "youtube music" to "com.google.android.apps.youtube.music",
        "whatsapp" to "com.whatsapp",
        "instagram" to "com.instagram.android",
        "snapchat" to "com.snapchat.android",
        "facebook" to "com.facebook.katana",
        "messenger" to "com.facebook.orca",
        "telegram" to "org.telegram.messenger",
        "twitter" to "com.twitter.android",
        "x" to "com.twitter.android",
        "spotify" to "com.spotify.music",
        "netflix" to "com.netflix.mediaclient",
        "chrome" to "com.android.chrome",
        "gmail" to "com.google.android.gm",
        "maps" to "com.google.android.apps.maps",
        "google maps" to "com.google.android.apps.maps",
        "photos" to "com.google.android.apps.photos",
        "play store" to "com.android.vending",
        "google" to "com.google.android.googlequicksearchbox",
        "discord" to "com.discord",
        "zoom" to "us.zoom.videomeetings",
    )

    fun run(ctx: Context, cmd: JSONObject): Res {
        val action = cmd.optString("action").lowercase()
        val value = cmd.optString("value").trim()
        val app = cmd.optString("app_name").trim()
        return try {
            when (action) {
                "status" -> status(ctx)
                "open_app" -> openApp(ctx, app.ifEmpty { value })
                "open_url" -> openUrl(ctx, value)
                "youtube_search", "search_youtube" -> youtubeSearch(ctx, value)
                "web_search", "search" -> webSearch(ctx, value)
                "key" -> key(ctx, value.ifEmpty { app }, cmd.optInt("count", 1))
                "swipe" -> swipe(ctx, cmd.optString("direction").ifEmpty { value })
                "tap" -> tap(cmd.optInt("x", -1), cmd.optInt("y", -1))
                "type_text" -> typeText(value)
                "call" -> call(ctx, value)
                else -> Res(false, "The phone app can't do '$action'.", unsupported = true)
            }
        } catch (e: Exception) {
            Res(false, "Phone error: ${e.message}")
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9]"), "")

    private fun needAccessibility() = Res(
        false,
        "Turn on the PragonMobile accessibility service on the phone (Settings > Accessibility > PragonMobile)."
    )

    @Suppress("DEPRECATION")
    private fun wake(ctx: Context): String {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isInteractive) {
            pm.newWakeLock(
                PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                "pragon:wake"
            ).acquire(3000)
            try { Thread.sleep(400) } catch (e: InterruptedException) { }
        }
        val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        return if (km.isKeyguardLocked) " (The phone is locked - unlock it to see the result.)" else ""
    }

    private fun go(ctx: Context, intent: Intent, okMsg: String): Res {
        val note = wake(ctx)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            ctx.startActivity(intent)
            Res(true, okMsg + note)
        } catch (e: ActivityNotFoundException) {
            Res(false, "No app on the phone can handle that.")
        } catch (e: Exception) {
            Res(
                false,
                "Android blocked the launch (${e.message}). In PragonMobile, allow 'Display over other apps' and the accessibility service."
            )
        }
    }

    // ── actions ──────────────────────────────────────────────────────────

    private fun status(ctx: Context): Res {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val a11y = if (PragonAccessibilityService.instance != null) "" else
            " Accessibility service is OFF - taps and buttons won't work until you enable it."
        return Res(
            true,
            "Connected to ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}), battery $level%.$a11y"
        )
    }

    private fun special(name: String): Intent? = when (name) {
        "camera" -> Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
        "settings" -> Intent(Settings.ACTION_SETTINGS)
        "wifi settings" -> Intent(Settings.ACTION_WIFI_SETTINGS)
        "bluetooth settings" -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
        "dialer", "phone" -> Intent(Intent.ACTION_DIAL)
        else -> null
    }

    private fun openApp(ctx: Context, name: String): Res {
        if (name.isBlank()) return Res(false, "Which app should I open?")
        val key = name.lowercase().trim()
        special(key)?.let { return go(ctx, it, "Opened $name on your phone.") }

        val pm = ctx.packageManager
        var intent: Intent? = ALIASES[key]?.let { pm.getLaunchIntentForPackage(it) }
        if (intent == null) {
            val q = norm(name)
            if (q.isNotEmpty()) {
                val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                val apps = pm.queryIntentActivities(main, 0)
                    .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
                val hit = apps.firstOrNull { norm(it.first) == q }
                    ?: apps.filter { norm(it.first).startsWith(q) }.minByOrNull { it.first.length }
                    ?: apps.filter { norm(it.first).contains(q) }.minByOrNull { it.first.length }
                intent = hit?.let { pm.getLaunchIntentForPackage(it.second) }
            }
        }
        if (intent == null) return Res(false, "I couldn't find an app called '$name' on the phone.")
        return go(ctx, intent, "Opened $name on your phone.")
    }

    private fun openUrl(ctx: Context, url0: String): Res {
        if (url0.isBlank()) return Res(false, "Which link should I open?")
        val url = if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(url0)) url0 else "https://$url0"
        return go(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(url)), "Opened $url on your phone.")
    }

    private fun youtubeSearch(ctx: Context, q: String): Res {
        if (q.isBlank()) return Res(false, "What should I search for on YouTube?")
        val i = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(q))
        )
        if (ctx.packageManager.getLaunchIntentForPackage(YT) != null) i.setPackage(YT)
        return go(ctx, i, "Searching YouTube for '$q' on your phone.")
    }

    private fun webSearch(ctx: Context, q: String): Res {
        if (q.isBlank()) return Res(false, "What should I search for?")
        val i = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://www.google.com/search?q=" + Uri.encode(q))
        )
        return go(ctx, i, "Searching Google for '$q' on your phone.")
    }

    private fun media(audio: AudioManager, code: Int, label: String): Res {
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        return Res(true, "Sent $label.")
    }

    private fun key(ctx: Context, name0: String, count0: Int): Res {
        val name = name0.lowercase().trim().replace(' ', '_')
        val count = count0.coerceIn(1, 30)
        val svc = PragonAccessibilityService.instance

        fun global(action: Int, label: String): Res {
            val s = svc ?: return needAccessibility()
            return if (s.performGlobalAction(action)) Res(true, "Done ($label).")
            else Res(false, "The phone refused '$label'.")
        }

        val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return when (name) {
            "home" -> global(AccessibilityService.GLOBAL_ACTION_HOME, "home")
            "back" -> global(AccessibilityService.GLOBAL_ACTION_BACK, "back")
            "recents" -> global(AccessibilityService.GLOBAL_ACTION_RECENTS, "recent apps")
            "notifications" -> global(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS, "notifications")
            "quick_settings" -> global(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS, "quick settings")
            "close_panel" -> global(AccessibilityService.GLOBAL_ACTION_BACK, "close panel")
            "power" -> global(AccessibilityService.GLOBAL_ACTION_POWER_DIALOG, "power menu")
            "lock", "sleep" ->
                if (Build.VERSION.SDK_INT >= 28) global(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN, "lock")
                else Res(false, "Locking the screen needs Android 9 or newer.", unsupported = true)
            "wake" -> Res(true, "Screen on." + wake(ctx))
            "volume_up", "volume_down" -> {
                val dir = if (name == "volume_up") AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
                repeat(count) { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, if (it == count - 1) AudioManager.FLAG_SHOW_UI else 0) }
                Res(true, "Volume ${if (name == "volume_up") "up" else "down"}" + (if (count > 1) " x$count" else "") + ".")
            }
            "mute" -> {
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI)
                Res(true, "Toggled mute.")
            }
            "play_pause" -> media(audio, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, "play/pause")
            "next" -> media(audio, KeyEvent.KEYCODE_MEDIA_NEXT, "next track")
            "previous" -> media(audio, KeyEvent.KEYCODE_MEDIA_PREVIOUS, "previous track")
            else -> Res(false, "The phone app doesn't support key '$name'.", unsupported = true)
        }
    }

    private fun swipe(ctx: Context, direction: String): Res {
        val s = PragonAccessibilityService.instance ?: return needAccessibility()
        val m = ctx.resources.displayMetrics
        val w = m.widthPixels.toFloat()
        val h = m.heightPixels.toFloat()
        val (x1, y1, x2, y2) = when (direction.lowercase().trim()) {
            "up" -> listOf(w / 2, h * .75f, w / 2, h * .25f)
            "down" -> listOf(w / 2, h * .25f, w / 2, h * .75f)
            "left" -> listOf(w * .85f, h / 2, w * .15f, h / 2)
            "right" -> listOf(w * .15f, h / 2, w * .85f, h / 2)
            else -> return Res(false, "Swipe direction must be up, down, left or right.")
        }
        val p = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        return if (s.gesture(p, 300)) Res(true, "Swiped ${direction.lowercase()}.")
        else Res(false, "The phone didn't perform the swipe.")
    }

    private fun tap(x: Int, y: Int): Res {
        val s = PragonAccessibilityService.instance ?: return needAccessibility()
        if (x < 0 || y < 0) return Res(false, "Tap needs x and y coordinates.")
        val p = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        return if (s.gesture(p, 60)) Res(true, "Tapped ($x, $y).") else Res(false, "The phone didn't perform the tap.")
    }

    private fun typeText(text: String): Res {
        val s = PragonAccessibilityService.instance ?: return needAccessibility()
        if (text.isEmpty()) return Res(false, "What should I type?")
        return if (s.typeText(text)) Res(true, "Typed it on your phone.")
        else Res(false, "Tap a text box on the phone first, then try again.")
    }

    private fun call(ctx: Context, number: String): Res {
        val digits = number.replace(Regex("[^0-9+*#]"), "")
        if (digits.isEmpty()) return Res(false, "Which number should I dial?")
        val i = Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(digits)))
        val r = go(ctx, i, "Opened the dialer with $digits - tap the call button to place it.")
        return r
    }
}
