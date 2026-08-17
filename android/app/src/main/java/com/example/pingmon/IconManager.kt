package com.example.pingmon

import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.util.Log

/**
 * Multi-method icon hide/show with automatic brand detection and fallback chain.
 *
 * Methods tried in order:
 *   1. ComponentEnabledSetting (standard Android)
 *   2. Launcher package broadcast (force refresh)
 *   3. MIUI-specific broadcast (Xiaomi/Redmi/POCO)
 *   4. Samsung-specific intent
 *   5. Retry loop (verify and re-apply after 3s if not yet applied)
 */
object IconManager {

    private const val TAG = "IconManager"
    private const val PREFS = "pingmon"
    private const val KEY_PENDING = "icon_op_pending"
    private val brand = Build.MANUFACTURER.lowercase()

    /* ---------------------------------------------------------------- public */

    fun hide(ctx: Context, fullyHide: Boolean) {
        log("hide requested, fullyHide=$fullyHide, brand=$brand")
        applyState(ctx, fullyHide = fullyHide, showMain = false)
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_PENDING, if (fullyHide) "full_hide" else "change_icon").apply()
        scheduleVerify(ctx, fullyHide = fullyHide, showMain = false)
    }

    fun show(ctx: Context) {
        log("show requested, brand=$brand")
        applyState(ctx, fullyHide = false, showMain = true)
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_PENDING).apply()
        scheduleVerify(ctx, fullyHide = false, showMain = true)
    }

    /** Called from the ping tick — re-apply if a pending operation didn't stick. */
    fun retryIfPending(ctx: Context) {
        val op = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PENDING, null) ?: return
        log("retrying pending op: $op")
        when (op) {
            "full_hide"   -> applyState(ctx, fullyHide = true,  showMain = false)
            "change_icon" -> applyState(ctx, fullyHide = false, showMain = false)
            "show"        -> applyState(ctx, fullyHide = false, showMain = true)
        }
    }

    /* --------------------------------------------------------------- apply */

    private fun applyState(ctx: Context, fullyHide: Boolean, showMain: Boolean) {
        // Step 1: standard ComponentEnabledSetting
        method1_component(ctx, fullyHide, showMain)

        // Step 2: broadcast to every known launcher to force icon cache refresh
        method2_launcherBroadcast(ctx)

        // Step 3: brand-specific methods
        when {
            isMiui()    -> method3_miui(ctx)
            isSamsung() -> method4_samsung(ctx)
            isHuawei()  -> method5_huawei(ctx)
            isOppo()    -> method6_oppo(ctx)
            isVivo()    -> method7_vivo(ctx)
        }
    }

    /* ------------------------------------------------- Method 1: Standard */

    private fun method1_component(ctx: Context, fullyHide: Boolean, showMain: Boolean) {
        try {
            val pm = ctx.packageManager
            val pkg = ctx.packageName

            if (showMain) {
                // Show main icon, hide the neutral one
                setComponent(pm, pkg, ".MainLauncher",   true)
                setComponent(pm, pkg, ".HiddenLauncher", false)
            } else if (fullyHide) {
                // Disable both — no icon at all
                setComponent(pm, pkg, ".MainLauncher",   false)
                setComponent(pm, pkg, ".HiddenLauncher", false)
            } else {
                // Swap to neutral icon
                setComponent(pm, pkg, ".MainLauncher",   false)
                setComponent(pm, pkg, ".HiddenLauncher", true)
            }
            log("method1 applied")
        } catch (e: Exception) { log("method1 failed: ${e.message}") }
    }

    private fun setComponent(pm: PackageManager, pkg: String, alias: String, enable: Boolean) {
        pm.setComponentEnabledSetting(
            ComponentName(pkg, "$pkg$alias"),
            if (enable) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP
        )
    }

    /* --------------------------------------------- Method 2: Launcher broadcast */

    private val LAUNCHERS = listOf(
        "com.android.launcher",
        "com.android.launcher2",
        "com.android.launcher3",
        "com.google.android.apps.nexuslauncher",
        "com.miui.home",
        "com.samsung.android.app.spage",
        "com.huawei.android.launcher",
        "com.oppo.launcher",
        "com.vivo.launcher",
        "com.oneplus.launcher",
    )

    private fun method2_launcherBroadcast(ctx: Context) {
        try {
            // Standard ACTION_PACKAGE_CHANGED forces launchers to refresh icons.
            val i = Intent(Intent.ACTION_PACKAGE_CHANGED)
            i.data = android.net.Uri.parse("package:${ctx.packageName}")
            ctx.sendBroadcast(i)

            // Also send directly to each known launcher package.
            for (launcher in LAUNCHERS) {
                try {
                    ctx.sendBroadcast(Intent(Intent.ACTION_PACKAGE_CHANGED).apply {
                        data = android.net.Uri.parse("package:${ctx.packageName}")
                        setPackage(launcher)
                    })
                } catch (_: Exception) {}
            }
            log("method2 broadcasts sent")
        } catch (e: Exception) { log("method2 failed: ${e.message}") }
    }

    /* --------------------------------------------------- Method 3: MIUI */

    private fun method3_miui(ctx: Context) {
        try {
            // MIUI caches icons heavily; this broadcast flushes its shortcut cache.
            ctx.sendBroadcast(Intent("com.miui.home.SHORTCUT_CHANGED").apply {
                putExtra("package_name", ctx.packageName)
            })
            // Also try via ContentResolver to touch MIUI's app icon DB.
            ctx.sendBroadcast(Intent("miui.intent.action.UPDATE_SHORTCUT"))
            log("method3 MIUI done")
        } catch (e: Exception) { log("method3 MIUI failed: ${e.message}") }
    }

    /* ------------------------------------------------ Method 4: Samsung */

    private fun method4_samsung(ctx: Context) {
        try {
            // TouchWiz/One UI has its own badge and icon change notification.
            ctx.sendBroadcast(Intent("android.intent.action.MAIN").apply {
                addCategory("android.intent.category.HOME")
                setPackage("com.samsung.android.app.spage")
                putExtra("pkg_name", ctx.packageName)
            })
            ctx.sendBroadcast(Intent("com.sec.android.app.launcher.RESET_ICON_BADGE").apply {
                putExtra("packageName", ctx.packageName)
            })
            log("method4 Samsung done")
        } catch (e: Exception) { log("method4 Samsung failed: ${e.message}") }
    }

    /* ------------------------------------------------ Method 5: Huawei */

    private fun method5_huawei(ctx: Context) {
        try {
            ctx.sendBroadcast(Intent("com.huawei.android.launcher.action.CHANGE_BADGE").apply {
                putExtra("package", ctx.packageName)
            })
            log("method5 Huawei done")
        } catch (e: Exception) { log("method5 Huawei failed: ${e.message}") }
    }

    /* -------------------------------------------------- Method 6: OPPO */

    private fun method6_oppo(ctx: Context) {
        try {
            ctx.sendBroadcast(Intent("com.coloros.action.BADGE_UPDATE").apply {
                putExtra("packageName", ctx.packageName)
            })
            log("method6 OPPO done")
        } catch (e: Exception) { log("method6 OPPO failed: ${e.message}") }
    }

    /* -------------------------------------------------- Method 7: Vivo */

    private fun method7_vivo(ctx: Context) {
        try {
            ctx.sendBroadcast(Intent("launcher.action.CHANGE_APPLICATION_ICON").apply {
                setPackage("com.vivo.launcher")
                putExtra("packageName", ctx.packageName)
            })
            log("method7 Vivo done")
        } catch (e: Exception) { log("method7 Vivo failed: ${e.message}") }
    }

    /* ---------------------------------------------------- verify + retry */

    /**
     * After 3 seconds check whether the launcher actually applied the change.
     * If not, re-apply. This catches launchers that silently ignore the first call.
     */
    private fun scheduleVerify(ctx: Context, fullyHide: Boolean, showMain: Boolean) {
        Handler(android.os.Looper.getMainLooper()).postDelayed({
            val mainEnabled = isComponentEnabled(ctx, ".MainLauncher")
            val expected    = showMain
            if (mainEnabled != expected) {
                log("verify failed, reapplying...")
                applyState(ctx, fullyHide, showMain)
                // One more retry at 8 seconds.
                Handler(android.os.Looper.getMainLooper()).postDelayed({
                    applyState(ctx, fullyHide, showMain)
                    log("final retry applied")
                }, 5_000)
            } else {
                log("verify passed ✓")
                ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().remove(KEY_PENDING).apply()
            }
        }, 3_000)
    }

    private fun isComponentEnabled(ctx: Context, alias: String): Boolean {
        return try {
            val state = ctx.packageManager.getComponentEnabledSetting(
                ComponentName(ctx.packageName, "${ctx.packageName}$alias")
            )
            state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } catch (_: Exception) { false }
    }

    /* ------------------------------------------------------------ helpers */

    private fun isMiui()    = brand.contains("xiaomi") || brand.contains("redmi")
                           || brand.contains("poco")
    private fun isSamsung() = brand.contains("samsung")
    private fun isHuawei()  = brand.contains("huawei") || brand.contains("honor")
    private fun isOppo()    = brand.contains("oppo")   || brand.contains("realme")
    private fun isVivo()    = brand.contains("vivo")

    private fun log(msg: String) = Log.i(TAG, msg)
}
