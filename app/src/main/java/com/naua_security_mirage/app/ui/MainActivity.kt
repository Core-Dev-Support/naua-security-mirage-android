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
import androidx.core.content.FileProvider
import com.naua_security_mirage.app.BuildConfig
import com.naua_security_mirage.app.util.AppLogger
import com.naua_security_mirage.app.util.AppShield
import com.naua_security_mirage.app.util.AnimationHelper
import com.naua_security_mirage.app.util.AppUpdateManager
import android.widget.RadioButton
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.content.res.Configuration
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Toast
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.view.animation.LinearInterpolator
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
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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
            Toast.makeText(this, "Разрешение на VPN не получено", Toast.LENGTH_SHORT).show()
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

    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            performDownloadLogs()
        } else {
            Toast.makeText(this, "Разрешение на запись файлов не предоставлено", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppShield.checkIntegrity(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

        settingsRepository = SettingsRepository(this)
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
        binding.rootContainer.post { applyAdaptiveSizes() }
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
        val apkPath = intent.getStringExtra(EXTRA_INSTALL_APK_PATH)
        if (!apkPath.isNullOrEmpty()) {
            val file = java.io.File(apkPath)
            if (file.exists()) {
                AppUpdateManager.installApk(this, file)
                return
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
        if (data.scheme == "mirage" && data.host == "auth") {
            lifecycleScope.launch {
                val res = com.naua_security_mirage.app.data.supabase.SupabaseManager.instance.handleOAuthCallback(data)
                if (res.isSuccess) {
                    val user = res.getOrNull()
                    Toast.makeText(this@MainActivity, "Вход через Google выполнен: ${user?.email}", Toast.LENGTH_SHORT).show()
                    updateAccountCardUI()
                    pendingAuthSuccessCallback?.invoke()
                    pendingAuthSuccessCallback = null
                } else {
                    val err = res.exceptionOrNull()?.message ?: "Ошибка авторизации Google"
                    Toast.makeText(this@MainActivity, err, Toast.LENGTH_LONG).show()
                }
            }
        } else if (data.scheme == "mirage" && data.host == "payment") {
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
    }

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
            openExternalLink(getString(R.string.cloudtips_url))
        }

        binding.btnTelegram.setOnClickListener {
            AnimationHelper.bounceClick(binding.btnTelegram, minScale = 0.93f, durationMs = 180)
            openExternalLink(getString(R.string.telegram_channel_url))
        }

        binding.btnSupportChat.setOnClickListener {
            AnimationHelper.bounceClick(binding.btnSupportChat, minScale = 0.93f, durationMs = 180)
            openExternalLink(getString(R.string.support_chat_url))
        }

        binding.btnSettings.setOnClickListener {
            AnimationHelper.bounceClick(binding.btnSettings, minScale = 0.88f, durationMs = 180)
            openSettings()
        }

        binding.btnBackFromSettings.setOnClickListener {
            AnimationHelper.bounceClick(binding.btnBackFromSettings, minScale = 0.88f, durationMs = 180)
            closeSettings()
        }

        setupServerPlanSelector()
    }

    private fun setupSettings() {
        setupAccountCard()

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
            showVpnActiveReconnectionNoticeIfNeeded()
        }
        binding.rowKillSwitch.setOnClickListener {
            binding.switchKillSwitch.toggle()
        }

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
            try {
                val intent = Intent("android.net.vpn.SETTINGS")
                startActivity(intent)
            } catch (e: Exception) {
                try {
                    val fallbackIntent = Intent(android.provider.Settings.ACTION_VPN_SETTINGS)
                    startActivity(fallbackIntent)
                } catch (e2: Exception) {
                    Toast.makeText(this, "Не удалось открыть системные настройки VPN", Toast.LENGTH_SHORT).show()
                }
            }
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

        binding.tvAppVersion.text = getString(R.string.app_version, BuildConfig.VERSION_NAME)

        binding.tvAppVersion.setOnClickListener {
            AnimationHelper.bounceClick(binding.tvAppVersion, minScale = 0.95f, durationMs = 150)
            AnimationHelper.spin(binding.ivCheckUpdatesSpinner, durationMs = 850, rotations = 1f)
            AppUpdateManager.checkForUpdates(this, settingsRepository, isManual = true)
        }
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
        if (isFrance) {
            binding.containerServerPlan.btnPlanFrance.background = ContextCompat.getDrawable(this, R.drawable.bg_pill_active)
            binding.containerServerPlan.btnPlanFrance.setTextColor(Color.WHITE)
            binding.containerServerPlan.btnPlanFree.background = ColorDrawable(Color.TRANSPARENT)
            binding.containerServerPlan.btnPlanFree.setTextColor(ContextCompat.getColor(this, R.color.ink_soft))
        } else {
            binding.containerServerPlan.btnPlanFree.background = ContextCompat.getDrawable(this, R.drawable.bg_pill_active)
            binding.containerServerPlan.btnPlanFree.setTextColor(Color.WHITE)
            binding.containerServerPlan.btnPlanFrance.background = ColorDrawable(Color.TRANSPARENT)
            binding.containerServerPlan.btnPlanFrance.setTextColor(ContextCompat.getColor(this, R.color.ink_soft))
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
            binding.cardAccount.tvAccountEmail.text = user.email

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
            } else {
                binding.cardAccount.tvAccountSubscriptionStatus.text = getString(R.string.sub_free_status)
                binding.cardAccount.tvAccountSubscriptionStatus.setTextColor(Color.parseColor("#F59E0B"))
                binding.cardAccount.tvAccountSubscribeLabel.text = getString(R.string.sub_buy_button)
                binding.cardAccount.btnAccountSubscribe.visibility = View.VISIBLE
            }
        }
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
                Toast.makeText(this, "Не удалось открыть браузер для оплаты", Toast.LENGTH_SHORT).show()
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
            0 -> "Отключено"
            3 -> "3 сек. (по умолчанию)"
            60 -> "1 минута"
            else -> "$intervalSec сек."
        }
        val descText = when (intervalSec) {
            0 -> "Отключено"
            3 -> "3 сек. (по умолч.)"
            60 -> "1 минута"
            else -> "$intervalSec сек."
        }
        binding.tvAutoPingValueBadge.text = badgeText
        binding.tvAutoPingDesc.text = getString(R.string.setting_auto_ping_desc, descText)
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
                binding.viewSettings.smoothScrollTo(0, binding.cardAutoPing.top)
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
            0 -> "Отключено"
            1 -> "1 сек. (по умолчанию)"
            else -> "$intervalSec сек."
        }
        val descText = when (intervalSec) {
            0 -> "Отключено"
            1 -> "1 сек. (по умолч.)"
            else -> "$intervalSec сек."
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
                binding.viewSettings.smoothScrollTo(0, binding.cardSpeed.top)
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

        val rotateAnim = android.view.animation.RotateAnimation(
            0f, 360f,
            android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f,
            android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f
        ).apply {
            duration = 900
            repeatCount = android.view.animation.Animation.INFINITE
            interpolator = LinearInterpolator()
        }
        binding.ivUpdateGeo.startAnimation(rotateAnim)

        lifecycleScope.launch {
            val result = geoRoutingRepository.updateGeoFiles(force = true)
            binding.ivUpdateGeo.clearAnimation()
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
        checkNotificationPermission()
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            vpnConsentLauncher.launch(prepareIntent)
        } else {
            MirageVpnService.start(this)
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
            while (isActive && isRefreshing) {
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
                    val servers = vlessKeyRepository.getVlessServers()
                    AppLogger.d("DataRefresh", "Получено конфигураций: ${servers.size}. Измерение пинга...")
                    val measured = pingRepository.measureAllPings(servers)
                    measured.forEach { s ->
                        val pingStr = if (s.pingMs in 1..9998) "${s.pingMs} ms" else "таймаут"
                        AppLogger.d("DataRefresh", "Узел ${s.tag} -> пинг: $pingStr")
                    }
                    val best = pingRepository.selectBestServer(measured)
                    val pingText = if (best.pingMs in 1..9998) "${best.pingMs} ms" else "Доступен"
                    AppLogger.i("DataRefresh", "Обновление данных завершено. Выбран оптимальный узел: ${best.tag} (пинг: $pingText)")
                    withContext(Dispatchers.Main) {
                        isRefreshing = false
                        refreshAnimationJob?.cancel()
                        binding.ivRefreshIcon.rotation = 0f
                        if (MirageVpnService.vpnState.value == VpnState.DISCONNECTED) {
                            lastMeasuredServerInfo = pingText
                            binding.tvStatusSub.text = pingText
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
                        val pingStr = if (pingRes in 1..9998) "$pingRes ms" else "таймаут"
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
            Toast.makeText(this, "Фоновое изображение удалено", Toast.LENGTH_SHORT).show()
        }

        val isLightPreset = settingsRepository.themePreset == SettingsRepository.THEME_LIGHT
        val defaultBg = if (isLightPreset) 0xFFF8F9FA.toInt() else 0xFF0A0B10.toInt()
        setupColorRow("Задний фон", binding.containerBgColors, BG_COLORS, settingsRepository.customBgColor, defaultBg) { color ->
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

        setupColorRow("Кнопка подключения", binding.containerConnectBtnColors, CONNECT_BTN_COLORS, settingsRepository.customConnectBtnColor, 0xFFE8A33D.toInt()) { color ->
            settingsRepository.customConnectBtnColor = color
            applyCurrentAppearance()
            setupAppearanceUI()
        }

        val defaultAction = if (isLightPreset) 0xFF1E293B.toInt() else 0xFFF3F1EA.toInt()
        setupColorRow("Верхние кнопки", binding.containerActionBtnColors, ACTION_BTN_COLORS, settingsRepository.customActionBtnColor, defaultAction) { color ->
            settingsRepository.customActionBtnColor = color
            applyCurrentAppearance()
            setupAppearanceUI()
        }

        val defaultText = if (isLightPreset) 0xFF0F172A.toInt() else 0xFFF3F1EA.toInt()
        setupColorRow("Цвет текста", binding.containerTextColors, TEXT_COLORS, settingsRepository.customTextColor, defaultText) { color ->
            settingsRepository.customTextColor = color
            applyCurrentAppearance()
            setupAppearanceUI()
        }

        setupColorRow("Переключатели", binding.containerSwitchColors, SWITCH_COLORS, settingsRepository.customSwitchColor, 0xFFE67E22.toInt()) { color ->
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
            SettingsRepository.STYLE_3D_CYBER_EARTH -> getString(R.string.connect_style_cyber_earth_desc) + " — неоновые континенты, меридианы и узлы трафика."
            SettingsRepository.STYLE_3D_QUANTUM_CORE -> getString(R.string.connect_style_quantum_core_desc) + " — вращающиеся 3D-орбитали и квантовое ядро."
            SettingsRepository.STYLE_3D_HOLO_SHIELD -> getString(R.string.connect_style_holo_shield_desc) + " — геодезический купол с лазерным сканированием."
            SettingsRepository.STYLE_3D_REALISTIC_EARTH -> getString(R.string.connect_style_realistic_earth_desc) + " — естественные материки, океаны и ночные огни."
            else -> getString(R.string.connect_style_standard_desc) + " — перекрашивается под темы и цвета."
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
                    binding.tvLogOutput.text = if (settingsRepository.logLevel == SettingsRepository.LOG_LEVEL_NONE) {
                        "Сбор логов отключен (None)\n"
                    } else {
                        "Логи отсутствуют\n"
                    }
                    return@collect
                }
                if (binding.viewLogs.visibility == View.VISIBLE) {
                    val currentText = binding.tvLogOutput.text.toString().trim()
                    if (currentText.isEmpty() || currentText == "Логи отсутствуют" || currentText.startsWith("Сбор логов отключен")) {
                        binding.tvLogOutput.text = "$line\n"
                    } else {
                        binding.tvLogOutput.append("$line\n")
                    }
                    binding.scrollLogOutput.post {
                        binding.scrollLogOutput.fullScroll(View.FOCUS_DOWN)
                    }
                }
            }
        }
    }

    private fun refreshLogViewer() {
        val logs = AppLogger.getAllLogs()
        if (logs.isEmpty()) {
            binding.tvLogOutput.text = if (settingsRepository.logLevel == SettingsRepository.LOG_LEVEL_NONE) {
                "Сбор логов отключен (None)"
            } else {
                "Логи отсутствуют"
            }
        } else {
            binding.tvLogOutput.text = logs.joinToString("\n") + "\n"
            binding.scrollLogOutput.post {
                binding.scrollLogOutput.fullScroll(View.FOCUS_DOWN)
            }
        }
    }

    private fun updateLogLevelChipsState() {
        val currentLevel = settingsRepository.logLevel
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
            Toast.makeText(this, "Сбор логов отключен (уровень None), журнал пуст", Toast.LENGTH_SHORT).show()
            return
        }
        val content = AppLogger.getAllLogsFormatted()
        if (content.isBlank() || AppLogger.getAllLogs().isEmpty()) {
            Toast.makeText(this, "Журнал логов пуст", Toast.LENGTH_SHORT).show()
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
        val timeStamp = java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", java.util.Locale.getDefault()).format(java.util.Date())
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
                        throw Exception("Не удалось создать запись в MediaStore")
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
                    Toast.makeText(this@MainActivity, "Лог сохранен: $savedPath", Toast.LENGTH_LONG).show()
                }
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Ошибка сохранения файла: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun shareLogs() {
        val level = settingsRepository.logLevel
        if (level == SettingsRepository.LOG_LEVEL_NONE && AppLogger.getAllLogs().isEmpty()) {
            Toast.makeText(this, "Сбор логов отключен (уровень None), журнал пуст", Toast.LENGTH_SHORT).show()
            return
        }
        val content = AppLogger.getAllLogsFormatted() + "\n\n" + com.naua_security_mirage.app.util.LogHelper.collectLogs(this).readText()
        if (content.isBlank() || AppLogger.getAllLogs().isEmpty()) {
            Toast.makeText(this, "Журнал логов пуст", Toast.LENGTH_SHORT).show()
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
                        putExtra(Intent.EXTRA_TEXT, "Логи NAUA Security Mirage ($level)")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(shareIntent, "Поделиться логами"))
                }
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Ошибка отправки: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun updatePerAppSettingDescription() {
        val bypassedCount = settingsRepository.bypassedAppPackages.size
        binding.tvPerAppSettingDesc.text = if (bypassedCount == 0) {
            "Все приложения включены • Нажмите для настройки."
        } else {
            "Исключено приложений: $bypassedCount • Нажмите для настройки."
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
            showVpnActiveReconnectionNoticeIfNeeded()
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
            showVpnActiveReconnectionNoticeIfNeeded()
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
        binding.tvPerAppStats.text = "Загрузка списка приложений..."

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
        if (MirageVpnService.vpnState.value == VpnState.CONNECTED) {
            updateVpnActiveNoticeBanner(animated = true)
            updateWebsitesVpnNoticeBanner(animated = true)
            Toast.makeText(this, "Переподключитесь к VPN, чтобы настройки вступили в силу", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateVpnActiveNoticeBanner(animated: Boolean = false) {
        val isVpnConnected = MirageVpnService.vpnState.value == VpnState.CONNECTED
        if (isVpnConnected) {
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
        binding.tvPerAppStats.text = "Всего: $total • Включено: $proxied • Исключено: $bypassed"
        binding.tvFilterAll.text = "Все ($total)"
        binding.tvFilterProxied.text = "Включенные ($proxied)"
        binding.tvFilterBypassed.text = "Исключенные ($bypassed)"
        updatePerAppSettingDescription()
    }

    private fun updateToggleAllButtonState() {
        val allProxied = allInstalledApps.isNotEmpty() && allInstalledApps.all { it.isProxied }
        binding.tvToggleAllApps.text = if (allProxied) "Исключить все" else "Выбрать все"
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
        if (isVpnConnected) {
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

        if (domain.length < 3 || !domain.contains('.') || domain.contains(' ') || domain.startsWith('.') || domain.endsWith('.')) {
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
            "Исключено сайтов: $activeCount из ${sites.size} • Нажмите для настройки."
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
        binding.tvCustomWebsitesHeaderSubtitle.text = "Всего: $total • Включено: $active • Отключено: $disabled"
        binding.tvFilterAllSites.text = "Все ($total)"
        binding.tvFilterActiveSites.text = "Включенные ($active)"
        binding.tvFilterDisabledSites.text = "Отключенные ($disabled)"
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
                showVpnActiveReconnectionNoticeIfNeeded()
            },
            onDelete = { site ->
                settingsRepository.removeCustomWebsite(site.domain)
                allCustomWebsites = allCustomWebsites.filterNot { it.domain.equals(site.domain, ignoreCase = true) }
                updateCustomWebsitesStats()
                filterAndDisplayWebsites(forceNotify = true)
                showVpnActiveReconnectionNoticeIfNeeded()
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
            binding.etAddWebsite.setText("")
            val imm = getSystemService(INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
            imm?.hideSoftInputFromWindow(binding.etAddWebsite.windowToken, 0)
            loadCustomWebsites()
            showVpnActiveReconnectionNoticeIfNeeded()
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
            showVpnActiveReconnectionNoticeIfNeeded()
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

        tvTitle.text = "Выбор цвета: $title"

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
            Toast.makeText(this, "Источник обновлений: $name", Toast.LENGTH_SHORT).show()
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

        val sectionHeaderColor = if (customText != 0) {
            ColorUtils.setAlphaComponent(customText, 220)
        } else if (isLightContext) {
            Color.parseColor("#0F172A")
        } else {
            Color.parseColor("#E2E8F0")
        }

        binding.tvBrandName.setTextColor(titleTextColor)
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

        binding.hintCard.background = getCardDrawable(isLightContext, 18f)
        binding.tvHint.setTextColor(subtitleTextColor)
        binding.ivHintIcon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.accent))

        binding.tvSettingsTitle.setTextColor(titleTextColor)
        binding.cardAccount.root.background = getCardDrawable(isLightContext, 18f)
        binding.cardAccount.tvAccountPrompt.setTextColor(subtitleTextColor)
        binding.cardAccount.tvAccountEmail.setTextColor(titleTextColor)
        binding.tvSectionNotifications.setTextColor(sectionHeaderColor)
        binding.cardNotification.background = getCardDrawable(isLightContext, 18f)
        binding.ivStatusNotificationIcon.imageTintList = ColorStateList.valueOf(finalActionColor)
        binding.tvStatusNotificationTitle.setTextColor(titleTextColor)
        binding.tvStatusNotificationDesc.setTextColor(subtitleTextColor)

        binding.cardQuickSettingsTile.background = getCardDrawable(isLightContext, 18f)
        binding.ivQuickSettingsTileIcon.imageTintList = ColorStateList.valueOf(finalActionColor)
        binding.tvQuickSettingsTileTitle.setTextColor(titleTextColor)
        binding.tvQuickSettingsTileDesc.setTextColor(subtitleTextColor)

        binding.tvSectionPrivacy.setTextColor(sectionHeaderColor)

        binding.cardKillSwitch.background = getCardDrawable(isLightContext, 18f)
        binding.ivKillSwitchIcon.imageTintList = ColorStateList.valueOf(finalActionColor)
        binding.tvKillSwitchTitle.setTextColor(titleTextColor)
        binding.tvKillSwitchDesc.setTextColor(subtitleTextColor)
        binding.tvSystemVpnDesc.setTextColor(subtitleTextColor)

        binding.cardAutoStart.background = getCardDrawable(isLightContext, 18f)
        binding.ivAutoStartIcon.imageTintList = ColorStateList.valueOf(finalActionColor)
        binding.tvAutoStartTitle.setTextColor(titleTextColor)
        binding.tvAutoStartDesc.setTextColor(subtitleTextColor)

        binding.cardPrivacy.background = getCardDrawable(isLightContext, 18f)
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

        binding.cardPerAppProxy.background = getCardDrawable(isLightContext, 18f)
        binding.ivPerAppIcon.imageTintList = ColorStateList.valueOf(finalActionColor)
        binding.tvPerAppSettingTitle.setTextColor(titleTextColor)
        binding.tvPerAppSettingDesc.setTextColor(subtitleTextColor)

        binding.cardCustomWebsites.background = getCardDrawable(isLightContext, 18f)
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

        binding.cardAutoPing.background = getCardDrawable(isLightContext, 18f)
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

        binding.cardLogs.background = getCardDrawable(isLightContext, 18f)
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
        binding.etAddWebsite.setTextColor(titleTextColor)
        binding.etAddWebsite.setHintTextColor(subtitleTextColor)
        binding.btnAddWebsite.background = getButtonChipDrawable(isLightContext, 12f)

        binding.containerSearchWebsites.background = getCardDrawable(isLightContext, 14f)
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

        updateThemeChipsState()
        updateLogLevelChipsState()
        updateConnectStyleChipsState()
        updatePerAppFilterChips()
        updateWebsiteFilterChips()
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
                    Toast.makeText(this@MainActivity, "Фоновое изображение установлено", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Не удалось загрузить изображение", Toast.LENGTH_SHORT).show()
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
        const val ACTION_QUICK_CONNECT = "com.naua_security_mirage.app.ACTION_QUICK_CONNECT"
        const val EXTRA_SHOW_UPDATE = "extra_show_update"
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
