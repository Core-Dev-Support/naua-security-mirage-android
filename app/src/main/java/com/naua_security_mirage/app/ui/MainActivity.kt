package com.naua_security_mirage.app.ui

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import androidx.core.content.FileProvider
import com.naua_security_mirage.app.BuildConfig
import com.naua_security_mirage.app.util.AppLogger
import com.naua_security_mirage.app.vpn.PrivateDnsInspector
import com.naua_security_mirage.app.util.AppShield
import com.naua_security_mirage.app.util.AnimationHelper
import com.naua_security_mirage.app.util.AppUpdateManager
import com.naua_security_mirage.app.data.model.DnsServer
import com.naua_security_mirage.app.util.DohTester
import com.naua_security_mirage.app.vpn.XrayVpnController
import android.widget.RadioButton
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.content.res.Configuration
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.text.HtmlCompat
import androidx.lifecycle.lifecycleScope
import com.naua_security_mirage.app.R
import com.naua_security_mirage.app.data.model.VpnState
import com.naua_security_mirage.app.data.repository.DeviceIdRepository
import com.naua_security_mirage.app.data.repository.GeoRoutingRepository
import com.naua_security_mirage.app.data.repository.PingRepository
import com.naua_security_mirage.app.data.repository.SettingsRepository
import com.naua_security_mirage.app.data.repository.VlessKeyRepository
import com.naua_security_mirage.app.databinding.ActivityMainBinding
import com.naua_security_mirage.app.vpn.MirageVpnService
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.graphics.ColorUtils
import android.app.Dialog
import android.graphics.drawable.ColorDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.constraintlayout.widget.ConstraintLayout
import com.naua_security_mirage.app.ui.view.MirageSwitch
import com.naua_security_mirage.app.vpn.MirageNotifyTileService
import com.naua_security_mirage.app.vpn.MirageTileService
import android.content.pm.ApplicationInfo
import androidx.recyclerview.widget.LinearLayoutManager
import com.naua_security_mirage.app.data.model.AppInfo
import com.naua_security_mirage.app.data.model.CustomWebsite
import com.naua_security_mirage.app.ui.adapter.AppProxyAdapter
import com.naua_security_mirage.app.ui.adapter.CustomWebsiteAdapter
import java.util.Locale
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var pingRepository: PingRepository
    private lateinit var vlessKeyRepository: VlessKeyRepository
    private lateinit var geoRoutingRepository: GeoRoutingRepository
    private var isRefreshing = false
    private var refreshAnimationJob: Job? = null
    private var lastMeasuredServerInfo: String? = null
    private var connectBreathingHandle: AnimationHelper.AnimationHandle? = null
    private var connectGlowHandle: AnimationHelper.AnimationHandle? = null
    private var currentVpnState: VpnState = VpnState.DISCONNECTED

    private var allInstalledApps: List<AppInfo> = emptyList()
    private var appProxyAdapter: AppProxyAdapter? = null
    private var perAppFilterMode = FILTER_ALL
    private var perAppSearchQuery = ""
    private var isAppsLoading = false

    private var allCustomWebsites: List<CustomWebsite> = emptyList()
    private var customWebsiteAdapter: CustomWebsiteAdapter? = null
    private var customWebsiteFilterMode = FILTER_ALL
    private var customWebsiteSearchQuery = ""

    private var isAutoPingExpanded = false
    private var isSpeedExpanded = false

    private val vpnConsentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            MirageVpnService.start(this)
        } else {
            Toast.makeText(this, R.string.toast_vpn_denied, Toast.LENGTH_SHORT).show()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->

    }

    private val pickBackgroundLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            saveAndApplyCustomBackground(uri)
        }
    }

    private val importSettingsLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            importSettings(uri)
        }
    }

    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            performDownloadLogs()
        } else {
            Toast.makeText(this, R.string.toast_storage_denied, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppShield.checkIntegrity(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

        settingsRepository = SettingsRepository(this)
        AnimationHelper.animationsEnabled = settingsRepository.areAnimationsEnabled
        AnimationHelper.excludeFromPress(binding.btnConnect, binding.connectGlowRing)
        pingRepository = PingRepository()
        vlessKeyRepository = VlessKeyRepository(this, DeviceIdRepository(this))
        geoRoutingRepository = GeoRoutingRepository(this)

        setupEdgeToEdge()
        setupUI()
        setupSettings()
        setupAppearanceUI()
        setupLogsUI()
        setupPerAppProxyUI()
        setupCustomWebsitesUI()
        applyCurrentAppearance()
        observeVpnState()
        checkNotificationPermission()

        updateUpdateSourceUI()
        binding.rootContainer.post {
            applyAdaptiveSizes()
            AnimationHelper.attachPressAnimations(binding.root)
        }
        AppUpdateManager.addUpdateListener { hasUpdate, info ->
            runOnUiThread {
                binding.containerUpdateBadge.visibility = if (hasUpdate) View.VISIBLE else View.GONE
            }
            if (hasUpdate && info != null) {
                com.naua_security_mirage.app.work.UpdateNotifier.notifyIfNewer(this, info.tagName)
            }
        }
        AppUpdateManager.checkForUpdates(this, settingsRepository, isManual = false)
        AppUpdateManager.showWhatsNewDialog(this, settingsRepository)
        maybeShowPrivateDnsDialog()

        lifecycleScope.launch(Dispatchers.IO) {
            delay(2500)
            com.naua_security_mirage.app.work.SubscriptionExpiryNotifier.check(this@MainActivity)
        }

        if (intent?.action == ACTION_QUICK_CONNECT) {
            handleConnectButtonClick()
        }
        handleAuthDeepLink(intent)
        handleUpdateIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent?.action == ACTION_QUICK_CONNECT) {
            handleConnectButtonClick()
        }
        handleAuthDeepLink(intent)
        handleUpdateIntent(intent)
    }

    private fun handleUpdateIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.getBooleanExtra(EXTRA_SHOW_SUBSCRIPTION, false)) {
            intent.removeExtra(EXTRA_SHOW_SUBSCRIPTION)
            showSubscriptionDialog()
            return
        }
        if (intent.getBooleanExtra(EXTRA_SHOW_SETTINGS, false)) {
            intent.removeExtra(EXTRA_SHOW_SETTINGS)
            openSettings()
            return
        }
        val apkPath = intent.getStringExtra(EXTRA_INSTALL_APK_PATH)
        if (!apkPath.isNullOrEmpty()) {
            try {
                val updatesDir = java.io.File(cacheDir, "updates").canonicalPath
                val file = java.io.File(apkPath)
                if (file.exists() && file.canonicalPath.startsWith(updatesDir + java.io.File.separator)) {
                    AppUpdateManager.installApk(this, file)
                    return
                } else {
                    AppLogger.w("DeepLink", "APK вне папки обновлений отклонён")
                }
            } catch (t: Throwable) {
                AppLogger.w("DeepLink", "Некорректный путь APK: ${t.message}")
            }
        }
        if (intent.getBooleanExtra(EXTRA_SHOW_UPDATE, false)) {
            AppUpdateManager.latestUpdate?.let { info ->
                AppUpdateManager.showUpdateAvailableDialog(this, info, settingsRepository)
            } ?: run {
                AppUpdateManager.checkForUpdates(this, settingsRepository, isManual = true)
            }
        }
    }

    private fun handleAuthDeepLink(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == "mirage" && data.host == "auth" && data.path == "/callback") {
            lifecycleScope.launch {
                val res = com.naua_security_mirage.app.data.supabase.SupabaseManager.instance.handleOAuthCallback(data)
                if (res.isSuccess) {
                    val user = res.getOrNull()
                    Toast.makeText(
                this@MainActivity,
                getString(
                    R.string.toast_google_signed_in_fmt,
                    if (settingsRepository.isEmailMasked) maskEmail(user?.email.orEmpty()) else user?.email
                ),
                Toast.LENGTH_SHORT
            ).show()
                    updateAccountCardUI()
                    pendingAuthSuccessCallback?.invoke()
                    pendingAuthSuccessCallback = null
                } else {
                    val err = res.exceptionOrNull()?.message ?: getString(R.string.auth_error_default)
                    Toast.makeText(this@MainActivity, err, Toast.LENGTH_LONG).show()
                }
            }
        } else if (data.scheme == "mirage" && data.host == "payment" && data.path == "/success") {
            handlePaymentReturn()
        }
    }

    private var pendingAuthSuccessCallback: (() -> Unit)? = null
    private var isWaitingForPayment = false
    private var paymentPollingJob: Job? = null

    private var paymentBaselineUntil: String? = null

    private suspend fun awaitSubscriptionExtension(): String? {
        val baseline = paymentBaselineUntil
        repeat(5) { attempt ->
            com.naua_security_mirage.app.data.supabase.SupabaseManager.instance.refreshSubscription()
            val current = com.naua_security_mirage.app.data.supabase.SupabaseManager.instance
                .subscription.value?.paidUntil?.take(10)
            if (current != null && current.isNotBlank()) {
                if (baseline == null || baseline.isBlank() || current > baseline) {
                    return current
                }
            }
            if (attempt < 4) delay(3000)
        }
        return null
    }

    private fun handlePaymentReturn() {
        val user = com.naua_security_mirage.app.data.supabase.SupabaseManager.instance.currentUser.value
        if (user == null) {
            return
        }
        if (paymentPollingJob?.isActive == true) return
        paymentPollingJob = lifecycleScope.launch {
            try {
                val renewedUntil = awaitSubscriptionExtension()
                isWaitingForPayment = false
                if (renewedUntil != null) {
                    settingsRepository.selectedServerPlan = SettingsRepository.PLAN_PREMIUM_FRANCE
                    updateServerPlanSelectorUI()
                    updateAccountCardUI()
                    paymentBaselineUntil = null
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.sub_renewed_toast, formatExpiry(renewedUntil)),
                        Toast.LENGTH_LONG,
                    ).show()
                } else {
                    Toast.makeText(this@MainActivity, R.string.sub_payment_pending, Toast.LENGTH_LONG).show()
                }
            } finally {
                paymentPollingJob = null
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        binding.rootContainer.post { applyAdaptiveSizes() }
    }

    private fun applyAdaptiveSizes() {
        val container = binding.rootContainer
        val available = container.width
        if (available <= 0) return

        val base = (CONNECT_BUTTON_DP * resources.displayMetrics.density).toInt()
        val edgeMargin = (available * 0.05f).toInt()
        val maxGlow = available - edgeMargin * 2

        var button = base
        if ((button * GLOW_RATIO).toInt() > maxGlow) {
            button = (maxGlow / GLOW_RATIO).toInt()
        }

        binding.btnConnect.updateSize(button)
        binding.connectGlowRing.updateSize((button * GLOW_RATIO).toInt())
    }

    private fun View.updateSize(size: Int) {
        val params = layoutParams
        if (params.width == size && params.height == size) return
        params.width = size
        params.height = size
        layoutParams = params
    }

    override fun onResume() {
        super.onResume()
        AppShield.checkIntegrity(this)
        AppUpdateManager.checkPendingInstall(this)
        if (::geoRoutingRepository.isInitialized) {
            updateGeoStatusText()
        }
        if (isWaitingForPayment) {
            handlePaymentReturn()
        }
        updateAccountCardUI()
        refreshPrivateDnsRow()
        refreshNodeStatus()
        refreshKillSwitchRow()
        ensureCardRows()
        maybeShowPrivateDnsDialog()
    }

    @Suppress("DEPRECATION")
    private fun setupEdgeToEdge() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        ViewCompat.setOnApplyWindowInsetsListener(binding.rootContainer) { _, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val density = resources.displayMetrics.density
            val padTop = insets.top + (16 * density).toInt()
            val padBottom = insets.bottom + (24 * density).toInt()

            binding.viewMain.setPadding(0, padTop, 0, padBottom)
            binding.viewSettings.setPadding(0, padTop, 0, padBottom)
            binding.viewAppearance.setPadding(0, insets.top + (16 * density).toInt(), 0, insets.bottom + (32 * density).toInt())
            binding.viewLogs.setPadding(0, padTop, 0, padBottom)
            binding.viewPerAppProxy.setPadding(0, padTop, 0, padBottom)
            binding.viewCustomWebsites.setPadding(0, padTop, 0, padBottom)

            windowInsets
        }
    }

    private fun withAccentFirstLine(source: CharSequence): CharSequence {
        val plain = source.toString()
        val breakAt = plain.indexOf("\n\n")
        if (breakAt <= 0) return source

        val accented = android.text.SpannableString(plain)
        accented.setSpan(
            android.text.style.ForegroundColorSpan(ContextCompat.getColor(this, R.color.accent)),
            0,
            breakAt,
            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        return accented
    }

    private fun setupUI() {

        binding.tvHint.text = androidx.core.text.HtmlCompat.fromHtml(
            getString(R.string.hint_text),
            androidx.core.text.HtmlCompat.FROM_HTML_MODE_LEGACY
        ).let { withAccentFirstLine(it) }

        binding.btnHeaderUpdate.setOnClickListener {
            AnimationHelper.bounceClick(binding.btnHeaderUpdate, minScale = 0.88f, durationMs = 180) {
                if (AppUpdateManager.hasUpdate()) {
                    AppUpdateManager.latestUpdate?.let { info ->
                        AppUpdateManager.showUpdateAvailableDialog(this, info, settingsRepository)
                    } ?: showUpdateSourceDialog()
                } else {
                    showUpdateSourceDialog()
                }
            }
        }

        binding.btnRefresh.setOnClickListener {
            AnimationHelper.bounceClick(binding.btnRefresh, minScale = 0.88f, durationMs = 180)
            performRefresh()
        }

        binding.btnConnect.setOnClickListener {
            AnimationHelper.bounceClick(binding.btnConnect, minScale = 0.92f, durationMs = 180)
            handleConnectButtonClick()
        }
        binding.btnConnect.setOnLongClickListener {
            performDownloadLogs()
            true
        }
        binding.view3DButton.setOnClickListener {
            binding.btnConnect.performClick()
        }

        binding.btnSupport.setOnClickListener {
            AnimationHelper.bounceClick(binding.btnSupport, minScale = 0.93f, durationMs = 180)
            showSupportWaysDialog()
        }

        binding.btnTelegram.setOnClickListener {
            AnimationHelper.bounceClick(binding.btnTelegram, minScale = 0.93f, durationMs = 180)
            openExternalLink(getString(R.string.telegram_channel_url))
        }

        binding.btnSupportChat.setOnClickListener {
            AnimationHelper.bounceClick(binding.btnSupportChat, minScale = 0.93f, durationMs = 180)
            openExternalLink(getString(R.string.support_chat_url))
        }

        setupAiModePill()
        setupInjectedRows()
        applyScreenshotBlocking()

        binding.btnSettings.setOnClickListener {
            AnimationHelper.bounceClick(binding.btnSettings, minScale = 0.88f, durationMs = 180)
            openSettings()
        }

        binding.btnLanguage.setOnClickListener {
            AnimationHelper.bounceClick(binding.btnLanguage, minScale = 0.88f, durationMs = 180)
            val next = if (settingsRepository.appLanguage == SettingsRepository.LANG_RU) {
                SettingsRepository.LANG_EN
            } else {
                SettingsRepository.LANG_RU
            }
            setAppLanguage(next)
        }

        binding.btnBackFromSettings.setOnClickListener {
            AnimationHelper.bounceClick(binding.btnBackFromSettings, minScale = 0.88f, durationMs = 180)
            closeSettings()
        }

        setupServerPlanSelector()
    }

    private fun setupSettings() {
        setupAccountCard()
        installPrivateDnsCard()

        binding.switchStatusNotification.isChecked = settingsRepository.isStatusNotificationEnabled
        binding.switchStatusNotification.setOnCheckedChangeListener { _, isChecked ->
            settingsRepository.isStatusNotificationEnabled = isChecked
        }
        binding.rowStatusNotification.setOnClickListener {
            binding.switchStatusNotification.toggle()
        }

        binding.switchQuickSettingsTile.isChecked = settingsRepository.isQuickSettingsTileEnabled
        com.naua_security_mirage.app.vpn.MirageTileService.setTileEnabled(this, settingsRepository.isQuickSettingsTileEnabled)
        binding.switchQuickSettingsTile.setOnCheckedChangeListener { _, isChecked ->
            settingsRepository.isQuickSettingsTileEnabled = isChecked
            com.naua_security_mirage.app.vpn.MirageTileService.setTileEnabled(this, isChecked)
        }
        binding.rowQuickSettingsTile.setOnClickListener {
            binding.switchQuickSettingsTile.toggle()
        }

        binding.switchKillSwitch.isChecked = settingsRepository.isKillSwitchEnabled
        binding.switchKillSwitch.setOnCheckedChangeListener { _, isChecked ->
            settingsRepository.isKillSwitchEnabled = isChecked
            refreshKillSwitchRow()
            showVpnActiveReconnectionNoticeIfNeeded()
        }
        binding.rowKillSwitch.setOnClickListener {
            AnimationHelper.bounceClick(binding.rowKillSwitch, minScale = 0.95f, durationMs = 160)
            openSystemVpnSettings()
            Toast.makeText(this, R.string.kill_switch_system_hint, Toast.LENGTH_LONG).show()
        }
        refreshKillSwitchRow()

        binding.switchAutoStart.isChecked = settingsRepository.isAutoStartOnBootEnabled
        binding.switchAutoStart.setOnCheckedChangeListener { _, isChecked ->
            settingsRepository.isAutoStartOnBootEnabled = isChecked
        }
        binding.rowAutoStart.setOnClickListener {
            binding.switchAutoStart.toggle()
        }

        binding.switchDirectRu.isChecked = settingsRepository.isDirectRuEnabled
        binding.switchDirectRu.setOnCheckedChangeListener { _, isChecked ->
            settingsRepository.isDirectRuEnabled = isChecked
            showVpnActiveReconnectionNoticeIfNeeded()
        }
        binding.rowDirectRu.setOnClickListener {
            binding.switchDirectRu.toggle()
        }

        binding.rowSystemVpn.setOnClickListener {
            AnimationHelper.bounceClick(binding.rowSystemVpn, minScale = 0.95f, durationMs = 160)
            openSystemVpnSettings()
        }

        binding.switchTelemetry.isChecked = settingsRepository.isAnonymousTelemetryEnabled
        binding.switchTelemetry.setOnCheckedChangeListener { _, isChecked ->
            settingsRepository.isAnonymousTelemetryEnabled = isChecked
            com.naua_security_mirage.app.MirageApp.updateTelemetryState(this, isChecked)
        }
        binding.rowTelemetry.setOnClickListener {
            binding.switchTelemetry.toggle()
        }

        binding.rowAppearance.setOnClickListener {
            AnimationHelper.bounceClick(binding.rowAppearance, minScale = 0.97f, durationMs = 180)
            openAppearance()
        }

        binding.cardPerAppProxy.setOnClickListener {
            AnimationHelper.bounceClick(binding.cardPerAppProxy, minScale = 0.97f, durationMs = 180)
            openPerAppProxy()
        }
        updatePerAppSettingDescription()

        binding.cardCustomWebsites.setOnClickListener {
            AnimationHelper.bounceClick(binding.cardCustomWebsites, minScale = 0.97f, durationMs = 180)
            openCustomWebsites()
        }
        updateCustomWebsitesSettingDescription()

        binding.cardLogs.setOnClickListener {
            AnimationHelper.bounceClick(binding.cardLogs, minScale = 0.97f, durationMs = 180)
            openLogs()
        }

        val levelFormatted = settingsRepository.logLevel.replaceFirstChar { it.uppercase() }
        binding.tvLogsSettingDesc.text = getString(R.string.setting_logs_desc, levelFormatted)

        setupAutoPingUI()
        setupSpeedUI()
        setupGeoRoutingUI()

        binding.btnTermsOfService.setOnClickListener {
            AnimationHelper.bounceClick(binding.btnTermsOfService, minScale = 0.94f, durationMs = 140) {
                openExternalLink(TERMS_URL)
            }
        }
        binding.btnPrivacyPolicy.setOnClickListener {
            AnimationHelper.bounceClick(binding.btnPrivacyPolicy, minScale = 0.94f, durationMs = 140) {
                openExternalLink(PRIVACY_URL)
            }
        }

        binding.cardCheckUpdates.setOnClickListener {
            AnimationHelper.bounceClick(binding.cardCheckUpdates, minScale = 0.97f, durationMs = 180) {
                showUpdateSourceDialog()
            }
        }

        binding.btnCheckUpdatesNow.setOnClickListener {
            AnimationHelper.bounceClick(binding.btnCheckUpdatesNow, minScale = 0.97f, durationMs = 180)
            AnimationHelper.spin(binding.ivCheckUpdatesSpinner, durationMs = 850, rotations = 1f)
            AppUpdateManager.checkForUpdates(this, settingsRepository, isManual = true)
        }

        binding.tvAppVersion.text = getString(R.string.app_version, "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")

    }

    private fun setupServerPlanSelector() {
        updateServerPlanSelectorUI()

        binding.containerServerPlan.btnPlanFree.setOnClickListener {
            if (settingsRepository.selectedServerPlan != SettingsRepository.PLAN_FREE) {
                settingsRepository.selectedServerPlan = SettingsRepository.PLAN_FREE
                animateServerPlanSwitch(isFrance = false)
                handleServerPlanChanged()
            }
        }

        binding.containerServerPlan.btnPlanFrance.setOnClickListener {
            val user = com.naua_security_mirage.app.data.supabase.SupabaseManager.instance.currentUser.value
            if (user == null) {
                showAuthDialog {
                    if (com.naua_security_mirage.app.data.supabase.SupabaseManager.instance.hasActiveSubscription()) {
                        if (settingsRepository.selectedServerPlan != SettingsRepository.PLAN_PREMIUM_FRANCE) {
                            settingsRepository.selectedServerPlan = SettingsRepository.PLAN_PREMIUM_FRANCE
                            animateServerPlanSwitch(isFrance = true)
                            handleServerPlanChanged()
                        }
                    } else {
                        showSubscriptionDialog()
                    }
                }
                return@setOnClickListener
            }

            if (!com.naua_security_mirage.app.data.supabase.SupabaseManager.instance.hasActiveSubscription()) {
                showSubscriptionDialog()
                return@setOnClickListener
            }

            if (settingsRepository.selectedServerPlan != SettingsRepository.PLAN_PREMIUM_FRANCE) {
                settingsRepository.selectedServerPlan = SettingsRepository.PLAN_PREMIUM_FRANCE
                animateServerPlanSwitch(isFrance = true)
                handleServerPlanChanged()
            }
        }
    }

    private fun animateServerPlanSwitch(isFrance: Boolean) {
        val activeView = if (isFrance) binding.containerServerPlan.btnPlanFrance else binding.containerServerPlan.btnPlanFree
        val inactiveView = if (isFrance) binding.containerServerPlan.btnPlanFree else binding.containerServerPlan.btnPlanFrance

        activeView.background = ContextCompat.getDrawable(this, R.drawable.bg_pill_active)
        activeView.setTextColor(Color.WHITE)
        inactiveView.background = ColorDrawable(Color.TRANSPARENT)
        inactiveView.setTextColor(ContextCompat.getColor(this, R.color.ink_soft))

        AnimationHelper.animateDirect(
            durationMs = 280L,
            interpolator = AnimationHelper.SpringOvershoot(1.6f),
            onUpdate = { fraction ->
                val scale = 0.88f + (1f - 0.88f) * fraction
                activeView.scaleX = scale
                activeView.scaleY = scale
                activeView.alpha = 0.6f + 0.4f * fraction.coerceIn(0f, 1f)
            },
            onEnd = {
                activeView.scaleX = 1f
                activeView.scaleY = 1f
                activeView.alpha = 1f
            }
        )

        AnimationHelper.animateDirect(
            durationMs = 200L,
            interpolator = AnimationHelper.EaseOutCubic,
            onUpdate = { fraction ->
                val scale = 1.04f - 0.04f * fraction
                inactiveView.scaleX = scale
                inactiveView.scaleY = scale
            },
            onEnd = {
                inactiveView.scaleX = 1f
                inactiveView.scaleY = 1f
            }
        )

        val container = binding.containerServerPlan.root
        AnimationHelper.animateDirect(
            durationMs = 220L,
            interpolator = AnimationHelper.SpringOvershoot(1.2f),
            onUpdate = { fraction ->
                val dx = if (isFrance) 3.5f * (1f - fraction) else -3.5f * (1f - fraction)
                container.translationX = dx
            },
            onEnd = {
                container.translationX = 0f
            }
        )
    }

    private fun handleServerPlanChanged() {
        val isVpnConnected = MirageVpnService.vpnState.value == VpnState.CONNECTED || MirageVpnService.vpnState.value == VpnState.CONNECTING
        if (isVpnConnected) {
            MirageVpnService.reconnect(this)
        }
    }

    private fun updateServerPlanSelectorUI() {
        val isFrance = settingsRepository.selectedServerPlan == SettingsRepository.PLAN_PREMIUM_FRANCE
        val customText = settingsRepository.customTextColor
        val hasWallpaper = !settingsRepository.customBgImagePath.isNullOrEmpty() &&
            java.io.File(settingsRepository.customBgImagePath ?: "").exists()
        val isLightNow = !hasWallpaper && (settingsRepository.themePreset == SettingsRepository.THEME_LIGHT ||
            (settingsRepository.customBgColor != 0 &&
                ColorUtils.calculateLuminance(settingsRepository.customBgColor) > 0.5))
        val inactiveText = if (customText != 0) {
            customText
        } else if (isLightNow) {
            Color.parseColor("#0F172A")
        } else {
            ContextCompat.getColor(this, R.color.ink_soft)
        }
        binding.containerServerPlan.root.background = getCardDrawable(isLightNow, 30f)
        if (isFrance) {
            binding.containerServerPlan.btnPlanFrance.background = ContextCompat.getDrawable(this, R.drawable.bg_pill_active)
            binding.containerServerPlan.btnPlanFrance.setTextColor(Color.WHITE)
            binding.containerServerPlan.btnPlanFree.background = ColorDrawable(Color.TRANSPARENT)
            binding.containerServerPlan.btnPlanFree.setTextColor(inactiveText)
        } else {
            binding.containerServerPlan.btnPlanFree.background = ContextCompat.getDrawable(this, R.drawable.bg_pill_active)
            binding.containerServerPlan.btnPlanFree.setTextColor(Color.WHITE)
            binding.containerServerPlan.btnPlanFrance.background = ColorDrawable(Color.TRANSPARENT)
            binding.containerServerPlan.btnPlanFrance.setTextColor(inactiveText)
        }
    }

    private fun setupAccountCard() {
        updateAccountCardUI()

        binding.cardAccount.btnAccountLogin.setOnClickListener {
            showAuthDialog()
        }
        binding.cardAccount.layoutAccountLoggedOut.setOnClickListener {
            showAuthDialog()
        }

        binding.cardAccount.btnAccountEye.setOnClickListener {
            AnimationHelper.bounceClick(binding.cardAccount.btnAccountEye, minScale = 0.88f, durationMs = 160)
            settingsRepository.isEmailMasked = !settingsRepository.isEmailMasked
            updateAccountCardUI()
        }

        binding.cardAccount.btnAccountLogout.setOnClickListener {
            com.naua_security_mirage.app.data.supabase.SupabaseManager.instance.signOut()
            updateAccountCardUI()
            if (settingsRepository.selectedServerPlan == SettingsRepository.PLAN_PREMIUM_FRANCE) {
                settingsRepository.selectedServerPlan = SettingsRepository.PLAN_FREE
                updateServerPlanSelectorUI()
                if (MirageVpnService.vpnState.value == VpnState.CONNECTED) {
                    handleServerPlanChanged()
                }
            }
        }

        binding.cardAccount.btnAccountSubscribe.setOnClickListener {
            showSubscriptionDialog()
        }

        lifecycleScope.launch {
            com.naua_security_mirage.app.data.supabase.SupabaseManager.instance.currentUser.collect { user ->
                updateAccountCardUI()
                if (user == null && settingsRepository.selectedServerPlan == SettingsRepository.PLAN_PREMIUM_FRANCE) {
                    settingsRepository.selectedServerPlan = SettingsRepository.PLAN_FREE
                    updateServerPlanSelectorUI()
                    if (MirageVpnService.vpnState.value == VpnState.CONNECTED) {
                        handleServerPlanChanged()
                    }
                }
            }
        }
        lifecycleScope.launch {
            com.naua_security_mirage.app.data.supabase.SupabaseManager.instance.subscription.collect { sub ->
                updateAccountCardUI()
                if (sub != null &&
                    !sub.isActive &&
                    settingsRepository.selectedServerPlan == SettingsRepository.PLAN_PREMIUM_FRANCE
                ) {
                    settingsRepository.selectedServerPlan = SettingsRepository.PLAN_FREE
                    updateServerPlanSelectorUI()
                    if (MirageVpnService.vpnState.value == VpnState.CONNECTED) {
                        handleServerPlanChanged()
                    }
                }
            }
        }
    }

    private fun maskEmail(email: String): String {
        val at = email.indexOf('@')
        if (at <= 1) return "***"
        return "${email[0]}***${email.substring(at)}"
    }

    private fun updateAccountCardUI() {
        val user = com.naua_security_mirage.app.data.supabase.SupabaseManager.instance.currentUser.value
        val sub = com.naua_security_mirage.app.data.supabase.SupabaseManager.instance.subscription.value
        val isActive = com.naua_security_mirage.app.data.supabase.SupabaseManager.instance.hasActiveSubscription()

        if (user == null) {
            binding.cardAccount.layoutAccountLoggedOut.visibility = View.VISIBLE
            binding.cardAccount.layoutAccountLoggedIn.visibility = View.GONE
        } else {
            binding.cardAccount.layoutAccountLoggedOut.visibility = View.GONE
            binding.cardAccount.layoutAccountLoggedIn.visibility = View.VISIBLE
            binding.cardAccount.tvAccountEmail.text =
                if (settingsRepository.isEmailMasked) maskEmail(user.email) else user.email
            binding.cardAccount.ivAccountEye.setImageResource(
                if (settingsRepository.isEmailMasked) R.drawable.ic_eye_off else R.drawable.ic_eye
            )

            if (isActive) {
                val untilStr = sub?.paidUntil?.take(10).orEmpty()
                val text = if (untilStr.isNotEmpty()) {
                    getString(R.string.sub_active_status, formatExpiry(untilStr))
                } else {
                    getString(R.string.sub_active_no_date)
                }
                binding.cardAccount.tvAccountSubscriptionStatus.text = text
                binding.cardAccount.tvAccountSubscriptionStatus.setTextColor(Color.parseColor("#10B981"))
                binding.cardAccount.tvAccountSubscribeLabel.text = getString(R.string.sub_renew_short)
                binding.cardAccount.btnAccountSubscribe.visibility = View.VISIBLE

                val ageMin = com.naua_security_mirage.app.data.supabase.SupabaseManager.instance.cacheAgeMinutes()
                if (ageMin < 0) {
                    binding.cardAccount.tvAccountCacheAge.visibility = View.GONE
                } else {
                    binding.cardAccount.tvAccountCacheAge.visibility = View.VISIBLE
                    binding.cardAccount.tvAccountCacheAge.text = when {
                        ageMin < 1 -> getString(R.string.sub_cache_just_now)
                        ageMin < 60 -> getString(R.string.sub_cache_minutes, ageMin.toInt())
                        else -> getString(R.string.sub_cache_hours, (ageMin / 60).toInt())
                    }
                }
            } else {
                binding.cardAccount.tvAccountSubscriptionStatus.text = getString(R.string.sub_free_status)
                binding.cardAccount.tvAccountSubscriptionStatus.setTextColor(Color.parseColor("#F59E0B"))
                binding.cardAccount.tvAccountSubscribeLabel.text = getString(R.string.sub_buy_button)
                binding.cardAccount.btnAccountSubscribe.visibility = View.VISIBLE
                binding.cardAccount.tvAccountCacheAge.visibility = View.GONE
            }
            restyleAllCardDividers()
        }
    }

    private data class DialogPalette(
        val cardBg: Int,
        val stroke: Int,
        val title: Int,
        val subtitle: Int,
        val accent: Int,
        val density: Float
    )

    private fun dialogPalette(): DialogPalette {
        val density = resources.displayMetrics.density
        val hasWallpaper = !settingsRepository.customBgImagePath.isNullOrEmpty() &&
                java.io.File(settingsRepository.customBgImagePath ?: "").exists()
        val isLightContext = !hasWallpaper && (
                settingsRepository.themePreset == SettingsRepository.THEME_LIGHT ||
                        (settingsRepository.customBgColor != 0 &&
                                ColorUtils.calculateLuminance(settingsRepository.customBgColor) > 0.5)
                )
        val customText = settingsRepository.customTextColor
        return DialogPalette(
            cardBg = if (isLightContext) Color.WHITE else ContextCompat.getColor(this, R.color.card),
            stroke = if (isLightContext) Color.parseColor("#E2E8F0")
            else ContextCompat.getColor(this, R.color.card_stroke),
            title = if (customText != 0) customText
            else if (isLightContext) Color.parseColor("#0F172A") else ContextCompat.getColor(this, R.color.ink),
            subtitle = if (customText != 0) ColorUtils.setAlphaComponent(customText, 190)
            else if (isLightContext) Color.parseColor("#475569")
            else ContextCompat.getColor(this, R.color.ink_soft),
            accent = ContextCompat.getColor(this, R.color.accent),
            density = density
        )
    }

    private fun showAuthDialog(onSuccess: (() -> Unit)? = null) {
        pendingAuthSuccessCallback = onSuccess
        val dialog = Dialog(this)
        val dialogBinding = com.naua_security_mirage.app.databinding.DialogAuthBinding.inflate(layoutInflater)
        dialog.setContentView(dialogBinding.root)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.9).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        val pal = dialogPalette()
        dialogBinding.root.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 24f * pal.density
            setColor(pal.cardBg)
            setStroke((1.2f * pal.density).toInt(), pal.stroke)
        }
        dialogBinding.tvAuthTitle.setTextColor(pal.title)
        dialogBinding.tvAuthDesc.setTextColor(pal.subtitle)
        dialogBinding.tvAuthBenefits.setTextColor(pal.subtitle)
        dialogBinding.btnAuthGoogle.backgroundTintList = ColorStateList.valueOf(pal.cardBg)
        dialogBinding.btnAuthGoogle.setTextColor(pal.title)

        AnimationHelper.popIn(dialogBinding.root, durationMs = 240)
        AnimationHelper.attachPressAnimations(dialogBinding.root)

        dialogBinding.btnAuthGoogle.setOnClickListener {
            com.naua_security_mirage.app.data.supabase.SupabaseManager.instance.signInWithGoogle(this)
            dialog.dismiss()
        }

        dialogBinding.btnAuthCancel.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun formatExpiry(isoDate: String): String {
        return try {
            val parts = isoDate.split("-")
            if (parts.size < 3) {
                isoDate
            } else {
                "${parts[2]}.${parts[1]}.${parts[0]}"
            }
        } catch (e: Exception) {
            isoDate
        }
    }

    private fun showSubscriptionDialog() {
        val user = com.naua_security_mirage.app.data.supabase.SupabaseManager.instance.currentUser.value
        if (user == null) {
            showAuthDialog { showSubscriptionDialog() }
            return
        }

        val dialog = Dialog(this)
        val dialogBinding = com.naua_security_mirage.app.databinding.DialogSubscriptionBinding.inflate(layoutInflater)
        dialog.setContentView(dialogBinding.root)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.9).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        val pal = dialogPalette()
        dialogBinding.root.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 24f * pal.density
            setColor(pal.cardBg)
            setStroke((1.2f * pal.density).toInt(), pal.stroke)
        }
        dialogBinding.tvSubTitle.setTextColor(pal.title)
        dialogBinding.tvSubDesc.setTextColor(pal.subtitle)
        dialogBinding.tvSubFeatures.setTextColor(pal.subtitle)
        dialogBinding.priceCardBox.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 16f * pal.density
            setColor(pal.cardBg)
            setStroke((1f * pal.density).toInt(), pal.stroke)
        }
        dialogBinding.btnSubCheck.backgroundTintList = ColorStateList.valueOf(pal.cardBg)
        dialogBinding.btnSubCheck.strokeColor = ColorStateList.valueOf(pal.stroke)
        dialogBinding.btnSubCheck.setTextColor(pal.title)

        val manager = com.naua_security_mirage.app.data.supabase.SupabaseManager.instance
        val subscription = manager.subscription.value
        if (manager.hasActiveSubscription() && subscription != null) {
            val until = subscription.paidUntil?.take(10)
            if (!until.isNullOrBlank()) {
                dialogBinding.tvSubDesc.text = getString(R.string.sub_active_until, formatExpiry(until))
                dialogBinding.btnSubPay.text = getString(R.string.sub_renew_button)
            }
        }

        dialogBinding.btnSubPay.setOnClickListener {
            isWaitingForPayment = true
            paymentBaselineUntil = subscription?.paidUntil?.take(10)
            val opened = com.naua_security_mirage.app.data.supabase.PaymentManager.openPaymentBrowser(this, user.id)
            if (!opened) {
                isWaitingForPayment = false
                Toast.makeText(this, R.string.toast_payment_browser, Toast.LENGTH_SHORT).show()
            }
        }

        dialogBinding.btnSubCheck.setOnClickListener {
            dialogBinding.btnSubCheck.isEnabled = false
            dialogBinding.btnSubCheck.text = getString(R.string.sub_checking)
            lifecycleScope.launch {
                val renewedUntil = awaitSubscriptionExtension()
                dialogBinding.btnSubCheck.isEnabled = true
                if (renewedUntil != null) {
                    isWaitingForPayment = false
                    paymentBaselineUntil = null
                    settingsRepository.selectedServerPlan = SettingsRepository.PLAN_PREMIUM_FRANCE
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.sub_renewed_toast, formatExpiry(renewedUntil)),
                        Toast.LENGTH_LONG,
                    ).show()
                    dialog.dismiss()
                    updateAccountCardUI()
                    updateServerPlanSelectorUI()
                } else {
                    Toast.makeText(this@MainActivity, R.string.sub_payment_pending, Toast.LENGTH_LONG).show()
                }
            }
        }

        AnimationHelper.popIn(dialogBinding.root, durationMs = 240)
        AnimationHelper.attachPressAnimations(dialogBinding.root)

        dialogBinding.btnSubCancel.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun setupAutoPingUI() {
        val currentInterval = settingsRepository.autoPingIntervalSeconds
        val initialIndex = AUTO_PING_STEPS.indexOf(currentInterval).let { if (it >= 0) it else 1 }
        binding.sbAutoPing.progress = initialIndex
        updateAutoPingDescription(AUTO_PING_STEPS[initialIndex])

        binding.rowAutoPing.setOnClickListener {
            AnimationHelper.bounceClick(binding.rowAutoPing, minScale = 0.97f, durationMs = 150)
            toggleAutoPingExpand()
        }

        binding.sbAutoPing.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val safeProgress = progress.coerceIn(0, AUTO_PING_STEPS.size - 1)
                val interval = AUTO_PING_STEPS[safeProgress]
                updateAutoPingDescription(interval)
                if (fromUser) {
                    settingsRepository.autoPingIntervalSeconds = interval
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun updateAutoPingDescription(intervalSec: Int) {
        val badgeText = when (intervalSec) {
            0 -> getString(R.string.interval_disabled)
            3 -> getString(R.string.interval_sec_default_fmt, 3)
            60 -> getString(R.string.interval_minute)
            else -> getString(R.string.interval_sec_fmt, intervalSec)
        }
        val descText = when (intervalSec) {
            0 -> getString(R.string.interval_disabled)
            3 -> getString(R.string.interval_sec_short_default_fmt, 3)
            60 -> getString(R.string.interval_minute)
            else -> getString(R.string.interval_sec_fmt, intervalSec)
        }
        binding.tvAutoPingValueBadge.text = badgeText
        binding.tvAutoPingDesc.text = getString(R.string.setting_auto_ping_desc, descText)
    }

    private fun absoluteTopInSettings(view: View): Int {
        var top = 0
        var current: View? = view
        while (current != null && current !== binding.viewSettings) {
            top += current.top
            current = current.parent as? View
        }
        return top
    }

    private fun toggleAutoPingExpand() {
        isAutoPingExpanded = !isAutoPingExpanded

        val startRot = binding.ivChevronAutoPing.rotation
        val targetRot = if (isAutoPingExpanded) 90f else 0f
        AnimationHelper.animateDirect(
            durationMs = 220,
            interpolator = AnimationHelper.EaseOutCubic,
            onUpdate = { fraction ->
                binding.ivChevronAutoPing.rotation = startRot + (targetRot - startRot) * fraction
            },
            onEnd = {
                binding.ivChevronAutoPing.rotation = targetRot
            }
        )

        val density = resources.displayMetrics.density
        if (isAutoPingExpanded) {
            binding.containerAutoPingExpand.visibility = View.VISIBLE
            binding.containerAutoPingExpand.alpha = 0f
            binding.containerAutoPingExpand.translationY = -12f * density
            AnimationHelper.animateDirect(
                durationMs = 220,
                interpolator = AnimationHelper.EaseOutCubic,
                onUpdate = { fraction ->
                    binding.containerAutoPingExpand.alpha = fraction
                    binding.containerAutoPingExpand.translationY = -12f * density * (1f - fraction)
                },
                onEnd = {
                    binding.containerAutoPingExpand.alpha = 1f
                    binding.containerAutoPingExpand.translationY = 0f
                }
            )
            binding.viewSettings.postDelayed({
                binding.viewSettings.smoothScrollTo(0, absoluteTopInSettings(binding.cardAutoPing))
            }, 100)
        } else {
            val startAlpha = binding.containerAutoPingExpand.alpha
            AnimationHelper.animateDirect(
                durationMs = 180,
                interpolator = AnimationHelper.EaseInOutCubic,
                onUpdate = { fraction ->
                    binding.containerAutoPingExpand.alpha = startAlpha * (1f - fraction)
                    binding.containerAutoPingExpand.translationY = -12f * density * fraction
                },
                onEnd = {
                    binding.containerAutoPingExpand.visibility = View.GONE
                    binding.containerAutoPingExpand.translationY = 0f
                    binding.containerAutoPingExpand.alpha = 1f
                }
            )
        }
    }

    private fun setupSpeedUI() {
        val currentInterval = settingsRepository.speedIntervalSeconds
        val initialIndex = SPEED_STEPS.indexOf(currentInterval).let { if (it >= 0) it else 1 }
        binding.sbSpeed.progress = initialIndex
        updateSpeedDescription(SPEED_STEPS[initialIndex])

        binding.rowSpeed.setOnClickListener {
            AnimationHelper.bounceClick(binding.rowSpeed, minScale = 0.97f, durationMs = 150)
            toggleSpeedExpand()
        }

        binding.sbSpeed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val safeProgress = progress.coerceIn(0, SPEED_STEPS.size - 1)
                val interval = SPEED_STEPS[safeProgress]
                updateSpeedDescription(interval)
                if (fromUser) {
                    settingsRepository.speedIntervalSeconds = interval
                    if (interval <= 0) {
                        binding.containerSpeedLive.visibility = View.GONE
                    }
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun updateSpeedDescription(intervalSec: Int) {
        val badgeText = when (intervalSec) {
            0 -> getString(R.string.interval_disabled)
            1 -> getString(R.string.interval_sec_default_fmt, 1)
            else -> getString(R.string.interval_sec_fmt, intervalSec)
        }
        val descText = when (intervalSec) {
            0 -> getString(R.string.interval_disabled)
            1 -> getString(R.string.interval_sec_short_default_fmt, 1)
            else -> getString(R.string.interval_sec_fmt, intervalSec)
        }
        binding.tvSpeedValueBadge.text = badgeText
        binding.tvSpeedDesc.text = getString(R.string.setting_speed_desc, descText)
    }

    private fun toggleSpeedExpand() {
        isSpeedExpanded = !isSpeedExpanded

        val startRot = binding.ivChevronSpeed.rotation
        val targetRot = if (isSpeedExpanded) 90f else 0f
        AnimationHelper.animateDirect(
            durationMs = 220,
            interpolator = AnimationHelper.EaseOutCubic,
            onUpdate = { fraction ->
                binding.ivChevronSpeed.rotation = startRot + (targetRot - startRot) * fraction
            },
            onEnd = {
                binding.ivChevronSpeed.rotation = targetRot
            }
        )

        val density = resources.displayMetrics.density
        if (isSpeedExpanded) {
            binding.containerSpeedExpand.visibility = View.VISIBLE
            binding.containerSpeedExpand.alpha = 0f
            binding.containerSpeedExpand.translationY = -12f * density
            AnimationHelper.animateDirect(
                durationMs = 220,
                interpolator = AnimationHelper.EaseOutCubic,
                onUpdate = { fraction ->
                    binding.containerSpeedExpand.alpha = fraction
                    binding.containerSpeedExpand.translationY = -12f * density * (1f - fraction)
                },
                onEnd = {
                    binding.containerSpeedExpand.alpha = 1f
                    binding.containerSpeedExpand.translationY = 0f
                }
            )
            binding.viewSettings.postDelayed({
                binding.viewSettings.smoothScrollTo(0, absoluteTopInSettings(binding.cardSpeed))
            }, 100)
        } else {
            val startAlpha = binding.containerSpeedExpand.alpha
            AnimationHelper.animateDirect(
                durationMs = 180,
                interpolator = AnimationHelper.EaseInOutCubic,
                onUpdate = { fraction ->
                    binding.containerSpeedExpand.alpha = startAlpha * (1f - fraction)
                    binding.containerSpeedExpand.translationY = -12f * density * fraction
                },
                onEnd = {
                    binding.containerSpeedExpand.visibility = View.GONE
                    binding.containerSpeedExpand.translationY = 0f
                    binding.containerSpeedExpand.alpha = 1f
                }
            )
        }
    }

    private var isGeoUpdating = false

    private var geoSpinHandle: AnimationHelper.AnimationHandle? = null

    private fun setupGeoRoutingUI() {
        updateGeoStatusText()

        binding.rowUpdateGeo.setOnClickListener {
            AnimationHelper.bounceClick(binding.rowUpdateGeo, minScale = 0.96f, durationMs = 150)
            updateGeoDatabasesManually()
        }
    }

    private fun updateGeoStatusText() {
        val isReady = geoRoutingRepository.isGeoFilesReady()
        val lastUpdate = geoRoutingRepository.getLastUpdateFormatted()
        binding.tvGeoStatus.text = if (isReady) {
            getString(R.string.geo_status_ready, lastUpdate)
        } else {
            getString(R.string.geo_status_not_downloaded)
        }
    }

    private fun updateGeoDatabasesManually() {
        if (isGeoUpdating) return
        isGeoUpdating = true
        binding.tvGeoStatus.text = getString(R.string.geo_status_updating)
        binding.rowUpdateGeo.isEnabled = false

        geoSpinHandle?.cancel()
        geoSpinHandle = AnimationHelper.loopDirect(periodMs = 900) { progress ->
            binding.ivUpdateGeo.rotation = progress * 360f
        }

        lifecycleScope.launch {
            val result = geoRoutingRepository.updateGeoFiles(force = true)
            geoSpinHandle?.cancel()
            geoSpinHandle = null
            binding.ivUpdateGeo.rotation = 0f
            binding.rowUpdateGeo.isEnabled = true
            isGeoUpdating = false

            if (result.isSuccess) {
                updateGeoStatusText()
                Toast.makeText(this@MainActivity, R.string.geo_update_success, Toast.LENGTH_SHORT).show()
                showVpnActiveReconnectionNoticeIfNeeded()
            } else {
                updateGeoStatusText()
                Toast.makeText(this@MainActivity, R.string.geo_update_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun handleConnectButtonClick() {
        val currentState = MirageVpnService.vpnState.value
        when (currentState) {
            VpnState.CONNECTED -> {
                MirageVpnService.stop(this)
            }
            VpnState.CONNECTING -> {
                MirageVpnService.stop(this)
            }
            VpnState.DISCONNECTED, VpnState.DISCONNECTING -> {
                if (settingsRepository.selectedServerPlan == SettingsRepository.PLAN_PREMIUM_FRANCE) {
                    val user = com.naua_security_mirage.app.data.supabase.SupabaseManager.instance.currentUser.value
                    if (user == null) {
                        showAuthDialog {
                            if (com.naua_security_mirage.app.data.supabase.SupabaseManager.instance.hasActiveSubscription()) {
                                requestVpnAndStart()
                            } else {
                                showSubscriptionDialog()
                            }
                        }
                        return
                    }
                    if (!com.naua_security_mirage.app.data.supabase.SupabaseManager.instance.hasActiveSubscription()) {
                        showSubscriptionDialog()
                        return
                    }
                }
                requestVpnAndStart()
            }
        }
    }

    private fun requestVpnAndStart() {
        showVpnConsentDialog {
            checkNotificationPermission()
            val prepareIntent = VpnService.prepare(this)
            if (prepareIntent != null) {
                vpnConsentLauncher.launch(prepareIntent)
            } else {
                MirageVpnService.start(this)
            }
        }
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun observeVpnState() {
        lifecycleScope.launch {
            MirageVpnService.vpnState.collect { state ->
                updateUiForState(state)
            }
        }

        lifecycleScope.launch {
            combine(
                MirageVpnService.sessionSeconds,
                MirageVpnService.activePing,
                MirageVpnService.vpnState
            ) { seconds, ping, state ->
                Triple(seconds, ping, state)
            }.collect { (seconds, ping, state) ->
                if (state == VpnState.CONNECTED) {
                    val minutes = seconds / 60
                    val remSeconds = seconds % 60
                    val timeStr = String.format("%02d:%02d", minutes, remSeconds)
                    val pingDisplay = if (ping > 0) ping else 45L
                    binding.tvStatusSub.text = String.format(
                        getString(R.string.status_sub_connected_format),
                        timeStr,
                        pingDisplay
                    )
                }
            }
        }

        lifecycleScope.launch {
            combine(
                MirageVpnService.activeSpeed,
                MirageVpnService.vpnState
            ) { speed, state ->
                Pair(speed, state)
            }.collect { (speed, state) ->
                if (state == VpnState.CONNECTED && speed.isEnabled && settingsRepository.speedIntervalSeconds > 0) {
                    binding.containerSpeedLive.visibility = View.VISIBLE
                    binding.tvSpeedDown.text = com.naua_security_mirage.app.vpn.VpnNotificationManager.formatSpeed(speed.downBps)
                    binding.tvSpeedUp.text = com.naua_security_mirage.app.vpn.VpnNotificationManager.formatSpeed(speed.upBps)
                } else {
                    binding.containerSpeedLive.visibility = View.GONE
                }
            }
        }
    }

    private fun updateUiForState(state: VpnState) {
        currentVpnState = state
        if (state == VpnState.CONNECTED) {
            aiModePending = false
            splitTunnelDirty = false
        }
        updateAiModePendingWarning()
        binding.view3DButton.vpnState = state
        when (state) {
            VpnState.DISCONNECTED -> {
                binding.containerSpeedLive.visibility = View.GONE
                connectBreathingHandle?.cancel()
                connectBreathingHandle = null
                connectGlowHandle?.cancel()
                connectGlowHandle = null

                binding.tvStatus.text = getString(R.string.status_disconnected)
                if (!isRefreshing) {
                    binding.tvStatusSub.text = lastMeasuredServerInfo ?: getString(R.string.status_sub_disconnected)
                }
                binding.connectGlowRing.visibility = View.INVISIBLE

                val currentScale = binding.btnConnect.scaleX
                if (currentScale != 1.0f) {
                    AnimationHelper.animateDirect(
                        durationMs = 220,
                        interpolator = AnimationHelper.EaseOutCubic,
                        onUpdate = { f ->
                            val s = currentScale + (1.0f - currentScale) * f
                            binding.btnConnect.scaleX = s
                            binding.btnConnect.scaleY = s
                        },
                        onEnd = {
                            binding.btnConnect.scaleX = 1.0f
                            binding.btnConnect.scaleY = 1.0f
                        }
                    )
                }
            }
            VpnState.CONNECTING -> {
                binding.containerSpeedLive.visibility = View.GONE
                lastMeasuredServerInfo = null
                binding.tvStatus.text = getString(R.string.status_connecting)
                binding.tvStatusSub.text = getString(R.string.status_sub_selecting)

                connectBreathingHandle?.cancel()
                connectBreathingHandle = AnimationHelper.startBreathing(
                    view = binding.btnConnect,
                    minScale = 0.95f,
                    maxScale = 1.04f,
                    periodMs = 1200
                )

                connectGlowHandle?.cancel()
                connectGlowHandle = AnimationHelper.startGlowPulse(
                    view = binding.connectGlowRing,
                    minScale = 1.0f,
                    maxScale = 1.18f,
                    minAlpha = 0.25f,
                    maxAlpha = 0.85f,
                    periodMs = 1200
                )
            }
            VpnState.CONNECTED -> {
                lastMeasuredServerInfo = null
                connectBreathingHandle?.cancel()
                connectBreathingHandle = null
                connectGlowHandle?.cancel()
                connectGlowHandle = null

                binding.tvStatus.text = getString(R.string.status_connected)
                binding.connectGlowRing.visibility = View.VISIBLE
                binding.connectGlowRing.alpha = 0.85f
                binding.connectGlowRing.scaleX = 1.04f
                binding.connectGlowRing.scaleY = 1.04f

                AnimationHelper.bounceClick(binding.btnConnect, minScale = 1.08f, durationMs = 260)
            }
            VpnState.DISCONNECTING -> {
                binding.containerSpeedLive.visibility = View.GONE
                connectBreathingHandle?.cancel()
                connectBreathingHandle = null
                connectGlowHandle?.cancel()
                connectGlowHandle = null

                binding.tvStatus.text = getString(R.string.status_disconnecting)
                binding.connectGlowRing.visibility = View.INVISIBLE
                binding.btnConnect.scaleX = 1.0f
                binding.btnConnect.scaleY = 1.0f
            }
        }
        updateVpnActiveNoticeBanner(animated = true)
    }

    private fun performRefresh() {
        if (isRefreshing) return
        isRefreshing = true

        refreshAnimationJob?.cancel()
        refreshAnimationJob = lifecycleScope.launch(Dispatchers.Main) {
            while (isActive && isRefreshing && AnimationHelper.animationsEnabled) {
                binding.ivRefreshIcon.rotation = (binding.ivRefreshIcon.rotation + 12f) % 360f
                delay(16)
            }
            binding.ivRefreshIcon.rotation = 0f
        }

        val currentState = MirageVpnService.vpnState.value
        AppLogger.i("DataRefresh", "Нажата кнопка обновления данных (состояние: ${currentState.name})")

        if (currentState == VpnState.DISCONNECTED) {
            AppLogger.i("DataRefresh", "Режим без активного VPN соединения. Загрузка серверов и измерение задержки...")
            binding.tvStatusSub.text = getString(R.string.refreshing_servers)
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val hasPaid = com.naua_security_mirage.app.data.supabase.SupabaseManager.instance
                        .hasActiveSubscription()
                    if (hasPaid) {
                        val freshKey = runCatching {
                            com.naua_security_mirage.app.data.supabase.SupabaseManager.instance
                                .ensureFranceVlessKey()
                        }.getOrNull()
                        AppLogger.i(
                            "DataRefresh",
                            if (freshKey != null) "Ключ платного узла обновлён"
                            else "Ключ платного узла не обновлён, используется кэш"
                        )
                    } else {
                        AppLogger.d("DataRefresh", "Нет активной подписки, платный узел пропущен")
                    }
                    val isPaidPlan = settingsRepository.selectedServerPlan == SettingsRepository.PLAN_PREMIUM_FRANCE
                    val francePingJob = if (isPaidPlan) {
                        async {
                            runCatching {
                                val manager = com.naua_security_mirage.app.data.supabase.SupabaseManager.instance
                                val uuid = manager.getActiveClientUuid()
                                val france = com.naua_security_mirage.app.data.supabase.SupabaseConfig
                                    .getFranceServer(uuid, manager.getActiveClientFlow(uuid))
                                val p = pingRepository.measurePing(france, timeoutMs = 1800)
                                if (p in 1..9998) "$p ms" else getString(R.string.ping_timeout)
                            }.getOrNull() ?: getString(R.string.ping_timeout)
                        }
                    } else {
                        null
                    }
                    val servers = vlessKeyRepository.getVlessServers()
                    AppLogger.d("DataRefresh", "Получено конфигураций: ${servers.size}. Измерение пинга...")
                    val measured = pingRepository.measureAllPings(servers)
                    measured.forEach { s ->
                        val pingStr = if (s.pingMs in 1..9998) "${s.pingMs} ms" else getString(R.string.ping_timeout)
                        AppLogger.d("DataRefresh", "Узел ${s.tag} -> пинг: $pingStr")
                    }
                    val best = pingRepository.selectBestServer(measured)
                    val pingText = if (best.pingMs in 1..9998) "${best.pingMs} ms" else getString(R.string.ping_timeout)
                    val francePingText = francePingJob?.await()

                    val statusText = if (francePingText != null) francePingText else pingText
                    AppLogger.i(
                        "DataRefresh",
                        if (isPaidPlan) {
                            "Обновление завершено. Показан пинг Франции $francePingText, бесплатные обновлены: ${best.tag} $pingText"
                        } else {
                            "Обновление завершено. Показан пинг бесплатного ${best.tag} $pingText"
                        }
                    )
                    withContext(Dispatchers.Main) {
                        isRefreshing = false
                        refreshAnimationJob?.cancel()
                        binding.ivRefreshIcon.rotation = 0f
                        if (MirageVpnService.vpnState.value == VpnState.DISCONNECTED) {
                            lastMeasuredServerInfo = statusText
                            binding.tvStatusSub.text = statusText
                        }
                    }
                } catch (e: Throwable) {
                    AppLogger.w("DataRefresh", "Ошибка при обновлении серверов без VPN: ${e.message}", e)
                    withContext(Dispatchers.Main) {
                        isRefreshing = false
                        refreshAnimationJob?.cancel()
                        binding.ivRefreshIcon.rotation = 0f
                        if (MirageVpnService.vpnState.value == VpnState.DISCONNECTED) {
                            binding.tvStatusSub.text = lastMeasuredServerInfo ?: getString(R.string.status_sub_disconnected)
                        }
                    }
                }
            }
        } else if (currentState == VpnState.CONNECTED) {
            AppLogger.i("DataRefresh", "Режим с активным подключением. Измерение задержки активного туннеля...")
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val active = MirageVpnService.activeServer.value
                    val servers = vlessKeyRepository.getVlessServers()
                    val target = active ?: servers.firstOrNull()
                    if (target != null) {
                        val pingRes = pingRepository.measurePing(target, timeoutMs = 2000)
                        val pingStr = if (pingRes in 1..9998) "$pingRes ms" else getString(R.string.ping_timeout)
                        AppLogger.i("DataRefresh", "Замер активного узла ${target.tag} завершен: $pingStr")
                    }
                    withContext(Dispatchers.Main) {
                        isRefreshing = false
                        refreshAnimationJob?.cancel()
                        binding.ivRefreshIcon.rotation = 0f
                    }
                } catch (e: Throwable) {
                    AppLogger.w("DataRefresh", "Ошибка при замере пинга активного VPN: ${e.message}", e)
                    withContext(Dispatchers.Main) {
                        isRefreshing = false
                        refreshAnimationJob?.cancel()
                        binding.ivRefreshIcon.rotation = 0f
                    }
                }
            }
        } else {
            isRefreshing = false
            refreshAnimationJob?.cancel()
            binding.ivRefreshIcon.rotation = 0f
        }
    }

    private fun openSettings() {
        val slideDist = 42f * resources.displayMetrics.density
        binding.viewSettings.scrollTo(0, 0)
        AnimationHelper.fadeAndSlideOut(binding.viewMain, toX = -slideDist, durationMs = 210)
        AnimationHelper.fadeAndSlideIn(binding.viewSettings, fromX = slideDist, durationMs = 230)
        binding.hintCard.visibility = View.GONE
        refreshPrivateDnsRow()
        refreshTunnelDnsRow()
    }

    private var privateDnsCard: View? = null
    private var nodeStatusCard: View? = null
    private var nodeStatusJob: Job? = null
    private var themedSubtitleColor: Int = 0

    private fun themedSubtitle(): Int =
        themedSubtitleColor.takeIf { it != 0 } ?: ContextCompat.getColor(this, R.color.ink_faint)

    private fun installPrivateDnsCard() {
        if (privateDnsCard != null) return
        val anchor = binding.cardDirectRu
        val parent = anchor.parent as? ViewGroup ?: return
        val card = layoutInflater.inflate(R.layout.card_private_dns, parent, false)
        val index = parent.indexOfChild(anchor) + 1
        parent.addView(card, index)
        privateDnsCard = card
        card.findViewById<View>(R.id.rowTunnelDns)?.setOnClickListener {
            AnimationHelper.bounceClick(it, minScale = 0.98f, durationMs = 140)
            showTunnelDnsDialog()
        }
        refreshTunnelDnsRow()

        val nodeCard = layoutInflater.inflate(R.layout.card_node_status, parent, false)
        val diagIndex = parent.indexOfChild(binding.cardSpeed)
        if (diagIndex >= 0) parent.addView(nodeCard, diagIndex) else parent.addView(nodeCard)
        nodeStatusCard = nodeCard
        nodeCard.findViewById<View>(R.id.tvNodeStatusAction)?.setOnClickListener {
            AnimationHelper.bounceClick(it, minScale = 0.93f, durationMs = 150)
            refreshNodeStatus(force = true)
        }
        nodeCard.findViewById<View>(R.id.tvFreeNodesAction)?.setOnClickListener {
            AnimationHelper.bounceClick(it, minScale = 0.93f, durationMs = 150)
            nodeStatusCard?.findViewById<TextView>(R.id.tvFreeNodesState)?.let { view ->
                view.text = getString(R.string.free_nodes_checking)
                measureFreeNodes(view)
            }
        }
        nodeCard.findViewById<View>(R.id.rowNodeHistory)?.setOnClickListener {
            AnimationHelper.bounceClick(it, minScale = 0.98f, durationMs = 150)
            refreshNodeStatus(force = true)
        }
        nodeCard.findViewById<View>(R.id.rowSessionHistory)?.setOnClickListener {
            AnimationHelper.bounceClick(it, minScale = 0.98f, durationMs = 150)
            showSessionHistoryDialog()
        }

        card.findViewById<View>(R.id.rowPrivateDns)?.setOnClickListener {
            AnimationHelper.bounceClick(it, minScale = 0.98f, durationMs = 150)
            openPrivateDnsSettings()
        }
        card.findViewById<View>(R.id.tvPrivateDnsAction)?.setOnClickListener {
            AnimationHelper.bounceClick(it, minScale = 0.93f, durationMs = 150)
            openPrivateDnsSettings()
        }
        applySettingsGroups()
    }

    private var nodeStatusGen = 0

    private fun refreshNodeStatus(force: Boolean = false) {
        val card = nodeStatusCard ?: return
        val stateView = card.findViewById<TextView>(R.id.tvNodeStatusState) ?: return
        val historyView = card.findViewById<TextView>(R.id.tvNodeHistory)
        if (!force && nodeStatusJob?.isActive == true) return
        nodeStatusJob?.cancel()
        val gen = ++nodeStatusGen

        nodeStatusJob = lifecycleScope.launch {
            if (!com.naua_security_mirage.app.data.supabase.SupabaseManager.instance.hasActiveSubscription()) {
                if (gen != nodeStatusGen) return@launch
                stateView.text = getString(R.string.node_status_no_subscription)
                stateView.setTextColor(themedSubtitle())
            } else {
                refreshFranceState(stateView, gen)
            }

            val events = com.naua_security_mirage.app.vpn.NodeStatusReporter.history(limit = 5)
            if (gen != nodeStatusGen) return@launch
            if (historyView != null) {
                historyView.text = when {
                    events == null -> getString(R.string.node_history_failed)
                    events.isEmpty() -> getString(R.string.node_history_empty)
                    else -> events.joinToString("\n") { ev ->
                        getString(
                            R.string.node_history_entry,
                            ev.changedAt?.let { formatNodeSince(it) } ?: "?",
                            nodeStateLabel(ev.state)
                        )
                    }
                }
                AppLogger.i(
                    "NodeStatus",
                    if (events == null) "История сбоев: загрузить не удалось"
                    else "История сбоев: записей ${events.size}"
                )
            }
        }
    }

    private suspend fun refreshFranceState(stateView: TextView, gen: Int) {
            val status = com.naua_security_mirage.app.vpn.NodeStatusReporter.fetch()
            if (gen != nodeStatusGen) return
            if (status == null) {
                stateView.text = getString(R.string.node_status_fetch_failed)
            } else {
                AppLogger.i(
                    "NodeStatus",
                    "Франция: state=${status.state} core=${status.core} reported=${status.reportedAt}"
                )
                val reported = status.reportedAt
                val stale = reported == null ||
                        System.currentTimeMillis() - reported > STATUS_STALE_MS
                val since = status.changedAt?.let { formatNodeSince(it) }
                stateView.text = when {
                    stale -> getString(
                        R.string.node_status_stale,
                        reported?.let { formatNodeSince(it) } ?: "?"
                    )
                    status.state == "up" -> getString(R.string.node_status_up, status.core ?: "?")
                    status.state == "down" -> getString(R.string.node_status_down, since ?: "?")
                    status.state == "wedged" -> getString(R.string.node_status_wedged, since ?: "?")
                    else -> getString(R.string.node_status_unknown)
                }
                stateView.setTextColor(
                    nodeColor(stale || status.state == "down" || status.state == "wedged")
                )
                com.naua_security_mirage.app.work.NodeStatusAlertNotifier.onStatus(
                    this@MainActivity,
                    status
                )
            }
    }

    private fun nodeStateLabel(state: String): String = when (state) {
        "up" -> getString(R.string.node_history_state_up)
        "down" -> getString(R.string.node_history_state_down)
        "wedged" -> getString(R.string.node_history_state_wedged)
        else -> state
    }

    private fun measureFreeNodes(view: TextView) {
        lifecycleScope.launch(Dispatchers.IO) {
            val measured = runCatching {
                val servers = vlessKeyRepository.getVlessServers()
                pingRepository.measureAllPings(servers)
            }.getOrNull()

            val total = measured?.size ?: -1
            val dead = measured?.filter { it.pingMs !in 1..9998 }?.map { it.tag } ?: emptyList()
            val alive = if (measured == null) -1 else total - dead.size

            val countryLines = if (measured != null && alive > 0) {
                measured.filter { it.pingMs in 1..9998 }.map { server ->
                    val ping = if (server.pingMs in 1..9998) "${server.pingMs} ms" else "—"
                    "${server.tag} · $ping"
                }
            } else emptyList()

            val text = when {
                measured == null -> getString(R.string.free_nodes_failed)
                total == 0 -> getString(R.string.free_nodes_none)
                dead.isEmpty() -> getString(R.string.free_nodes_alive, alive, total) +
                        (if (countryLines.isNotEmpty()) "\n" + countryLines.joinToString("\n") else "")
                else -> getString(R.string.free_nodes_alive, alive, total) + "\n" +
                    getString(R.string.free_nodes_dead, dead.joinToString(", ")) +
                    (if (countryLines.isNotEmpty()) "\n" + countryLines.joinToString("\n") else "")
            }
            withContext(Dispatchers.Main) {
                view.text = text
                view.setTextColor(nodeColor(measured != null && alive == 0))
            }
            AppLogger.i(
                "NodeStatus",
                if (measured == null) "Бесплатные узлы: проверка не удалась"
                else "Бесплатные узлы: отвечают $alive из $total" +
                    (if (dead.isNotEmpty()) ", нет ответа: ${dead.joinToString(", ")}" else "")
            )
        }
    }

    private fun nodeColor(bad: Boolean): Int {
        if (!bad) return themedSubtitle()
        return Color.parseColor(
            "#" + Integer.toHexString(ContextCompat.getColor(this, R.color.danger))
        )
    }

    private fun formatNodeSince(epochMs: Long): String {
        return try {
            java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.US).format(java.util.Date(epochMs))
        } catch (t: Throwable) {
            AppLogger.w("NodeStatus", "Не удалось отформатировать время: ${t.message}")
            ""
        }
    }

    private fun refreshPrivateDnsRow() {
        val card = privateDnsCard ?: return
        val stateView = card.findViewById<TextView>(R.id.tvPrivateDnsState) ?: return
        val actionView = card.findViewById<TextView>(R.id.tvPrivateDnsAction)
        val state = PrivateDnsInspector.read(this)
        stateView.text = when (state.mode) {
            PrivateDnsInspector.Mode.OFF -> getString(R.string.private_dns_state_off)
            PrivateDnsInspector.Mode.OPPORTUNISTIC -> getString(R.string.private_dns_state_auto)
            PrivateDnsInspector.Mode.STRICT ->
                getString(R.string.private_dns_state_strict, state.specifier ?: "?")
            PrivateDnsInspector.Mode.UNKNOWN -> getString(R.string.private_dns_state_unknown)
        }
        val risky = state.mode == PrivateDnsInspector.Mode.STRICT
        stateView.setTextColor(
            if (risky) Color.parseColor("#${Integer.toHexString(ContextCompat.getColor(this, R.color.danger))}")
            else themedSubtitle()
        )
        actionView?.visibility = View.VISIBLE
        AppLogger.i("PrivateDns", "Настройки приложения: ${PrivateDnsInspector.describe(state)}")
    }

    private fun openSystemVpnSettings() {
        try {
            val intent = Intent("android.net.vpn.SETTINGS")
            startActivity(intent)
        } catch (e: Exception) {
            try {
                val fallbackIntent = Intent(android.provider.Settings.ACTION_VPN_SETTINGS)
                startActivity(fallbackIntent)
            } catch (e2: Exception) {
                Toast.makeText(this, R.string.toast_system_vpn_settings, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun refreshKillSwitchRow() {
        val locked = com.naua_security_mirage.app.vpn.LockdownInspector.isOurAppLocked(this)
        binding.tvKillSwitchDesc.setText(
            if (!settingsRepository.isKillSwitchEnabled) R.string.setting_kill_switch_desc
            else if (locked) R.string.kill_switch_active
            else R.string.kill_switch_needs_system
        )
    }

    private fun openPrivateDnsSettings() {
        val attempts = listOf(
            Intent("android.settings.PRIVATE_DNS_SETTINGS"),
            Intent(Settings.ACTION_WIRELESS_SETTINGS),
            Intent(Settings.ACTION_SETTINGS)
        )
        for (intent in attempts) {
            try {
                startActivity(intent)
                return
            } catch (_: Throwable) {
            }
        }
        AppLogger.w("PrivateDns", "Не удалось открыть настройки Android")
    }

    private var privateDnsDialog: Dialog? = null

    private data class DnsChoice(
        val server: DnsServer,
        val nameRes: Int,
        val url: String
    )

    private fun dnsChoices(): List<DnsChoice> {
        val builtIn = DnsServer.BUILT_IN.map { candidate ->
            DnsChoice(candidate, dnsNameRes(candidate.id), candidate.url)
        }
        val customUrl = settingsRepository.customDnsUrl.trim()
        if (customUrl.isBlank()) return builtIn
        return builtIn + DnsChoice(
            DnsServer(id = XrayVpnController.CUSTOM_DNS_ID, url = customUrl),
            R.string.dns_server_custom,
            customUrl
        )
    }

    private fun dnsNameRes(id: String): Int = when (id) {
        "cloudflare" -> R.string.dns_server_cloudflare
        "google" -> R.string.dns_server_google
        "quad9" -> R.string.dns_server_quad9
        "adguard" -> R.string.dns_server_adguard
        "mullvad" -> R.string.dns_server_mullvad
        "yandex" -> R.string.dns_server_yandex
        else -> R.string.dns_server_custom
    }

    private fun currentDnsName(): String {
        val id = settingsRepository.selectedDnsId
        if (id.isBlank()) return getString(R.string.dns_server_cloudflare)
        return getString(dnsNameRes(id))
    }

    private fun refreshTunnelDnsRow() {
        val card = privateDnsCard ?: return
        val state = card.findViewById<TextView>(R.id.tvTunnelDnsState) ?: return
        val selected = settingsRepository.selectedDnsId
        state.text = if (selected.isBlank()) {
            getString(R.string.tunnel_dns_default)
        } else {
            getString(R.string.tunnel_dns_selected_fmt, currentDnsName())
        }
        AppLogger.i("TunnelDns", "Выбран DNS-резолвер: ${selected.ifBlank { "cloudflare (по умолчанию)" }}")
    }

    private fun showTunnelDnsDialog() {
        val dialog = Dialog(this)
        dialog.setContentView(R.layout.dialog_tunnel_dns)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        val density = resources.displayMetrics.density
        val maxHeight = (resources.displayMetrics.heightPixels * 0.78f).toInt()
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.92f).toInt(),
            LinearLayout.LayoutParams.WRAP_CONTENT
        )

        val hasWallpaper = !settingsRepository.customBgImagePath.isNullOrEmpty() &&
                java.io.File(settingsRepository.customBgImagePath ?: "").exists()
        val isLightContext = !hasWallpaper && (
                settingsRepository.themePreset == SettingsRepository.THEME_LIGHT ||
                        (settingsRepository.customBgColor != 0 &&
                                ColorUtils.calculateLuminance(settingsRepository.customBgColor) > 0.5)
                )
        val customText = settingsRepository.customTextColor
        val cardBg = if (isLightContext) Color.WHITE else ContextCompat.getColor(this, R.color.card)
        val stroke = if (isLightContext) Color.parseColor("#E2E8F0")
        else ContextCompat.getColor(this, R.color.card_stroke)
        val titleColor = if (customText != 0) customText
        else if (isLightContext) Color.parseColor("#0F172A")
        else ContextCompat.getColor(this, R.color.ink)
        val bodyColor = if (customText != 0) ColorUtils.setAlphaComponent(customText, 190)
        else if (isLightContext) Color.parseColor("#475569")
        else ContextCompat.getColor(this, R.color.ink_soft)
        val accentColor = ContextCompat.getColor(this, R.color.accent)

        val root = dialog.findViewById<LinearLayout>(R.id.dialogTunnelDnsRoot)
        root.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 24f * density
            setColor(cardBg)
            setStroke((1.2f * density).toInt(), stroke)
        }
        dialog.findViewById<TextView>(R.id.tvTunnelDnsTitle).setTextColor(titleColor)
        dialog.findViewById<TextView>(R.id.tvTunnelDnsSubtitle).setTextColor(bodyColor)
        dialog.findViewById<TextView>(R.id.tvTunnelDnsProgress).setTextColor(bodyColor)
        val testBtn = dialog.findViewById<TextView>(R.id.btnTunnelDnsTest)
        val customBtn = dialog.findViewById<TextView>(R.id.btnTunnelDnsCustom)
        testBtn.setTextColor(bodyColor)
        customBtn.setTextColor(accentColor)
        testBtn.background = GradientDrawable().apply {
            cornerRadius = 14f * density
            setColor(ColorUtils.setAlphaComponent(bodyColor, 26))
            setStroke((1.2f * density).toInt(), stroke)
        }
        customBtn.background = GradientDrawable().apply {
            cornerRadius = 14f * density
            setColor(ColorUtils.setAlphaComponent(accentColor, 34))
            setStroke((1.2f * density).toInt(), ColorUtils.setAlphaComponent(accentColor, 170))
        }

        val container = dialog.findViewById<LinearLayout>(R.id.containerTunnelDns)
        val progress = dialog.findViewById<TextView>(R.id.tvTunnelDnsProgress)
        val scroll = dialog.findViewById<ScrollView>(R.id.scrollTunnelDns)
        val rows = mutableMapOf<String, TextView>()
        val choices = dnsChoices()

        dialog.show()
        AnimationHelper.popIn(root, durationMs = 220)
        AnimationHelper.attachPressAnimations(root)
        scroll.post {
            val params = scroll.layoutParams
            if (scroll.height > maxHeight) {
                params.height = maxHeight
                scroll.layoutParams = params
            }
        }

        fun buildRows() {
            container.removeAllViews()
            rows.clear()
            val selectedId = settingsRepository.selectedDnsId
            for (choice in choices) {
                val row = layoutInflater.inflate(R.layout.row_dns_server, container, false)
                val nameView = row.findViewById<TextView>(R.id.tvDnsServerName)
                val urlView = row.findViewById<TextView>(R.id.tvDnsServerUrl)
                val latencyView = row.findViewById<TextView>(R.id.tvDnsServerLatency)
                nameView.text = getString(choice.nameRes) +
                        if (choice.server.id == selectedId) "  ✓" else ""
                nameView.setTextColor(
                    if (choice.server.id == selectedId) accentColor else titleColor
                )
                urlView.text = choice.url
                urlView.setTextColor(bodyColor)
                latencyView.text = ""
                latencyView.setTextColor(bodyColor)
                row.setOnClickListener {
                    AnimationHelper.bounceClick(row, minScale = 0.98f, durationMs = 140)
                    settingsRepository.selectedDnsId = choice.server.id
                    Toast.makeText(this, R.string.tunnel_dns_applied, Toast.LENGTH_SHORT).show()
                    refreshTunnelDnsRow()
                    buildRows()
                }
                rows[choice.server.id] = latencyView
                container.addView(row)
                if (container.childCount > 1) {
                    val divider = View(this).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            (1f * density).toInt().coerceAtLeast(1)
                        ).apply {
                            marginStart = (16f * density).toInt()
                            marginEnd = (16f * density).toInt()
                        }
                        setBackgroundColor(stroke)
                    }
                    container.addView(divider)
                }
            }
        }
        buildRows()

        testBtn.setOnClickListener {
            AnimationHelper.bounceClick(it, minScale = 0.94f, durationMs = 140)
            progress.visibility = View.VISIBLE
            progress.text = getString(R.string.tunnel_dns_testing)
            testBtn.isEnabled = false
            val throughTunnel = MirageVpnService.vpnState.value == VpnState.CONNECTED
            lifecycleScope.launch(Dispatchers.IO) {
                val outcomes = choices.map { choice ->
                    async { DohTester.probe(choice.server, throughTunnel) }
                }.awaitAll()
                withContext(Dispatchers.Main) {
                    progress.visibility = View.GONE
                    testBtn.isEnabled = true
                    for (result in outcomes) {
                        val view = rows[result.server.id] ?: continue
                        view.text = when {
                            !result.reachable -> getString(R.string.tunnel_dns_failed)
                            !result.answered -> getString(R.string.tunnel_dns_not_dns)
                            else -> getString(R.string.tunnel_dns_latency_fmt, result.latencyMs)
                        }
                        view.setTextColor(
                            if (result.reachable && result.answered) accentColor else bodyColor
                        )
                    }
                    val reachable = outcomes.count { it.reachable && it.answered }
                    AppLogger.i(
                        "TunnelDns",
                        "Проверка завершена: годных $reachable из ${outcomes.size}, " +
                            "через туннель=$throughTunnel, имя пробы ${DohTester.PROBE_NAME}"
                    )
                    for (result in outcomes) {
                        AppLogger.i("TunnelDns", DohTester.logLine(result))
                    }
                }
            }
        }

        customBtn.setOnClickListener {
            AnimationHelper.bounceClick(it, minScale = 0.94f, durationMs = 140)
            val input = EditText(this).apply {
                setText(settingsRepository.customDnsUrl)
                hint = getString(R.string.tunnel_dns_custom_hint)
                setSingleLine()
                setTextColor(titleColor)
                setHintTextColor(bodyColor)
            }
            AlertDialog.Builder(this)
                .setTitle(R.string.tunnel_dns_custom_title)
                .setView(input)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val value = input.text.toString().trim()
                    if (!value.startsWith("https://")) {
                        Toast.makeText(this, R.string.tunnel_dns_custom_bad, Toast.LENGTH_LONG).show()
                        return@setPositiveButton
                    }
                    settingsRepository.customDnsUrl = value
                    settingsRepository.selectedDnsId = XrayVpnController.CUSTOM_DNS_ID
                    Toast.makeText(this, R.string.tunnel_dns_custom_saved, Toast.LENGTH_SHORT).show()
                    refreshTunnelDnsRow()
                    buildRows()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun maybeShowPrivateDnsDialog() {
        if (PrivateDnsInspector.read(this).mode != PrivateDnsInspector.Mode.STRICT) return
        if (privateDnsDialog?.isShowing == true) return
        val now = System.currentTimeMillis()
        if (now - settingsRepository.privateDnsDialogTime < 30 * 60 * 1000L) return
        settingsRepository.privateDnsDialogTime = now

        val dialog = Dialog(this)
        dialog.setContentView(R.layout.dialog_private_dns)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.92f).toInt(),
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        val density = resources.displayMetrics.density
        val hasWallpaper = !settingsRepository.customBgImagePath.isNullOrEmpty() &&
                java.io.File(settingsRepository.customBgImagePath ?: "").exists()
        val isLightContext = !hasWallpaper && (
                settingsRepository.themePreset == SettingsRepository.THEME_LIGHT ||
                        (settingsRepository.customBgColor != 0 &&
                                ColorUtils.calculateLuminance(settingsRepository.customBgColor) > 0.5)
                )
        val customText = settingsRepository.customTextColor
        val cardBgColor = if (isLightContext) Color.WHITE else ContextCompat.getColor(this, R.color.card)
        val strokeColor = if (isLightContext) Color.parseColor("#E2E8F0")
        else ContextCompat.getColor(this, R.color.card_stroke)
        val titleColor = if (customText != 0) customText
        else if (isLightContext) Color.parseColor("#0F172A") else ContextCompat.getColor(this, R.color.ink)
        val bodyColor = if (customText != 0) ColorUtils.setAlphaComponent(customText, 190)
        else if (isLightContext) Color.parseColor("#475569")
        else ContextCompat.getColor(this, R.color.ink_soft)

        val root = dialog.findViewById<LinearLayout>(R.id.dialogPrivateDnsRoot)
        root.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 24f * density
            setColor(cardBgColor)
            setStroke((1.2f * density).toInt(), strokeColor)
        }
        dialog.findViewById<ImageView>(R.id.ivPrivateDnsWarningIcon).imageTintList =
            ColorStateList.valueOf(ContextCompat.getColor(this, R.color.danger))
        dialog.findViewById<TextView>(R.id.tvPrivateDnsWarningTitle).setTextColor(titleColor)
        dialog.findViewById<TextView>(R.id.tvPrivateDnsWarningBody).setTextColor(bodyColor)
        val laterButton = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 14f * density
            setColor(ColorUtils.setAlphaComponent(bodyColor, 26))
            setStroke((1.2f * density).toInt(), strokeColor)
        }
        val accentColor = ContextCompat.getColor(this, R.color.accent)
        val openButton = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 14f * density
            setColor(ColorUtils.setAlphaComponent(accentColor, 34))
            setStroke((1.2f * density).toInt(), ColorUtils.setAlphaComponent(accentColor, 170))
        }
        dialog.findViewById<TextView>(R.id.btnPrivateDnsLater).setTextColor(bodyColor)
        dialog.findViewById<View>(R.id.btnPrivateDnsLater).background = laterButton
        dialog.findViewById<TextView>(R.id.btnPrivateDnsOpen).setTextColor(accentColor)
        dialog.findViewById<View>(R.id.btnPrivateDnsOpen).background = openButton
        dialog.findViewById<View>(R.id.btnPrivateDnsLater).setOnClickListener {
            AnimationHelper.bounceClick(it, minScale = 0.94f, durationMs = 140) {
                dialog.dismiss()
            }
        }
        dialog.findViewById<View>(R.id.btnPrivateDnsOpen).setOnClickListener {
            AnimationHelper.bounceClick(it, minScale = 0.94f, durationMs = 140) {
                dialog.dismiss()
                openPrivateDnsSettings()
            }
        }
        privateDnsDialog = dialog
        dialog.setOnDismissListener { if (privateDnsDialog === dialog) privateDnsDialog = null }
        dialog.show()
        AnimationHelper.popIn(root, durationMs = 220)
        AnimationHelper.attachPressAnimations(root)
    }

    private fun closeSettings() {
        val slideDist = 42f * resources.displayMetrics.density
        AnimationHelper.fadeAndSlideOut(binding.viewSettings, toX = slideDist, durationMs = 210)
        AnimationHelper.fadeAndSlideIn(binding.viewMain, fromX = -slideDist, durationMs = 230)
        binding.hintCard.visibility = View.VISIBLE
    }

    private fun openAppearance() {
        val slideDist = 42f * resources.displayMetrics.density
        AnimationHelper.fadeAndSlideOut(binding.viewSettings, toX = -slideDist, durationMs = 210)
        AnimationHelper.fadeAndSlideIn(binding.viewAppearance, fromX = slideDist, durationMs = 230)
    }

    private fun closeAppearance() {
        val slideDist = 42f * resources.displayMetrics.density
        AnimationHelper.fadeAndSlideOut(binding.viewAppearance, toX = slideDist, durationMs = 210)
        AnimationHelper.fadeAndSlideIn(binding.viewSettings, fromX = -slideDist, durationMs = 230)
    }

    private fun openLogs() {
        refreshLogViewer()
        updateLogLevelChipsState()
        val slideDist = 42f * resources.displayMetrics.density
        AnimationHelper.fadeAndSlideOut(binding.viewSettings, toX = -slideDist, durationMs = 210)
        AnimationHelper.fadeAndSlideIn(binding.viewLogs, fromX = slideDist, durationMs = 230)
    }

    private fun closeLogs() {
        val slideDist = 42f * resources.displayMetrics.density
        AnimationHelper.fadeAndSlideOut(binding.viewLogs, toX = slideDist, durationMs = 210)
        AnimationHelper.fadeAndSlideIn(binding.viewSettings, fromX = -slideDist, durationMs = 230)
    }

    private fun openPerAppProxy() {
        loadInstalledAppsIfNeeded()
        updatePerAppFilterChips()
        splitTunnelDirty = false
        updateVpnActiveNoticeBanner(animated = false)
        val slideDist = 42f * resources.displayMetrics.density
        AnimationHelper.fadeAndSlideOut(binding.viewSettings, toX = -slideDist, durationMs = 210)
        AnimationHelper.fadeAndSlideIn(binding.viewPerAppProxy, fromX = slideDist, durationMs = 230)
    }

    private fun closePerAppProxy() {
        updatePerAppSettingDescription()
        val slideDist = 42f * resources.displayMetrics.density
        AnimationHelper.fadeAndSlideOut(binding.viewPerAppProxy, toX = slideDist, durationMs = 210)
        AnimationHelper.fadeAndSlideIn(binding.viewSettings, fromX = -slideDist, durationMs = 230)
    }

    private fun openCustomWebsites() {
        loadCustomWebsites()
        updateWebsiteFilterChips()
        splitTunnelDirty = false
        updateWebsitesVpnNoticeBanner(animated = false)
        val slideDist = 42f * resources.displayMetrics.density
        AnimationHelper.fadeAndSlideOut(binding.viewSettings, toX = -slideDist, durationMs = 210)
        AnimationHelper.fadeAndSlideIn(binding.viewCustomWebsites, fromX = slideDist, durationMs = 230)
    }

    private fun closeCustomWebsites() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
        imm?.hideSoftInputFromWindow(binding.root.windowToken, 0)
        updateCustomWebsitesSettingDescription()
        val slideDist = 42f * resources.displayMetrics.density
        AnimationHelper.fadeAndSlideOut(binding.viewCustomWebsites, toX = slideDist, durationMs = 210)
        AnimationHelper.fadeAndSlideIn(binding.viewSettings, fromX = -slideDist, durationMs = 230)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (binding.viewCustomWebsites.visibility == View.VISIBLE) {
            closeCustomWebsites()
        } else if (binding.viewPerAppProxy.visibility == View.VISIBLE) {
            closePerAppProxy()
        } else if (binding.viewLogs.visibility == View.VISIBLE) {
            closeLogs()
        } else if (binding.viewAppearance.visibility == View.VISIBLE) {
            closeAppearance()
        } else if (binding.viewSettings.visibility == View.VISIBLE) {
            closeSettings()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    private fun setupAppearanceUI() {
        binding.btnBackFromAppearance.setOnClickListener {
            AnimationHelper.bounceClick(binding.btnBackFromAppearance, minScale = 0.88f, durationMs = 180)
            closeAppearance()
        }

        updateThemeChipsState()
        binding.chipThemeDark.setOnClickListener {
            AnimationHelper.bounceClick(binding.chipThemeDark, minScale = 0.93f, durationMs = 180)
            settingsRepository.themePreset = SettingsRepository.THEME_DARK
            settingsRepository.customBgColor = 0
            val path = settingsRepository.customBgImagePath
            if (path != null) {
                try { File(path).delete() } catch (_: Exception) {}
                settingsRepository.customBgImagePath = null
            }
            settingsRepository.customConnectBtnColor = 0
            settingsRepository.customActionBtnColor = 0
            settingsRepository.customTextColor = 0
            settingsRepository.customSwitchColor = 0
            applyCurrentAppearance()
            setupAppearanceUI()
        }

        binding.chipThemeLight.setOnClickListener {
            AnimationHelper.bounceClick(binding.chipThemeLight, minScale = 0.93f, durationMs = 180)
            settingsRepository.themePreset = SettingsRepository.THEME_LIGHT
            settingsRepository.customBgColor = 0
            val path = settingsRepository.customBgImagePath
            if (path != null) {
                try { File(path).delete() } catch (_: Exception) {}
                settingsRepository.customBgImagePath = null
            }
            settingsRepository.customConnectBtnColor = 0
            settingsRepository.customActionBtnColor = 0
            settingsRepository.customTextColor = 0
            settingsRepository.customSwitchColor = 0
            applyCurrentAppearance()
            setupAppearanceUI()
        }

        val hasWallpaper = !settingsRepository.customBgImagePath.isNullOrEmpty() && File(settingsRepository.customBgImagePath ?: "").exists()
        if (hasWallpaper) {
            binding.btnRemoveBgPhoto.visibility = View.VISIBLE
            binding.tvPickPhoto.text = getString(R.string.action_change_photo)
        } else {
            binding.btnRemoveBgPhoto.visibility = View.GONE
            binding.tvPickPhoto.text = getString(R.string.action_pick_photo)
        }

        binding.btnPickBgPhoto.setOnClickListener {
            AnimationHelper.bounceClick(binding.btnPickBgPhoto, minScale = 0.93f, durationMs = 180)
            pickBackgroundLauncher.launch("image/*")
        }

        binding.btnRemoveBgPhoto.setOnClickListener {
            AnimationHelper.bounceClick(binding.btnRemoveBgPhoto, minScale = 0.93f, durationMs = 180)
            val path = settingsRepository.customBgImagePath
            if (path != null) {
                try { File(path).delete() } catch (_: Exception) {}
            }
            settingsRepository.customBgImagePath = null
            applyCurrentAppearance()
            setupAppearanceUI()
            Toast.makeText(this, R.string.toast_wallpaper_removed, Toast.LENGTH_SHORT).show()
        }

        val isLightPreset = settingsRepository.themePreset == SettingsRepository.THEME_LIGHT
        val defaultBg = if (isLightPreset) 0xFFF8F9FA.toInt() else 0xFF0A0B10.toInt()
        setupColorRow(getString(R.string.color_row_bg), binding.containerBgColors, BG_COLORS, settingsRepository.customBgColor, defaultBg) { color ->
            val path = settingsRepository.customBgImagePath
            if (path != null) {
                try { File(path).delete() } catch (_: Exception) {}
                settingsRepository.customBgImagePath = null
            }
            settingsRepository.customBgColor = color
            settingsRepository.themePreset = SettingsRepository.THEME_CUSTOM
            applyCurrentAppearance()
            setupAppearanceUI()
        }

        setupColorRow(getString(R.string.color_row_connect), binding.containerConnectBtnColors, CONNECT_BTN_COLORS, settingsRepository.customConnectBtnColor, 0xFFE8A33D.toInt()) { color ->
            settingsRepository.customConnectBtnColor = color
            applyCurrentAppearance()
            setupAppearanceUI()
        }

        val defaultAction = if (isLightPreset) 0xFF1E293B.toInt() else 0xFFF3F1EA.toInt()
        setupColorRow(getString(R.string.color_row_action), binding.containerActionBtnColors, ACTION_BTN_COLORS, settingsRepository.customActionBtnColor, defaultAction) { color ->
            settingsRepository.customActionBtnColor = color
            applyCurrentAppearance()
            setupAppearanceUI()
        }

        val defaultText = if (isLightPreset) 0xFF0F172A.toInt() else 0xFFF3F1EA.toInt()
        setupColorRow(getString(R.string.color_row_text), binding.containerTextColors, TEXT_COLORS, settingsRepository.customTextColor, defaultText) { color ->
            settingsRepository.customTextColor = color
            applyCurrentAppearance()
            setupAppearanceUI()
        }

        setupColorRow(getString(R.string.color_row_switch), binding.containerSwitchColors, SWITCH_COLORS, settingsRepository.customSwitchColor, 0xFFE67E22.toInt()) { color ->
            settingsRepository.customSwitchColor = color
            applyCurrentAppearance()
            setupAppearanceUI()
        }

        binding.btnResetAppearance.setOnClickListener {
            AnimationHelper.bounceClick(binding.btnResetAppearance, minScale = 0.92f, durationMs = 200)
            AnimationHelper.shake(binding.btnResetAppearance, amplitudePx = 12f, cycles = 3, durationMs = 300)
            val path = settingsRepository.customBgImagePath
            if (path != null) {
                try { File(path).delete() } catch (_: Exception) {}
            }
            settingsRepository.resetAppearanceToDefaults()
            settingsRepository.themePreset = SettingsRepository.THEME_DARK
            settingsRepository.connectBtnStyle = SettingsRepository.STYLE_STANDARD
            applyCurrentAppearance()
            setupAppearanceUI()
            updateThemeChipsState()
            updateConnectStyleChipsState()
            Toast.makeText(this, getString(R.string.reset_confirm), Toast.LENGTH_SHORT).show()
        }

        setupConnectStyleChips()
    }

    private fun setupConnectStyleChips() {
        updateConnectStyleChipsState()

        val styleChipsList = listOf(
            binding.chipStyleStandard to SettingsRepository.STYLE_STANDARD,
            binding.chipStyleCyberEarth to SettingsRepository.STYLE_3D_CYBER_EARTH,
            binding.chipStyleQuantumCore to SettingsRepository.STYLE_3D_QUANTUM_CORE,
            binding.chipStyleHoloShield to SettingsRepository.STYLE_3D_HOLO_SHIELD,
            binding.chipStyleRealisticEarth to SettingsRepository.STYLE_3D_REALISTIC_EARTH
        )

        styleChipsList.forEach { (chip, styleKey) ->
            chip.setOnClickListener {
                AnimationHelper.bounceClick(chip, minScale = 0.93f, durationMs = 180)
                if (settingsRepository.connectBtnStyle != styleKey) {
                    settingsRepository.connectBtnStyle = styleKey
                    updateConnectStyleChipsState()
                    applyCurrentAppearance()
                }
            }
        }
    }

    private fun updateConnectStyleChipsState() {
        val currentStyle = settingsRepository.connectBtnStyle
        val preset = settingsRepository.themePreset
        val isLight = preset == SettingsRepository.THEME_LIGHT
        val density = resources.displayMetrics.density

        val hasWallpaper = !settingsRepository.customBgImagePath.isNullOrEmpty() && File(settingsRepository.customBgImagePath ?: "").exists()
        val isLightContext = !hasWallpaper && (isLight || (settingsRepository.customBgColor != 0 && ColorUtils.calculateLuminance(settingsRepository.customBgColor) > 0.5))

        val customAccent = settingsRepository.customConnectBtnColor
        val activeStrokeColor = if (customAccent != 0) customAccent else Color.parseColor("#E8A33D")
        val inactiveStrokeColor = if (isLightContext) Color.parseColor("#CBD5E1") else ContextCompat.getColor(this, R.color.card_stroke)
        val chipBaseColor = if (isLightContext) Color.WHITE else ContextCompat.getColor(this, R.color.card)

        val inactiveTextColor = if (settingsRepository.customTextColor != 0) {
            settingsRepository.customTextColor
        } else if (isLightContext) {
            Color.parseColor("#0F172A")
        } else {
            ContextCompat.getColor(this, R.color.ink)
        }

        val styleChips = listOf(
            Triple(SettingsRepository.STYLE_STANDARD, binding.chipStyleStandard, binding.tvStyleStandard),
            Triple(SettingsRepository.STYLE_3D_CYBER_EARTH, binding.chipStyleCyberEarth, binding.tvStyleCyberEarth),
            Triple(SettingsRepository.STYLE_3D_QUANTUM_CORE, binding.chipStyleQuantumCore, binding.tvStyleQuantumCore),
            Triple(SettingsRepository.STYLE_3D_HOLO_SHIELD, binding.chipStyleHoloShield, binding.tvStyleHoloShield),
            Triple(SettingsRepository.STYLE_3D_REALISTIC_EARTH, binding.chipStyleRealisticEarth, binding.tvStyleRealisticEarth)
        )

        styleChips.forEach { (styleKey, chip, textView) ->
            val isSelected = (styleKey == currentStyle)
            val drawable = GradientDrawable().apply {
                cornerRadius = 14f * density
                setColor(chipBaseColor)
                setStroke(
                    (if (isSelected) 2.5f * density else 1f * density).toInt(),
                    if (isSelected) activeStrokeColor else inactiveStrokeColor
                )
            }
            chip.background = drawable
            textView.setTextColor(if (isSelected) activeStrokeColor else inactiveTextColor)
        }

        binding.tvStyleDescription.text = when (currentStyle) {
            SettingsRepository.STYLE_3D_CYBER_EARTH -> getString(R.string.connect_style_cyber_earth_desc) + getString(R.string.style_suffix_cyber)
            SettingsRepository.STYLE_3D_QUANTUM_CORE -> getString(R.string.connect_style_quantum_core_desc) + getString(R.string.style_suffix_quantum)
            SettingsRepository.STYLE_3D_HOLO_SHIELD -> getString(R.string.connect_style_holo_shield_desc) + getString(R.string.style_suffix_holo)
            SettingsRepository.STYLE_3D_REALISTIC_EARTH -> getString(R.string.connect_style_realistic_earth_desc) + getString(R.string.style_suffix_realistic)
            else -> getString(R.string.connect_style_standard_desc) + getString(R.string.style_suffix_standard)
        }
    }

    private fun setupLogsUI() {
        binding.btnBackFromLogs.setOnClickListener {
            AnimationHelper.bounceClick(binding.btnBackFromLogs, minScale = 0.88f, durationMs = 180)
            closeLogs()
        }

        binding.btnClearLogs.setOnClickListener {
            AnimationHelper.spin(binding.ivClearLogsIcon, degrees = 360f, durationMs = 380)
            AnimationHelper.bounceClick(binding.btnClearLogs, minScale = 0.92f, durationMs = 200)
            AnimationHelper.animateDirect(
                durationMs = 240,
                interpolator = AnimationHelper.EaseOutCubic,
                onUpdate = { f ->
                    val a = if (f < 0.5f) 1f - 0.75f * (f / 0.5f) else 0.25f + 0.75f * ((f - 0.5f) / 0.5f)
                    binding.tvLogOutput.alpha = a
                },
                onEnd = { binding.tvLogOutput.alpha = 1f }
            )
            AppLogger.clear()
            AppLogger.system("Diagnostics", "Журнал логов очищен")
            refreshLogViewer()
            Toast.makeText(this, getString(R.string.action_clear_logs), Toast.LENGTH_SHORT).show()
        }

        binding.chipLogAuto.setOnClickListener {
            AnimationHelper.bounceClick(binding.chipLogAuto, minScale = 0.92f, durationMs = 160)
            settingsRepository.logLevel = SettingsRepository.LOG_LEVEL_AUTO
            updateLogLevelChipsState()
            AppLogger.system("Diagnostics", "Уровень сбора логов изменен на: auto (автоматический режим)")
        }

        binding.chipLogDebug.setOnClickListener {
            AnimationHelper.bounceClick(binding.chipLogDebug, minScale = 0.92f, durationMs = 160)
            settingsRepository.logLevel = SettingsRepository.LOG_LEVEL_DEBUG
            updateLogLevelChipsState()
            AppLogger.system("Diagnostics", "Уровень сбора логов изменен на: debug (максимальная отладка)")
        }

        binding.chipLogInfo.setOnClickListener {
            AnimationHelper.bounceClick(binding.chipLogInfo, minScale = 0.92f, durationMs = 160)
            settingsRepository.logLevel = SettingsRepository.LOG_LEVEL_INFO
            updateLogLevelChipsState()
            AppLogger.system("Diagnostics", "Уровень сбора логов изменен на: info (стандартный режим)")
        }

        binding.chipLogWarning.setOnClickListener {
            AnimationHelper.bounceClick(binding.chipLogWarning, minScale = 0.92f, durationMs = 160)
            settingsRepository.logLevel = SettingsRepository.LOG_LEVEL_WARNING
            updateLogLevelChipsState()
            AppLogger.system("Diagnostics", "Уровень сбора логов изменен на: warning (только предупреждения и ошибки)")
        }

        binding.chipLogError.setOnClickListener {
            AnimationHelper.bounceClick(binding.chipLogError, minScale = 0.92f, durationMs = 160)
            settingsRepository.logLevel = SettingsRepository.LOG_LEVEL_ERROR
            updateLogLevelChipsState()
            AppLogger.system("Diagnostics", "Уровень сбора логов изменен на: error (только критические ошибки)")
        }

        binding.chipLogNone.setOnClickListener {
            AnimationHelper.bounceClick(binding.chipLogNone, minScale = 0.92f, durationMs = 160)
            settingsRepository.logLevel = SettingsRepository.LOG_LEVEL_NONE
            updateLogLevelChipsState()
            AppLogger.system("Diagnostics", "Уровень сбора логов изменен на: none (сбор логов приостановлен)")
        }

        binding.btnDownloadLogs.setOnClickListener {
            AnimationHelper.bounceClick(binding.btnDownloadLogs, minScale = 0.93f, durationMs = 180)
            downloadLogs()
        }

        binding.btnShareLogs.setOnClickListener {
            AnimationHelper.bounceClick(binding.btnShareLogs, minScale = 0.93f, durationMs = 180)
            shareLogs()
        }

        lifecycleScope.launch {
            AppLogger.logFlow.collect { line ->
                if (line == "LOGS_CLEARED") {
                    logOutputHasLines = false
                    logPending.setLength(0)
                    binding.tvLogOutput.text = if (settingsRepository.logLevel == SettingsRepository.LOG_LEVEL_NONE) {
                        getString(R.string.log_none_detail)
                    } else {
                        getString(R.string.log_empty_detail)
                    }
                    return@collect
                }
                if (binding.viewLogs.visibility != View.VISIBLE) return@collect
                logPending.append(line).append('\n')
                if (logAppendScheduled) return@collect
                logAppendScheduled = true
                binding.scrollLogOutput.postDelayed({
                    logAppendScheduled = false
                    flushLogOutput()
                }, LOG_FLUSH_MS)
            }
        }
    }

    private fun flushLogOutput() {
        val pending = logPending.toString()
        if (pending.isEmpty()) return
        logPending.setLength(0)
        val scroll = binding.scrollLogOutput
        val stickToBottom = isLogOutputAtBottom()
        val previousY = scroll.scrollY
        if (logOutputHasLines) {
            binding.tvLogOutput.append(pending)
        } else {
            binding.tvLogOutput.text = pending
            logOutputHasLines = true
        }
        scroll.post {
            if (stickToBottom) scroll.fullScroll(View.FOCUS_DOWN) else scroll.scrollTo(0, previousY)
        }
    }

    private fun isLogOutputAtBottom(): Boolean {
        val scroll = binding.scrollLogOutput
        val child = scroll.getChildAt(0) ?: return true
        val gap = child.bottom - (scroll.scrollY + scroll.height)
        return gap <= (12f * resources.displayMetrics.density).toInt()
    }

    private var logOutputHasLines = false
    private var logAppendScheduled = false
    private val logPending = StringBuilder()

    private fun refreshLogViewer() {
        val logs = AppLogger.getAllLogs()
        logPending.setLength(0)
        logAppendScheduled = false
        if (logs.isEmpty()) {
            logOutputHasLines = false
            binding.tvLogOutput.text = if (settingsRepository.logLevel == SettingsRepository.LOG_LEVEL_NONE) {
                getString(R.string.log_none_short)
            } else {
                getString(R.string.log_empty_short)
            }
        } else {
            logOutputHasLines = true
            binding.tvLogOutput.text = logs.joinToString("\n") + "\n"
            binding.scrollLogOutput.post {
                binding.scrollLogOutput.fullScroll(View.FOCUS_DOWN)
            }
        }
    }

    private fun updateLogLevelChipsState() {        val currentLevel = settingsRepository.logLevel
        val preset = settingsRepository.themePreset
        val isLight = preset == SettingsRepository.THEME_LIGHT
        val density = resources.displayMetrics.density

        val hasWallpaper = !settingsRepository.customBgImagePath.isNullOrEmpty() && File(settingsRepository.customBgImagePath ?: "").exists()
        val isLightContext = !hasWallpaper && (isLight || (settingsRepository.customBgColor != 0 && ColorUtils.calculateLuminance(settingsRepository.customBgColor) > 0.5))

        val customAccent = settingsRepository.customConnectBtnColor
        val activeStrokeColor = if (customAccent != 0) customAccent else Color.parseColor("#E8A33D")
        val inactiveStrokeColor = if (isLightContext) Color.parseColor("#CBD5E1") else ContextCompat.getColor(this, R.color.card_stroke)
        val chipBaseColor = if (isLightContext) Color.WHITE else ContextCompat.getColor(this, R.color.card)

        val inactiveTextColor = if (settingsRepository.customTextColor != 0) {
            settingsRepository.customTextColor
        } else if (isLightContext) {
            Color.parseColor("#0F172A")
        } else {
            ContextCompat.getColor(this, R.color.ink)
        }

        val chips = listOf(
            Triple(SettingsRepository.LOG_LEVEL_AUTO, binding.chipLogAuto, binding.tvLogAuto),
            Triple(SettingsRepository.LOG_LEVEL_DEBUG, binding.chipLogDebug, binding.tvLogDebug),
            Triple(SettingsRepository.LOG_LEVEL_INFO, binding.chipLogInfo, binding.tvLogInfo),
            Triple(SettingsRepository.LOG_LEVEL_WARNING, binding.chipLogWarning, binding.tvLogWarning),
            Triple(SettingsRepository.LOG_LEVEL_ERROR, binding.chipLogError, binding.tvLogError),
            Triple(SettingsRepository.LOG_LEVEL_NONE, binding.chipLogNone, binding.tvLogNone)
        )

        chips.forEach { (level, chip, textView) ->
            val isSelected = (level == currentLevel)
            val drawable = GradientDrawable().apply {
                cornerRadius = 14f * density
                setColor(chipBaseColor)
                setStroke(
                    (if (isSelected) 2.5f * density else 1f * density).toInt(),
                    if (isSelected) activeStrokeColor else inactiveStrokeColor
                )
            }
            chip.background = drawable
            textView.setTextColor(if (isSelected) activeStrokeColor else inactiveTextColor)
        }

        val levelFormatted = currentLevel.replaceFirstChar { it.uppercase() }
        binding.tvLogsSettingDesc.text = getString(R.string.setting_logs_desc, levelFormatted)
    }

    private fun downloadLogs() {
        val level = settingsRepository.logLevel
        if (level == SettingsRepository.LOG_LEVEL_NONE && AppLogger.getAllLogs().isEmpty()) {
            Toast.makeText(this, R.string.toast_logs_disabled, Toast.LENGTH_SHORT).show()
            return
        }
        val content = AppLogger.getAllLogsFormatted()
        if (content.isBlank() || AppLogger.getAllLogs().isEmpty()) {
            Toast.makeText(this, R.string.toast_logs_empty, Toast.LENGTH_SHORT).show()
            return
        }

        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
            ) {
                storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                return
            }
        }
        performDownloadLogs()
    }

    private fun performDownloadLogs() {
        val level = settingsRepository.logLevel
        val timeStamp = java.text.SimpleDateFormat("dd-MM-yyyy_HH-mm-ss", java.util.Locale.getDefault()).format(java.util.Date())
        val fileName = "Log-NAUA-Security-Mirage-${timeStamp}-$level.txt"
        val content = AppLogger.getAllLogsFormatted() + "\n\n" + com.naua_security_mirage.app.util.LogHelper.collectLogs(this).readText()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val folderName = "NAUA Security Mirage Logs"
                val savedPath = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                        put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$folderName")
                    }
                    val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    if (uri != null) {
                        contentResolver.openOutputStream(uri)?.use { os ->
                            os.write(content.toByteArray(Charsets.UTF_8))
                        }
                        "Downloads/$folderName/$fileName"
                    } else {
                        throw Exception(getString(R.string.log_mediastore_error))
                    }
                } else {
                    val downloadsDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), folderName)
                    if (!downloadsDir.exists()) {
                        downloadsDir.mkdirs()
                    }
                    val file = File(downloadsDir, fileName)
                    file.writeText(content, Charsets.UTF_8)
                    file.absolutePath
                }

                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, getString(R.string.log_saved_fmt, savedPath), Toast.LENGTH_LONG).show()
                }
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, getString(R.string.log_save_error_fmt, e.message), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun exportSettings() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val prefs = getSharedPreferences("mirage_settings_prefs", MODE_PRIVATE)
                val json = org.json.JSONObject()
                for ((key, value) in prefs.all) {
                    when (value) {
                        is Boolean -> json.put(key, value)
                        is Int -> json.put(key, value)
                        is Long -> json.put(key, value)
                        is Float -> json.put(key, value.toDouble())
                        is String -> json.put(key, value)
                        is Set<*> -> json.put(key, org.json.JSONArray(value.filterIsInstance<String>()))
                    }
                }
                val wrapper = org.json.JSONObject()
                    .put("app", "NAUA Security Mirage")
                    .put("version", 1)
                    .put("settings", json)
                val content = wrapper.toString(2)
                val timeStamp = java.text.SimpleDateFormat("dd-MM-yyyy_HH-mm-ss", java.util.Locale.getDefault())
                    .format(java.util.Date())
                val fileName = "Mirage-Settings-$timeStamp.json"
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/json")
                    put(
                        android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                        "${android.os.Environment.DIRECTORY_DOWNLOADS}/NAUA Security Mirage"
                    )
                }
                val uri = contentResolver.insert(
                    android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                ) ?: throw IllegalStateException(getString(R.string.log_mediastore_error))
                contentResolver.openOutputStream(uri)?.use { os ->
                    os.write(content.toByteArray(Charsets.UTF_8))
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, R.string.backup_saved, Toast.LENGTH_LONG).show()
                }
                AppLogger.i("Backup", "Настройки выгружены: $fileName")
            } catch (e: Throwable) {
                AppLogger.w("Backup", "Выгрузка не удалась: ${e.message}")
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, R.string.backup_failed, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun importSettings(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val stream = contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("empty")
                val bytes = stream.use { input ->
                    val buffer = ByteArray(8192)
                    val out = java.io.ByteArrayOutputStream()
                    while (out.size() <= MAX_IMPORT_BYTES) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                    }
                    if (out.size() > MAX_IMPORT_BYTES) throw IllegalStateException("too large")
                    out.toByteArray()
                }
                val text = String(bytes, Charsets.UTF_8)
                val settings = org.json.JSONObject(text).optJSONObject("settings")
                    ?: throw IllegalStateException("no settings")
                val prefs = getSharedPreferences("mirage_settings_prefs", MODE_PRIVATE)
                val editor = prefs.edit()
                val keys = settings.keys()
                var count = 0
                while (keys.hasNext()) {
                    val key = keys.next()
                    when (val value = settings.opt(key)) {
                        is Boolean -> editor.putBoolean(key, value)
                        is Int -> editor.putInt(key, value)
                        is Long -> editor.putLong(key, value)
                        is Double -> editor.putFloat(key, value.toFloat())
                        is String -> editor.putString(key, value)
                        is org.json.JSONArray -> {
                            val set = mutableSetOf<String>()
                            for (i in 0 until value.length()) {
                                set.add(value.optString(i))
                            }
                            editor.putStringSet(key, set)
                        }
                    }
                    count++
                }
                editor.apply()
                AppLogger.i("Backup", "Настройки загружены: $count записей")
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, R.string.backup_restored, Toast.LENGTH_LONG).show()
                    recreate()
                }
            } catch (e: Throwable) {
                AppLogger.w("Backup", "Загрузка не удалась: ${e.message}")
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, R.string.backup_failed, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun shareLogs() {
        val level = settingsRepository.logLevel
        if (level == SettingsRepository.LOG_LEVEL_NONE && AppLogger.getAllLogs().isEmpty()) {
            Toast.makeText(this, R.string.toast_logs_disabled, Toast.LENGTH_SHORT).show()
            return
        }
        val content = AppLogger.getAllLogsFormatted() + "\n\n" + com.naua_security_mirage.app.util.LogHelper.collectLogs(this).readText()
        if (content.isBlank() || AppLogger.getAllLogs().isEmpty()) {
            Toast.makeText(this, R.string.toast_logs_empty, Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val fileName = "Log-NAUA-Security-Mirage-$level.txt"
                val cacheLogsDir = File(cacheDir, "mirage_logs")
                if (!cacheLogsDir.exists()) {
                    cacheLogsDir.mkdirs()
                }
                val logFile = File(cacheLogsDir, fileName)
                logFile.writeText(content, Charsets.UTF_8)

                val uri = FileProvider.getUriForFile(
                    this@MainActivity,
                    "${applicationContext.packageName}.fileprovider",
                    logFile
                )

                withContext(Dispatchers.Main) {
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_SUBJECT, fileName)
                        putExtra(Intent.EXTRA_TEXT, getString(R.string.log_share_subject_fmt, level))
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(shareIntent, getString(R.string.log_share_title)))
                }
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, getString(R.string.log_share_error_fmt, e.message), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun updatePerAppSettingDescription() {
        val bypassedCount = settingsRepository.bypassedAppPackages.size
        binding.tvPerAppSettingDesc.text = if (bypassedCount == 0) {
            getString(R.string.per_app_all_enabled)
        } else {
            getString(R.string.per_app_excluded_fmt, bypassedCount)
        }
    }

    private fun setupPerAppProxyUI() {
        appProxyAdapter = AppProxyAdapter(this) { app, isProxied ->
            settingsRepository.setAppProxied(app.packageName, isProxied)
            allInstalledApps = allInstalledApps.map {
                if (it.packageName == app.packageName) it.copy(isProxied = isProxied) else it
            }
            updatePerAppStats()
            updateToggleAllButtonState()
            notifySplitTunnelChanged()
        }
        binding.rvAppList.layoutManager = LinearLayoutManager(this)
        binding.rvAppList.adapter = appProxyAdapter

        binding.btnBackFromPerApp.setOnClickListener {
            AnimationHelper.bounceClick(binding.btnBackFromPerApp, minScale = 0.88f, durationMs = 180)
            closePerAppProxy()
        }

        binding.chipFilterAll.setOnClickListener {
            AnimationHelper.bounceClick(binding.chipFilterAll, minScale = 0.92f, durationMs = 150)
            perAppFilterMode = FILTER_ALL
            updatePerAppFilterChips()
            filterAndDisplayApps()
        }

        binding.chipFilterProxied.setOnClickListener {
            AnimationHelper.bounceClick(binding.chipFilterProxied, minScale = 0.92f, durationMs = 150)
            perAppFilterMode = FILTER_PROXIED
            updatePerAppFilterChips()
            filterAndDisplayApps()
        }

        binding.chipFilterBypassed.setOnClickListener {
            AnimationHelper.bounceClick(binding.chipFilterBypassed, minScale = 0.92f, durationMs = 150)
            perAppFilterMode = FILTER_BYPASSED
            updatePerAppFilterChips()
            filterAndDisplayApps()
        }

        binding.btnToggleAllApps.setOnClickListener {
            AnimationHelper.bounceClick(binding.btnToggleAllApps, minScale = 0.92f, durationMs = 150)
            val allProxied = allInstalledApps.isNotEmpty() && allInstalledApps.all { it.isProxied }
            val newProxied = !allProxied
            val allPkgs = allInstalledApps.map { it.packageName }
            settingsRepository.setAllAppsProxied(newProxied, allPkgs)
            allInstalledApps = allInstalledApps.map { it.copy(isProxied = newProxied) }
            filterAndDisplayApps(forceNotify = true)
            updatePerAppStats()
            updateToggleAllButtonState()
            notifySplitTunnelChanged()
        }

        binding.etSearchApps.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                perAppSearchQuery = s?.toString()?.trim() ?: ""
                binding.ivClearSearch.visibility = if (perAppSearchQuery.isNotEmpty()) View.VISIBLE else View.GONE
                filterAndDisplayApps()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.ivClearSearch.setOnClickListener {
            AnimationHelper.bounceClick(binding.ivClearSearch, minScale = 0.85f, durationMs = 150)
            binding.etSearchApps.setText("")
        }
    }

    private fun loadInstalledAppsIfNeeded() {
        if (allInstalledApps.isNotEmpty() || isAppsLoading) return
        isAppsLoading = true
        binding.pbAppsLoading.visibility = View.VISIBLE
        binding.rvAppList.visibility = View.GONE
        binding.tvEmptyAppsSearch.visibility = View.GONE
        binding.tvPerAppStats.text = getString(R.string.apps_loading)

        lifecycleScope.launch(Dispatchers.IO) {
            val pm = packageManager
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val resolveInfos = pm.queryIntentActivities(mainIntent, 0)
            val seenPackages = HashSet<String>()
            val resultList = ArrayList<AppInfo>()

            for (ri in resolveInfos) {
                val pkg = ri.activityInfo.packageName
                if (pkg == packageName) continue
                if (!seenPackages.add(pkg)) continue

                val appName = try {
                    ri.loadLabel(pm).toString()
                } catch (_: Exception) {
                    pkg
                }
                val appIcon = try {
                    ri.loadIcon(pm)
                } catch (_: Exception) {
                    null
                }
                val isSystem = try {
                    val appInfo = pm.getApplicationInfo(pkg, 0)
                    (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                } catch (_: Exception) {
                    false
                }
                val isProxied = settingsRepository.isAppProxied(pkg)
                resultList.add(AppInfo(name = appName, packageName = pkg, icon = appIcon, isSystem = isSystem, isProxied = isProxied))
            }

            resultList.sortBy { it.name.lowercase(Locale.getDefault()) }

            withContext(Dispatchers.Main) {
                allInstalledApps = resultList
                isAppsLoading = false
                binding.pbAppsLoading.visibility = View.GONE
                binding.rvAppList.visibility = View.VISIBLE
                filterAndDisplayApps()
                updatePerAppStats()
                updateToggleAllButtonState()
            }
        }
    }

    private fun filterAndDisplayApps(forceNotify: Boolean = false) {
        val query = perAppSearchQuery.lowercase(Locale.getDefault())
        val filtered = allInstalledApps.filter { app ->
            val matchesSearch = query.isEmpty() ||
                app.name.lowercase(Locale.getDefault()).contains(query) ||
                app.packageName.lowercase(Locale.getDefault()).contains(query)

            val matchesFilter = when (perAppFilterMode) {
                FILTER_PROXIED -> app.isProxied
                FILTER_BYPASSED -> !app.isProxied
                else -> true
            }

            matchesSearch && matchesFilter
        }
        appProxyAdapter?.submitList(filtered, forceNotify)
        binding.tvEmptyAppsSearch.visibility = if (filtered.isEmpty() && !isAppsLoading) View.VISIBLE else View.GONE
    }

    private fun showVpnActiveReconnectionNoticeIfNeeded() {
        splitTunnelDirty = true
        if (MirageVpnService.vpnState.value == VpnState.CONNECTED) {
            updateVpnActiveNoticeBanner(animated = true)
            updateWebsitesVpnNoticeBanner(animated = true)
            Toast.makeText(this, R.string.vpn_active_reconnect_notice, Toast.LENGTH_SHORT).show()
        }
    }

    private fun notifySplitTunnelChanged() {
        showVpnActiveReconnectionNoticeIfNeeded()
        if (MirageVpnService.vpnState.value != VpnState.CONNECTED) {
            Toast.makeText(this, R.string.vpn_change_applies_next_time, Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateVpnActiveNoticeBanner(animated: Boolean = false) {
        val isVpnConnected = MirageVpnService.vpnState.value == VpnState.CONNECTED
        if (isVpnConnected && splitTunnelDirty) {
            if (binding.bannerVpnActiveNotice.visibility != View.VISIBLE) {
                binding.bannerVpnActiveNotice.visibility = View.VISIBLE
                if (animated) {
                    AnimationHelper.popIn(binding.bannerVpnActiveNotice, durationMs = 220)
                }
            }
        } else {
            binding.bannerVpnActiveNotice.visibility = View.GONE
        }
    }

    private fun updatePerAppStats() {
        val total = allInstalledApps.size
        val proxied = allInstalledApps.count { it.isProxied }
        val bypassed = total - proxied
        binding.tvPerAppStats.text = getString(R.string.per_app_stats_fmt, total, proxied, bypassed)
        binding.tvFilterAll.text = getString(R.string.filter_all_fmt, total)
        binding.tvFilterProxied.text = getString(R.string.filter_proxied_fmt, proxied)
        binding.tvFilterBypassed.text = getString(R.string.filter_bypassed_fmt, bypassed)
        updatePerAppSettingDescription()
    }

    private fun updateToggleAllButtonState() {
        val allProxied = allInstalledApps.isNotEmpty() && allInstalledApps.all { it.isProxied }
        binding.tvToggleAllApps.text = if (allProxied) getString(R.string.action_exclude_all) else getString(R.string.action_select_all)
    }

    private fun updatePerAppFilterChips() {
        val isLight = settingsRepository.themePreset == SettingsRepository.THEME_LIGHT
        val hasWallpaper = !settingsRepository.customBgImagePath.isNullOrEmpty() && File(settingsRepository.customBgImagePath ?: "").exists()
        val isLightContext = !hasWallpaper && (isLight || (settingsRepository.customBgColor != 0 && ColorUtils.calculateLuminance(settingsRepository.customBgColor) > 0.5))
        val density = resources.displayMetrics.density

        val customAccent = settingsRepository.customConnectBtnColor
        val activeStrokeColor = if (customAccent != 0) customAccent else Color.parseColor("#E8A33D")
        val inactiveStrokeColor = if (isLightContext) Color.parseColor("#CBD5E1") else ContextCompat.getColor(this, R.color.card_stroke)
        val chipBaseColor = if (isLightContext) Color.WHITE else ContextCompat.getColor(this, R.color.card)

        val chips = listOf(
            Triple(binding.chipFilterAll, binding.tvFilterAll, perAppFilterMode == FILTER_ALL),
            Triple(binding.chipFilterProxied, binding.tvFilterProxied, perAppFilterMode == FILTER_PROXIED),
            Triple(binding.chipFilterBypassed, binding.tvFilterBypassed, perAppFilterMode == FILTER_BYPASSED)
        )

        for ((chip, text, isActive) in chips) {
            val bg = GradientDrawable().apply {
                cornerRadius = 12f * density
                setColor(chipBaseColor)
                if (isActive) {
                    setStroke((2f * density).toInt(), activeStrokeColor)
                } else {
                    setStroke((1f * density).toInt(), inactiveStrokeColor)
                }
            }
            chip.background = bg
            if (isActive) {
                text.setTextColor(activeStrokeColor)
            } else {
                val inactiveTextColor = if (settingsRepository.customTextColor != 0) {
                    ColorUtils.setAlphaComponent(settingsRepository.customTextColor, 210)
                } else if (isLightContext) {
                    Color.parseColor("#475569")
                } else {
                    ContextCompat.getColor(this, R.color.ink)
                }
                text.setTextColor(inactiveTextColor)
            }
        }
    }

    private fun updateWebsitesVpnNoticeBanner(animated: Boolean = false) {
        val isVpnConnected = MirageVpnService.vpnState.value == VpnState.CONNECTED
        if (isVpnConnected && splitTunnelDirty) {
            if (binding.bannerWebsitesVpnNotice.visibility != View.VISIBLE) {
                binding.bannerWebsitesVpnNotice.visibility = View.VISIBLE
                if (animated) {
                    AnimationHelper.popIn(binding.bannerWebsitesVpnNotice, durationMs = 220)
                }
            }
        } else {
            binding.bannerWebsitesVpnNotice.visibility = View.GONE
        }
    }

    private fun cleanDomain(rawInput: String): String? {
        var domain = rawInput.trim().lowercase(Locale.getDefault())
        if (domain.startsWith("http://")) domain = domain.removePrefix("http://")
        if (domain.startsWith("https://")) domain = domain.removePrefix("https://")
        val slashIdx = domain.indexOf('/')
        if (slashIdx != -1) domain = domain.substring(0, slashIdx)
        val colonIdx = domain.indexOf(':')
        if (colonIdx != -1) domain = domain.substring(0, colonIdx)
        if (domain.startsWith("www.")) domain = domain.removePrefix("www.")
        domain = domain.trim().trimEnd('.')

        if (domain.length < 3 || !domain.contains('.') || domain.contains(' ') || domain.contains('|') || domain.contains('\n') || domain.startsWith('.') || domain.endsWith('.')) {
            return null
        }
        return domain
    }

    private fun updateCustomWebsitesSettingDescription() {
        val sites = settingsRepository.getCustomWebsites()
        val activeCount = sites.count { it.isEnabled }
        binding.tvCustomWebsitesDesc.text = if (sites.isEmpty()) {
            getString(R.string.setting_custom_websites_proxy_desc)
        } else {
            getString(R.string.sites_excluded_fmt, activeCount, sites.size)
        }
    }

    private fun loadCustomWebsites() {
        allCustomWebsites = settingsRepository.getCustomWebsites()
        filterAndDisplayWebsites()
        updateCustomWebsitesStats()
    }

    private fun filterAndDisplayWebsites(forceNotify: Boolean = false) {
        val query = customWebsiteSearchQuery.lowercase(Locale.getDefault())
        val filtered = allCustomWebsites.filter { site ->
            val matchesSearch = query.isEmpty() || site.domain.lowercase(Locale.getDefault()).contains(query)
            val matchesFilter = when (customWebsiteFilterMode) {
                FILTER_PROXIED -> site.isEnabled
                FILTER_BYPASSED -> !site.isEnabled
                else -> true
            }
            matchesSearch && matchesFilter
        }
        customWebsiteAdapter?.submitList(filtered, forceNotify)
        binding.tvEmptyWebsites.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun updateCustomWebsitesStats() {
        val total = allCustomWebsites.size
        val active = allCustomWebsites.count { it.isEnabled }
        val disabled = total - active
        binding.tvCustomWebsitesHeaderSubtitle.text = getString(R.string.sites_subtitle_fmt, total, active, disabled)
        binding.tvFilterAllSites.text = getString(R.string.filter_all_fmt, total)
        binding.tvFilterActiveSites.text = getString(R.string.filter_proxied_fmt, active)
        binding.tvFilterDisabledSites.text = getString(R.string.filter_disabled_fmt, disabled)
        updateCustomWebsitesSettingDescription()
    }

    private fun setupCustomWebsitesUI() {
        customWebsiteAdapter = CustomWebsiteAdapter(
            context = this,
            onToggle = { site, isEnabled ->
                settingsRepository.setCustomWebsiteEnabled(site.domain, isEnabled)
                allCustomWebsites = allCustomWebsites.map {
                    if (it.domain.equals(site.domain, ignoreCase = true)) it.copy(isEnabled = isEnabled) else it
                }
                updateCustomWebsitesStats()
                filterAndDisplayWebsites()
                notifySplitTunnelChanged()
            },
            onDelete = { site ->
                settingsRepository.removeCustomWebsite(site.domain)
                allCustomWebsites = allCustomWebsites.filterNot { it.domain.equals(site.domain, ignoreCase = true) }
                updateCustomWebsitesStats()
                filterAndDisplayWebsites(forceNotify = true)
                notifySplitTunnelChanged()
                Toast.makeText(this, R.string.custom_website_removed, Toast.LENGTH_SHORT).show()
            }
        )

        binding.rvCustomWebsites.layoutManager = LinearLayoutManager(this)
        binding.rvCustomWebsites.adapter = customWebsiteAdapter

        binding.btnBackFromCustomWebsites.setOnClickListener {
            AnimationHelper.bounceClick(binding.btnBackFromCustomWebsites, minScale = 0.88f, durationMs = 180)
            closeCustomWebsites()
        }

        fun tryAddWebsite() {
            val raw = binding.etAddWebsite.text?.toString() ?: ""
            val cleaned = cleanDomain(raw)
            if (cleaned == null) {
                Toast.makeText(this, R.string.custom_website_invalid, Toast.LENGTH_SHORT).show()
                return
            }
            val added = settingsRepository.addCustomWebsite(cleaned)
            if (!added) {
                Toast.makeText(this, R.string.custom_website_exists, Toast.LENGTH_SHORT).show()
                return
            }
            val persisted = settingsRepository.getCustomWebsites().any { it.domain.equals(cleaned, ignoreCase = true) }
            if (!persisted) {
                AppLogger.e("Websites", "Сайт не сохранился после добавления")
                Toast.makeText(this, R.string.custom_website_save_failed, Toast.LENGTH_SHORT).show()
                return
            }
            binding.etAddWebsite.setText("")
            customWebsiteFilterMode = FILTER_ALL
            customWebsiteSearchQuery = ""
            binding.etSearchWebsites.setText("")
            updateWebsiteFilterChips()
            val imm = getSystemService(INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
            imm?.hideSoftInputFromWindow(binding.etAddWebsite.windowToken, 0)
            loadCustomWebsites()
            notifySplitTunnelChanged()
            Toast.makeText(this, R.string.custom_website_added, Toast.LENGTH_SHORT).show()
        }

        binding.btnAddWebsite.setOnClickListener {
            AnimationHelper.bounceClick(binding.btnAddWebsite, minScale = 0.92f, durationMs = 150)
            tryAddWebsite()
        }

        binding.etAddWebsite.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_GO) {
                tryAddWebsite()
                true
            } else {
                false
            }
        }

        binding.etSearchWebsites.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                customWebsiteSearchQuery = s?.toString()?.trim() ?: ""
                binding.ivClearSearchWebsites.visibility = if (customWebsiteSearchQuery.isNotEmpty()) View.VISIBLE else View.GONE
                filterAndDisplayWebsites()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.ivClearSearchWebsites.setOnClickListener {
            AnimationHelper.bounceClick(binding.ivClearSearchWebsites, minScale = 0.85f, durationMs = 150)
            binding.etSearchWebsites.setText("")
        }

        binding.chipFilterAllSites.setOnClickListener {
            AnimationHelper.bounceClick(binding.chipFilterAllSites, minScale = 0.92f, durationMs = 150)
            customWebsiteFilterMode = FILTER_ALL
            updateWebsiteFilterChips()
            filterAndDisplayWebsites()
        }

        binding.chipFilterActiveSites.setOnClickListener {
            AnimationHelper.bounceClick(binding.chipFilterActiveSites, minScale = 0.92f, durationMs = 150)
            customWebsiteFilterMode = FILTER_PROXIED
            updateWebsiteFilterChips()
            filterAndDisplayWebsites()
        }

        binding.chipFilterDisabledSites.setOnClickListener {
            AnimationHelper.bounceClick(binding.chipFilterDisabledSites, minScale = 0.92f, durationMs = 150)
            customWebsiteFilterMode = FILTER_BYPASSED
            updateWebsiteFilterChips()
            filterAndDisplayWebsites()
        }

        binding.btnClearAllSites.setOnClickListener {
            AnimationHelper.bounceClick(binding.btnClearAllSites, minScale = 0.92f, durationMs = 150)
            if (allCustomWebsites.isEmpty()) return@setOnClickListener
            settingsRepository.clearCustomWebsites()
            loadCustomWebsites()
            notifySplitTunnelChanged()
            Toast.makeText(this, R.string.custom_websites_cleared, Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateWebsiteFilterChips() {
        val isLight = settingsRepository.themePreset == SettingsRepository.THEME_LIGHT
        val hasWallpaper = !settingsRepository.customBgImagePath.isNullOrEmpty() && File(settingsRepository.customBgImagePath ?: "").exists()
        val isLightContext = !hasWallpaper && (isLight || (settingsRepository.customBgColor != 0 && ColorUtils.calculateLuminance(settingsRepository.customBgColor) > 0.5))
        val density = resources.displayMetrics.density

        val customAccent = settingsRepository.customConnectBtnColor
        val activeStrokeColor = if (customAccent != 0) customAccent else Color.parseColor("#E8A33D")
        val inactiveStrokeColor = if (isLightContext) Color.parseColor("#CBD5E1") else ContextCompat.getColor(this, R.color.card_stroke)
        val chipBaseColor = if (isLightContext) Color.WHITE else ContextCompat.getColor(this, R.color.card)

        val chips = listOf(
            Triple(binding.chipFilterAllSites, binding.tvFilterAllSites, customWebsiteFilterMode == FILTER_ALL),
            Triple(binding.chipFilterActiveSites, binding.tvFilterActiveSites, customWebsiteFilterMode == FILTER_PROXIED),
            Triple(binding.chipFilterDisabledSites, binding.tvFilterDisabledSites, customWebsiteFilterMode == FILTER_BYPASSED)
        )

        for ((chip, text, isActive) in chips) {
            val bg = GradientDrawable().apply {
                cornerRadius = 12f * density
                setColor(chipBaseColor)
                if (isActive) {
                    setStroke((2f * density).toInt(), activeStrokeColor)
                } else {
                    setStroke((1f * density).toInt(), inactiveStrokeColor)
                }
            }
            chip.background = bg
            if (isActive) {
                text.setTextColor(activeStrokeColor)
            } else {
                val inactiveTextColor = if (settingsRepository.customTextColor != 0) {
                    ColorUtils.setAlphaComponent(settingsRepository.customTextColor, 210)
                } else if (isLightContext) {
                    Color.parseColor("#475569")
                } else {
                    ContextCompat.getColor(this, R.color.ink)
                }
                text.setTextColor(inactiveTextColor)
            }
        }
    }

    private var pillAiMode: LinearLayout? = null

    private var aiModePendingView: TextView? = null

    private var aiModePending = false

    private var splitTunnelDirty = false


    private data class ToggleRow(
        val row: View,
        val icon: ImageView,
        val title: TextView,
        val desc: TextView,
        val divider: View?,
        val switch: MirageSwitch
    )

    private data class ChevronRow(
        val row: View,
        val icon: ImageView,
        val title: TextView,
        val desc: TextView
    )

    private fun addChevronRow(
        card: LinearLayout,
        iconRes: Int,
        titleRes: Int,
        descRes: Int,
        anchor: View? = null
    ): ChevronRow {
        val density = resources.displayMetrics.density
        val divider = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (1f * density).toInt().coerceAtLeast(1)
            ).apply {
                marginStart = (16f * density).toInt()
                marginEnd = (16f * density).toInt()
            }
        }
        val row = layoutInflater.inflate(R.layout.row_chevron, card, false)
        if (anchor != null) {
            val anchorIndex = card.indexOfChild(anchor)
            if (anchorIndex >= 0) {
                card.addView(divider, anchorIndex + 1)
                card.addView(row, anchorIndex + 2)
            } else {
                card.addView(divider)
                card.addView(row)
            }
        } else {
            card.addView(divider)
            card.addView(row)
        }
        val arrow = row.findViewById<View>(R.id.rowChevronArrow)
        val holder = ChevronRow(
            row = row,
            icon = row.findViewById(R.id.rowChevronIcon),
            title = row.findViewById(R.id.rowChevronTitle),
            desc = row.findViewById(R.id.rowChevronDesc)
        )
        holder.icon.setImageResource(iconRes)
        holder.title.setText(titleRes)
        holder.desc.setText(descRes)
        arrow?.visibility = View.GONE
        row.id = View.NO_ID
        holder.icon.id = View.NO_ID
        holder.title.id = View.NO_ID
        holder.desc.id = View.NO_ID
        arrow?.id = View.NO_ID
        return holder
    }

    private fun obtainChevronRow(
        existing: ChevronRow?,
        card: LinearLayout,
        iconRes: Int,
        titleRes: Int,
        descRes: Int,
        anchor: View?,
        onClick: () -> Unit
    ): ChevronRow {
        val holder = if (existing != null && existing.row.parent != null) {
            existing.title.setText(titleRes)
            existing.desc.setText(descRes)
            existing
        } else {
            addChevronRow(card, iconRes, titleRes, descRes, anchor)
        }
        holder.icon.setImageResource(iconRes)
        holder.row.setOnClickListener {
            AnimationHelper.bounceClick(holder.row, minScale = 0.98f, durationMs = 140)
            onClick()
        }
        return holder
    }

    private fun ensureCardRows() {
        val card = cardBackup ?: return
        rowExportSettings = obtainChevronRow(
            rowExportSettings,
            card,
            R.drawable.ic_download,
            R.string.backup_export,
            R.string.backup_export_desc,
            null
        ) { exportSettings() }
        rowImportSettings = obtainChevronRow(
            rowImportSettings,
            card,
            R.drawable.ic_share,
            R.string.backup_import,
            R.string.backup_import_desc,
            null
        ) { importSettingsLauncher.launch("application/json") }
    }

    private fun logCardChildren(name: String, card: ViewGroup) {
        val summary = (0 until card.childCount).joinToString(" | ") { i ->
            val child = card.getChildAt(i)
            var label = "${child.javaClass.simpleName}:${child.visibility}"
            val title = findRowTitle(child)?.take(28)
            if (!title.isNullOrBlank()) label += "[$title]"
            label
        }
        AppLogger.i("UI", "$name children(${card.childCount}): $summary")
    }

    private fun findRowTitle(view: View): String? {
        if (view is TextView) {
            val text = view.text?.toString()
            if (!text.isNullOrBlank()) return text
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val found = findRowTitle(view.getChildAt(i))
                if (!found.isNullOrBlank()) return found
            }
        }
        return null
    }

    private var rowPayment: ToggleRow? = null
    private var rowScreenshots: ToggleRow? = null
    private var rowAnimations: ToggleRow? = null
    private var rowExportSettings: ChevronRow? = null
    private var rowImportSettings: ChevronRow? = null
    private var cardAnimations: LinearLayout? = null
    private var animationsSectionTitle: TextView? = null
    private var cardBackup: LinearLayout? = null
    private var backupSectionTitle: TextView? = null
    private val injectedSectionTitles = mutableListOf<TextView>()

    private fun addToggleRow(
        card: LinearLayout,
        iconRes: Int,
        titleRes: Int,
        descRes: Int,
        withDivider: Boolean = true,
        anchor: View? = null
    ): ToggleRow {
        val density = resources.displayMetrics.density
        var divider: View? = null
        if (withDivider) {
            divider = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (1f * density).toInt().coerceAtLeast(1)
                ).apply {
                    marginStart = (16f * density).toInt()
                    marginEnd = (16f * density).toInt()
                }
            }
        }
        val row = layoutInflater.inflate(R.layout.row_setting_toggle, card, false)
        if (anchor != null) {
            val anchorIndex = card.indexOfChild(anchor)
            if (anchorIndex >= 0) {
                if (divider != null) card.addView(divider, anchorIndex + 1)
                card.addView(row, anchorIndex + (if (divider != null) 2 else 1))
            } else {
                if (divider != null) card.addView(divider)
                card.addView(row)
            }
        } else {
            if (divider != null) card.addView(divider)
            card.addView(row)
        }
        val holder = ToggleRow(
            row = row,
            icon = row.findViewById(R.id.rowToggleIcon),
            title = row.findViewById(R.id.rowToggleTitle),
            desc = row.findViewById(R.id.rowToggleDesc),
            divider = divider,
            switch = row.findViewById(R.id.rowToggleSwitch)
        )
        holder.icon.setImageResource(iconRes)
        holder.title.setText(titleRes)
        holder.desc.setText(descRes)
        return holder
    }

    private fun addSettingsSection(
        parent: ViewGroup,
        anchor: View,
        titleRes: Int
    ): Pair<LinearLayout, TextView> {
        val density = resources.displayMetrics.density
        val title = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (16f * density).toInt()
                bottomMargin = (8f * density).toInt()
            }
            setPadding((24f * density).toInt(), 0, (24f * density).toInt(), 0)
            setText(titleRes)
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
        }
        val card = LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = (20f * density).toInt()
                marginEnd = (20f * density).toInt()
                bottomMargin = (12f * density).toInt()
            }
            orientation = LinearLayout.VERTICAL
        }
        val anchorIndex = parent.indexOfChild(anchor)
        parent.addView(title, anchorIndex + 1)
        parent.addView(card, anchorIndex + 2)
        injectedSectionTitles.add(title)
        return card to title
    }

    private fun setupInjectedRows() {
        rowPayment = addToggleRow(
            binding.cardNotification,
            R.drawable.ic_notification,
            R.string.setting_payment_notification,
            R.string.setting_payment_notification_desc
        ).also { holder ->
            holder.switch.isChecked = settingsRepository.isPaymentNotificationEnabled
            holder.switch.setOnCheckedChangeListener { _, checked ->
                settingsRepository.isPaymentNotificationEnabled = checked
                MirageNotifyTileService.requestUpdate(this)
            }
            holder.row.setOnClickListener {
                AnimationHelper.bounceClick(holder.row, minScale = 0.98f, durationMs = 140)
                holder.switch.toggle()
            }
        }

        rowScreenshots = addToggleRow(
            binding.cardKillSwitch,
            R.drawable.ic_image,
            R.string.setting_screenshots,
            R.string.setting_screenshots_desc,
            anchor = binding.rowSystemVpn
        ).also { holder ->
            holder.switch.isChecked = !settingsRepository.isScreenshotBlockingEnabled
            holder.switch.setOnCheckedChangeListener { _, allowed ->
                if (allowed) {
                    showScreenshotWarningDialog(onAllow = {
                        settingsRepository.isScreenshotBlockingEnabled = false
                        applyScreenshotBlocking()
                        holder.switch.isChecked = true
                        holder.desc.setText(R.string.setting_screenshots_allowed)
                        Toast.makeText(this, R.string.screenshot_warning_allowed_toast, Toast.LENGTH_LONG).show()
                    }, onKeep = {
                        settingsRepository.isScreenshotBlockingEnabled = true
                        holder.switch.isChecked = false
                        holder.desc.setText(R.string.setting_screenshots_desc)
                    })
                    holder.switch.isChecked = false
                    holder.desc.setText(R.string.setting_screenshots_desc)
                } else {
                    settingsRepository.isScreenshotBlockingEnabled = true
                    applyScreenshotBlocking()
                    holder.desc.setText(R.string.setting_screenshots_desc)
                }
            }
            holder.row.setOnClickListener {
                AnimationHelper.bounceClick(holder.row, minScale = 0.98f, durationMs = 140)
                holder.switch.toggle()
            }
        }

        val settingsRoot = binding.cardAppearance.parent as ViewGroup
        val (backupCard, backupTitle) = addSettingsSection(
            settingsRoot,
            binding.cardAppearance,
            R.string.section_backup
        )
        cardBackup = backupCard
        backupSectionTitle = backupTitle
        val (animationsCard, animationsTitle) = addSettingsSection(
            settingsRoot,
            binding.cardAppearance,
            R.string.section_animations
        )
        cardAnimations = animationsCard
        animationsSectionTitle = animationsTitle
        rowAnimations = addToggleRow(
            animationsCard,
            R.drawable.ic_sparkle,
            R.string.setting_animations,
            R.string.setting_animations_desc,
            withDivider = false
        ).also { holder ->
            holder.switch.isChecked = settingsRepository.areAnimationsEnabled
            holder.switch.setOnCheckedChangeListener { _, checked ->
                settingsRepository.areAnimationsEnabled = checked
                AnimationHelper.animationsEnabled = checked
                holder.desc.setText(
                    if (checked) R.string.setting_animations_desc else R.string.setting_animations_off
                )
            }
            holder.row.setOnClickListener {
                AnimationHelper.bounceClick(holder.row, minScale = 0.98f, durationMs = 140)
                holder.switch.toggle()
            }
        }

        rowExportSettings = obtainChevronRow(
            rowExportSettings,
            backupCard,
            R.drawable.ic_download,
            R.string.backup_export,
            R.string.backup_export_desc,
            null
        ) { exportSettings() }

        rowImportSettings = obtainChevronRow(
            rowImportSettings,
            backupCard,
            R.drawable.ic_share,
            R.string.backup_import,
            R.string.backup_import_desc,
            null
        ) { importSettingsLauncher.launch("application/json") }

        logCardChildren("cardBackup", backupCard)

        setupHideSectionsEntry()

        applyCurrentAppearance()
    }

    private fun setAppLanguage(lang: String) {
        if (settingsRepository.appLanguage == lang) return
        settingsRepository.appLanguage = lang
        androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(
            androidx.core.os.LocaleListCompat.forLanguageTags(lang)
        )
        MirageTileService.requestUpdate(this)
        MirageNotifyTileService.requestUpdate(this)
    }

    private data class SettingsGroup(
        val id: String,
        val titleRes: Int,
        val hideable: Boolean = true,
        val reorderable: Boolean = true
    )

    private data class SectionSub(val key: String, val titleRes: Int)

    private val settingsGroups = listOf(
        SettingsGroup("account", R.string.account_title),
        SettingsGroup("notifications", R.string.section_notifications),
        SettingsGroup("privacy", R.string.section_privacy),
        SettingsGroup("appearance", R.string.section_appearance, hideable = false),
        SettingsGroup("backup", R.string.section_backup),
        SettingsGroup("animations", R.string.section_animations),
        SettingsGroup("split", R.string.section_per_app_proxy),
        SettingsGroup("diagnostics", R.string.section_diagnostics),
        SettingsGroup("updates", R.string.section_updates)
    )

    private var rowHideSectionsIcon: ImageView? = null
    private var rowHideSectionsTitle: TextView? = null
    private var rowHideSectionsDesc: TextView? = null
    private var rowHideSectionsArrow: ImageView? = null
    private var rowHideSectionsRow: View? = null

    private fun settingsGroupViews(id: String): List<View> = when (id) {
        "account" -> listOfNotNull(binding.tvSectionAccount, binding.cardAccount.root)
        "notifications" -> listOfNotNull(binding.tvSectionNotifications, binding.cardNotification)
        "privacy" -> listOfNotNull(binding.tvSectionPrivacy, binding.cardKillSwitch)
        "appearance" -> listOfNotNull(
            binding.tvSectionAppearance,
            binding.cardAppearance
        )
        "animations" -> listOfNotNull(animationsSectionTitle, cardAnimations)
        "backup" -> listOfNotNull(backupSectionTitle, cardBackup)
        "split" -> listOfNotNull(binding.tvSectionPerAppProxy, binding.cardDirectRu, privateDnsCard)
        "diagnostics" -> listOfNotNull(binding.tvSectionLogs, nodeStatusCard, binding.cardSpeed)
        "updates" -> listOfNotNull(binding.tvSectionUpdates, binding.cardCheckUpdates, binding.btnCheckUpdatesNow)
        else -> emptyList()
    }

    private fun settingsGroupOrder(): List<String> {
        val known = settingsGroups.map { it.id }
        val saved = settingsRepository.sectionOrder.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() && it in known }
        return saved + known.filter { it !in saved }
    }

    private fun sectionSubs(groupId: String): List<SectionSub> = when (groupId) {
        "account" -> listOf(SectionSub("sub_subscribe", R.string.sub_renew_short))
        "notifications" -> listOf(
            SectionSub("sub_status", R.string.setting_status_change),
            SectionSub("sub_payment", R.string.setting_payment_notification),
            SectionSub("sub_tile", R.string.setting_quick_tile)
        )
        "privacy" -> listOf(
            SectionSub("sub_kill", R.string.setting_kill_switch),
            SectionSub("sub_sysvpn", R.string.action_system_vpn),
            SectionSub("sub_shots", R.string.setting_screenshots),
            SectionSub("sub_auto", R.string.setting_auto_start),
            SectionSub("sub_telemetry", R.string.setting_telemetry)
        )
        "appearance" -> listOf(SectionSub("sub_appear", R.string.setting_appearance))
        "backup" -> listOf(
            SectionSub("sub_backup_export", R.string.backup_export),
            SectionSub("sub_backup_import", R.string.backup_import)
        )
        "animations" -> listOf(SectionSub("sub_anims", R.string.setting_animations))
        "split" -> listOf(
            SectionSub("sub_direct", R.string.setting_direct_ru),
            SectionSub("sub_perapp", R.string.setting_per_app_proxy),
            SectionSub("sub_sites", R.string.setting_custom_websites_proxy),
            SectionSub("sub_dns", R.string.private_dns_title)
        )
        "diagnostics" -> listOf(
            SectionSub("sub_free", R.string.free_nodes_title),
            SectionSub("sub_paid", R.string.node_status_title),
            SectionSub("sub_hist", R.string.node_history_title),
            SectionSub("sub_sessions", R.string.session_history_title),
            SectionSub("sub_speed", R.string.setting_speed),
            SectionSub("sub_ping", R.string.setting_auto_ping),
            SectionSub("sub_logs", R.string.setting_logs)
        )
        "updates" -> listOf(
            SectionSub("sub_source", R.string.setting_updates),
            SectionSub("sub_checknow", R.string.btn_check_updates_now)
        )
        else -> emptyList()
    }

    private fun sectionSubViews(key: String): List<View> = when (key) {
        "sub_subscribe" -> listOfNotNull(binding.cardAccount.btnAccountSubscribe)
        "sub_status" -> listOfNotNull(binding.rowStatusNotification)
        "sub_payment" -> listOfNotNull(rowPayment?.row)
        "sub_tile" -> listOfNotNull(binding.rowQuickSettingsTile)
        "sub_kill" -> listOfNotNull(binding.rowKillSwitch)
        "sub_sysvpn" -> listOfNotNull(binding.rowSystemVpn)
        "sub_shots" -> listOfNotNull(rowScreenshots?.row)
        "sub_auto" -> listOfNotNull(binding.rowAutoStart)
        "sub_telemetry" -> listOfNotNull(binding.rowTelemetry)
        "sub_appear" -> listOfNotNull(binding.rowAppearance)
        "sub_backup_export" -> listOfNotNull(rowExportSettings?.row)
        "sub_backup_import" -> listOfNotNull(rowImportSettings?.row)
        "sub_anims" -> listOfNotNull(rowAnimations?.row)
        "sub_direct" -> listOfNotNull(binding.rowDirectRu)
        "sub_perapp" -> listOfNotNull(binding.cardPerAppProxy)
        "sub_sites" -> listOfNotNull(binding.cardCustomWebsites)
        "sub_dns" -> listOfNotNull(privateDnsCard)
        "sub_free" -> listOfNotNull(nodeStatusCard?.findViewById(R.id.rowFreeNodes))
        "sub_paid" -> listOfNotNull(nodeStatusCard?.findViewById(R.id.rowPaidNode))
        "sub_hist" -> listOfNotNull(nodeStatusCard?.findViewById(R.id.rowNodeHistory))
        "sub_sessions" -> listOfNotNull(nodeStatusCard?.findViewById(R.id.rowSessionHistory))
        "sub_speed" -> listOfNotNull(binding.rowSpeed, binding.containerSpeedExpand)
        "sub_ping" -> listOfNotNull(binding.rowAutoPing, binding.containerAutoPingExpand)
        "sub_logs" -> listOfNotNull(binding.cardLogs)
        "sub_source" -> listOfNotNull(binding.cardCheckUpdates)
        "sub_checknow" -> listOfNotNull(binding.btnCheckUpdatesNow)
        else -> emptyList()
    }

    private fun placeView(container: ViewGroup, view: View, index: Int = -1) {
        (view.parent as? ViewGroup)?.removeView(view)
        val at = if (index < 0) container.childCount else index.coerceIn(0, container.childCount)
        container.addView(view, at)
    }

    private fun applySettingsGroups() {
        val container = binding.viewSettings.getChildAt(0) as? ViewGroup ?: return
        val hidden = settingsRepository.hiddenSections
        for (id in settingsGroupOrder()) {
            val group = settingsGroups.first { it.id == id }
            val groupGone = hidden.contains(id)
            for (view in settingsGroupViews(id)) {
                view.visibility = if (groupGone) View.GONE else View.VISIBLE
                if (!group.reorderable) continue
                val parent = view.parent as? ViewGroup ?: continue
                if (parent !== container) continue
                parent.removeView(view)
                val at = parent.indexOfChild(binding.spacerSettingsFooter)
                placeView(parent, view, at)
            }
        }
        for (footer in listOfNotNull<View>(
            binding.spacerSettingsFooter,
            binding.btnTermsOfService.parent as? View,
            binding.tvAppVersion
        )) {
            placeView(container, footer)
        }
        for (group in settingsGroups) {
            if (hidden.contains(group.id)) continue
            for (sub in sectionSubs(group.id)) {
                val subGone = hidden.contains(sub.key)
                for (view in sectionSubViews(sub.key)) {
                    val keepShut = (sub.key == "sub_speed" && view === binding.containerSpeedExpand && !isSpeedExpanded) ||
                        (sub.key == "sub_ping" && view === binding.containerAutoPingExpand && !isAutoPingExpanded)
                    view.visibility = if (subGone || keepShut) View.GONE else View.VISIBLE
                }
            }
        }
        cleanupDividers(binding.cardNotification)
        cleanupDividers(binding.cardKillSwitch)
        cleanupDividers(binding.cardDirectRu)
        cleanupDividers(binding.cardSpeed)
        cleanupDividers(binding.cardAppearance)
        cardBackup?.let(::cleanupDividers)
        (privateDnsCard as? ViewGroup)?.let(::cleanupDividers)
        (nodeStatusCard as? ViewGroup)?.let(::cleanupDividers)
    }

    private fun restyleAllCardDividers() {
        cleanupDividers(binding.cardNotification)
        cleanupDividers(binding.cardKillSwitch)
        cleanupDividers(binding.cardDirectRu)
        cleanupDividers(binding.cardSpeed)
        cleanupDividers(binding.cardAppearance)
        cardBackup?.let(::cleanupDividers)
        (privateDnsCard as? ViewGroup)?.let(::cleanupDividers)
        (nodeStatusCard as? ViewGroup)?.let(::cleanupDividers)
        cleanupDividers(binding.cardAccount.root)
    }

    private fun paintNestedDividers(view: View, maxPx: Int, stroke: Int) {
        if (view.visibility == View.GONE) return
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                paintNestedDividers(view.getChildAt(index), maxPx, stroke)
            }
            return
        }
        if (view.javaClass != View::class.java) return
        val height = view.layoutParams?.height ?: return
        if (height in 1..maxPx) view.setBackgroundColor(stroke)
    }

    private fun cleanupDividers(card: ViewGroup) {
        val density = resources.displayMetrics.density
        val maxPx = (2f * density).toInt().coerceAtLeast(2)
        val stroke = currentCardStrokeColor()
        val kids = (0 until card.childCount).map { card.getChildAt(it) }
        fun isDivider(view: View): Boolean {
            if (view.javaClass != View::class.java) return false
            val height = view.layoutParams?.height ?: return false
            return height in 1..maxPx
        }
        fun gone(view: View) = view.visibility == View.GONE
        kids.forEachIndexed { index, view ->
            if (!isDivider(view)) return@forEachIndexed
            val above = kids.take(index).lastOrNull { !isDivider(it) && !gone(it) }
            val below = kids.drop(index + 1).firstOrNull { !isDivider(it) && !gone(it) }
            val keep = above != null && below != null
            view.visibility = if (keep) View.VISIBLE else View.GONE
            if (keep) view.setBackgroundColor(stroke)
        }
        paintNestedDividers(card, maxPx, stroke)
    }

    private fun currentCardStrokeColor(): Int {
        val hasWallpaper = !settingsRepository.customBgImagePath.isNullOrEmpty() &&
                java.io.File(settingsRepository.customBgImagePath ?: "").exists()
        val isLightContext = !hasWallpaper && (
                settingsRepository.themePreset == SettingsRepository.THEME_LIGHT ||
                        (settingsRepository.customBgColor != 0 &&
                                ColorUtils.calculateLuminance(settingsRepository.customBgColor) > 0.5)
                )
        return if (isLightContext) Color.parseColor("#E2E8F0")
        else ContextCompat.getColor(this, R.color.card_stroke)
    }

    private fun setupHideSectionsEntry() {
        val container = binding.viewSettings.getChildAt(0) as? ViewGroup ?: return
        val row = container.findViewById<View>(R.id.rowSectionManager) ?: return
        rowHideSectionsRow = row
        rowHideSectionsIcon = row.findViewById(R.id.ivSectionManagerIcon)
        rowHideSectionsTitle = row.findViewById(R.id.tvSectionManagerTitle)
        rowHideSectionsDesc = row.findViewById(R.id.tvSectionManagerDesc)
        rowHideSectionsArrow = row.findViewById(R.id.ivSectionManagerArrow)
        rowHideSectionsIcon?.setImageResource(R.drawable.ic_eye_off)
        rowHideSectionsTitle?.setText(R.string.setting_hide_sections)
        rowHideSectionsDesc?.setText(R.string.setting_hide_sections_desc)
        row.setOnClickListener {
            AnimationHelper.bounceClick(it, minScale = 0.98f, durationMs = 140)
            showHideSectionsDialog()
        }
    }

    private fun showHideSectionsDialog() {
        val dialog = Dialog(this)
        dialog.setContentView(R.layout.dialog_hide_sections)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.92f).toInt(),
            LinearLayout.LayoutParams.WRAP_CONTENT
        )

        val density = resources.displayMetrics.density
        val hasWallpaper = !settingsRepository.customBgImagePath.isNullOrEmpty() &&
                java.io.File(settingsRepository.customBgImagePath ?: "").exists()
        val isLightContext = !hasWallpaper && (
                settingsRepository.themePreset == SettingsRepository.THEME_LIGHT ||
                        (settingsRepository.customBgColor != 0 &&
                                ColorUtils.calculateLuminance(settingsRepository.customBgColor) > 0.5)
                )
        val customText = settingsRepository.customTextColor
        val cardBgColor = if (isLightContext) Color.WHITE else ContextCompat.getColor(this, R.color.card)
        val strokeColor = if (isLightContext) Color.parseColor("#E2E8F0")
        else ContextCompat.getColor(this, R.color.card_stroke)
        val titleTextColor = if (customText != 0) customText
        else if (isLightContext) Color.parseColor("#0F172A") else ContextCompat.getColor(this, R.color.ink)
        val subtitleTextColor = if (customText != 0) ColorUtils.setAlphaComponent(customText, 190)
        else if (isLightContext) Color.parseColor("#475569")
        else ContextCompat.getColor(this, R.color.ink_soft)
        val accentColor = ContextCompat.getColor(this, R.color.accent)

        val root = dialog.findViewById<LinearLayout>(R.id.dialogHideSectionsRoot)
        root.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 24f * density
            setColor(cardBgColor)
            setStroke((1.2f * density).toInt(), strokeColor)
        }
        dialog.findViewById<TextView>(R.id.tvHideSectionsTitle).setTextColor(titleTextColor)
        dialog.findViewById<TextView>(R.id.tvHideSectionsSubtitle).setTextColor(subtitleTextColor)
        dialog.findViewById<TextView>(R.id.tvHideSectionsSubtitle).setTextColor(subtitleTextColor)

        val goBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 18f * density
            setColor(ColorUtils.setAlphaComponent(accentColor, 34))
            setStroke((1f * density).toInt(), ColorUtils.setAlphaComponent(accentColor, 150))
        }
        val hideAll = dialog.findViewById<TextView>(R.id.btnHideAllSections)
        val showAll = dialog.findViewById<TextView>(R.id.btnShowAllSections)
        hideAll.setTextColor(titleTextColor)
        hideAll.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 18f * density
            setColor(cardBgColor)
            setStroke((1f * density).toInt(), strokeColor)
        }
        showAll.setTextColor(accentColor)
        showAll.background = goBg
        dialog.findViewById<TextView>(R.id.tvHideSectionsClose).setTextColor(subtitleTextColor)
        dialog.findViewById<TextView>(R.id.tvHideSectionsClose).background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 14f * density
            setColor(cardBgColor)
            setStroke((1f * density).toInt(), strokeColor)
        }
        val resetView = dialog.findViewById<TextView>(R.id.btnHideResetView)
        resetView.setTextColor(subtitleTextColor)
        resetView.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 14f * density
            setColor(cardBgColor)
            setStroke((1f * density).toInt(), strokeColor)
        }

        val container = dialog.findViewById<LinearLayout>(R.id.containerHideSections)

        val expanded = mutableSetOf<String>()
        var lockedHeight = 0

        fun fitHideList() {
            val scroll = dialog.findViewById<ScrollView>(R.id.scrollHideSections) ?: return
            root.post {
                if (lockedHeight == 0) {
                    val ceiling = (resources.displayMetrics.heightPixels * 0.82f).toInt()
                    lockedHeight = minOf(root.height, ceiling).takeIf { it > 0 } ?: 0
                }
                if (lockedHeight > 0) {
                    dialog.window?.setLayout(
                        (resources.displayMetrics.widthPixels * 0.92f).toInt(),
                        lockedHeight
                    )
                    AnimationHelper.clampScrollToBudget(dialog, root, scroll, lockedHeight)
                }
            }
        }

        fun refreshRows() {
            container.removeAllViews()
            val order = settingsGroupOrder()
            val hidden = settingsRepository.hiddenSections
            order.forEachIndexed { index, id ->
                val group = settingsGroups.first { it.id == id }
                val isHidden = hidden.contains(id)
                val isOpen = expanded.contains(id)
                val row = LinearLayout(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    gravity = Gravity.CENTER_VERTICAL
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, (6f * density).toInt(), 0, (6f * density).toInt())
                }
                val up = ImageView(this).apply {
                    layoutParams = LinearLayout.LayoutParams((32f * density).toInt(), (32f * density).toInt())
                    setImageResource(R.drawable.ic_arrow_up)
                    imageTintList = ColorStateList.valueOf(
                        if (!group.reorderable || index == 0) ColorUtils.setAlphaComponent(subtitleTextColor, 90) else subtitleTextColor
                    )
                    setPadding((6f * density).toInt(), (6f * density).toInt(), (6f * density).toInt(), (6f * density).toInt())
                    isEnabled = group.reorderable && index != 0
                }
                val down = ImageView(this).apply {
                    layoutParams = LinearLayout.LayoutParams((32f * density).toInt(), (32f * density).toInt())
                    setImageResource(R.drawable.ic_arrow_down)
                    imageTintList = ColorStateList.valueOf(
                        if (!group.reorderable || index == order.size - 1) ColorUtils.setAlphaComponent(subtitleTextColor, 90) else subtitleTextColor
                    )
                    setPadding((6f * density).toInt(), (6f * density).toInt(), (6f * density).toInt(), (6f * density).toInt())
                    isEnabled = group.reorderable && index != order.size - 1
                }
                val eye = ImageView(this).apply {
                    layoutParams = LinearLayout.LayoutParams((40f * density).toInt(), (40f * density).toInt()).apply {
                        marginStart = (4f * density).toInt()
                        marginEnd = (10f * density).toInt()
                    }
                    setImageResource(
                        if (isHidden) R.drawable.ic_eye_off else R.drawable.ic_eye
                    )
                    imageTintList = ColorStateList.valueOf(if (isHidden) subtitleTextColor else accentColor)
                    setPadding((6f * density).toInt(), (6f * density).toInt(), (6f * density).toInt(), (6f * density).toInt())
                }
                val title = TextView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    setText(group.titleRes)
                    setTextColor(if (isHidden) subtitleTextColor else titleTextColor)
                    textSize = 14f
                    setTypeface(typeface, Typeface.BOLD)
                }
                val expand = ImageView(this).apply {
                    layoutParams = LinearLayout.LayoutParams((32f * density).toInt(), (32f * density).toInt())
                    setImageResource(R.drawable.ic_chevron_right)
                    rotation = if (isOpen) 90f else 0f
                    imageTintList = ColorStateList.valueOf(subtitleTextColor)
                    setPadding((6f * density).toInt(), (6f * density).toInt(), (6f * density).toInt(), (6f * density).toInt())
                }
                fun toggleOpen() {
                    if (expanded.contains(id)) expanded.remove(id) else expanded.add(id)
                    refreshRows()
                }
                title.setOnClickListener { toggleOpen() }
                expand.setOnClickListener {
                    AnimationHelper.bounceClick(expand, minScale = 0.88f, durationMs = 140)
                    toggleOpen()
                }
                up.setOnClickListener {
                    if (!group.reorderable || index == 0) return@setOnClickListener
                    AnimationHelper.bounceClick(up, minScale = 0.88f, durationMs = 140)
                    val mutable = order.toMutableList()
                    val moved = mutable.removeAt(index)
                    mutable.add(index - 1, moved)
                    settingsRepository.sectionOrder = mutable.joinToString(",")
                    applySettingsGroups()
                    refreshRows()
                }
                down.setOnClickListener {
                    if (!group.reorderable || index == order.size - 1) return@setOnClickListener
                    AnimationHelper.bounceClick(down, minScale = 0.88f, durationMs = 140)
                    val mutable = order.toMutableList()
                    val moved = mutable.removeAt(index)
                    mutable.add(index + 1, moved)
                    settingsRepository.sectionOrder = mutable.joinToString(",")
                    applySettingsGroups()
                    refreshRows()
                }
                if (group.hideable) {
                    eye.setOnClickListener {
                        AnimationHelper.bounceClick(eye, minScale = 0.88f, durationMs = 140)
                        val mutable = settingsRepository.hiddenSections.toMutableSet()
                        if (isHidden) mutable.remove(id) else mutable.add(id)
                        settingsRepository.hiddenSections = mutable
                        applySettingsGroups()
                        refreshRows()
                    }
                } else {
                    eye.alpha = 0.25f
                }
                row.addView(up)
                row.addView(down)
                row.addView(eye)
                row.addView(title)
                row.addView(expand)
                container.addView(row)
                if (isOpen) {
                    for (sub in sectionSubs(id)) {
                        val subHidden = hidden.contains(sub.key)
                        val subRow = LinearLayout(this).apply {
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT
                            )
                            gravity = Gravity.CENTER_VERTICAL
                            orientation = LinearLayout.HORIZONTAL
                            setPadding((112f * density).toInt(), (4f * density).toInt(), 0, (4f * density).toInt())
                        }
                        val subEye = ImageView(this).apply {
                            layoutParams = LinearLayout.LayoutParams((36f * density).toInt(), (36f * density).toInt()).apply {
                                marginEnd = (10f * density).toInt()
                            }
                            setImageResource(
                                if (subHidden) R.drawable.ic_eye_off else R.drawable.ic_eye
                            )
                            imageTintList = ColorStateList.valueOf(if (subHidden) subtitleTextColor else accentColor)
                            setPadding((6f * density).toInt(), (6f * density).toInt(), (6f * density).toInt(), (6f * density).toInt())
                        }
                        val subTitle = TextView(this).apply {
                            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                            setText(sub.titleRes)
                            setTextColor(if (subHidden) subtitleTextColor else titleTextColor)
                            textSize = 13f
                        }
                        subEye.setOnClickListener {
                            AnimationHelper.bounceClick(subEye, minScale = 0.88f, durationMs = 140)
                            val mutable = settingsRepository.hiddenSections.toMutableSet()
                            if (subHidden) mutable.remove(sub.key) else mutable.add(sub.key)
                            settingsRepository.hiddenSections = mutable
                            applySettingsGroups()
                            refreshRows()
                        }
                        subRow.addView(subEye)
                        subRow.addView(subTitle)
                        container.addView(subRow)
                    }
                }
            }
            fitHideList()
        }

        hideAll.setOnClickListener {
            AnimationHelper.bounceClick(hideAll, minScale = 0.94f, durationMs = 140)
            settingsRepository.hiddenSections =
                settingsGroups.filter { it.hideable }.map { it.id }.toSet()
            applySettingsGroups()
            refreshRows()
        }
        showAll.setOnClickListener {
            AnimationHelper.bounceClick(showAll, minScale = 0.94f, durationMs = 140)
            settingsRepository.hiddenSections = emptySet()
            applySettingsGroups()
            refreshRows()
        }
        dialog.findViewById<View>(R.id.tvHideSectionsClose).setOnClickListener { dialog.dismiss() }
        dialog.findViewById<View>(R.id.btnHideResetView).setOnClickListener {
            AnimationHelper.bounceClick(it, minScale = 0.94f, durationMs = 140)
            settingsRepository.sectionOrder = ""
            applySettingsGroups()
            refreshRows()
        }

        refreshRows()
        dialog.show()
        AnimationHelper.popIn(root, durationMs = 240)
        AnimationHelper.attachPressAnimations(root)
        fitHideList()
    }

    private fun styleInjectedRows(
        isLightContext: Boolean,
        titleTextColor: Int,
        subtitleTextColor: Int,
        accentColor: Int,
        sectionHeaderColor: Int
    ) {
        val strokeColor = if (isLightContext) Color.parseColor("#E2E8F0")
        else ContextCompat.getColor(this, R.color.card_stroke)
        listOfNotNull(rowPayment, rowScreenshots, rowAnimations).forEach { holder ->
            holder.icon.imageTintList = ColorStateList.valueOf(accentColor)
            holder.title.setTextColor(titleTextColor)
            holder.desc.setTextColor(subtitleTextColor)
            holder.divider?.setBackgroundColor(strokeColor)
            holder.switch.setLightMode(isLightContext)
            val customSwitch = settingsRepository.customSwitchColor
            if (customSwitch != 0) holder.switch.setActiveColor(customSwitch)
            else holder.switch.resetColors()
        }
        listOfNotNull(rowExportSettings, rowImportSettings).forEach { holder ->
            holder.icon.imageTintList = ColorStateList.valueOf(accentColor)
            holder.title.setTextColor(titleTextColor)
            holder.desc.setTextColor(subtitleTextColor)
        }

        rowScreenshots?.desc?.setText(
            if (settingsRepository.isScreenshotBlockingEnabled) R.string.setting_screenshots_desc
            else R.string.setting_screenshots_allowed
        )
        rowAnimations?.desc?.setText(
            if (settingsRepository.areAnimationsEnabled) R.string.setting_animations_desc
            else R.string.setting_animations_off
        )

        cardAnimations?.background = getCardDrawable(isLightContext, 18f)
        cardBackup?.background = getCardDrawable(isLightContext, 18f)
        restyleAllCardDividers()
        injectedSectionTitles.forEach { it.setTextColor(sectionHeaderColor) }

        rowHideSectionsIcon?.imageTintList = ColorStateList.valueOf(accentColor)
        rowHideSectionsTitle?.setTextColor(titleTextColor)
        rowHideSectionsDesc?.setTextColor(subtitleTextColor)
        rowHideSectionsArrow?.imageTintList = ColorStateList.valueOf(subtitleTextColor)
    }

    private fun applyScreenshotBlocking() {
        if (settingsRepository.isScreenshotBlockingEnabled) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    private fun showScreenshotWarningDialog(onAllow: () -> Unit, onKeep: () -> Unit) {
        val dialog = Dialog(this)
        dialog.setContentView(R.layout.dialog_screenshot_warning)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.92f).toInt(),
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        val density = resources.displayMetrics.density
        val hasWallpaper = !settingsRepository.customBgImagePath.isNullOrEmpty() &&
                java.io.File(settingsRepository.customBgImagePath ?: "").exists()
        val isLightContext = !hasWallpaper && (
                settingsRepository.themePreset == SettingsRepository.THEME_LIGHT ||
                        (settingsRepository.customBgColor != 0 &&
                                ColorUtils.calculateLuminance(settingsRepository.customBgColor) > 0.5)
                )
        val customText = settingsRepository.customTextColor
        val cardBgColor = if (isLightContext) Color.WHITE else ContextCompat.getColor(this, R.color.card)
        val strokeColor = if (isLightContext) Color.parseColor("#E2E8F0")
        else ContextCompat.getColor(this, R.color.card_stroke)
        val titleColor = if (customText != 0) customText
        else if (isLightContext) Color.parseColor("#0F172A") else ContextCompat.getColor(this, R.color.ink)
        val bodyColor = if (customText != 0) ColorUtils.setAlphaComponent(customText, 190)
        else if (isLightContext) Color.parseColor("#475569")
        else ContextCompat.getColor(this, R.color.ink_soft)

        val root = dialog.findViewById<LinearLayout>(R.id.dialogScreenshotRoot)
        root.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 24f * density
            setColor(cardBgColor)
            setStroke((1.2f * density).toInt(), strokeColor)
        }
        dialog.findViewById<ImageView>(R.id.ivScreenshotWarningIcon).imageTintList =
            ColorStateList.valueOf(ContextCompat.getColor(this, R.color.danger))
        dialog.findViewById<TextView>(R.id.tvScreenshotWarningTitle).setTextColor(titleColor)
        dialog.findViewById<TextView>(R.id.tvScreenshotWarningBody).setTextColor(bodyColor)

        val riskCard = dialog.findViewById<TextView>(R.id.tvScreenshotWarningRisks)
        riskCard.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 16f * density
            setColor(ColorUtils.setAlphaComponent(ContextCompat.getColor(this@MainActivity, R.color.danger), 26))
            setStroke((1f * density).toInt(), ColorUtils.setAlphaComponent(ContextCompat.getColor(this@MainActivity, R.color.danger), 110))
        }
        riskCard.setTextColor(ContextCompat.getColor(this, R.color.danger))

        dialog.findViewById<TextView>(R.id.btnScreenshotKeepBlocked).setTextColor(bodyColor)
        dialog.findViewById<TextView>(R.id.btnScreenshotAllow).setTextColor(
            ContextCompat.getColor(this, R.color.accent)
        )
        dialog.findViewById<View>(R.id.btnScreenshotAllow).setOnClickListener {
            dialog.dismiss()
            onAllow()
        }
        dialog.findViewById<View>(R.id.btnScreenshotKeepBlocked).setOnClickListener {
            dialog.dismiss()
            onKeep()
        }
        dialog.show()
        AnimationHelper.popIn(root, durationMs = 220)
        AnimationHelper.attachPressAnimations(root)
    }

    private fun showSessionHistoryDialog() {
        val dialog = Dialog(this)
        dialog.setContentView(R.layout.dialog_session_history)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.92f).toInt(),
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        val density = resources.displayMetrics.density
        val hasWallpaper = !settingsRepository.customBgImagePath.isNullOrEmpty() &&
                java.io.File(settingsRepository.customBgImagePath ?: "").exists()
        val isLightContext = !hasWallpaper && (
                settingsRepository.themePreset == SettingsRepository.THEME_LIGHT ||
                        (settingsRepository.customBgColor != 0 &&
                                ColorUtils.calculateLuminance(settingsRepository.customBgColor) > 0.5)
                )
        val customText = settingsRepository.customTextColor
        val cardBgColor = if (isLightContext) Color.WHITE else ContextCompat.getColor(this, R.color.card)
        val strokeColor = if (isLightContext) Color.parseColor("#E2E8F0")
        else ContextCompat.getColor(this, R.color.card_stroke)
        val titleColor = if (customText != 0) customText
        else if (isLightContext) Color.parseColor("#0F172A") else ContextCompat.getColor(this, R.color.ink)
        val bodyColor = if (customText != 0) ColorUtils.setAlphaComponent(customText, 190)
        else if (isLightContext) Color.parseColor("#475569")
        else ContextCompat.getColor(this, R.color.ink_soft)

        val root = dialog.findViewById<LinearLayout>(R.id.dialogSessionHistoryRoot)
        root.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 24f * density
            setColor(cardBgColor)
            setStroke((1.2f * density).toInt(), strokeColor)
        }
        dialog.findViewById<TextView>(R.id.tvSessionHistoryDialogTitle).setTextColor(titleColor)
        val close = dialog.findViewById<TextView>(R.id.tvSessionHistoryClose)
        close.setTextColor(bodyColor)
        close.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 14f * density
            setColor(cardBgColor)
            setStroke((1f * density).toInt(), strokeColor)
        }
        close.setOnClickListener { dialog.dismiss() }

        val container = dialog.findViewById<LinearLayout>(R.id.containerSessionHistory)
        val entries = com.naua_security_mirage.app.data.SessionJournal.list(this)
        if (entries.isEmpty()) {
            val empty = TextView(this).apply {
                text = getString(R.string.session_history_empty)
                setTextColor(bodyColor)
                textSize = 13f
                setPadding(0, (8f * density).toInt(), 0, (8f * density).toInt())
            }
            container.addView(empty)
        } else {
            val dateFormat = java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.getDefault())
            for (entry in entries) {
                val line = TextView(this).apply {
                    text = "${dateFormat.format(java.util.Date(entry.startedAt))} · ${entry.node} · ${formatSessionDuration(entry.seconds)}"
                    setTextColor(bodyColor)
                    textSize = 13f
                    setPadding(0, (6f * density).toInt(), 0, (6f * density).toInt())
                }
                container.addView(line)
            }
        }
        dialog.show()
        AnimationHelper.popIn(root, durationMs = 220)
        AnimationHelper.attachPressAnimations(root)
    }

    private fun formatSessionDuration(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
        else String.format("%02d:%02d", m, s)
    }

    private fun showVpnConsentDialog(onAccepted: () -> Unit) {
        if (settingsRepository.isVpnConsentAccepted) {
            onAccepted()
            return
        }
        val dialog = Dialog(this)
        dialog.setContentView(R.layout.dialog_vpn_consent)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.92f).toInt(),
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        val density = resources.displayMetrics.density
        val hasWallpaper = !settingsRepository.customBgImagePath.isNullOrEmpty() &&
                java.io.File(settingsRepository.customBgImagePath ?: "").exists()
        val isLightContext = !hasWallpaper && (
                settingsRepository.themePreset == SettingsRepository.THEME_LIGHT ||
                        (settingsRepository.customBgColor != 0 &&
                                ColorUtils.calculateLuminance(settingsRepository.customBgColor) > 0.5)
                )
        val customText = settingsRepository.customTextColor
        val cardBgColor = if (isLightContext) Color.WHITE else ContextCompat.getColor(this, R.color.card)
        val strokeColor = if (isLightContext) Color.parseColor("#E2E8F0")
        else ContextCompat.getColor(this, R.color.card_stroke)
        val titleColor = if (customText != 0) customText
        else if (isLightContext) Color.parseColor("#0F172A") else ContextCompat.getColor(this, R.color.ink)
        val bodyColor = if (customText != 0) ColorUtils.setAlphaComponent(customText, 190)
        else if (isLightContext) Color.parseColor("#475569")
        else ContextCompat.getColor(this, R.color.ink_soft)
        val accentColor = ContextCompat.getColor(this, R.color.accent)

        val root = dialog.findViewById<LinearLayout>(R.id.dialogConsentRoot)
        root.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 24f * density
            setColor(cardBgColor)
            setStroke((1.2f * density).toInt(), strokeColor)
        }
        dialog.findViewById<ImageView>(R.id.ivConsentIcon).imageTintList =
            ColorStateList.valueOf(accentColor)
        dialog.findViewById<TextView>(R.id.tvConsentTitle).setTextColor(titleColor)
        dialog.findViewById<TextView>(R.id.tvConsentBody).setTextColor(bodyColor)

        val linkCard = dialog.findViewById<View>(R.id.btnConsentLicense).parent as View
        linkCard.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 16f * density
            setColor(if (isLightContext) Color.parseColor("#F1F5F9")
            else ColorUtils.setAlphaComponent(accentColor, 24))
            setStroke((1f * density).toInt(), ColorUtils.setAlphaComponent(accentColor, 90))
        }
        dialog.findViewById<TextView>(R.id.btnConsentLicense).setTextColor(accentColor)
        dialog.findViewById<TextView>(R.id.btnConsentPrivacy).setTextColor(accentColor)
        dialog.findViewById<TextView>(R.id.btnConsentDecline).setTextColor(bodyColor)
        dialog.findViewById<TextView>(R.id.btnConsentAccept).setTextColor(accentColor)

        dialog.findViewById<View>(R.id.btnConsentLicense).setOnClickListener { openExternalLink(TERMS_URL) }
        dialog.findViewById<View>(R.id.btnConsentPrivacy).setOnClickListener { openExternalLink(PRIVACY_URL) }
        dialog.findViewById<View>(R.id.btnConsentDecline).setOnClickListener { dialog.dismiss() }
        dialog.findViewById<View>(R.id.btnConsentAccept).setOnClickListener {
            settingsRepository.isVpnConsentAccepted = true
            dialog.dismiss()
            onAccepted()
        }
        dialog.setCancelable(true)
        dialog.show()
        AnimationHelper.popIn(root, durationMs = 240)
        AnimationHelper.attachPressAnimations(root)
    }

    private fun setupAiModePill() {
        val density = resources.displayMetrics.density
        val pill = layoutInflater.inflate(R.layout.pill_ai_mode, binding.viewMain, false) as LinearLayout
        pill.layoutParams = ConstraintLayout.LayoutParams(
            ConstraintLayout.LayoutParams.WRAP_CONTENT,
            (46f * density).toInt()
        ).apply {
            startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            topToBottom = R.id.containerSpeedLive
            topMargin = (24f * density).toInt()
        }
        binding.viewMain.addView(pill)

        val warn = TextView(this).apply {
            id = R.id.tvAiModePending
            layoutParams = ConstraintLayout.LayoutParams(0, ConstraintLayout.LayoutParams.WRAP_CONTENT).apply {
                startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                topToBottom = R.id.pillAiMode
                topMargin = (8f * density).toInt()
                marginStart = (22f * density).toInt()
                marginEnd = (22f * density).toInt()
            }
            text = getString(R.string.ai_mode_pending_off)
            textSize = 11.5f
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        binding.viewMain.addView(warn)
        aiModePendingView = warn

        val supportParams = binding.btnSupport.layoutParams as? ConstraintLayout.LayoutParams
        if (supportParams != null) {
            supportParams.topToBottom = R.id.tvAiModePending
            supportParams.topMargin = (12f * density).toInt()
            supportParams.goneTopMargin = (24f * density).toInt()
            binding.btnSupport.layoutParams = supportParams
        }

        pillAiMode = pill

        applyAiModePillTheme()

        pill.setOnClickListener {
            AnimationHelper.bounceClick(pill, minScale = 0.93f, durationMs = 180)
            val enabled = !settingsRepository.isAiModeEnabled
            settingsRepository.isAiModeEnabled = enabled
            aiModePending = enabled && currentVpnState == VpnState.CONNECTED
            applyAiModePillTheme()
            Toast.makeText(
                this,
                if (enabled) R.string.ai_mode_on else R.string.ai_mode_off,
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun showSupportWaysDialog() {
        val dialog = Dialog(this)
        dialog.setContentView(R.layout.dialog_support_ways)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.92f).toInt(),
            LinearLayout.LayoutParams.WRAP_CONTENT
        )

        val density = resources.displayMetrics.density
        val hasWallpaper = !settingsRepository.customBgImagePath.isNullOrEmpty() &&
                java.io.File(settingsRepository.customBgImagePath ?: "").exists()
        val isLightContext = !hasWallpaper && (
                settingsRepository.themePreset == SettingsRepository.THEME_LIGHT ||
                        (settingsRepository.customBgColor != 0 &&
                                ColorUtils.calculateLuminance(settingsRepository.customBgColor) > 0.5)
                )
        val customText = settingsRepository.customTextColor

        val cardBgColor = if (isLightContext) Color.WHITE else ContextCompat.getColor(this, R.color.card)
        val strokeColor = if (isLightContext) Color.parseColor("#E2E8F0")
        else ContextCompat.getColor(this, R.color.card_stroke)
        val titleTextColor = if (customText != 0) customText
        else if (isLightContext) Color.parseColor("#0F172A") else ContextCompat.getColor(this, R.color.ink)
        val subtitleTextColor = if (customText != 0) ColorUtils.setAlphaComponent(customText, 190)
        else if (isLightContext) Color.parseColor("#475569")
        else ContextCompat.getColor(this, R.color.ink_soft)
        val accentColor = ContextCompat.getColor(this, R.color.accent)

        val root = dialog.findViewById<LinearLayout>(R.id.dialogSupportWaysRoot)
        root.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 24f * density
            setColor(cardBgColor)
            setStroke((1.2f * density).toInt(), strokeColor)
        }

        dialog.findViewById<TextView>(R.id.tvSupportWaysTitle).setTextColor(titleTextColor)
        dialog.findViewById<TextView>(R.id.tvSupportWaysSubtitle).setTextColor(subtitleTextColor)
        dialog.findViewById<TextView>(R.id.tvSupportWaysHint).setTextColor(subtitleTextColor)
        dialog.findViewById<TextView>(R.id.tvSupportWaysHint).background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 12f * density
            setColor(cardBgColor)
            setStroke((1f * density).toInt(), strokeColor)
        }
        dialog.findViewById<ImageView>(R.id.ivSupportWaysClose).imageTintList =
            ColorStateList.valueOf(subtitleTextColor)

        val rows = listOf(
            Triple(R.id.rowWayCloudtips, R.id.tvWayCloudtipsTitle, R.id.tvWayCloudtipsDesc),
            Triple(R.id.rowWayTributeTelegram, R.id.tvWayTributeTelegramTitle, R.id.tvWayTributeTelegramDesc),
            Triple(R.id.rowWayTributeWeb, R.id.tvWayTributeWebTitle, R.id.tvWayTributeWebDesc),
        )

        rows.forEach { (rowId, titleId, descId) ->
            dialog.findViewById<LinearLayout>(rowId).background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 18f * density
                setColor(cardBgColor)
                setStroke((1f * density).toInt(), strokeColor)
            }
            dialog.findViewById<TextView>(titleId).setTextColor(titleTextColor)
            dialog.findViewById<TextView>(descId).setTextColor(subtitleTextColor)
        }

        val goBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 18f * density
            setColor(ColorUtils.setAlphaComponent(accentColor, 34))
            setStroke((1f * density).toInt(), ColorUtils.setAlphaComponent(accentColor, 150))
        }
        listOf(R.id.tvWayCloudtipsGo, R.id.tvWayTributeTelegramGo, R.id.tvWayTributeWebGo).forEach {
            dialog.findViewById<TextView>(it).background = goBg
        }

        fun open(rowId: Int, url: String) {
            dialog.findViewById<View>(rowId).setOnClickListener {
                dialog.dismiss()
                openExternalLink(url)
            }
        }

        open(R.id.rowWayCloudtips, getString(R.string.cloudtips_url))
        open(R.id.rowWayTributeTelegram, getString(R.string.tribute_tg_url))
        open(R.id.rowWayTributeWeb, getString(R.string.tribute_web_url))

        dialog.findViewById<View>(R.id.ivSupportWaysClose).setOnClickListener { dialog.dismiss() }

        dialog.show()
        AnimationHelper.popIn(root, durationMs = 240)
        AnimationHelper.attachPressAnimations(root)
    }

    private fun applyAiModePillTheme() {
        val pill = pillAiMode ?: return
        val isOn = settingsRepository.isAiModeEnabled
        val density = resources.displayMetrics.density
        val hasWallpaper = !settingsRepository.customBgImagePath.isNullOrEmpty() &&
                java.io.File(settingsRepository.customBgImagePath ?: "").exists()
        val isLightContext = !hasWallpaper && (
                settingsRepository.themePreset == SettingsRepository.THEME_LIGHT ||
                        (settingsRepository.customBgColor != 0 &&
                                ColorUtils.calculateLuminance(settingsRepository.customBgColor) > 0.5)
                )
        val customText = settingsRepository.customTextColor
        val customAccent = settingsRepository.customActionBtnColor

        val accent = when {
            customAccent != 0 -> customAccent
            isLightContext -> Color.parseColor("#B45309")
            else -> ContextCompat.getColor(this, R.color.accent)
        }
        val title = if (customText != 0) customText else if (isLightContext) Color.parseColor("#0F172A")
        else ContextCompat.getColor(this, R.color.ink)
        val dimmed = if (isOn) title else if (customText != 0) ColorUtils.setAlphaComponent(customText, 140)
        else if (isLightContext) Color.parseColor("#64748B") else ColorUtils.setAlphaComponent(title, 170)

        pill.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 23f * density
            if (isOn) {
                setColor(ColorUtils.setAlphaComponent(accent, if (isLightContext) 34 else 46))
                setStroke((1f * density).toInt(), ColorUtils.setAlphaComponent(accent, 190))
            } else {
                setColor(
                    if (isLightContext) Color.parseColor("#F1F5F9")
                    else ContextCompat.getColor(this@MainActivity, R.color.card)
                )
                setStroke(
                    (1f * density).toInt(),
                    if (isLightContext) Color.parseColor("#CBD5E1")
                    else ContextCompat.getColor(this@MainActivity, R.color.card_stroke)
                )
            }
        }
        pill.findViewById<TextView>(R.id.tvAiMode).setTextColor(dimmed)
        pill.findViewById<ImageView>(R.id.ivAiModeIcon).imageTintList =
            ColorStateList.valueOf(if (isOn) accent else dimmed)
        pill.findViewById<View>(R.id.dotAiMode).background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (isOn) accent else ColorUtils.setAlphaComponent(dimmed, 90))
        }

        aiModePendingView?.setTextColor(ContextCompat.getColor(this, R.color.danger))
        updateAiModePendingWarning()
    }

    private fun updateAiModePendingWarning() {
        val warn = aiModePendingView ?: return
        val connected = currentVpnState == VpnState.CONNECTED
        val show = settingsRepository.isAiModeEnabled && (aiModePending || !connected)
        if (!show) {
            warn.visibility = View.GONE
            warn.alpha = 1f
            warn.translationY = 0f
            return
        }
        warn.text = getString(
            if (connected) R.string.ai_mode_pending_restart else R.string.ai_mode_pending_off
        )
        if (warn.visibility != View.VISIBLE) {
            warn.visibility = View.VISIBLE
            val density = resources.displayMetrics.density
            warn.alpha = 0f
            warn.translationY = 8f * density
            AnimationHelper.animateDirect(
                durationMs = 220,
                interpolator = AnimationHelper.EaseOutCubic,
                onUpdate = { fraction ->
                    warn.alpha = fraction
                    warn.translationY = 8f * density * (1f - fraction)
                },
                onEnd = {
                    warn.alpha = 1f
                    warn.translationY = 0f
                }
            )
        }
    }

    private fun getCardDrawable(isLight: Boolean, cornerRadiusDp: Float): GradientDrawable {
        val density = resources.displayMetrics.density
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = cornerRadiusDp * density
            if (isLight) {
                setColor(Color.WHITE)
                setStroke((1f * density).toInt(), Color.parseColor("#E2E8F0"))
            } else {
                setColor(ContextCompat.getColor(this@MainActivity, R.color.card))
                setStroke((1f * density).toInt(), ContextCompat.getColor(this@MainActivity, R.color.card_stroke))
            }
        }
    }

    private fun getButtonChipDrawable(isLight: Boolean, cornerRadiusDp: Float): GradientDrawable {
        val density = resources.displayMetrics.density
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = cornerRadiusDp * density
            if (isLight) {
                setColor(Color.parseColor("#F1F5F9"))
                setStroke((1f * density).toInt(), Color.parseColor("#CBD5E1"))
            } else {
                setColor(ContextCompat.getColor(this@MainActivity, R.color.card))
                setStroke((1f * density).toInt(), ContextCompat.getColor(this@MainActivity, R.color.card_stroke))
            }
        }
    }

    private fun updateThemeChipsState() {
        val preset = settingsRepository.themePreset
        val isDark = preset == SettingsRepository.THEME_DARK
        val isLight = preset == SettingsRepository.THEME_LIGHT
        val density = resources.displayMetrics.density

        val hasWallpaper = !settingsRepository.customBgImagePath.isNullOrEmpty() && File(settingsRepository.customBgImagePath ?: "").exists()
        val isLightContext = !hasWallpaper && (isLight || (settingsRepository.customBgColor != 0 && ColorUtils.calculateLuminance(settingsRepository.customBgColor) > 0.5))

        val customAccent = settingsRepository.customConnectBtnColor
        val activeStrokeColor = if (customAccent != 0) customAccent else Color.parseColor("#E8A33D")
        val inactiveStrokeColor = if (isLightContext) Color.parseColor("#CBD5E1") else ContextCompat.getColor(this, R.color.card_stroke)
        val chipBaseColor = if (isLightContext) Color.WHITE else ContextCompat.getColor(this, R.color.card)

        val inactiveTextColor = if (settingsRepository.customTextColor != 0) {
            settingsRepository.customTextColor
        } else if (isLightContext) {
            Color.parseColor("#0F172A")
        } else {
            ContextCompat.getColor(this, R.color.ink)
        }

        val darkDrawable = GradientDrawable().apply {
            cornerRadius = 14f * density
            val bg = if (isDark) ColorUtils.setAlphaComponent(activeStrokeColor, 38) else chipBaseColor
            setColor(bg)
            setStroke((if (isDark) 2.5f * density else 1f * density).toInt(), if (isDark) activeStrokeColor else inactiveStrokeColor)
        }
        binding.chipThemeDark.background = darkDrawable
        binding.tvThemeDark.setTextColor(if (isDark) activeStrokeColor else inactiveTextColor)

        val lightDrawable = GradientDrawable().apply {
            cornerRadius = 14f * density
            val bg = if (isLight) ColorUtils.setAlphaComponent(activeStrokeColor, 38) else chipBaseColor
            setColor(bg)
            setStroke((if (isLight) 2.5f * density else 1f * density).toInt(), if (isLight) activeStrokeColor else inactiveStrokeColor)
        }
        binding.chipThemeLight.background = lightDrawable
        binding.tvThemeLight.setTextColor(if (isLight) activeStrokeColor else inactiveTextColor)
    }

    private fun setupColorRow(
        title: String,
        container: LinearLayout,
        colors: List<Int>,
        currentColor: Int,
        defaultColor: Int,
        onColorSelected: (Int) -> Unit
    ) {
        container.removeAllViews()
        val density = resources.displayMetrics.density
        val size = (38 * density).toInt()
        val margin = (5 * density).toInt()

        val hasWallpaper = !settingsRepository.customBgImagePath.isNullOrEmpty() && File(settingsRepository.customBgImagePath ?: "").exists()
        val isLightContext = !hasWallpaper && (settingsRepository.themePreset == SettingsRepository.THEME_LIGHT || (settingsRepository.customBgColor != 0 && ColorUtils.calculateLuminance(settingsRepository.customBgColor) > 0.5))

        for (color in colors) {
            val isSelected = (currentColor != 0 && currentColor == color) || (currentColor == 0 && color == defaultColor)
            val isColorBright = ColorUtils.calculateLuminance(color) > 0.65

            val view = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    setMargins(margin, margin, margin, margin)
                }
                val drawable = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color)
                    if (isSelected) {
                        val strokeColor = if (isColorBright && !isLightContext) Color.BLACK else if (isColorBright && isLightContext) Color.parseColor("#0F172A") else Color.WHITE
                        setStroke((3.5f * density).toInt(), strokeColor)
                    } else {
                        val strokeColor = if (isLightContext) Color.parseColor("#33000000") else Color.parseColor("#33FFFFFF")
                        setStroke((1f * density).toInt(), strokeColor)
                    }
                }
                background = drawable
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    AnimationHelper.bounceClick(this, minScale = 0.82f, durationMs = 180) {
                        onColorSelected(color)
                    }
                }
            }
            container.addView(view)
        }

        val isCustomSelected = (currentColor != 0 && !colors.contains(currentColor))
        val paletteView = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                setMargins(margin, margin, margin, margin)
            }
            isClickable = true
            isFocusable = true

            val bgDrawable = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                if (isCustomSelected) {
                    setColor(currentColor)
                    val isBright = ColorUtils.calculateLuminance(currentColor) > 0.65
                    val strokeColor = if (isBright && !isLightContext) Color.BLACK else if (isBright && isLightContext) Color.parseColor("#0F172A") else Color.WHITE
                    setStroke((3.5f * density).toInt(), strokeColor)
                } else {
                    setColor(if (isLightContext) Color.parseColor("#F1F5F9") else Color.parseColor("#1C1F2E"))
                    setStroke((1f * density).toInt(), if (isLightContext) Color.parseColor("#CBD5E1") else Color.parseColor("#33FFFFFF"))
                }
            }
            background = bgDrawable

            val icon = ImageView(this@MainActivity).apply {
                val iconSize = (18 * density).toInt()
                layoutParams = FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER)
                setImageResource(R.drawable.ic_palette)
                val iconTint = if (isCustomSelected) {
                    if (ColorUtils.calculateLuminance(currentColor) > 0.65) Color.BLACK else Color.WHITE
                } else {
                    if (isLightContext) Color.parseColor("#475569") else ContextCompat.getColor(this@MainActivity, R.color.ink)
                }
                imageTintList = ColorStateList.valueOf(iconTint)
            }
            addView(icon)

            setOnClickListener {
                AnimationHelper.bounceClick(this, minScale = 0.84f, durationMs = 180) {
                    val initial = if (currentColor != 0) currentColor else defaultColor
                    showColorPickerDialog(title, initial, onColorSelected)
                }
            }
        }
        container.addView(paletteView)
    }

    private fun showColorPickerDialog(
        title: String,
        initialColor: Int,
        onColorPicked: (Int) -> Unit
    ) {
        val dialog = Dialog(this)
        dialog.setContentView(R.layout.dialog_color_picker)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.92f).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        val density = resources.displayMetrics.density
        val hasWallpaper = !settingsRepository.customBgImagePath.isNullOrEmpty() && File(settingsRepository.customBgImagePath ?: "").exists()
        val isLightContext = !hasWallpaper && (settingsRepository.themePreset == SettingsRepository.THEME_LIGHT || (settingsRepository.customBgColor != 0 && ColorUtils.calculateLuminance(settingsRepository.customBgColor) > 0.5))

        val root = dialog.findViewById<LinearLayout>(R.id.dialogColorPickerRoot)
        val tvTitle = dialog.findViewById<TextView>(R.id.tvPickerTitle)
        val vPreview = dialog.findViewById<View>(R.id.vColorPreview)
        val tvHexLabel = dialog.findViewById<TextView>(R.id.tvHexLabel)
        val etHexCode = dialog.findViewById<EditText>(R.id.etHexCode)
        val tvHueLabel = dialog.findViewById<TextView>(R.id.tvHueLabel)
        val sbHue = dialog.findViewById<SeekBar>(R.id.sbHue)
        val tvSatLabel = dialog.findViewById<TextView>(R.id.tvSatLabel)
        val sbSat = dialog.findViewById<SeekBar>(R.id.sbSat)
        val tvValLabel = dialog.findViewById<TextView>(R.id.tvValLabel)
        val sbVal = dialog.findViewById<SeekBar>(R.id.sbVal)
        val tvQuickLabel = dialog.findViewById<TextView>(R.id.tvQuickLabel)
        val containerQuickColors = dialog.findViewById<LinearLayout>(R.id.containerQuickColors)
        val btnCancel = dialog.findViewById<TextView>(R.id.btnPickerCancel)
        val btnApply = dialog.findViewById<TextView>(R.id.btnPickerApply)

        tvTitle.text = getString(R.string.color_choose_fmt, title)

        val dialogBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 24f * density
            if (isLightContext) {
                setColor(Color.WHITE)
                setStroke((1f * density).toInt(), Color.parseColor("#E2E8F0"))
            } else {
                setColor(Color.parseColor("#14161E"))
                setStroke((1f * density).toInt(), Color.parseColor("#222533"))
            }
        }
        root.background = dialogBg

        val primaryText = if (isLightContext) Color.parseColor("#0F172A") else ContextCompat.getColor(this, R.color.ink)
        val secondaryText = if (isLightContext) Color.parseColor("#64748B") else ContextCompat.getColor(this, R.color.ink_soft)

        tvTitle.setTextColor(primaryText)
        tvHexLabel.setTextColor(secondaryText)
        tvHueLabel.setTextColor(secondaryText)
        tvSatLabel.setTextColor(secondaryText)
        tvValLabel.setTextColor(secondaryText)
        tvQuickLabel.setTextColor(secondaryText)
        btnCancel.setTextColor(secondaryText)

        val hexInputBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 10f * density
            if (isLightContext) {
                setColor(Color.parseColor("#F1F5F9"))
                setStroke((1f * density).toInt(), Color.parseColor("#CBD5E1"))
            } else {
                setColor(Color.parseColor("#1D202D"))
                setStroke((1f * density).toInt(), Color.parseColor("#2B2F42"))
            }
        }
        etHexCode.background = hexInputBg
        etHexCode.setTextColor(primaryText)

        val applyBtnBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 14f * density
            val accentColor = if (settingsRepository.customConnectBtnColor != 0) settingsRepository.customConnectBtnColor else Color.parseColor("#E8A33D")
            setColor(accentColor)
        }
        btnApply.background = applyBtnBg
        btnApply.setTextColor(Color.WHITE)

        fun createThumb(): GradientDrawable {
            val thumbSize = (20 * density).toInt()
            return GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setSize(thumbSize, thumbSize)
                setColor(Color.WHITE)
                setStroke((2.5f * density).toInt(), Color.parseColor("#333333"))
            }
        }

        val hsv = FloatArray(3)
        Color.colorToHSV(initialColor, hsv)

        val hueColors = intArrayOf(
            Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN, Color.BLUE, Color.MAGENTA, Color.RED
        )
        sbHue.progressDrawable = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, hueColors).apply {
            cornerRadius = 6f * density
        }
        sbHue.thumb = createThumb()

        sbSat.thumb = createThumb()
        sbVal.thumb = createThumb()

        var isUpdating = false

        fun updateUiFromHsv() {
            val currentColor = Color.HSVToColor(hsv)

            val previewDrawable = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 14f * density
                setColor(currentColor)
                val strokeColor = if (ColorUtils.calculateLuminance(currentColor) > 0.6) Color.parseColor("#33000000") else Color.parseColor("#33FFFFFF")
                setStroke((1.5f * density).toInt(), strokeColor)
            }
            vPreview.background = previewDrawable

            val hexStr = String.format("#%06X", (0xFFFFFF and currentColor))
            if (etHexCode.text.toString() != hexStr) {
                isUpdating = true
                etHexCode.setText(hexStr)
                etHexCode.setSelection(hexStr.length)
                isUpdating = false
            }

            val pureColorAtV = Color.HSVToColor(floatArrayOf(hsv[0], 1f, hsv[2]))
            val desatColorAtV = Color.HSVToColor(floatArrayOf(hsv[0], 0f, hsv[2]))
            sbSat.progressDrawable = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(desatColorAtV, pureColorAtV)).apply {
                cornerRadius = 6f * density
            }

            val brightColorAtS = Color.HSVToColor(floatArrayOf(hsv[0], hsv[1], 1f))
            sbVal.progressDrawable = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Color.BLACK, brightColorAtS)).apply {
                cornerRadius = 6f * density
            }
        }

        sbHue.progress = hsv[0].toInt().coerceIn(0, 360)
        sbSat.progress = (hsv[1] * 100).toInt().coerceIn(0, 100)
        sbVal.progress = (hsv[2] * 100).toInt().coerceIn(0, 100)
        updateUiFromHsv()

        sbHue.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    hsv[0] = progress.toFloat()
                    updateUiFromHsv()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        sbSat.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    hsv[1] = progress / 100f
                    updateUiFromHsv()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        sbVal.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    hsv[2] = progress / 100f
                    updateUiFromHsv()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        etHexCode.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                if (isUpdating) return
                val text = s?.toString()?.trim() ?: ""
                val cleanHex = if (text.startsWith("#")) text.substring(1) else text
                if (cleanHex.length == 6 && cleanHex.all { it in "0123456789ABCDEFabcdef" }) {
                    try {
                        val parsed = Color.parseColor("#$cleanHex")
                        Color.colorToHSV(parsed, hsv)
                        isUpdating = true
                        sbHue.progress = hsv[0].toInt().coerceIn(0, 360)
                        sbSat.progress = (hsv[1] * 100).toInt().coerceIn(0, 100)
                        sbVal.progress = (hsv[2] * 100).toInt().coerceIn(0, 100)
                        isUpdating = false
                        updateUiFromHsv()
                    } catch (_: Exception) {}
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        val quickColors = listOf(
            0xFFEF4444.toInt(),
            0xFFF97316.toInt(),
            0xFFE8A33D.toInt(),
            0xFF10B981.toInt(),
            0xFF06B6D4.toInt(),
            0xFF3B82F6.toInt(),
            0xFF8B5CF6.toInt(),
            0xFFEC4899.toInt(),
            0xFFFFFFFF.toInt(),
            0xFF94A3B8.toInt(),
            0xFF0F172A.toInt(),
            0xFF000000.toInt()
        )

        val swatchSize = (32 * density).toInt()
        val swatchMargin = (4 * density).toInt()

        for (quickColor in quickColors) {
            val swatch = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(swatchSize, swatchSize).apply {
                    setMargins(swatchMargin, swatchMargin, swatchMargin, swatchMargin)
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(quickColor)
                    val strokeColor = if (ColorUtils.calculateLuminance(quickColor) > 0.6) Color.parseColor("#33000000") else Color.parseColor("#33FFFFFF")
                    setStroke((1f * density).toInt(), strokeColor)
                }
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    AnimationHelper.bounceClick(this, minScale = 0.82f, durationMs = 150)
                    Color.colorToHSV(quickColor, hsv)
                    isUpdating = true
                    sbHue.progress = hsv[0].toInt().coerceIn(0, 360)
                    sbSat.progress = (hsv[1] * 100).toInt().coerceIn(0, 100)
                    sbVal.progress = (hsv[2] * 100).toInt().coerceIn(0, 100)
                    isUpdating = false
                    updateUiFromHsv()
                }
            }
            containerQuickColors.addView(swatch)
        }

        btnCancel.setOnClickListener {
            AnimationHelper.bounceClick(btnCancel, minScale = 0.92f, durationMs = 150) {
                dialog.dismiss()
            }
        }

        btnApply.setOnClickListener {
            AnimationHelper.bounceClick(btnApply, minScale = 0.92f, durationMs = 150) {
                val finalColor = Color.HSVToColor(hsv)
                onColorPicked(finalColor)
                dialog.dismiss()
            }
        }

        dialog.show()
        AnimationHelper.popIn(root, durationMs = 240)
        AnimationHelper.attachPressAnimations(root)
    }

    private fun updateUpdateSourceUI() {
        val descText = when (settingsRepository.updateSource) {
            SettingsRepository.UPDATE_SOURCE_UPTODOWN -> getString(R.string.setting_updates_source_uptodown)
            else -> getString(R.string.setting_updates_source_github)
        }
        binding.tvUpdateSettingDesc.text = descText
    }

    private fun showUpdateSourceDialog() {
        val dialog = Dialog(this)
        dialog.setContentView(R.layout.dialog_update_source)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.92f).toInt(),
            LinearLayout.LayoutParams.WRAP_CONTENT
        )

        val density = resources.displayMetrics.density
        val hasWallpaper = !settingsRepository.customBgImagePath.isNullOrEmpty() && File(settingsRepository.customBgImagePath ?: "").exists()
        val isLightContext = !hasWallpaper && (settingsRepository.themePreset == SettingsRepository.THEME_LIGHT || (settingsRepository.customBgColor != 0 && ColorUtils.calculateLuminance(settingsRepository.customBgColor) > 0.5))

        val root = dialog.findViewById<LinearLayout>(R.id.dialogUpdateSourceRoot)
        val tvTitle = dialog.findViewById<TextView>(R.id.tvUpdateSourceDialogTitle)
        val tvSubtitle = dialog.findViewById<TextView>(R.id.tvUpdateSourceDialogSubtitle)
        val ivClose = dialog.findViewById<ImageView>(R.id.ivUpdateSourceDialogClose)
        val vDivider = dialog.findViewById<View>(R.id.vUpdateSourceDivider)

        val rowGithub = dialog.findViewById<LinearLayout>(R.id.rowSourceGithub)
        val tvGithubTitle = dialog.findViewById<TextView>(R.id.tvSourceGithubTitle)
        val tvGithubDesc = dialog.findViewById<TextView>(R.id.tvSourceGithubDesc)
        val badgeGithub = dialog.findViewById<TextView>(R.id.badgeSourceGithub)
        val rbGithub = dialog.findViewById<RadioButton>(R.id.rbSourceGithub)
        val ivGithub = dialog.findViewById<ImageView>(R.id.ivSourceGithubIcon)

        val rowUptodown = dialog.findViewById<LinearLayout>(R.id.rowSourceUptodown)
        val tvUptodownTitle = dialog.findViewById<TextView>(R.id.tvSourceUptodownTitle)
        val tvUptodownDesc = dialog.findViewById<TextView>(R.id.tvSourceUptodownDesc)
        val badgeUptodown = dialog.findViewById<TextView>(R.id.badgeSourceUptodown)
        val rbUptodown = dialog.findViewById<RadioButton>(R.id.rbSourceUptodown)
        val ivUptodown = dialog.findViewById<ImageView>(R.id.ivSourceUptodownIcon)

        val customAction = settingsRepository.customActionBtnColor
        val customText = settingsRepository.customTextColor

        val cardBgColor = if (isLightContext) Color.WHITE else ContextCompat.getColor(this, R.color.card)
        val strokeColor = if (isLightContext) Color.parseColor("#E2E8F0") else ContextCompat.getColor(this, R.color.card_stroke)
        val titleTextColor = if (customText != 0) customText else (if (isLightContext) Color.parseColor("#0F172A") else Color.parseColor("#F8FAFC"))
        val subtitleTextColor = if (customText != 0) ColorUtils.setAlphaComponent(customText, 190) else (if (isLightContext) Color.parseColor("#475569") else Color.parseColor("#94A3B8"))
        val accentColor = if (customAction != 0) customAction else (if (isLightContext) Color.parseColor("#B45309") else Color.parseColor("#F59E0B"))

        root.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 24f * density
            setColor(cardBgColor)
            setStroke((1.2f * density).toInt(), strokeColor)
        }

        tvTitle.setTextColor(titleTextColor)
        tvSubtitle.setTextColor(subtitleTextColor)
        vDivider.setBackgroundColor(strokeColor)
        ivClose.imageTintList = ColorStateList.valueOf(subtitleTextColor)

        tvGithubTitle.setTextColor(titleTextColor)
        tvGithubDesc.setTextColor(subtitleTextColor)
        tvUptodownTitle.setTextColor(titleTextColor)
        tvUptodownDesc.setTextColor(subtitleTextColor)

        ivGithub.imageTintList = ColorStateList.valueOf(accentColor)
        ivUptodown.imageTintList = ColorStateList.valueOf(subtitleTextColor)

        val badgeRecommendBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 8f * density
            setColor(ColorUtils.setAlphaComponent(accentColor, 35))
            setStroke((1f * density).toInt(), ColorUtils.setAlphaComponent(accentColor, 90))
        }
        badgeGithub.background = badgeRecommendBg
        badgeGithub.setTextColor(accentColor)

        val badgeModBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 8f * density
            setColor(ColorUtils.setAlphaComponent(Color.parseColor("#64748B"), 30))
            setStroke((1f * density).toInt(), ColorUtils.setAlphaComponent(Color.parseColor("#64748B"), 80))
        }
        badgeUptodown.background = badgeModBg
        badgeUptodown.setTextColor(Color.parseColor("#94A3B8"))

        rbGithub.buttonTintList = ColorStateList.valueOf(accentColor)
        rbUptodown.buttonTintList = ColorStateList.valueOf(accentColor)

        rowGithub.background = getCardDrawable(isLightContext, 14f)
        rowUptodown.background = getCardDrawable(isLightContext, 14f)

        val btnCheckNow = dialog.findViewById<LinearLayout>(R.id.btnDialogCheckUpdatesNow)
        val ivSpinner = dialog.findViewById<ImageView>(R.id.ivDialogCheckUpdatesSpinner)
        val tvCheckNow = dialog.findViewById<TextView>(R.id.tvDialogCheckUpdatesNow)

        btnCheckNow.background = getCardDrawable(isLightContext, 14f)
        tvCheckNow.setTextColor(titleTextColor)
        ivSpinner.imageTintList = ColorStateList.valueOf(accentColor)

        fun updateRadios(source: String) {
            rbGithub.isChecked = (source == SettingsRepository.UPDATE_SOURCE_GITHUB)
            rbUptodown.isChecked = (source == SettingsRepository.UPDATE_SOURCE_UPTODOWN)
        }

        updateRadios(settingsRepository.updateSource)

        val selectSource: (String, String) -> Unit = { newSource, name ->
            settingsRepository.updateSource = newSource
            updateRadios(newSource)
            updateUpdateSourceUI()
            Toast.makeText(this, getString(R.string.toast_source_fmt, name), Toast.LENGTH_SHORT).show()
        }

        rowGithub.setOnClickListener {
            AnimationHelper.bounceClick(rowGithub, minScale = 0.96f, durationMs = 150) {
                selectSource(SettingsRepository.UPDATE_SOURCE_GITHUB, "GitHub Releases")
            }
        }

        rowUptodown.setOnClickListener {
            AnimationHelper.bounceClick(rowUptodown, minScale = 0.96f, durationMs = 150) {
                selectSource(SettingsRepository.UPDATE_SOURCE_UPTODOWN, "Uptodown App Store")
            }
        }

        btnCheckNow.setOnClickListener {
            AnimationHelper.bounceClick(btnCheckNow, minScale = 0.96f, durationMs = 150)
            AnimationHelper.spin(ivSpinner, durationMs = 850, rotations = 1f)
            AppUpdateManager.checkForUpdates(this, settingsRepository, isManual = true)
        }

        ivClose.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
        AnimationHelper.popIn(root, durationMs = 240)
        AnimationHelper.attachPressAnimations(root)
    }


    private fun applyCurrentAppearance() {
        val preset = settingsRepository.themePreset
        val isLightPreset = preset == SettingsRepository.THEME_LIGHT
        val customBg = settingsRepository.customBgColor
        val customBgPath = settingsRepository.customBgImagePath
        val customConnect = settingsRepository.customConnectBtnColor
        val customAction = settingsRepository.customActionBtnColor
        val customText = settingsRepository.customTextColor
        val customSwitch = settingsRepository.customSwitchColor

        val hasWallpaper = !customBgPath.isNullOrEmpty() && File(customBgPath).exists()
        val isLightContext: Boolean

        if (hasWallpaper) {
            val bitmap = loadWallpaperBitmap(customBgPath!!)
            if (bitmap != null) {
                binding.ivCustomBackground.setImageBitmap(bitmap)
                binding.ivCustomBackground.visibility = View.VISIBLE
                binding.vBackgroundDim.visibility = View.VISIBLE
            } else {
                binding.ivCustomBackground.visibility = View.GONE
                binding.vBackgroundDim.visibility = View.GONE
            }
            binding.rootContainer.setBackgroundColor(Color.parseColor("#0A0B10"))
            isLightContext = false
        } else {
            binding.ivCustomBackground.visibility = View.GONE
            binding.vBackgroundDim.visibility = View.GONE

            val finalBg = if (customBg != 0) {
                customBg
            } else if (isLightPreset) {
                Color.parseColor("#F8FAFC")
            } else {
                ContextCompat.getColor(this, R.color.bg)
            }

            binding.rootContainer.setBackgroundColor(finalBg)
            isLightContext = ColorUtils.calculateLuminance(finalBg) > 0.5
        }

        val insetsController = WindowInsetsControllerCompat(window, window.decorView)
        insetsController.isAppearanceLightStatusBars = isLightContext
        insetsController.isAppearanceLightNavigationBars = isLightContext

        val baseConnectColor = if (customConnect != 0) {
            customConnect
        } else {
            Color.parseColor("#E8A33D")
        }

        val connectBg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            gradientType = GradientDrawable.RADIAL_GRADIENT
            gradientRadius = 100f * resources.displayMetrics.density
            setGradientCenter(0.38f, 0.32f)
            val lightColor = ColorUtils.blendARGB(baseConnectColor, Color.WHITE, 0.25f)
            val deepColor = ColorUtils.blendARGB(baseConnectColor, Color.BLACK, 0.25f)
            colors = intArrayOf(lightColor, baseConnectColor, deepColor)
        }

        val connectStyle = settingsRepository.connectBtnStyle
        if (connectStyle == SettingsRepository.STYLE_STANDARD) {
            binding.view3DButton.visibility = View.GONE
            binding.btnConnect.background = connectBg
            binding.ivPowerIcon.visibility = View.VISIBLE
            binding.tvSectionConnectBtn.visibility = View.VISIBLE
            binding.cardConnectBtnSection.visibility = View.VISIBLE
            binding.tvConnectBtnColorsNote.visibility = View.GONE
        } else {
            binding.view3DButton.visibility = View.VISIBLE
            binding.view3DButton.style = connectStyle
            binding.view3DButton.vpnState = currentVpnState
            binding.btnConnect.background = null
            binding.ivPowerIcon.visibility = View.GONE
            binding.tvSectionConnectBtn.visibility = View.GONE
            binding.cardConnectBtnSection.visibility = View.GONE
            binding.tvConnectBtnColorsNote.visibility = View.VISIBLE
        }

        val glowBg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(ColorUtils.setAlphaComponent(baseConnectColor, 40))
            setStroke((2 * resources.displayMetrics.density).toInt(), ColorUtils.setAlphaComponent(baseConnectColor, 160))
        }
        binding.connectGlowRing.background = glowBg

        val defaultActionColor = if (isLightContext) Color.parseColor("#1E293B") else ContextCompat.getColor(this, R.color.ink)
        val finalActionColor = if (customAction != 0) customAction else defaultActionColor
        val actionColorList = ColorStateList.valueOf(finalActionColor)

        binding.ivHeaderUpdateIcon.imageTintList = actionColorList
        binding.ivRefreshIcon.imageTintList = actionColorList
        binding.ivSettingsIcon.imageTintList = actionColorList
        binding.ivBackSettingsIcon.imageTintList = actionColorList
        binding.ivBackAppearanceIcon.imageTintList = actionColorList
        binding.ivBackLogsIcon.imageTintList = actionColorList
        binding.ivClearLogsIcon.imageTintList = actionColorList
        binding.ivBackPerAppIcon.imageTintList = actionColorList

        binding.btnHeaderUpdate.background = getButtonChipDrawable(isLightContext, 12f)
        binding.btnRefresh.background = getButtonChipDrawable(isLightContext, 12f)
        binding.btnSettings.background = getButtonChipDrawable(isLightContext, 12f)
        binding.btnLanguage.background = getButtonChipDrawable(isLightContext, 12f)
        binding.btnBackFromSettings.background = getButtonChipDrawable(isLightContext, 12f)
        binding.btnBackFromAppearance.background = getButtonChipDrawable(isLightContext, 12f)
        binding.btnBackFromLogs.background = getButtonChipDrawable(isLightContext, 12f)
        binding.btnClearLogs.background = getButtonChipDrawable(isLightContext, 12f)
        binding.btnBackFromPerApp.background = getButtonChipDrawable(isLightContext, 12f)

        val titleTextColor = if (customText != 0) {
            customText
        } else if (isLightContext) {
            Color.parseColor("#0F172A")
        } else {
            ContextCompat.getColor(this, R.color.ink)
        }

        val subtitleTextColor = if (customText != 0) {
            ColorUtils.setAlphaComponent(customText, 190)
        } else if (isLightContext) {
            Color.parseColor("#475569")
        } else {
            ContextCompat.getColor(this, R.color.ink_soft)
        }
        themedSubtitleColor = subtitleTextColor

        val sectionHeaderColor = if (customText != 0) {
            ColorUtils.setAlphaComponent(customText, 220)
        } else if (isLightContext) {
            Color.parseColor("#0F172A")
        } else {
            Color.parseColor("#E2E8F0")
        }

        binding.tvBrandName.setTextColor(titleTextColor)
        (binding.btnLanguage.getChildAt(0) as? TextView)?.let {
            it.text = settingsRepository.appLanguage.uppercase()
            it.setTextColor(titleTextColor)
        }
        binding.tvBrandSub.setTextColor(subtitleTextColor)
        binding.tvStatus.setTextColor(titleTextColor)
        binding.tvStatusSub.setTextColor(subtitleTextColor)

        binding.containerSpeedLive.background = getButtonChipDrawable(isLightContext, 16f)
        binding.ivSpeedDown.imageTintList = ColorStateList.valueOf(Color.parseColor("#10B981"))
        binding.ivSpeedUp.imageTintList = ColorStateList.valueOf(Color.parseColor("#00D2FF"))
        binding.tvSpeedDown.setTextColor(titleTextColor)
        binding.tvSpeedUp.setTextColor(titleTextColor)

        binding.btnSupport.background = getCardDrawable(isLightContext, 30f)
        binding.tvSupport.setTextColor(titleTextColor)
        binding.ivSupportIcon.imageTintList = ColorStateList.valueOf(Color.parseColor("#EF4444"))

        binding.btnTelegram.background = getCardDrawable(isLightContext, 30f)
        binding.tvTelegram.setTextColor(titleTextColor)
        binding.ivTelegramIcon.imageTintList = ColorStateList.valueOf(Color.parseColor("#2AABEE"))

        binding.btnSupportChat.background = getCardDrawable(isLightContext, 30f)
        binding.tvSupportChat.setTextColor(titleTextColor)
        binding.ivSupportChatIcon.imageTintList = ColorStateList.valueOf(Color.parseColor("#38BDF8"))

        styleInjectedRows(isLightContext, titleTextColor, subtitleTextColor, finalActionColor, sectionHeaderColor)

        applyAiModePillTheme()

        binding.hintCard.background = getCardDrawable(isLightContext, 18f)
        binding.tvHint.setTextColor(subtitleTextColor)
        binding.ivHintIcon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.accent))

        binding.tvSettingsTitle.setTextColor(titleTextColor)
        binding.cardAccount.root.background = getCardDrawable(isLightContext, 18f)
        binding.cardAccount.tvAccountPrompt.setTextColor(subtitleTextColor)
        binding.cardAccount.tvAccountLoggedOutTitle.setTextColor(titleTextColor)
        binding.cardAccount.ivAccountLoggedOutIcon.imageTintList = ColorStateList.valueOf(finalActionColor)
        binding.cardAccount.ivAccountEye.imageTintList = ColorStateList.valueOf(finalActionColor)
        binding.cardAccount.tvAccountEmail.setTextColor(titleTextColor)
        binding.tvSectionAccount.setTextColor(sectionHeaderColor)
        binding.tvSectionNotifications.setTextColor(sectionHeaderColor)
        binding.cardNotification.background = getCardDrawable(isLightContext, 18f)
        binding.ivStatusNotificationIcon.imageTintList = ColorStateList.valueOf(finalActionColor)
        binding.tvStatusNotificationTitle.setTextColor(titleTextColor)
        binding.tvStatusNotificationDesc.setTextColor(subtitleTextColor)

        binding.ivQuickSettingsTileIcon.imageTintList = ColorStateList.valueOf(finalActionColor)
        binding.tvQuickSettingsTileTitle.setTextColor(titleTextColor)
        binding.tvQuickSettingsTileDesc.setTextColor(subtitleTextColor)

        binding.tvSectionPrivacy.setTextColor(sectionHeaderColor)

        binding.cardKillSwitch.background = getCardDrawable(isLightContext, 18f)
        binding.ivKillSwitchIcon.imageTintList = ColorStateList.valueOf(finalActionColor)
        binding.tvKillSwitchTitle.setTextColor(titleTextColor)
        binding.tvKillSwitchDesc.setTextColor(subtitleTextColor)
        binding.tvSystemVpnDesc.setTextColor(subtitleTextColor)

        binding.ivAutoStartIcon.imageTintList = ColorStateList.valueOf(finalActionColor)
        binding.tvAutoStartTitle.setTextColor(titleTextColor)
        binding.tvAutoStartDesc.setTextColor(subtitleTextColor)

        binding.ivTelemetryIcon.imageTintList = ColorStateList.valueOf(finalActionColor)
        binding.tvTelemetryTitle.setTextColor(titleTextColor)
        binding.tvTelemetryDesc.setTextColor(subtitleTextColor)

        binding.tvSectionAppearance.setTextColor(sectionHeaderColor)
        binding.cardAppearance.background = getCardDrawable(isLightContext, 18f)
        binding.ivAppearanceIcon.imageTintList = ColorStateList.valueOf(finalActionColor)
        binding.tvAppearanceSettingTitle.setTextColor(titleTextColor)
        binding.tvAppearanceSettingDesc.setTextColor(subtitleTextColor)

        binding.tvSectionPerAppProxy.setTextColor(sectionHeaderColor)

        binding.cardDirectRu.background = getCardDrawable(isLightContext, 18f)
        binding.ivDirectRuIcon.imageTintList = ColorStateList.valueOf(finalActionColor)
        binding.tvDirectRuTitle.setTextColor(titleTextColor)
        binding.tvDirectRuDesc.setTextColor(subtitleTextColor)
        binding.tvGeoStatus.setTextColor(subtitleTextColor)
        binding.ivUpdateGeo.imageTintList = ColorStateList.valueOf(subtitleTextColor)

        binding.ivPerAppIcon.imageTintList = ColorStateList.valueOf(finalActionColor)
        binding.tvPerAppSettingTitle.setTextColor(titleTextColor)
        binding.tvPerAppSettingDesc.setTextColor(subtitleTextColor)

        binding.ivCustomWebsitesIcon.imageTintList = ColorStateList.valueOf(finalActionColor)
        binding.tvCustomWebsitesTitle.setTextColor(titleTextColor)
        binding.tvCustomWebsitesDesc.setTextColor(subtitleTextColor)

        binding.tvSectionLogs.setTextColor(sectionHeaderColor)

        val autoPingDensity = resources.displayMetrics.density

        binding.cardSpeed.background = getCardDrawable(isLightContext, 18f)
        binding.ivSpeedIcon.imageTintList = ColorStateList.valueOf(finalActionColor)
        binding.tvSpeedTitle.setTextColor(titleTextColor)
        binding.tvSpeedDesc.setTextColor(subtitleTextColor)
        binding.ivChevronSpeed.imageTintList = ColorStateList.valueOf(subtitleTextColor)
        binding.tvSpeedCurrentLabel.setTextColor(subtitleTextColor)

        val speedAccent = baseConnectColor
        val speedBadgeBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 8f * autoPingDensity
            setColor(ColorUtils.setAlphaComponent(speedAccent, 35))
            setStroke((1f * autoPingDensity).toInt(), ColorUtils.setAlphaComponent(speedAccent, 120))
        }
        binding.tvSpeedValueBadge.background = speedBadgeBg
        binding.tvSpeedValueBadge.setTextColor(speedAccent)
        binding.sbSpeed.thumbTintList = ColorStateList.valueOf(speedAccent)
        binding.sbSpeed.progressTintList = ColorStateList.valueOf(speedAccent)

        binding.ivAutoPingIcon.imageTintList = ColorStateList.valueOf(finalActionColor)
        binding.tvAutoPingTitle.setTextColor(titleTextColor)
        binding.tvAutoPingDesc.setTextColor(subtitleTextColor)
        binding.ivChevronAutoPing.imageTintList = ColorStateList.valueOf(subtitleTextColor)
        binding.tvAutoPingCurrentLabel.setTextColor(subtitleTextColor)

        val autoPingAccent = baseConnectColor
        val autoPingBadgeBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 8f * autoPingDensity
            setColor(ColorUtils.setAlphaComponent(autoPingAccent, 35))
            setStroke((1f * autoPingDensity).toInt(), ColorUtils.setAlphaComponent(autoPingAccent, 120))
        }
        binding.tvAutoPingValueBadge.background = autoPingBadgeBg
        binding.tvAutoPingValueBadge.setTextColor(autoPingAccent)
        binding.sbAutoPing.thumbTintList = ColorStateList.valueOf(autoPingAccent)
        binding.sbAutoPing.progressTintList = ColorStateList.valueOf(autoPingAccent)

        binding.ivLogsIcon.imageTintList = ColorStateList.valueOf(finalActionColor)
        binding.tvLogsSettingTitle.setTextColor(titleTextColor)
        binding.tvLogsSettingDesc.setTextColor(subtitleTextColor)

        binding.tvSectionUpdates.setTextColor(sectionHeaderColor)
        binding.cardCheckUpdates.background = getCardDrawable(isLightContext, 18f)
        binding.ivUpdateIcon.imageTintList = ColorStateList.valueOf(finalActionColor)
        binding.tvUpdateSettingTitle.setTextColor(titleTextColor)
        binding.tvUpdateSettingDesc.setTextColor(subtitleTextColor)

        binding.btnCheckUpdatesNow.background = getCardDrawable(isLightContext, 18f)
        binding.ivCheckUpdatesSpinner.imageTintList = ColorStateList.valueOf(finalActionColor)
        binding.tvCheckUpdatesActionText.setTextColor(finalActionColor)

        val legalTextColor = if (customText != 0) {
            ColorUtils.setAlphaComponent(customText, 230)
        } else if (isLightContext) {
            Color.parseColor("#1E293B")
        } else {
            Color.parseColor("#CBD5E1")
        }
        binding.btnTermsOfService.setTextColor(legalTextColor)
        binding.tvLegalDot.setTextColor(sectionHeaderColor)
        binding.btnPrivacyPolicy.setTextColor(legalTextColor)
        binding.tvAppVersion.setTextColor(sectionHeaderColor)

        binding.tvAppearanceTitle.setTextColor(titleTextColor)
        binding.tvSectionThemes.setTextColor(sectionHeaderColor)
        binding.tvSectionBg.setTextColor(sectionHeaderColor)
        binding.cardBgSection.background = getCardDrawable(isLightContext, 18f)
        binding.btnPickBgPhoto.background = getButtonChipDrawable(isLightContext, 14f)
        binding.tvPickPhoto.setTextColor(titleTextColor)
        binding.ivPickPhotoIcon.imageTintList = ColorStateList.valueOf(titleTextColor)

        binding.btnRemoveBgPhoto.background = getButtonChipDrawable(isLightContext, 14f)
        binding.ivRemovePhotoIcon.imageTintList = ColorStateList.valueOf(Color.parseColor("#EF4444"))

        binding.tvSectionConnectBtnStyle.setTextColor(sectionHeaderColor)
        binding.cardConnectBtnStyle.background = getCardDrawable(isLightContext, 18f)
        binding.tvStyleDescription.setTextColor(subtitleTextColor)
        binding.tvConnectBtnColorsNote.setTextColor(subtitleTextColor)

        binding.tvSectionConnectBtn.setTextColor(sectionHeaderColor)
        binding.cardConnectBtnSection.background = getCardDrawable(isLightContext, 18f)

        binding.tvSectionActionBtns.setTextColor(sectionHeaderColor)
        binding.cardActionBtnsSection.background = getCardDrawable(isLightContext, 18f)

        binding.tvSectionTextColor.setTextColor(sectionHeaderColor)
        binding.cardTextColorSection.background = getCardDrawable(isLightContext, 18f)

        binding.tvSectionSwitches.setTextColor(sectionHeaderColor)
        binding.cardSwitchSection.background = getCardDrawable(isLightContext, 18f)

        binding.btnResetAppearance.background = getButtonChipDrawable(isLightContext, 14f)

        binding.tvLogsTitle.setTextColor(titleTextColor)
        binding.tvSectionLogLevel.setTextColor(sectionHeaderColor)
        binding.cardLogViewer.background = getCardDrawable(isLightContext, 18f)
        binding.tvLogStatusHeader.setTextColor(subtitleTextColor)
        binding.tvLogOutput.setTextColor(titleTextColor)

        val density = resources.displayMetrics.density
        val chipBaseColor = if (isLightContext) Color.WHITE else ContextCompat.getColor(this, R.color.card)
        val inactiveStrokeColor = if (isLightContext) Color.parseColor("#CBD5E1") else ContextCompat.getColor(this, R.color.card_stroke)

        val downloadDrawable = GradientDrawable().apply {
            cornerRadius = 14f * density
            setColor(chipBaseColor)
            setStroke((1f * density).toInt(), inactiveStrokeColor)
        }
        binding.btnDownloadLogs.background = downloadDrawable
        binding.ivDownloadIcon.imageTintList = ColorStateList.valueOf(titleTextColor)
        binding.tvDownloadLogs.setTextColor(titleTextColor)

        val shareDrawable = GradientDrawable().apply {
            cornerRadius = 14f * density
            setColor(chipBaseColor)
            setStroke((1f * density).toInt(), inactiveStrokeColor)
        }
        binding.btnShareLogs.background = shareDrawable
        binding.ivShareIcon.imageTintList = ColorStateList.valueOf(titleTextColor)
        binding.tvShareLogs.setTextColor(titleTextColor)

        binding.tvPerAppHeaderTitle.setTextColor(titleTextColor)
        binding.tvPerAppStats.setTextColor(subtitleTextColor)
        binding.containerSearchApps.background = getCardDrawable(isLightContext, 14f)
        binding.ivSearchIcon.imageTintList = ColorStateList.valueOf(subtitleTextColor)
        binding.etSearchApps.setTextColor(titleTextColor)
        binding.etSearchApps.setHintTextColor(subtitleTextColor)
        binding.ivClearSearch.imageTintList = ColorStateList.valueOf(subtitleTextColor)
        binding.btnToggleAllApps.background = getButtonChipDrawable(isLightContext, 12f)
        binding.tvToggleAllApps.setTextColor(titleTextColor)
        binding.tvEmptyAppsSearch.setTextColor(subtitleTextColor)

        binding.bannerVpnActiveNotice.background = getCardDrawable(isLightContext, 14f)
        updateVpnActiveNoticeBanner(animated = false)

        appProxyAdapter?.isLightContext = isLightContext
        appProxyAdapter?.customTextColor = customText
        appProxyAdapter?.customSwitchColor = customSwitch
        appProxyAdapter?.notifyDataSetChanged()

        binding.tvCustomWebsitesHeaderTitle.setTextColor(titleTextColor)
        binding.tvCustomWebsitesHeaderSubtitle.setTextColor(subtitleTextColor)
        binding.ivBackCustomWebsitesIcon.imageTintList = ColorStateList.valueOf(titleTextColor)

        binding.containerAddWebsite.background = getCardDrawable(isLightContext, 14f)
        (binding.containerAddWebsite.getChildAt(0) as? ImageView)?.imageTintList =
            ColorStateList.valueOf(subtitleTextColor)
        binding.etAddWebsite.setTextColor(titleTextColor)
        binding.etAddWebsite.setHintTextColor(subtitleTextColor)
        binding.btnAddWebsite.background = getButtonChipDrawable(isLightContext, 12f)

        binding.containerSearchWebsites.background = getCardDrawable(isLightContext, 14f)
        (binding.containerSearchWebsites.getChildAt(0) as? ImageView)?.imageTintList =
            ColorStateList.valueOf(subtitleTextColor)
        binding.etSearchWebsites.setTextColor(titleTextColor)
        binding.etSearchWebsites.setHintTextColor(subtitleTextColor)
        binding.ivClearSearchWebsites.imageTintList = ColorStateList.valueOf(subtitleTextColor)

        binding.btnClearAllSites.background = getButtonChipDrawable(isLightContext, 12f)
        binding.tvEmptyWebsites.setTextColor(subtitleTextColor)

        binding.bannerWebsitesVpnNotice.background = getCardDrawable(isLightContext, 14f)
        updateWebsitesVpnNoticeBanner(animated = false)

        customWebsiteAdapter?.isLightContext = isLightContext
        customWebsiteAdapter?.customTextColor = customText
        customWebsiteAdapter?.customSwitchColor = customSwitch
        customWebsiteAdapter?.notifyDataSetChanged()

        binding.switchStatusNotification.setLightMode(isLightContext)
        binding.switchQuickSettingsTile.setLightMode(isLightContext)
        binding.switchKillSwitch.setLightMode(isLightContext)
        binding.switchAutoStart.setLightMode(isLightContext)
        binding.switchDirectRu.setLightMode(isLightContext)
        binding.switchTelemetry.setLightMode(isLightContext)
        if (customSwitch != 0) {
            binding.switchStatusNotification.setActiveColor(customSwitch)
            binding.switchQuickSettingsTile.setActiveColor(customSwitch)
            binding.switchKillSwitch.setActiveColor(customSwitch)
            binding.switchAutoStart.setActiveColor(customSwitch)
            binding.switchDirectRu.setActiveColor(customSwitch)
            binding.switchTelemetry.setActiveColor(customSwitch)
        } else {
            binding.switchStatusNotification.resetColors()
            binding.switchQuickSettingsTile.resetColors()
            binding.switchKillSwitch.resetColors()
            binding.switchAutoStart.resetColors()
            binding.switchDirectRu.resetColors()
            binding.switchTelemetry.resetColors()
        }

        binding.btnBackFromCustomWebsites.background = getButtonChipDrawable(isLightContext, 12f)
        (binding.btnAddWebsite.getChildAt(0) as? TextView)?.setTextColor(titleTextColor)
        binding.cardAccount.tvAccountCacheAge.setTextColor(subtitleTextColor)

        updateServerPlanSelectorUI()
        styleRuntimeCards(isLightContext, titleTextColor, subtitleTextColor, finalActionColor, baseConnectColor)

        updateThemeChipsState()
        updateLogLevelChipsState()
        updateConnectStyleChipsState()
        updatePerAppFilterChips()
        updateWebsiteFilterChips()
    }

    private fun styleRuntimeCards(
        isLightContext: Boolean,
        titleTextColor: Int,
        subtitleTextColor: Int,
        finalActionColor: Int,
        accentColor: Int
    ) {
        val density = resources.displayMetrics.density
        val actionPillBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 8f * density
            setColor(ColorUtils.setAlphaComponent(accentColor, 35))
            setStroke((1f * density).toInt(), ColorUtils.setAlphaComponent(accentColor, 120))
        }
        privateDnsCard?.let { card ->
            card.background = getCardDrawable(isLightContext, 18f)
            card.findViewById<ImageView>(R.id.ivPrivateDnsIcon)?.imageTintList =
                ColorStateList.valueOf(finalActionColor)
            card.findViewById<TextView>(R.id.tvPrivateDnsTitle)?.setTextColor(titleTextColor)
            card.findViewById<TextView>(R.id.tvPrivateDnsState)?.setTextColor(subtitleTextColor)
            card.findViewById<TextView>(R.id.tvPrivateDnsAction)?.let {
                it.background = actionPillBg
                it.setTextColor(accentColor)
            }
            card.findViewById<ImageView>(R.id.ivTunnelDnsIcon)?.imageTintList =
                ColorStateList.valueOf(finalActionColor)
            card.findViewById<TextView>(R.id.tvTunnelDnsTitle)?.setTextColor(titleTextColor)
            card.findViewById<TextView>(R.id.tvTunnelDnsState)?.setTextColor(subtitleTextColor)
        }
        nodeStatusCard?.let { card ->
            card.background = getCardDrawable(isLightContext, 18f)
            card.findViewById<ImageView>(R.id.ivNodeStatusIcon)?.imageTintList =
                ColorStateList.valueOf(finalActionColor)
            card.findViewById<ImageView>(R.id.ivFreeNodesIcon)?.imageTintList =
                ColorStateList.valueOf(finalActionColor)
            card.findViewById<ImageView>(R.id.ivNodeHistoryIcon)?.imageTintList =
                ColorStateList.valueOf(finalActionColor)
            card.findViewById<ImageView>(R.id.ivSessionHistoryIcon)?.imageTintList =
                ColorStateList.valueOf(finalActionColor)
            card.findViewById<TextView>(R.id.tvNodeStatusTitle)?.setTextColor(titleTextColor)
            card.findViewById<TextView>(R.id.tvNodeStatusState)?.setTextColor(subtitleTextColor)
            card.findViewById<TextView>(R.id.tvFreeNodesTitle)?.setTextColor(titleTextColor)
            card.findViewById<TextView>(R.id.tvFreeNodesState)?.setTextColor(subtitleTextColor)
            card.findViewById<TextView>(R.id.tvNodeHistoryTitle)?.setTextColor(titleTextColor)
            card.findViewById<TextView>(R.id.tvNodeHistory)?.setTextColor(subtitleTextColor)
            card.findViewById<TextView>(R.id.tvSessionHistoryTitle)?.setTextColor(titleTextColor)
            card.findViewById<TextView>(R.id.tvSessionHistoryDesc)?.setTextColor(subtitleTextColor)
            card.findViewById<TextView>(R.id.tvNodeStatusAction)?.let {
                it.background = actionPillBg
                it.setTextColor(accentColor)
            }
            card.findViewById<TextView>(R.id.tvFreeNodesAction)?.let {
                it.background = actionPillBg
                it.setTextColor(accentColor)
            }
        }
    }

    private fun saveAndApplyCustomBackground(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val destFile = File(filesDir, "custom_wallpaper.jpg")
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(destFile).use { output ->
                        input.copyTo(output)
                    }
                }
                withContext(Dispatchers.Main) {
                    settingsRepository.customBgColor = 0
                    settingsRepository.customBgImagePath = destFile.absolutePath
                    settingsRepository.themePreset = SettingsRepository.THEME_CUSTOM
                    applyCurrentAppearance()
                    setupAppearanceUI()
                    Toast.makeText(this@MainActivity, R.string.toast_wallpaper_set, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, R.string.toast_wallpaper_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun loadWallpaperBitmap(path: String): Bitmap? {
        return try {
            val file = File(path)
            if (!file.exists()) return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            val reqWidth = resources.displayMetrics.widthPixels.coerceAtLeast(1080)
            val reqHeight = resources.displayMetrics.heightPixels.coerceAtLeast(1920)
            val sampleSize = calculateInSampleSize(bounds, reqWidth, reqHeight)
            val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            BitmapFactory.decodeFile(path, opts)
        } catch (e: Throwable) {
            null
        }
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    override fun onDestroy() {
        super.onDestroy()
        connectBreathingHandle?.cancel()
        connectGlowHandle?.cancel()
        refreshAnimationJob?.cancel()
    }

    private fun openExternalLink(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Toast.makeText(this, url, Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        private const val STATUS_STALE_MS = 10 * 60 * 1000L
        private const val MAX_IMPORT_BYTES = 512 * 1024
        private const val LOG_FLUSH_MS = 250L
        const val ACTION_QUICK_CONNECT = "com.naua_security_mirage.app.ACTION_QUICK_CONNECT"
        const val EXTRA_SHOW_UPDATE = "extra_show_update"
    const val EXTRA_SHOW_SUBSCRIPTION = "extra_show_subscription"
    const val EXTRA_SHOW_SETTINGS = "extra_show_settings"
        const val EXTRA_UPDATE_TAG = "extra_update_tag"
        const val EXTRA_INSTALL_APK_PATH = "extra_install_apk_path"

        const val TERMS_URL =
            "https://docs.google.com/document/d/1Q0_MpGF5D1GGoFu2HcdTle0kvLb3S7L52x2XIc47eLw/edit?usp=sharing"
        const val PRIVACY_URL =
            "https://docs.google.com/document/d/1FrmDpGS3sC_kQYyv1feNO2G2XMQr_ZV4_GAS-Qm4y1I/edit?usp=sharing"

        private val AUTO_PING_STEPS = intArrayOf(0, 3, 5, 10, 15, 20, 30, 45, 60)

        private const val CONNECT_BUTTON_DP = 180f
        private const val GLOW_RATIO = 214f / 180f
        private val SPEED_STEPS = intArrayOf(0, 1, 2, 3, 5)

        private const val FILTER_ALL = 0
        private const val FILTER_PROXIED = 1
        private const val FILTER_BYPASSED = 2

        private val BG_COLORS = listOf(
            0xFF0A0B10.toInt(),
            0xFF000000.toInt(),
            0xFF0F172A.toInt(),
            0xFF1E1B4B.toInt(),
            0xFF14221A.toInt(),
            0xFF2A0845.toInt(),
            0xFF1C1917.toInt(),
            0xFFF8F9FA.toInt()
        )

        private val CONNECT_BTN_COLORS = listOf(
            0xFFE8A33D.toInt(),
            0xFF00D2FF.toInt(),
            0xFF10B981.toInt(),
            0xFF8B5CF6.toInt(),
            0xFFEF4444.toInt(),
            0xFFEC4899.toInt(),
            0xFF3B82F6.toInt(),
            0xFFE2E8F0.toInt()
        )

        private val ACTION_BTN_COLORS = listOf(
            0xFFF3F1EA.toInt(),
            0xFFE8A33D.toInt(),
            0xFF00D2FF.toInt(),
            0xFF10B981.toInt(),
            0xFF8B5CF6.toInt(),
            0xFFEF4444.toInt(),
            0xFF94A3B8.toInt()
        )

        private val TEXT_COLORS = listOf(
            0xFFF3F1EA.toInt(),
            0xFFE2E8F0.toInt(),
            0xFFFDE68A.toInt(),
            0xFFA7F3D0.toInt(),
            0xFFC4B5FD.toInt(),
            0xFFFCA5A5.toInt(),
            0xFF0F172A.toInt()
        )

        private val SWITCH_COLORS = listOf(
            0xFFE67E22.toInt(),
            0xFF00D2FF.toInt(),
            0xFF10B981.toInt(),
            0xFF8B5CF6.toInt(),
            0xFFEF4444.toInt(),
            0xFFF59E0B.toInt(),
            0xFFEC4899.toInt()
        )
    }
}
