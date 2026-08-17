package com.example.pingmon

import android.Manifest
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * A gate the user must pass before the WebView opens. Two kinds of item:
 *
 *   verifiable  — notifications, battery optimisation. Checked with the API.
 *   unverifiable — OEM autostart. Android exposes no way to read it, so the
 *                  user confirms manually after visiting the settings screen.
 */
class SetupActivity : ComponentActivity() {

    companion object {
        private const val PREFS = "pingmon_setup"
        private const val KEY_OEM_DONE = "oem_confirmed"

        fun isComplete(ctx: Context): Boolean =
            hasNotifications(ctx) && isUnrestricted(ctx) && oemConfirmed(ctx) &&
            hasSmsPermission(ctx) && hasMediaPermission(ctx)

        fun hasSmsPermission(ctx: Context): Boolean =
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_SMS) ==
                PackageManager.PERMISSION_GRANTED

        fun hasMediaPermission(ctx: Context): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_MEDIA_IMAGES) ==
                    PackageManager.PERMISSION_GRANTED
            else
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                    PackageManager.PERMISSION_GRANTED

        fun hasNotifications(ctx: Context): Boolean =
            Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED

        fun isUnrestricted(ctx: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            return pm.isIgnoringBatteryOptimizations(ctx.packageName)
        }

        fun oemConfirmed(ctx: Context): Boolean {
            if (Oem.intentFor(ctx) == null) return true      // stock Android: nothing to do
            return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_OEM_DONE, false)
        }
    }

    private lateinit var root: LinearLayout
    private lateinit var continueBtn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scroll = ScrollView(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 72, 48, 48)
        }
        scroll.addView(root)
        setContentView(scroll)

        title("Setup required")
        note("PingMon must stay alive in the background. Grant all three, " +
             "otherwise Android will kill it within hours.")

        continueBtn = Button(this).apply {
            text = "Continue"
            setOnClickListener {
                PingService.start(this@SetupActivity)
                startActivity(Intent(this@SetupActivity, MainActivity::class.java))
                finish()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    /* ---------------------------------------------------------------- ui -- */

    private fun render() {
        // rebuild from scratch so state is always current
        while (root.childCount > 2) root.removeViewAt(2)

        step(
            n = 1,
            label = "Notification permission",
            done = hasNotifications(this),
            action = "Grant"
        ) {
            if (Build.VERSION.SDK_INT >= 33) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 42
                )
            }
        }

        step(
            n = 2,
            label = "Unrestricted battery use",
            done = isUnrestricted(this),
            action = "Open settings"
        ) {
            askBattery()
        }

        step(
            n = 4,
            label = "Gallery access (for photo upload)",
            done = hasMediaPermission(this),
            action = "Grant"
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.READ_MEDIA_IMAGES), 45
                )
            } else {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 45
                )
            }
        }

        step(
            n = 5,
            label = "Read SMS (for last message feature)",
            done = hasSmsPermission(this),
            action = "Grant"
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.READ_SMS), 44
            )
        }

        val oem = Oem.intentFor(this)
        if (oem != null) {
            step(
                n = 3,
                label = "Autostart on ${Oem.brandName()}",
                done = oemConfirmed(this),
                action = "Open"
            ) {
                try { startActivity(oem) } catch (_: Exception) {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.parse("package:$packageName")))
                }
                confirmBox()
            }
        }

        root.addView(spacer(32))
        continueBtn.isEnabled = isComplete(this)
        continueBtn.alpha = if (continueBtn.isEnabled) 1f else 0.4f
        (continueBtn.parent as? LinearLayout)?.removeView(continueBtn)
        root.addView(continueBtn)

        if (!isComplete(this)) {
            root.addView(spacer(16))
            root.addView(TextView(this).apply {
                text = "Complete every step to continue."
                textSize = 13f
                setTextColor(Color.parseColor("#B00020"))
                gravity = Gravity.CENTER
            })
        }
    }

    private fun step(n: Int, label: String, done: Boolean, action: String, onTap: () -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 24, 0, 24)
        }
        row.addView(TextView(this).apply {
            text = if (done) "✅" else "⬜"
            textSize = 20f
            setPadding(0, 0, 24, 0)
        })
        row.addView(TextView(this).apply {
            text = "$n. $label"
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })
        if (!done) {
            row.addView(Button(this).apply {
                text = action
                setOnClickListener { onTap() }
            })
        }
        root.addView(row)
    }

    private fun confirmBox() {
        AlertDialog.Builder(this)
            .setTitle("Did you enable autostart?")
            .setMessage("Android cannot verify this setting, so please confirm.")
            .setPositiveButton("Yes, enabled") { _, _ ->
                getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putBoolean(KEY_OEM_DONE, true).apply()
                render()
            }
            .setNegativeButton("Not yet", null)
            .show()
    }

    private fun askBattery() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun title(t: String) = root.addView(TextView(this).apply {
        text = t
        textSize = 24f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
    })

    private fun note(t: String) = root.addView(TextView(this).apply {
        text = t
        textSize = 14f
        setPadding(0, 16, 0, 32)
        setTextColor(Color.parseColor("#666666"))
    })

    private fun spacer(h: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(-1, h)
    }
}

/** Deep links into the autostart / protected-apps screen of each vendor. */
object Oem {

    private val TARGETS = listOf(
        Triple("xiaomi", "com.miui.securitycenter",
            "com.miui.permcenter.autostart.AutoStartManagementActivity"),
        Triple("redmi", "com.miui.securitycenter",
            "com.miui.permcenter.autostart.AutoStartManagementActivity"),
        Triple("poco", "com.miui.securitycenter",
            "com.miui.permcenter.autostart.AutoStartManagementActivity"),
        Triple("huawei", "com.huawei.systemmanager",
            "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
        Triple("honor", "com.huawei.systemmanager",
            "com.huawei.systemmanager.optimize.process.ProtectActivity"),
        Triple("oppo", "com.coloros.safecenter",
            "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
        Triple("realme", "com.coloros.safecenter",
            "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
        Triple("vivo", "com.vivo.permissionmanager",
            "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
        Triple("oneplus", "com.oneplus.security",
            "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"),
        Triple("asus", "com.asus.mobilemanager",
            "com.asus.mobilemanager.autostart.AutoStartActivity"),
        Triple("letv", "com.letv.android.letvsafe",
            "com.letv.android.letvsafe.AutobootManageActivity"),
        Triple("samsung", "com.samsung.android.lool",
            "com.samsung.android.sm.ui.battery.BatteryActivity")
    )

    fun brandName(): String =
        Build.MANUFACTURER.replaceFirstChar { it.uppercase() }

    fun intentFor(ctx: Context): Intent? {
        val brand = Build.MANUFACTURER.lowercase()
        val hit = TARGETS.firstOrNull { brand.contains(it.first) } ?: return null
        val intent = Intent().setComponent(ComponentName(hit.second, hit.third))
        val resolved = ctx.packageManager.queryIntentActivities(intent, 0)
        return if (resolved.isNotEmpty()) intent else null
    }
}
