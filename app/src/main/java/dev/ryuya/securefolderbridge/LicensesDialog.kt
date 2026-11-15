package dev.ryuya.securefolderbridge

import android.content.Intent
import android.net.Uri
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textview.MaterialTextView

/** Offline license contents, grouped by component instead of one long combined page. */
object LicensesDialog {
    private data class Component(val name: String, val file: String, val url: String)
    private val components = listOf(
        Component("Secure Folder Bridge · MIT", "MIT.txt", "https://github.com/OneUI-Mods/secure-folder-bridge"),
        Component("Material Components · Apache 2.0", "APACHE-2.0.txt", "https://github.com/material-components/material-components-android"),
        Component("AndroidX · Apache 2.0", "APACHE-2.0.txt", "https://android.googlesource.com/platform/frameworks/support/"),
        Component("Kotlin · Apache 2.0", "APACHE-2.0.txt", "https://github.com/JetBrains/kotlin"),
    )

    fun show(activity: AppCompatActivity) {
        MaterialAlertDialogBuilder(activity).setTitle(R.string.licenses)
            .setItems(components.map { it.name }.toTypedArray()) { _, index ->
                val component = components[index]
                val padding = (24 * activity.resources.displayMetrics.density).toInt()
                val body = MaterialTextView(activity).apply {
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
                    text = activity.assets.open(component.file).bufferedReader().use { it.readText() }
                    setPadding(padding, padding / 2, padding, padding / 2)
                    setTextIsSelectable(true)
                }
                val scroll = ScrollView(activity).apply { addView(body) }
                MaterialAlertDialogBuilder(activity).setTitle(component.name).setView(scroll)
                    .setNeutralButton(R.string.project) { _, _ ->
                        activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(component.url)))
                    }
                    .setPositiveButton(R.string.close, null).show()
            }
            .setPositiveButton(R.string.close, null).show()
    }
}
