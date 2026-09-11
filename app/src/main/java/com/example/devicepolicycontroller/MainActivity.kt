package com.example.devicepolicycontroller

import android.app.Activity
import android.app.AlertDialog
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

class MainActivity : Activity() {
    private lateinit var dpm: DevicePolicyManager
    private lateinit var admin: ComponentName
    private lateinit var controls: LinearLayout
    private lateinit var ownerStatus: TextView
    private lateinit var installerPackage: EditText
    private lateinit var approvedInstallers: TextView
    private lateinit var linkHandlerPackage: EditText
    private lateinit var linkHandlerStatus: TextView
    private val prefs by lazy { getSharedPreferences("controller_security", Context.MODE_PRIVATE) }
    private var unlocked = false

    private data class ManagedRestriction(val title: String, val description: String, val key: String)

    private val restrictions = listOf(
        ManagedRestriction("External APK installs", "Block unapproved sources. Approved apps can use the managed installer below.", "no_install_unknown_sources"),
        ManagedRestriction("Private DNS", "Prevent people from changing Private DNS in Settings.", "no_config_private_dns"),
        ManagedRestriction("Wi-Fi", "Prevent changes to Wi-Fi networks and settings.", "no_config_wifi"),
        ManagedRestriction("Factory reset", "Prevent device users from initiating a factory reset.", "no_factory_reset")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        dpm = getSystemService(DevicePolicyManager::class.java)
        admin = ComponentName(this, PolicyAdminReceiver::class.java)
        controls = findViewById(R.id.controls)
        ownerStatus = findViewById(R.id.ownerStatus)
        findViewById<TextView>(R.id.subtitle).text = "Password-protected controls for organization-owned Android devices."
        findViewById<Button>(R.id.changePassword).setOnClickListener { showPasswordDialog(changing = true) }
        installerPackage = findViewById(R.id.installerPackage)
        approvedInstallers = findViewById(R.id.approvedInstallers)
        findViewById<Button>(R.id.addInstaller).setOnClickListener { addApprovedInstaller() }
        linkHandlerPackage = findViewById(R.id.linkHandlerPackage)
        linkHandlerStatus = findViewById(R.id.linkHandlerStatus)
        findViewById<Button>(R.id.allowLinkHandler).setOnClickListener { allowLinkHandler() }
        findViewById<Button>(R.id.blockExternalLinks).setOnClickListener { blockExternalLinks() }
        refresh()
        if (!hasPassword()) showPasswordDialog(changing = false) else showUnlockDialog()
    }

    private fun refresh() {
        val owner = dpm.isDeviceOwnerApp(packageName)
        ownerStatus.text = if (owner) {
            "✓ This app is the device owner. Changes are applied immediately."
        } else {
            "Device owner setup required. Controls are shown but cannot be applied until this app is provisioned as device owner."
        }
        if (owner && unlocked && prefs.getString("approved_link_handler", null) == null) {
            setExternalLinkHandler(ComponentName(this, BlockedLinkActivity::class.java))
        }
        renderControls(owner && unlocked)
        renderApprovedInstallers(owner && unlocked)
        renderLinkHandler(owner && unlocked)
    }

    private fun renderLinkHandler(enabled: Boolean) {
        linkHandlerPackage.isEnabled = enabled
        findViewById<Button>(R.id.allowLinkHandler).isEnabled = enabled
        findViewById<Button>(R.id.blockExternalLinks).isEnabled = enabled
        val allowed = prefs.getString("approved_link_handler", null)
        linkHandlerStatus.text = allowed?.let { "Allowed external-link handler: $it" }
            ?: "External http and https links are blocked."
    }

    private fun allowLinkHandler() {
        val handlerPackage = linkHandlerPackage.text.toString().trim()
        if (!canManageLinks()) return
        if (!handlerPackage.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+"))) {
            toast("Enter a valid Android package name."); return
        }
        if (packageManager.getLaunchIntentForPackage(handlerPackage) == null) {
            toast("Install the link-handler app before allowing it."); return
        }
        setExternalLinkHandler(ComponentName(handlerPackage, findLinkActivity(handlerPackage) ?: run {
            toast("That app cannot open web links."); return
        }))
        prefs.edit().putString("approved_link_handler", handlerPackage).apply()
        linkHandlerPackage.text.clear()
        refresh()
        toast("External links are now routed to $handlerPackage.")
    }

    private fun blockExternalLinks() {
        if (!canManageLinks()) return
        setExternalLinkHandler(ComponentName(this, BlockedLinkActivity::class.java))
        prefs.edit().remove("approved_link_handler").apply()
        refresh()
        toast("External http and https links are blocked.")
    }

    private fun canManageLinks(): Boolean {
        if (unlocked && dpm.isDeviceOwnerApp(packageName)) return true
        toast("Unlock and provision device owner first.")
        return false
    }

    private fun setExternalLinkHandler(component: ComponentName) {
        val filter = IntentFilter(Intent.ACTION_VIEW).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            addDataScheme("http")
            addDataScheme("https")
        }
        dpm.addPersistentPreferredActivity(admin, filter, component)
    }

    private fun findLinkActivity(handlerPackage: String): String? {
        val query = Intent(Intent.ACTION_VIEW).setData(android.net.Uri.parse("https://example.com"))
        return packageManager.queryIntentActivities(query, 0)
            .firstOrNull { it.activityInfo.packageName == handlerPackage }?.activityInfo?.name
    }

    private fun renderApprovedInstallers(enabled: Boolean) {
        installerPackage.isEnabled = enabled
        findViewById<Button>(R.id.addInstaller).isEnabled = enabled
        val packages = approvedInstallerPackages()
        approvedInstallers.text = if (packages.isEmpty()) "No approved installer apps." else
            "Approved: " + packages.sorted().joinToString(" • ") + "\nTap this list to remove all approved apps."
        approvedInstallers.setOnClickListener {
            if (enabled && packages.isNotEmpty()) {
                prefs.edit().remove("approved_installers").apply()
                refresh()
                toast("Approved installer apps removed.")
            }
        }
    }

    private fun addApprovedInstaller() {
        val packageName = installerPackage.text.toString().trim()
        if (!unlocked || !dpm.isDeviceOwnerApp(this.packageName)) { toast("Unlock and provision device owner first."); return }
        if (!packageName.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+"))) {
            toast("Enter a valid Android package name."); return
        }
        prefs.edit().putStringSet("approved_installers", approvedInstallerPackages() + packageName).apply()
        installerPackage.text.clear()
        refresh()
    }

    private fun approvedInstallerPackages(): Set<String> = prefs.getStringSet("approved_installers", emptySet())?.toSet() ?: emptySet()

    private fun renderControls(enabled: Boolean) {
        controls.removeAllViews()
        restrictions.forEach { restriction ->
            val toggle = Switch(this).apply {
                text = "${restriction.title}\n${restriction.description}"
                textSize = 16f
                setPadding(8, 20, 8, 20)
                isEnabled = enabled
                isChecked = dpm.getUserRestrictions(admin).getBoolean(restriction.key, false)
                setOnCheckedChangeListener { _, checked -> applyRestriction(restriction, checked) }
            }
            controls.addView(toggle, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun applyRestriction(restriction: ManagedRestriction, disabled: Boolean) {
        if (!dpm.isDeviceOwnerApp(packageName) || !unlocked) {
            toast("Unlock the controller and provision it as device owner first.")
            refresh()
            return
        }
        if (disabled) dpm.addUserRestriction(admin, restriction.key) else dpm.clearUserRestriction(admin, restriction.key)
        toast("${restriction.title} changes ${if (disabled) "blocked" else "allowed"}.")
    }

    private fun showUnlockDialog() {
        val password = passwordField("Controller password")
        AlertDialog.Builder(this)
            .setTitle("Unlock device controls")
            .setView(password)
            .setCancelable(false)
            .setPositiveButton("Unlock") { _, _ ->
                if (verify(password.text.toString())) { unlocked = true; refresh() } else { toast("Incorrect password."); showUnlockDialog() }
            }
            .show()
    }

    private fun showPasswordDialog(changing: Boolean) {
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 8, 48, 0) }
        val current = passwordField("Current password")
        val newPassword = passwordField("New password (at least 8 characters)")
        val confirmation = passwordField("Confirm new password")
        if (changing) container.addView(current)
        container.addView(newPassword)
        container.addView(confirmation)
        AlertDialog.Builder(this)
            .setTitle(if (changing) "Change password" else "Create controller password")
            .setMessage("This password protects access to policy controls on this device.")
            .setView(container)
            .setCancelable(!changing && !hasPassword())
            .setPositiveButton("Save") { _, _ ->
                val newValue = newPassword.text.toString()
                when {
                    changing && !verify(current.text.toString()) -> toast("Current password is incorrect.")
                    newValue.length < 8 -> toast("Use at least 8 characters.")
                    newValue != confirmation.text.toString() -> toast("Passwords do not match.")
                    else -> { savePassword(newValue); unlocked = true; refresh(); toast("Controller password saved.") }
                }
            }
            .show()
    }

    private fun passwordField(hint: String) = EditText(this).apply {
        this.hint = hint
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun hasPassword() = prefs.contains("hash") && prefs.contains("salt")

    private fun savePassword(password: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        prefs.edit().putString("salt", Base64.getEncoder().encodeToString(salt))
            .putString("hash", hash(password, salt)).apply()
    }

    private fun verify(password: String): Boolean {
        val salt = prefs.getString("salt", null)?.let { Base64.getDecoder().decode(it) } ?: return false
        val expected = prefs.getString("hash", null) ?: return false
        return MessageDigest.isEqual(Base64.getDecoder().decode(expected), Base64.getDecoder().decode(hash(password, salt)))
    }

    private fun hash(password: String, salt: ByteArray): String = Base64.getEncoder().encodeToString(
        MessageDigest.getInstance("SHA-256").digest(salt + password.toByteArray(Charsets.UTF_8))
    )

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
