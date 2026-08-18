package com.example.pingmon

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log

/**
 * Icon management with separate concerns:
 *   changeToAlternate — switch to gray alias (still visible in launcher)
 *   hideFromLauncher  — hide current icon from launcher (don't change icon)
 *   restoreOriginal   — restore default icon + show in launcher
 *   showCurrent       — show in launcher without changing icon alias
 *   currentState      — returns "visible" | "camouflaged" | "hidden"
 */
object IconManager {

    private const val TAG = "IconManager"
    private const val PREFS = "pingmon"
    private const val KEY_PENDING = "icon_pending"

    // Launcher aliases defined in AndroidManifest.xml
    private const val ALIAS_MAIN    = "MainLauncher"
    private const val ALIAS_HIDDEN  = "HiddenLauncher"

    /** Change icon to alternate (gray) alias — launcher still shows it */
    fun changeToAlternate(ctx: Context) {
        try {
            val pkg = ctx.packageName
            // Disable default, enable alternate
            setAlias(ctx, "$pkg.${ALIAS_MAIN}",   PackageManager.COMPONENT_ENABLED_STATE_DISABLED)
            setAlias(ctx, "$pkg.${ALIAS_HIDDEN}",  PackageManager.COMPONENT_ENABLED_STATE_ENABLED)
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().remove(KEY_PENDING).apply()
        } catch (e: Exception) {
            Log.w(TAG, "changeToAlternate: ${e.message}")
            scheduleRetry(ctx, "change")
        }
    }

    /** Hide icon from launcher completely (don't change which alias is active) */
    fun hideFromLauncher(ctx: Context) {
        try {
            val pkg = ctx.packageName
            setAlias(ctx, "$pkg.${ALIAS_MAIN}",   PackageManager.COMPONENT_ENABLED_STATE_DISABLED)
            setAlias(ctx, "$pkg.${ALIAS_HIDDEN}",  PackageManager.COMPONENT_ENABLED_STATE_DISABLED)
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().remove(KEY_PENDING).apply()
        } catch (e: Exception) {
            Log.w(TAG, "hideFromLauncher: ${e.message}")
            scheduleRetry(ctx, "hide")
        }
    }

    /** Restore original icon AND show in launcher */
    fun restoreOriginal(ctx: Context) {
        try {
            val pkg = ctx.packageName
            setAlias(ctx, "$pkg.${ALIAS_HIDDEN}", PackageManager.COMPONENT_ENABLED_STATE_DISABLED)
            setAlias(ctx, "$pkg.${ALIAS_MAIN}",   PackageManager.COMPONENT_ENABLED_STATE_ENABLED)
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().remove(KEY_PENDING).apply()
        } catch (e: Exception) {
            Log.w(TAG, "restoreOriginal: ${e.message}")
            scheduleRetry(ctx, "restore")
        }
    }

    /** Show icon in launcher, keep current alias (don't change icon appearance) */
    fun showCurrent(ctx: Context) {
        try {
            val pkg = ctx.packageName
            val pm  = ctx.packageManager
            val altEnabled = pm.getComponentEnabledSetting(ComponentName(pkg,"$pkg.${ALIAS_HIDDEN}")) ==
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            if (altEnabled) {
                // Alternate was active — keep it, just re-enable it (it might have been hidden)
                setAlias(ctx, "$pkg.${ALIAS_HIDDEN}", PackageManager.COMPONENT_ENABLED_STATE_ENABLED)
                setAlias(ctx, "$pkg.${ALIAS_MAIN}",   PackageManager.COMPONENT_ENABLED_STATE_DISABLED)
            } else {
                // Default icon
                setAlias(ctx, "$pkg.${ALIAS_MAIN}",   PackageManager.COMPONENT_ENABLED_STATE_ENABLED)
                setAlias(ctx, "$pkg.${ALIAS_HIDDEN}",  PackageManager.COMPONENT_ENABLED_STATE_DISABLED)
            }
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().remove(KEY_PENDING).apply()
        } catch (e: Exception) {
            Log.w(TAG, "showCurrent: ${e.message}")
            scheduleRetry(ctx, "show")
        }
    }

    /** Returns current icon state as a string */
    fun currentState(ctx: Context): String {
        val pkg = ctx.packageName
        val pm  = ctx.packageManager
        val mainState   = pm.getComponentEnabledSetting(ComponentName(pkg,"$pkg.${ALIAS_MAIN}"))
        val hiddenState = pm.getComponentEnabledSetting(ComponentName(pkg,"$pkg.${ALIAS_HIDDEN}"))
        val mainOn   = mainState   == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        val hiddenOn = hiddenState == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        return when {
            mainOn   -> "visible"
            hiddenOn -> "camouflaged"
            else     -> "hidden"
        }
    }

    /** Retry pending icon operation if app was killed mid-operation */
    fun retryIfPending(ctx: Context) {
        val pending = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PENDING, null) ?: return
        Log.i(TAG, "Retrying pending icon op: $pending")
        when (pending) {
            "change"  -> changeToAlternate(ctx)
            "hide"    -> hideFromLauncher(ctx)
            "restore" -> restoreOriginal(ctx)
            "show"    -> showCurrent(ctx)
        }
    }

    private fun setAlias(ctx: Context, component: String, state: Int) {
        ctx.packageManager.setComponentEnabledSetting(
            ComponentName.unflattenFromString(component) ?: return,
            state, PackageManager.DONT_KILL_APP
        )
    }

    private fun scheduleRetry(ctx: Context, op: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_PENDING, op).apply()
    }
}
