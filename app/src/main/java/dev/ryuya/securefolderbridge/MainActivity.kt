package dev.ryuya.securefolderbridge

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.DynamicColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView
    private lateinit var connection: MaterialSwitch
    private lateinit var readonly: MaterialSwitch
    private var refreshing = false
    private val ja get() = resources.configuration.locales[0].language == "ja"
    private fun words(japanese: String, english: String) = if (ja) japanese else english
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private val settings get() = getSharedPreferences("settings", MODE_PRIVATE)
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(32))
        }
        val scroll = ScrollView(this).apply { addView(content); isFillViewport = true }
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(scroll)
        content.addView(text("Secure Folder\nBridge", 32, 20))
        content.addView(text(words("セキュリティフォルダを、いつものファイル管理から。", "Your Secure Folder, in your usual file manager."), 17, 24))
        val card = MaterialCardView(this).apply { radius = dp(28).toFloat() }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(18), dp(22), dp(22))
        }
        card.addView(panel)
        content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(28) })
        panel.addView(text(words("接続先", "Destination"), 14, 8))
        panel.addView(text(words("内部ストレージ / SecureFolder", "Internal storage / SecureFolder"), 20, 12))
        status = text("", 15, 12)
        panel.addView(status)
        connection = MaterialSwitch(this).apply {
            text = words("フォルダを接続", "Connect folder")
            setOnCheckedChangeListener { _, checked ->
                if (!refreshing) {
                    if (checked) confirmConnection() else startService(Intent(this@MainActivity, BridgeService::class.java).setAction("STOP"))
                }
            }
        }
        panel.addView(connection, LinearLayout.LayoutParams(-1, dp(56)))
        readonly = MaterialSwitch(this).apply {
            text = words("読み取り専用", "Read only")
            isChecked = settings.getBoolean("readonly", false)
            setOnCheckedChangeListener { _, checked -> settings.edit().putBoolean("readonly", checked).apply() }
        }
        content.addView(readonly, LinearLayout.LayoutParams(-1, dp(64)))
        content.addView(text(words("OFFにすると、移動・削除なども元のファイルに反映されます。接続中は変更できません。", "When off, moves and deletions affect the original files. Change this before connecting."), 14, 24))
        content.addView(button(words("保存先のパスをコピー", "Copy folder path")) {
            getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Secure Folder", "/storage/emulated/0/SecureFolder"))
        })
        content.addView(button(words("rootとロック状態を確認", "Check root and lock status")) {
            status.text = words("確認しています…", "Checking…")
            worker.execute {
                val result = runCatching { RootShell.check(this) }
                runOnUiThread {
                    status.text = result.fold(
                        { if (it == "AVAILABLE") words("接続できます", "Ready to connect") else words("本体とセキュリティフォルダを解除してください", "Unlock the phone and Secure Folder") },
                        { it.message ?: "Root unavailable" },
                    )
                }
            }
        })
        content.addView(text(words("接続中だけrootの監視処理が動作し、本体またはセキュリティフォルダのロック時に接続を解除します。画面消灯でも解除します。再起動後に自動接続はしません。", "A root monitor runs only while connected. The folder disconnects when the phone or Secure Folder locks, or the screen turns off. It does not reconnect after reboot."), 14, 18))
        content.addView(text(words("解除しても、他アプリが既に開いたファイルや保存したサムネイルは消去しません。対象は共有ストレージで、アプリ内部データは含みません。", "Disconnecting does not erase files already opened or thumbnails saved by another app. Only shared storage is connected; private app data is excluded."), 14, 24))
        content.addView(button(words("ライセンス", "Licenses")) { licenses() })
        content.addView(text("0.1.0 · ryuya0124", 12, 0))
    }

    private fun confirmConnection() {
        refreshing = true
        connection.isChecked = false
        refreshing = false
        MaterialAlertDialogBuilder(this)
            .setTitle(words("Secure Folderを接続しますか？", "Connect Secure Folder?"))
            .setMessage(words("接続中は、通常側でファイルへアクセスできるアプリから内容が見えるようになります。" + if (readonly.isChecked) "読み取り専用で接続します。" else "変更や削除は元のデータにも反映されます。", "While connected, apps with access to shared storage on the main profile can see its contents. " + if (readonly.isChecked) "Files will be read-only." else "Changes and deletions affect the original data."))
            .setNegativeButton(words("キャンセル", "Cancel"), null)
            .setPositiveButton(words("接続", "Connect")) { _, _ ->
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 10)
                startForegroundService(Intent(this, BridgeService::class.java))
            }.show()
    }

    private fun refresh() {
        val state = settings.getString("state", "OFF") ?: "OFF"
        refreshing = true
        connection.isChecked = state == "CONNECTED" || state == "CONNECTING"
        connection.isEnabled = state != "CONNECTING"
        readonly.isEnabled = !connection.isChecked
        status.text = when (state) {
            "CONNECTED" -> words("接続中", "Connected")
            "CONNECTING" -> words("接続しています…", "Connecting…")
            "LOCKED" -> words("ロックされたため接続を解除しました", "Disconnected because the profile locked")
            "OFF" -> words("未接続", "Disconnected")
            else -> state
        }
        refreshing = false
    }

    override fun onStart() {
        super.onStart()
        registerReceiver(receiver, IntentFilter(BridgeService.ACTION_STATE), RECEIVER_NOT_EXPORTED)
        refresh()
    }

    override fun onStop() {
        unregisterReceiver(receiver)
        super.onStop()
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    private fun text(value: String, size: Int, bottom: Int) = TextView(this).apply {
        text = value
        textSize = size.toFloat()
        setPadding(0, 0, 0, dp(bottom))
    }

    private fun button(label: String, action: () -> Unit) = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
        text = label
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) }
    }

    private fun licenses() {
        val names = arrayOf("Secure Folder Bridge · MIT", "Material Components · Apache 2.0", "AndroidX · Apache 2.0", "Kotlin · Apache 2.0")
        val urls = arrayOf("https://opensource.org/license/mit", "https://github.com/material-components/material-components-android/blob/master/LICENSE", "https://android.googlesource.com/platform/frameworks/support/+/androidx-main/LICENSE.txt", "https://github.com/JetBrains/kotlin/blob/master/license/LICENSE.txt")
        MaterialAlertDialogBuilder(this).setTitle(words("オープンソースライセンス", "Open-source licenses"))
            .setItems(names) { _, index ->
                val body = text(assets.open(if (index == 0) "MIT.txt" else "APACHE-2.0.txt").bufferedReader().use { it.readText() }, 14, 0)
                body.setPadding(dp(24), dp(12), dp(24), dp(12))
                body.setTextIsSelectable(true)
                val scroll = ScrollView(this).apply { addView(body) }
                MaterialAlertDialogBuilder(this).setTitle(names[index]).setView(scroll)
                    .setNeutralButton(words("プロジェクト", "Project")) { _, _ -> startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(urls[index]))) }
                    .setPositiveButton(words("閉じる", "Close"), null).show()
            }
            .setPositiveButton(words("閉じる", "Close"), null).show()
    }
}
