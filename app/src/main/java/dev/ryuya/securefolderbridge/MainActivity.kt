package dev.ryuya.securefolderbridge

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.net.Uri
import android.provider.Settings
import android.view.View
import android.view.WindowInsets
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.ryuya.securefolderbridge.databinding.ActivityMainBinding
import dev.ryuya.securefolderbridge.databinding.ViewDestinationBinding
import java.util.concurrent.Executors

/** Presentation only. The service owns the connection independently of this screen. */
class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val worker = Executors.newSingleThreadExecutor()
    private val settings get() = getSharedPreferences("settings", MODE_PRIVATE)
    private var refreshing = false
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = render()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, 0)
            binding.contentScroll.setPadding(0, 0, 0, bars.bottom)
            insets
        }
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.language -> startActivity(Intent(Settings.ACTION_APP_LOCALE_SETTINGS, Uri.parse("package:$packageName")))
                R.id.licenses -> LicensesDialog.show(this)
                R.id.about -> MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.about)
                    .setMessage(getString(R.string.about_body, BuildConfig.VERSION_NAME))
                    .setPositiveButton(R.string.close, null).show()
                else -> return@setOnMenuItemClickListener false
            }
            true
        }
        binding.versionFooter.text = getString(R.string.version_footer, BuildConfig.VERSION_NAME)
        configureDestination(binding.mainDestination, 0, R.string.main_profile, R.drawable.ic_phone, R.string.copy_main_path)
        configureDestination(binding.workDestination, 10, R.string.work_profile, R.drawable.ic_work, R.string.copy_work_path)
        binding.connectionSwitch.setOnCheckedChangeListener { _, enabled ->
            if (!refreshing) {
                if (enabled) confirmConnection() else {
                    startService(Intent(this, BridgeService::class.java).setAction("STOP"))
                }
            }
        }
        binding.readOnlySwitch.setOnCheckedChangeListener { _, enabled ->
            if (!refreshing) {
                settings.edit().putBoolean("readonly", enabled).apply()
                render()
            }
        }
        binding.workProfileSwitch.setOnCheckedChangeListener { _, enabled ->
            if (!refreshing) {
                settings.edit().putBoolean("work_profile", enabled).apply()
                render()
            }
        }
        binding.checkAccess.setOnClickListener { checkAccess() }
    }

    private fun configureDestination(row: ViewDestinationBinding, user: Int, name: Int, icon: Int, description: Int) {
        val path = "/storage/emulated/$user/SecureFolder"
        row.profileName.setText(name)
        row.profileIcon.setImageResource(icon)
        row.folderPath.text = path
        row.copyPath.contentDescription = getString(description)
        row.copyPath.setOnClickListener {
            getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(getString(name), path))
        }
    }

    private fun render() {
        val state = settings.getString("state", "OFF") ?: "OFF"
        val connected = state == "CONNECTED"
        val connecting = state == "CONNECTING"
        val active = connected || connecting
        val readonly = settings.getBoolean("readonly", false)
        val work = settings.getBoolean("work_profile", true)
        val error = state.startsWith("ERROR:")
        refreshing = true
        binding.connectionSwitch.isChecked = active
        binding.connectionSwitch.isEnabled = !connecting
        binding.readOnlySwitch.isChecked = readonly
        binding.workProfileSwitch.isChecked = work
        binding.readOnlySwitch.isEnabled = !active
        binding.workProfileSwitch.isEnabled = !active
        binding.optionsLockedHint.visibility = if (active) View.VISIBLE else View.GONE
        binding.connectingProgress.visibility = if (connecting) View.VISIBLE else View.GONE
        binding.accessMode.setText(if (readonly) R.string.read_only else R.string.read_write)
        binding.accessMode.setChipIconResource(if (readonly) R.drawable.ic_lock else R.drawable.ic_edit)
        binding.connectionStatus.setText(when {
            connected -> R.string.connected
            connecting -> R.string.connecting
            error -> R.string.connection_error
            else -> R.string.disconnected
        })
        binding.connectionSummary.setText(when {
            connected -> R.string.connected_summary
            connecting -> R.string.connecting_summary
            error -> R.string.error_summary
            state == "LOCKED" -> R.string.locked_summary
            else -> R.string.disconnected_summary
        })
        binding.connectionCard.setCardBackgroundColor(MaterialColors.getColor(binding.root,
            if (connected) com.google.android.material.R.attr.colorSecondaryContainer
            else com.google.android.material.R.attr.colorSurfaceContainerHigh))
        binding.errorCard.visibility = if (error) View.VISIBLE else View.GONE
        binding.errorDetails.text = if (error) state.removePrefix("ERROR:").trim() else ""
        binding.mainDestination.profileStatus.setText(when {
            connected -> R.string.connected
            connecting -> R.string.connecting
            else -> R.string.disconnected
        })
        binding.workDestination.profileStatus.setText(when {
            !work -> R.string.disabled
            connecting -> R.string.connecting
            !connected -> R.string.disconnected
            settings.getString("work_state", "") == "WORK_CONNECTED" -> R.string.connected
            else -> R.string.work_paused
        })
        refreshing = false
    }

    private fun confirmConnection() {
        render() // The switch reflects the real state until confirmation starts the service.
        val access = getString(if (binding.workProfileSwitch.isChecked) R.string.confirm_both else R.string.confirm_main)
        val mode = getString(if (binding.readOnlySwitch.isChecked) R.string.confirm_read_only else R.string.confirm_read_write)
        MaterialAlertDialogBuilder(this).setTitle(R.string.confirm_title)
            .setMessage("$access\n\n$mode")
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.connect) { _, _ ->
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 10)
                startForegroundService(Intent(this, BridgeService::class.java))
            }.show()
    }

    private fun checkAccess() {
        binding.checkAccess.isEnabled = false
        binding.checkAccess.setText(R.string.checking)
        worker.execute {
            val result = runCatching { RootShell.check(this) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                binding.checkAccess.isEnabled = true
                binding.checkAccess.setText(R.string.check_access)
                val message = result.fold(
                    { getString(if (it == "AVAILABLE") R.string.access_ready else R.string.access_locked) },
                    { it.message ?: getString(R.string.error_summary) },
                )
                MaterialAlertDialogBuilder(this).setTitle(R.string.access_title)
                    .setMessage(message).setPositiveButton(R.string.close, null).show()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        registerReceiver(receiver, IntentFilter(BridgeService.ACTION_STATE), RECEIVER_NOT_EXPORTED)
        render()
    }

    override fun onStop() {
        unregisterReceiver(receiver)
        super.onStop()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }
}
