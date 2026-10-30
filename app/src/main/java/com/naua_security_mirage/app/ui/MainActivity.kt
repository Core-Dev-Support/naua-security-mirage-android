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
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import androidx.core.content.FileProvider
import com.naua_security_mirage.app.BuildConfig
import com.naua_security_mirage.app.util.AppLogger
import com.naua_security_mirage.app.vpn.PrivateDnsInspector
import com.naua_security_mirage.app.util.AnimationHelper
import com.naua_security_mirage.app.util.AppUpdateManager
import com.naua_security_mirage.app.data.model.DnsServer
import com.naua_security_mirage.app.util.DohTester
import com.naua_security_mirage.app.util.DnsProbeResult
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
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.text.HtmlCompat
import androidx.lifecycle.lifecycleScope
import androidx.transition.AutoTransition
import androidx.transition.TransitionManager
import com.naua_security_mirage.app.R
import com.naua_security_mirage.app.data.model.VpnState
import com.naua_security_mirage.app.data.model.VlessServer
import com.naua_security_mirage.app.data.config.AppConfig
import com.naua_security_mirage.app.data.config.AppConfigRepository
import com.naua_security_mirage.app.data.config.PaidNode
import com.naua_security_mirage.app.data.config.SubscriptionPlan
import com.naua_security_mirage.app.data.repository.DeviceIdRepository
import com.naua_security_mirage.app.data.repository.GeoRoutingRepository
import com.naua_security_mirage.app.data.repository.PingRepository
import com.naua_security_mirage.app.data.repository.SettingsRepository
import com.naua_security_mirage.app.data.repository.VlessKeyRepository
import com.naua_security_mirage.app.databinding.ActivityMainBinding
import com.naua_security_mirage.app.vpn.BelarusNodeMonitor
import com.naua_security_mirage.app.vpn.MirageVpnService
import com.naua_security_mirage.app.vpn.NodeEventStore
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
import android.widget.PopupWindow
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
import android.animation.Animator
import android.animation.ValueAnimator
import android.annotation.ColorRes
import android.annotation.DrawableRes
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var settingsRepository: SettingsRepository
    private var uiStyleRow: LinearLayout? = null
    private var uiStyleSwitch: MirageSwitch? = null
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

    private var currentGlassTab = GLASS_TAB_HOME
    private var lastNodeCore: String? = null
    private val glassSyncHandler = Handler(Looper.getMainLooper())
    private val glassSyncRunnable = object : Runnable {
        override fun run() {
            syncGlassShell()
            glassSyncHandler.postDelayed(this, 700L)
        }
    }

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

    private var pendingStorageAction: (() -> Unit)? = null

    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        val action = pendingStorageAction
        pendingStorageAction = null
        if (isGranted) {
            action?.invoke()
        } else {
            Toast.makeText(this, R.string.toast_storage_denied, Toast.LENGTH_SHORT).show()
        }
    }

    private fun withLegacyStoragePermission(action: () -> Unit) {
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            action()
            return
        }
        pendingStorageAction = action
        storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = SettingsRepository(this)
        if (prefs.uiStyle == SettingsRepository.UI_STYLE_GLASS) {
            setTheme(R.style.Theme_Mirage_Glass)
        }
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

        settingsRepository = prefs
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
        maybeShowPrivateDnsDialog()

        lifecycleScope.launch(Dispatchers.IO) {
            delay(2500)
            com.naua_security_mirage.app.work.SubscriptionExpiryNotifier.check(this@MainActivity)
        }

        // Первый запрос подписки мог не пройти из-за сети. Повторяем при
        // запуске, пока нет подтверждённого кэша.
        lifecycleScope.launch {
            delay(4000)
            val supabase = com.naua_security_mirage.app.data.supabase.SupabaseManager.instance
            val age = supabase.cacheAgeMinutes()
            val active = supabase.subscription.value?.isActive == true
            if (supabase.currentUser.value != null && (!active || age < 0 || age > 10)) {
                supabase.refreshSubscription()
            }
        }

        if (intent?.action == ACTION_QUICK_CONNECT &&
            com.naua_security_mirage.app.util.OneTimeToken.consume(
                intent.getStringExtra(EXTRA_QUICK_CONNECT_TOKEN)
            )
        ) {
            handleConnectButtonClick()
        }
        handleAuthDeepLink(intent)
        handleUpdateIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent?.action == ACTION_QUICK_CONNECT &&
            com.naua_security_mirage.app.util.OneTimeToken.consume(
                intent.getStringExtra(EXTRA_QUICK_CONNECT_TOKEN)
            )
        ) {
            handleConnectButtonClick()
        }
        handleAuthDeepLink(intent)
        handleUpdateIntent(intent)
    }

    private fun handleUpdateIntent(intent: Intent?) {
        if (intent == null) return
        // Забираем значения и сразу убираем их из intent: при пересоздании
        // Activity (поворот, смена темы) установка APK или диалог обновления
        // не должны сработать повторно.
        val installPath = intent.getStringExtra(EXTRA_INSTALL_APK_PATH)
        val showUpdate = intent.getBooleanExtra(EXTRA_SHOW_UPDATE, false)
        intent.removeExtra(EXTRA_INSTALL_APK_PATH)
        intent.removeExtra(EXTRA_SHOW_UPDATE)
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
        if (!installPath.isNullOrEmpty()) {
            try {
                val updatesDir = java.io.File(cacheDir, "updates").canonicalFile
                val file = java.io.File(installPath).canonicalFile
                if (file.exists() && file.parentFile == updatesDir) {
                    AppUpdateManager.installApk(this, file)
                    return
                } else {
                    AppLogger.w("DeepLink", "APK вне папки обновлений отклонён")
                }
            } catch (t: Throwable) {
                AppLogger.w("DeepLink", "Некорректный путь APK: ${t.message}")
            }
        }
        if (showUpdate) {
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
            // Интент остаётся у Activity: при пересоздании экрана (поворот,
            // смена темы) он придёт снова, а попытка входа к тому моменту уже
            // израсходована — получилось бы ложное «ответ не соответствует».
            intent.data = null
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
                    // Появился токен сессии — привязываем устройство к аккаунту
                    // сразу, не дожидаясь следующего запуска приложения.
                    com.naua_security_mirage.app.push.MirageMessagingService.refresh()
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
        // Deep link может прислать любое приложение. Реагируем только если
        // пользователь действительно начинал оплату в этой сессии; состояние
        // подписки всё равно подтверждается сервером.
        if (!isWaitingForPayment) return
        if (paymentPollingJob?.isActive == true) return
        paymentPollingJob = lifecycleScope.launch {
            try {
                val renewedUntil = awaitSubscriptionExtension()
                isWaitingForPayment = false
                if (renewedUntil != null) {
                    if (!settingsRepository.isPaidPlan()) {
                        settingsRepository.selectedServerPlan = settingsRepository.lastPaidPlan
                    }
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
        AppUpdateManager.checkPendingInstall(this)
        if (::geoRoutingRepository.isInitialized) {
            updateGeoStatusText()
        }
        if (isWaitingForPayment) {
            handlePaymentReturn()
        }
        updateAccountCardUI()
        AppConfigRepository.refresh()
        updateServerPlanSelectorUI()
        refreshPrivateDnsRow()
        refreshNodeStatus()
        refreshKillSwitchRow()
        syncSettingsSwitches()
        ensureCardRows()
        maybeShowPrivateDnsDialog()
        applyScreenshotBlocking()
        glassSyncHandler.removeCallbacks(glassSyncRunnable)
        stopAuroraTickLoop()
        if (settingsRepository.uiStyle == SettingsRepository.UI_STYLE_GLASS) {
            glassSyncHandler.post(glassSyncRunnable)
            startAuroraTickLoop()
        }
    }

    override fun onPause() {
        glassSyncHandler.removeCallbacks(glassSyncRunnable)
        stopAuroraTickLoop()
        super.onPause()
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

            auroraInsetsTop = insets.top
            auroraInsetsBottom = insets.bottom

            val aurora = binding.auroraShell
            aurora.auSheetContainer.setPadding(
                (16 * density).toInt(), 0, (16 * density).toInt(),
                (30 * density).toInt() + insets.bottom
            )
            applyAuroraScreenPadding()

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

        // Кнопка «Поблагодарить разработчика»: только способы доната.
        binding.btnSupport.setOnClickListener {
            AnimationHelper.bounceClick(binding.btnSupport, minScale = 0.93f, durationMs = 180)
            showSupportWaysDialog(showDonations = true)
        }

        // Кнопка Telegram: канал, поддержка и бот — своё меню, без донатов.
        binding.btnTelegram.setOnClickListener {
            AnimationHelper.bounceClick(binding.btnTelegram, minScale = 0.93f, durationMs = 180)
            showSupportWaysDialog(showDonations = false)
        }

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
        setupGlassShell()
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

    private fun syncSettingsSwitches() {
        binding.switchStatusNotification.setChecked(settingsRepository.isStatusNotificationEnabled, false)
        binding.switchQuickSettingsTile.setChecked(settingsRepository.isQuickSettingsTileEnabled, false)
        binding.switchKillSwitch.setChecked(settingsRepository.isKillSwitchEnabled, false)
        binding.switchAutoStart.setChecked(settingsRepository.isAutoStartOnBootEnabled, false)
        binding.switchDirectRu.setChecked(settingsRepository.isDirectRuEnabled, false)
        binding.switchTelemetry.setChecked(settingsRepository.isAnonymousTelemetryEnabled, false)
        rowPayment?.switch?.setChecked(settingsRepository.isPaymentNotificationEnabled, false)
        rowScreenshots?.switch?.setChecked(!settingsRepository.isScreenshotBlockingEnabled, false)
        uiStyleSwitch?.setLightMode(
            settingsRepository.uiStyle != SettingsRepository.UI_STYLE_GLASS &&
                settingsRepository.themePreset == SettingsRepository.THEME_LIGHT
        )
        updateUiStyleRow()
    }

    private data class PaidNodeChoice(val plan: String, val label: String, val flag: String)

    private fun paidNodeChoices(): List<PaidNodeChoice> {
        return AppConfigRepository.nodes().map { node ->
            PaidNodeChoice(node.id, node.label(), node.flag)
        }
    }

    private fun paidNodeLabel(plan: String): String {
        AppConfigRepository.node(plan)?.let { return it.label() }
        return when (plan) {
            SettingsRepository.PLAN_PREMIUM_BELARUS -> getString(R.string.plan_belarus)
            SettingsRepository.PLAN_PREMIUM_FRANCE -> getString(R.string.plan_france)
            else -> plan
        }
    }

    private fun paidNodePlanForLabel(): String {
        val stored = settingsRepository.lastPaidPlan
        if (AppConfigRepository.node(stored) != null) return stored
        return AppConfigRepository.nodes().firstOrNull()?.id ?: stored
    }

    private fun requestPaidPlan(plan: String) {
        val manager = com.naua_security_mirage.app.data.supabase.SupabaseManager.instance
        fun commit() {
            settingsRepository.lastPaidPlan = plan
            if (settingsRepository.selectedServerPlan == plan) return
            settingsRepository.selectedServerPlan = plan
            animateServerPlanSwitch(isPaid = true)
            updateServerPlanSelectorUI()
            handleServerPlanChanged()
        }
        if (manager.currentUser.value == null) {
            showAuthDialog {
                if (manager.hasActiveSubscription()) commit() else showSubscriptionDialog()
            }
            return
        }
        if (!manager.hasActiveSubscription()) {
            showSubscriptionDialog()
            return
        }
        commit()
    }

    private fun paidNodeFlagRes(plan: String): Int {
        val flag = AppConfigRepository.node(plan)?.flag ?: when (plan) {
            SettingsRepository.PLAN_PREMIUM_BELARUS -> "belarus"
            SettingsRepository.PLAN_PREMIUM_FRANCE -> "france"
            else -> "generic"
        }
        return flagDrawable(flag)
    }

    private fun flagDrawable(flag: String): Int = when (flag.lowercase()) {
        "france" -> R.drawable.ic_france
        "belarus" -> R.drawable.ic_belarus
        "finland" -> R.drawable.ic_finland
        else -> R.drawable.ic_node_generic
    }

    private class PlanPickerStyle(
        val density: Float,
        val cardBgColor: Int,
        val strokeColor: Int,
        val titleColor: Int,
        val bodyColor: Int,
        val accent: Int
    )

    private fun planPickerStyle(): PlanPickerStyle {
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
        val accent = ContextCompat.getColor(this, R.color.accent)
        return PlanPickerStyle(density, cardBgColor, strokeColor, titleColor, bodyColor, accent)
    }

    private fun planPickerList(style: PlanPickerStyle): LinearLayout {
        val density = style.density
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                (6f * density).toInt(),
                (6f * density).toInt(),
                (6f * density).toInt(),
                (6f * density).toInt()
            )
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 18f * density
                setColor(style.cardBgColor)
                setStroke((1.2f * density).toInt(), style.strokeColor)
            }
        }
    }

    private fun showPaidServerPicker() {
        val anchor = binding.containerServerPlan.btnPlanFranceGroup
        val choices = paidNodeChoices()
        if (choices.isEmpty()) return
        val style = planPickerStyle()
        val density = style.density
        val list = planPickerList(style)

        val popup = PopupWindow(
            list,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            true
        )
        popup.elevation = 12f * density

        val selectedPlan = if (settingsRepository.isPaidPlan()) {
            settingsRepository.selectedServerPlan
        } else {
            settingsRepository.lastPaidPlan
        }
        for ((index, choice) in choices.withIndex()) {
            if (index > 0) {
                list.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        (1f * density).toInt().coerceAtLeast(1)
                    )
                    setBackgroundColor(style.strokeColor)
                })
            }
            val selected = choice.plan == selectedPlan
            lateinit var row: LinearLayout
            row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                isClickable = true
                isFocusable = true
                setPadding(
                    (12f * density).toInt(),
                    (10f * density).toInt(),
                    (14f * density).toInt(),
                    (10f * density).toInt()
                )
                setOnClickListener {
                    AnimationHelper.bounceClick(row, minScale = 0.97f, durationMs = 140)
                    popup.dismiss()
                    requestPaidPlan(choice.plan)
                }
            }
            row.addView(ImageView(this).apply {
                setImageResource(flagDrawable(choice.flag))
                layoutParams = LinearLayout.LayoutParams(
                    (20f * density).toInt(),
                    (20f * density).toInt()
                )
                contentDescription = null
            })
            row.addView(TextView(this).apply {
                text = choice.label
                setTextColor(if (selected) style.accent else style.titleColor)
                textSize = 14f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginStart = (10f * density).toInt()
                }
            })
            if (selected) {
                row.addView(TextView(this).apply {
                    text = "✓"
                    setTextColor(style.accent)
                    textSize = 15f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        marginStart = (10f * density).toInt()
                    }
                })
            }
            list.addView(row)
        }

        popup.showAsDropDown(anchor, 0, (4f * density).toInt())
        AnimationHelper.popIn(list, durationMs = 150)
        AnimationHelper.attachPressAnimations(list)
    }
    private fun freeNodeKeyOf(server: VlessServer): String =
        listOf(server.address, server.port.toString(), server.uuid).joinToString("|")

    private fun showFreeServerPicker() {
        val anchor = binding.containerServerPlan.btnPlanFree
        val style = planPickerStyle()
        val density = style.density
        val list = planPickerList(style)
        val popup = PopupWindow(
            list,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            true
        )
        popup.elevation = 12f * density
        popup.setOnDismissListener { freePickerJob?.cancel() }

        fillFreePickerRows(list, popup, style, freePickerSnapshot)

        popup.showAsDropDown(anchor, 0, (4f * density).toInt())
        AnimationHelper.popIn(list, durationMs = 150)
        AnimationHelper.attachPressAnimations(list)

        freePickerJob?.cancel()
        freePickerJob = lifecycleScope.launch {
            val measured = withContext(Dispatchers.IO) {
                runCatching {
                    val servers = vlessKeyRepository.getVlessServers()
                    if (servers.isEmpty()) emptyList<VlessServer>() else pingRepository.measureAllPings(servers)
                }.getOrNull()
            } ?: return@launch
            freePickerSnapshot = measured
            if (!popup.isShowing) return@launch
            fillFreePickerRows(list, popup, style, measured)
            runCatching { popup.update() }
        }
    }

    private fun fillFreePickerRows(
        list: LinearLayout,
        popup: PopupWindow,
        style: PlanPickerStyle,
        servers: List<VlessServer>
    ) {
        val density = style.density
        val preferredKey = settingsRepository.preferredFreeNodeKey
        list.removeAllViews()

        val rows = mutableListOf<Pair<String?, String>>()
        rows.add(null to getString(R.string.free_server_auto))
        servers.forEachIndexed { index, server ->
            val ping = if (server.pingMs in 1..9998) {
                getString(R.string.picker_ping_format, server.pingMs.toInt())
            } else {
                getString(R.string.ping_timeout)
            }
            rows.add(
                freeNodeKeyOf(server) to
                        "${getString(R.string.free_server_node_format, index + 1)} · $ping"
            )
        }

        rows.forEachIndexed { index, row ->
            val key = row.first
            val label = row.second
            if (index > 0) {
                list.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        (1f * density).toInt().coerceAtLeast(1)
                    )
                    setBackgroundColor(style.strokeColor)
                })
            }
            val selected = if (key == null) preferredKey.isNullOrBlank() else key == preferredKey
            lateinit var rowView: LinearLayout
            rowView = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                isFocusable = true
                setPadding(
                    (12f * density).toInt(),
                    (10f * density).toInt(),
                    (14f * density).toInt(),
                    (10f * density).toInt()
                )
                setOnClickListener {
                    AnimationHelper.bounceClick(rowView, minScale = 0.97f, durationMs = 140)
                    popup.dismiss()
                    settingsRepository.preferredFreeNodeKey = key
                    applyFreeNodeChoice(label)
                }
            }
            rowView.addView(TextView(this).apply {
                text = label
                setTextColor(if (selected) style.accent else style.titleColor)
                textSize = 14f
                setTypeface(typeface, Typeface.BOLD)
                maxLines = 1
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            })
            if (selected) {
                rowView.addView(TextView(this).apply {
                    text = "✓"
                    setTextColor(style.accent)
                    textSize = 15f
                    setTypeface(typeface, Typeface.BOLD)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        marginStart = (10f * density).toInt()
                    }
                })
            }
            list.addView(rowView)
        }
    }

    private fun applyFreeNodeChoice(label: String) {
        AppLogger.i("FreeNodes", "Выбран бесплатный узел: $label")
        val active = MirageVpnService.vpnState.value == VpnState.CONNECTED ||
                MirageVpnService.vpnState.value == VpnState.CONNECTING
        if (active && settingsRepository.selectedServerPlan == SettingsRepository.PLAN_FREE) {
            AppLogger.i("FreeNodes", "Переподключение на выбранный бесплатный узел")
            MirageVpnService.reconnect(this)
        }
    }

    private fun setupServerPlanSelector() {
        updateServerPlanSelectorUI()

        lifecycleScope.launch {
            AppConfigRepository.config.collect {
                val before = settingsRepository.selectedServerPlan
                AppConfigRepository.applySettings()
                val after = settingsRepository.selectedServerPlan
                updateServerPlanSelectorUI()
                val active = MirageVpnService.vpnState.value == VpnState.CONNECTED ||
                        MirageVpnService.vpnState.value == VpnState.CONNECTING
                if (before != after && active) {
                    AppLogger.i(
                        "AppConfig",
                        "План '$before' убран из конфига, переключение на '$after'"
                    )
                    handleServerPlanChanged()
                }
            }
        }

        binding.containerServerPlan.btnPlanFree.setOnClickListener {
            if (settingsRepository.selectedServerPlan != SettingsRepository.PLAN_FREE) {
                settingsRepository.selectedServerPlan = SettingsRepository.PLAN_FREE
                animateServerPlanSwitch(isPaid = false)
                handleServerPlanChanged()
            }
        }

        binding.containerServerPlan.btnPlanFranceGroup.setOnClickListener {
            requestPaidPlan(paidNodePlanForLabel())
        }

        binding.containerServerPlan.btnPaidServerChevron.setOnClickListener {
            AnimationHelper.bounceClick(
                binding.containerServerPlan.btnPaidServerChevron,
                minScale = 0.86f,
                durationMs = 140
            )
            showPaidServerPicker()
        }
    }

    private fun animateServerPlanSwitch(isPaid: Boolean) {
        val activeView = if (isPaid) binding.containerServerPlan.btnPlanFranceGroup else binding.containerServerPlan.btnPlanFree
        val inactiveView = if (isPaid) binding.containerServerPlan.btnPlanFree else binding.containerServerPlan.btnPlanFranceGroup

        activeView.background = ContextCompat.getDrawable(this, R.drawable.bg_pill_active)
        inactiveView.background = ColorDrawable(Color.TRANSPARENT)

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
                val dx = if (isPaid) 3.5f * (1f - fraction) else -3.5f * (1f - fraction)
                container.translationX = dx
            },
            onEnd = {
                container.translationX = 0f
            }
        )
    }

    private fun handleServerPlanChanged() {
        // Анимация переключения меняет только фоны сегментов. Без пересчёта
        // цветов в светлой теме активная подпись остаётся тёмной на тёмной
        // пилюле, а неактивная — белой на белой карточке: текста не видно.
        updateServerPlanSelectorUI()
        val isVpnConnected = MirageVpnService.vpnState.value == VpnState.CONNECTED ||
                MirageVpnService.vpnState.value == VpnState.CONNECTING
        if (isVpnConnected) {
            MirageVpnService.reconnect(this)
        }
    }

    private fun updateServerPlanSelectorUI() {
        val nodes = AppConfigRepository.nodes()
        val hasPaidNodes = nodes.isNotEmpty()
        binding.containerServerPlan.btnPlanFranceGroup.visibility =
            if (hasPaidNodes) View.VISIBLE else View.GONE
        if (!hasPaidNodes && settingsRepository.selectedServerPlan != SettingsRepository.PLAN_FREE) {
            settingsRepository.selectedServerPlan = SettingsRepository.PLAN_FREE
        }
        val plan = settingsRepository.selectedServerPlan
        val isPaid = settingsRepository.isPaidPlan(plan) && AppConfigRepository.node(plan) != null
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
        val label = binding.containerServerPlan.btnPlanFrance
        val chevron = binding.containerServerPlan.btnPaidServerChevron
        val paidTarget = if (isPaid) plan else paidNodePlanForLabel()
        label.setText(paidNodeLabel(paidTarget))
        val flag = binding.containerServerPlan.ivPaidNodeFlag
        flag.visibility = View.VISIBLE
        flag.setImageResource(paidNodeFlagRes(paidTarget))
        val flagDensity = resources.displayMetrics.density
        label.setPaddingRelative(
            (2f * flagDensity).toInt(),
            label.paddingTop,
            label.paddingEnd,
            label.paddingBottom
        )
        label.setTextColor(if (isPaid) Color.WHITE else inactiveText)
        chevron.visibility = View.VISIBLE
        chevron.imageTintList = ColorStateList.valueOf(
            if (isPaid) Color.WHITE else ContextCompat.getColor(this, R.color.accent)
        )
        if (isPaid) {
            binding.containerServerPlan.btnPlanFranceGroup.background =
                ContextCompat.getDrawable(this, R.drawable.bg_pill_active)
            binding.containerServerPlan.btnPlanFree.background = ColorDrawable(Color.TRANSPARENT)
            binding.containerServerPlan.btnPlanFree.setTextColor(inactiveText)
        } else {
            binding.containerServerPlan.btnPlanFranceGroup.background = ColorDrawable(Color.TRANSPARENT)
            binding.containerServerPlan.btnPlanFree.background =
                ContextCompat.getDrawable(this, R.drawable.bg_pill_active)
            binding.containerServerPlan.btnPlanFree.setTextColor(Color.WHITE)
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
            if (settingsRepository.isPaidPlan()) {
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
                if (user == null && settingsRepository.isPaidPlan()) {
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
                if (sub != null && !sub.isActive && settingsRepository.isPaidPlan()) {
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

    // «Чип» в стиле кнопок «Перейти» из окон поддержки: полупрозрачная
    // акцентная заливка с такой же обводкой. Раньше «Войти» был плотной
    // тёмной плашкой и в светлой теме выбивался из остальных кнопок.
    private fun styleAccentChip(view: TextView) {
        val density = resources.displayMetrics.density
        val accent = ContextCompat.getColor(this, R.color.accent)
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 18f * density
            setColor(ColorUtils.setAlphaComponent(accent, 34))
            setStroke((1f * density).toInt(), ColorUtils.setAlphaComponent(accent, 150))
        }
        view.setTextColor(accent)
    }

    private fun updateAccountCardUI() {
        styleAccentChip(binding.cardAccount.btnAccountLogin)
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
                val expiredStr = sub?.paidUntil?.take(10).orEmpty()
                binding.cardAccount.tvAccountSubscriptionStatus.text = if (expiredStr.isNotEmpty()) {
                    getString(R.string.sub_expired_status, formatExpiry(expiredStr))
                } else {
                    getString(R.string.sub_free_status)
                }
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

    /**
     * Вторичная кнопка в диалоге: заливка и обводка заметно отличаются от фона
     * окна. Иначе «Отмена» в светлой теме была белой на белом, а в тёмной —
     * тёмной на тёмной, и кнопку выдавал только текст.
     */
    private fun applySecondaryButtonStyle(
        button: com.google.android.material.button.MaterialButton,
        pal: DialogPalette
    ) {
        val isLight = ColorUtils.calculateLuminance(pal.cardBg) > 0.5
        button.backgroundTintList = ColorStateList.valueOf(
            if (isLight) Color.parseColor("#F1F5F9") else Color.parseColor("#1F2330")
        )
        button.strokeWidth = (1f * pal.density).toInt().coerceAtLeast(1)
        button.strokeColor = ColorStateList.valueOf(
            if (isLight) Color.parseColor("#CBD5E1") else Color.parseColor("#33FFFFFF")
        )
        button.setTextColor(pal.title)
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
            dialog.dismiss()
            lifecycleScope.launch {
                val user = com.naua_security_mirage.app.data.supabase.SupabaseManager.instance
                    .signInWithGoogle(this@MainActivity)
                if (user != null) {
                    Toast.makeText(
                        this@MainActivity,
                        getString(
                            R.string.toast_google_signed_in_fmt,
                            if (settingsRepository.isEmailMasked) maskEmail(user.email) else user.email
                        ),
                        Toast.LENGTH_SHORT
                    ).show()
                    com.naua_security_mirage.app.push.MirageMessagingService.refresh()
                }
            }
        }

        dialogBinding.btnAuthCancel.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    /**
     * Диалог выбора тарифа: 1 месяц, 3 месяца, 6 месяцев, год.
     *
     * Суммы те же, что знает вебхук, поэтому сервер сам начислит нужное число
     * дней по оплаченной сумме.
     */
    private fun showPaymentOptionsDialog(
        userId: String,
        isRenewal: Boolean,
        onPick: (amountRub: Int, days: Int) -> Unit
    ) {
        val dialog = Dialog(this)
        val optionsBinding = com.naua_security_mirage.app.databinding.DialogPaymentOptionsBinding
            .inflate(layoutInflater)
        dialog.setContentView(optionsBinding.root)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.92f).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        val pal = dialogPalette()
        optionsBinding.dialogPayOptionsRoot.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 24f * pal.density
            setColor(pal.cardBg)
            setStroke((1.2f * pal.density).toInt(), pal.stroke)
        }
        optionsBinding.tvPayOptionsTitle.apply {
            setText(if (isRenewal) R.string.sub_options_title else R.string.sub_options_title_buy)
            setTextColor(pal.title)
        }
        optionsBinding.tvPayOptionsSubtitle.apply {
            setText(if (isRenewal) R.string.sub_options_subtitle else R.string.sub_options_subtitle_buy)
            setTextColor(pal.subtitle)
        }

        fun renderPlans(plans: List<SubscriptionPlan>) {
            optionsBinding.containerPayOptions.removeAllViews()
            for (plan in plans) {
                val row = layoutInflater.inflate(
                    R.layout.item_payment_option,
                    optionsBinding.containerPayOptions,
                    false
                )
                // Фон строки брался из @drawable/bg_card (тёмный): в светлой теме
                // название срока на нём не читалось. Красим в цвет карточки окна.
                row.background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 16f * pal.density
                    setColor(pal.cardBg)
                    setStroke((1f * pal.density).toInt(), pal.stroke)
                }
                row.findViewById<TextView>(R.id.tvPayOptionTitle).apply {
                    text = plan.label()
                    setTextColor(pal.title)
                }
                row.findViewById<TextView>(R.id.tvPayOptionPrice).apply {
                    text = plan.badge?.let {
                        getString(R.string.sub_option_price_discount, plan.priceRub, it)
                    } ?: getString(R.string.sub_option_price, plan.priceRub)
                    setTextColor(Color.parseColor("#E8A33D"))
                }
                row.setOnClickListener {
                    AnimationHelper.bounceClick(row, minScale = 0.97f, durationMs = 160)
                    dialog.dismiss()
                    onPick(plan.priceRub, plan.days)
                }
                optionsBinding.containerPayOptions.addView(row)
            }
        }
        renderPlans(AppConfigRepository.config.value.plans.ifEmpty { AppConfig.DEFAULT_PLANS })
        AppConfigRepository.refresh()
        val configJob = lifecycleScope.launch {
            AppConfigRepository.config.collect { config ->
                renderPlans(config.plans.ifEmpty { AppConfig.DEFAULT_PLANS })
            }
        }
        dialog.setOnDismissListener { configJob.cancel() }
        optionsBinding.btnPayOptionsCancel.apply {
            applySecondaryButtonStyle(this, pal)
        }
        optionsBinding.btnPayOptionsCancel.setOnClickListener { dialog.dismiss() }

        AnimationHelper.popIn(optionsBinding.root, durationMs = 240)
        dialog.show()
    }

    /**
     * Просит у сервера пробный день и переключает план на платный.
     *
     * Решение о выдаче принимает сервер: он проверяет, что пробник не был
     * выдан ни этому аккаунту, ни этому устройству, и сам записывает пробник в
     * подписку. Приложение только спрашивает.
     */
    private fun requestFreeTrial(trigger: View) {
        val manager = com.naua_security_mirage.app.data.supabase.SupabaseManager.instance
        if (manager.currentUser.value == null) {
            showAuthDialog { requestFreeTrial(trigger) }
            return
        }
        trigger.isEnabled = false
        lifecycleScope.launch {
            val deviceKey = com.naua_security_mirage.app.data.repository.DeviceIdRepository(this@MainActivity)
                .getDeviceFingerprint()
            val result = manager.requestFreeTrial(deviceKey)
            trigger.isEnabled = true
            result.onSuccess { paidUntil ->
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.sub_trial_granted, formatExpiry(paidUntil)),
                    Toast.LENGTH_LONG
                ).show()
                if (!settingsRepository.isPaidPlan()) {
                    settingsRepository.selectedServerPlan = settingsRepository.lastPaidPlan
                }
                updateAccountCardUI()
                updateServerPlanSelectorUI()
            }.onFailure { error ->
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.sub_trial_failed, error.message ?: "неизвестная ошибка"),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun formatExpiry(isoDate: String): String {        return try {
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
        val accentColor = ContextCompat.getColor(this, R.color.accent)
        dialogBinding.tvSubTrialDesc.setTextColor(pal.subtitle)
        applySecondaryButtonStyle(dialogBinding.btnSubCheck, pal)
        // Крестик закрытия: в светлой теме @color/ink_muted почти сливался
        // с белой карточкой.
        dialogBinding.btnSubCancel.imageTintList = ColorStateList.valueOf(pal.subtitle)
        dialogBinding.dividerSubHeader.setBackgroundColor(pal.stroke)
        // «Попробовать 1 день» и «Оплатить» в светлой теме оставались с
        // тёмным фоном @color/card: подгоняем под остальные кнопки.
        dialogBinding.btnSubTrial.backgroundTintList = ColorStateList.valueOf(pal.cardBg)
        dialogBinding.btnSubTrial.strokeColor = ColorStateList.valueOf(accentColor)
        dialogBinding.btnSubTrial.setTextColor(accentColor)
        dialogBinding.btnSubPay.backgroundTintList = ColorStateList.valueOf(accentColor)
        dialogBinding.btnSubPay.setTextColor(Color.WHITE)

        val manager = com.naua_security_mirage.app.data.supabase.SupabaseManager.instance
        fun renderPlanSummary(config: AppConfig) {
            val minPlan = config.plans.minByOrNull { it.days } ?: config.plans.firstOrNull()
            if (minPlan != null) {
                dialogBinding.tvSubPrice.text =
                    getString(R.string.sub_price_from_fmt, config.minPriceRub)
                dialogBinding.tvSubPlanPeriod.text =
                    getString(R.string.sub_plan_days_fmt, minPlan.days)
            }
        }
        renderPlanSummary(AppConfigRepository.config.value)
        AppConfigRepository.refresh(force = true)
        val configJob = lifecycleScope.launch {
            AppConfigRepository.config.collect { renderPlanSummary(it) }
        }
        dialog.setOnDismissListener { configJob.cancel() }
        val subscription = manager.subscription.value
        val hasActive = manager.hasActiveSubscription()
        if (hasActive && subscription != null) {
            val until = subscription.paidUntil?.take(10)
            if (!until.isNullOrBlank()) {
                dialogBinding.tvSubDesc.text = getString(R.string.sub_active_until, formatExpiry(until))
                dialogBinding.btnSubPay.text = getString(R.string.sub_renew_button)
            }
        }

        // Пробный день доступен только тем, у кого подписки ещё не было.
        val canTry = !hasActive
        dialogBinding.btnSubTrial.visibility = if (canTry) View.VISIBLE else View.GONE
        dialogBinding.tvSubTrialDesc.visibility = if (canTry) View.VISIBLE else View.GONE

        dialogBinding.btnSubPay.setOnClickListener {
            showPaymentOptionsDialog(user.id, hasActive) { amountRub, days ->
                isWaitingForPayment = true
                paymentBaselineUntil = subscription?.paidUntil?.take(10)
                val opened = com.naua_security_mirage.app.data.supabase.PaymentManager
                    .openPaymentBrowser(this, user.id, amountRub, days)
                if (!opened) {
                    isWaitingForPayment = false
                    Toast.makeText(this, R.string.toast_payment_browser, Toast.LENGTH_SHORT).show()
                }
            }
        }

        dialogBinding.btnSubTrial.setOnClickListener {
            requestFreeTrial(dialogBinding.btnSubTrial)
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
                    if (!settingsRepository.isPaidPlan()) {
                        settingsRepository.selectedServerPlan = settingsRepository.lastPaidPlan
                    }
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
                if (settingsRepository.isPaidPlan()) {
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
                    val pingDisplay = if (ping in 1..9998) ping.toString() else "—"
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
            splitTunnelDirty = false
        }
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
                    val paidPlan = settingsRepository.selectedServerPlan
                    val paidNode = AppConfigRepository.node(paidPlan)
                    val isPaidPlan = paidNode != null

                    if (hasPaid && paidNode != null) {
                        when (paidNode.kind) {
                            PaidNode.Kind.BELARUS -> AppLogger.i(
                                "DataRefresh",
                                "Ключ Беларуси обновляется параллельно с замером задержки"
                            )
                            PaidNode.Kind.FRANCE -> {
                                val freshKey = runCatching {
                                    com.naua_security_mirage.app.data.supabase.SupabaseManager.instance
                                        .ensureFranceVlessKey()
                                }.getOrNull()
                                AppLogger.i(
                                    "DataRefresh",
                                    if (freshKey != null) "Ключ платного узла обновлён"
                                    else "Ключ платного узла не обновлён, используется кэш"
                                )
                            }
                            PaidNode.Kind.VLESS -> {
                                val freshKey = runCatching {
                                    com.naua_security_mirage.app.data.supabase.SupabaseManager.instance
                                        .ensureNodeVlessKey(paidNode)
                                }.getOrNull()
                                AppLogger.i(
                                    "DataRefresh",
                                    if (freshKey != null) "Ключ узла ${paidNode.id} обновлён"
                                    else "Ключ узла ${paidNode.id} не обновлён, используется кэш"
                                )
                            }
                        }
                    } else {
                        AppLogger.d("DataRefresh", "Нет активной подписки, платный узел пропущен")
                    }

                    val paidPingJob = if (isPaidPlan && hasPaid) {
                        async {
                            runCatching {
                                when (paidNode.kind) {
                                    PaidNode.Kind.BELARUS -> {
                                        val probe = BelarusNodeMonitor.probe(
                                            this@MainActivity,
                                            forceRefresh = true
                                        )
                                        when {
                                            probe.reason == "no_session" ->
                                                getString(R.string.refresh_paid_login_required)
                                            probe.server == null -> getString(R.string.refresh_paid_no_key)
                                            probe.reachable -> "${probe.pingMs} ms"
                                            else -> getString(R.string.ping_timeout)
                                        }
                                    }
                                    PaidNode.Kind.FRANCE -> {
                                        val manager = com.naua_security_mirage.app.data.supabase.SupabaseManager.instance
                                        val uuid = manager.getActiveClientUuid()
                                        val server = com.naua_security_mirage.app.data.supabase.SupabaseConfig
                                            .getFranceServer(uuid, manager.getActiveClientFlow(uuid))
                                        val p = pingRepository.measurePing(server, timeoutMs = 1800)
                                        if (p in 1..9998) "$p ms" else getString(R.string.ping_timeout)
                                    }
                                    PaidNode.Kind.VLESS -> {
                                        val manager = com.naua_security_mirage.app.data.supabase.SupabaseManager.instance
                                        if (manager.currentUser.value == null) {
                                            getString(R.string.refresh_paid_login_required)
                                        } else {
                                            val key = manager.getNodeVlessKey(paidNode.id)
                                                ?: manager.ensureNodeVlessKey(paidNode)
                                            val server = key?.let {
                                                com.naua_security_mirage.app.data.model.VlessServer.fromUri(
                                                    it,
                                                    paidNode.label()
                                                )
                                            }
                                            if (server == null) {
                                                getString(R.string.refresh_paid_no_key)
                                            } else {
                                                val p = pingRepository.measurePing(server, timeoutMs = 1800)
                                                if (p in 1..9998) "$p ms" else getString(R.string.ping_timeout)
                                            }
                                        }
                                    }
                                }
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
                    val paidPingText = paidPingJob?.await()

                    val statusText = if (paidPingText != null) paidPingText else pingText
                    AppLogger.i(
                        "DataRefresh",
                        if (paidPingText != null) {
                            "Обновление завершено. Показан пинг платного узла $paidPingText, бесплатные обновлены: ${best.tag} $pingText"
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
        if (settingsRepository.uiStyle == SettingsRepository.UI_STYLE_GLASS) {
            openGlassDetailedSettings()
            return
        }
        val slideDist = 42f * resources.displayMetrics.density
        binding.viewSettings.scrollTo(0, 0)
        AnimationHelper.fadeAndSlideOut(binding.viewMain, toX = -slideDist, durationMs = 210)
        AnimationHelper.fadeAndSlideIn(binding.viewSettings, fromX = slideDist, durationMs = 230)
        binding.hintCard.visibility = View.GONE
        refreshPrivateDnsRow()
        refreshTunnelDnsRow()
    }

    private fun openGlassDetailedSettings() {
        binding.viewSettings.background = ContextCompat.getDrawable(this, R.drawable.bg_glass_shell)
        binding.viewSettings.visibility = View.VISIBLE
        binding.viewSettings.alpha = 0f
        binding.viewSettings.scrollTo(0, 0)
        binding.viewSettings.animate().alpha(1f).setDuration(180L).start()
        refreshPrivateDnsRow()
        refreshTunnelDnsRow()
    }

    private var privateDnsCard: View? = null
    private var nodeStatusCard: View? = null
    private var nodeStatusJob: Job? = null
    private var freePickerJob: Job? = null
    private var freePickerSnapshot: List<VlessServer> = emptyList()
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
        val trackedPlan = if (settingsRepository.isPaidPlan()) {
            settingsRepository.selectedServerPlan
        } else {
            settingsRepository.lastPaidPlan
        }

        val isBelarusPlan = trackedPlan == SettingsRepository.PLAN_PREMIUM_BELARUS
        val trackedNode = AppConfigRepository.node(trackedPlan)
        val isGenericPlan = trackedNode?.kind == PaidNode.Kind.VLESS

        nodeStatusJob = lifecycleScope.launch {
            val manager = com.naua_security_mirage.app.data.supabase.SupabaseManager.instance
            if (manager.currentUser.value == null) {
                if (gen != nodeStatusGen) return@launch
                stateView.text = getString(R.string.node_status_login_required)
                stateView.setTextColor(themedSubtitle())
            } else if (!manager.hasActiveSubscription()) {
                if (gen != nodeStatusGen) return@launch
                stateView.text = getString(R.string.node_status_no_subscription)
                stateView.setTextColor(themedSubtitle())
            } else if (isBelarusPlan) {
                refreshBelarusState(stateView, gen, forceRefresh = force)
            } else if (isGenericPlan) {
                stateView.text = getString(R.string.node_status_untracked)
                stateView.setTextColor(themedSubtitle())
            } else {
                refreshFranceState(stateView, gen)
            }

            if (gen != nodeStatusGen) return@launch
            val history: List<NodeHistoryRow>? = if (isBelarusPlan) {
                withContext(Dispatchers.IO) {
                    NodeEventStore.history(
                        this@MainActivity,
                        NodeEventStore.NODE_BELARUS,
                        limit = 5
                    )
                }.map { NodeHistoryRow(it.state, it.at, it.detail) }
            } else if (isGenericPlan) {
                null
            } else {
                com.naua_security_mirage.app.vpn.NodeStatusReporter.history(limit = 5)
                    ?.map { NodeHistoryRow(it.state, it.changedAt, null) }
            }
            if (gen != nodeStatusGen) return@launch
            if (historyView != null) {
                historyView.text = if (isGenericPlan) {
                    getString(R.string.node_history_untracked)
                } else when {
                    history == null -> getString(R.string.node_history_failed)
                    history.isEmpty() -> getString(R.string.node_history_empty)
                    else -> history.joinToString("\n") { row ->
                        getString(
                            R.string.node_history_entry,
                            row.at?.let { formatNodeSince(it) } ?: "?",
                            nodeHistoryStateLabel(row.state, row.detail)
                        )
                    }
                }
                AppLogger.i(
                    "NodeStatus",
                    if (history == null) "История сбоев: загрузить не удалось"
                    else "История сбоев: записей ${history.size}"
                )
            }
        }
    }

    private suspend fun refreshBelarusState(stateView: TextView, gen: Int, forceRefresh: Boolean) {
        val probe = BelarusNodeMonitor.probe(this@MainActivity, forceRefresh = forceRefresh)
        if (gen != nodeStatusGen) return

        if (probe.reason == "no_session") {
            stateView.text = getString(R.string.node_status_login_required)
            stateView.setTextColor(nodeColor(true))
            AppLogger.w("NodeStatus", "Беларусь: требуется вход в аккаунт")
            return
        }

        if (probe.reason == "no_subscription") {
            stateView.text = getString(R.string.node_status_no_subscription)
            stateView.setTextColor(themedSubtitle())
            return
        }

        val server = probe.server
        if (server == null) {
            stateView.text = getString(R.string.node_status_belarus_no_key)
            stateView.setTextColor(nodeColor(true))
            AppLogger.w(
                "NodeStatus",
                "Беларусь: ключ узла не получен (${probe.reason}), проверка не выполнена"
            )
            return
        }

        if (probe.reachable) {
            stateView.text = getString(R.string.node_status_belarus_up, probe.pingMs)
            stateView.setTextColor(themedSubtitle())
            AppLogger.i(
                "NodeStatus",
                "Беларусь: узел отвечает с устройства за ${probe.pingMs} мс " +
                        "(${server.address}:${server.port})"
            )
        } else {
            stateView.text = getString(R.string.node_status_belarus_down)
            stateView.setTextColor(nodeColor(true))
            AppLogger.w(
                "NodeStatus",
                "Беларусь: узел не отвечает с устройства (${server.address}:${server.port})"
            )
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
                lastNodeCore = status.core
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

    private data class NodeHistoryRow(
        val state: String,
        val at: Long?,
        val detail: String?
    )

    private fun nodeHistoryStateLabel(state: String, detail: String?): String = when (detail) {
        "no_session" -> getString(R.string.node_history_state_no_session)
        "no_key" -> getString(R.string.node_history_state_no_key)
        "bad_key" -> getString(R.string.node_history_state_bad_key)
        "no_ping" -> getString(R.string.node_history_state_no_ping)
        "tunnel" -> getString(R.string.node_history_state_tunnel_failed)
        else -> nodeStateLabel(state)
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
    ) {
        fun urlLine(host: android.content.Context): String = when (server.id) {
            DnsServer.AUTO_ID -> host.getString(R.string.tunnel_dns_auto_hint)
            DnsServer.PLAIN_ID -> host.getString(R.string.tunnel_dns_plain_hint)
            else -> url
        }
    }

    private fun dnsChoices(): List<DnsChoice> {
        val auto = DnsChoice(
            DnsServer(id = DnsServer.AUTO_ID, url = ""),
            R.string.dns_server_auto,
            ""
        )
        val plain = DnsChoice(
            DnsServer(id = DnsServer.PLAIN_ID, url = ""),
            R.string.dns_server_plain,
            ""
        )
        val builtIn = DnsServer.BUILT_IN.map { candidate ->
            DnsChoice(candidate, dnsNameRes(candidate.id), candidate.url)
        }
        val customUrl = settingsRepository.customDnsUrl.trim()
        val custom = if (customUrl.isBlank()) {
            emptyList()
        } else {
            listOf(
                DnsChoice(
                    DnsServer(id = XrayVpnController.CUSTOM_DNS_ID, url = customUrl),
                    R.string.dns_server_custom,
                    customUrl
                )
            )
        }
        return listOf(auto, plain) + builtIn + custom
    }

    private fun dnsNameRes(id: String): Int = when (id) {
        DnsServer.AUTO_ID -> R.string.dns_server_auto
        DnsServer.PLAIN_ID -> R.string.dns_server_plain
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
        if (id == DnsServer.AUTO_ID) {
            val picked = DnsServer.byId(settingsRepository.autoDnsPickedId)
            return if (picked == null) {
                getString(R.string.dns_server_auto)
            } else {
                getString(R.string.tunnel_dns_auto_chosen_fmt, getString(dnsNameRes(picked.id)))
            }
        }
        if (id.isBlank() || id == DnsServer.PLAIN_ID) {
            return getString(R.string.dns_server_plain)
        }
        return getString(dnsNameRes(id))
    }

    private fun refreshTunnelDnsRow() {
        val card = privateDnsCard ?: return
        val state = card.findViewById<TextView>(R.id.tvTunnelDnsState) ?: return
        val selected = settingsRepository.selectedDnsId
        state.text = when {
            selected.isBlank() -> getString(R.string.tunnel_dns_default)
            selected == DnsServer.AUTO_ID -> currentDnsName()
            else -> getString(R.string.tunnel_dns_selected_fmt, currentDnsName())
        }
        AppLogger.i(
            "TunnelDns",
            "DNS-резолвер: ${selected.ifBlank { "cloudflare (по умолчанию)" }}" +
                    if (selected == DnsServer.AUTO_ID) ", прошлый авто-выбор=${settingsRepository.autoDnsPickedId}" else ""
        )
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
        val kept = mutableMapOf<String, DnsProbeResult>()
        var choices = dnsChoices()

        dialog.show()
        AnimationHelper.popIn(root, durationMs = 220)
        AnimationHelper.attachPressAnimations(root)
        lockDialogList(dialog, root, scroll)

        fun renderLatency(result: DnsProbeResult) {
            val view = rows[result.server.id] ?: return
            view.text = when {
                !result.reachable -> getString(R.string.tunnel_dns_failed)
                result.dnsStatus == "REFUSED" -> getString(R.string.tunnel_dns_refused)
                result.dnsStatus == "SERVFAIL" -> getString(R.string.tunnel_dns_servfail)
                result.dnsStatus == "FORMERR" -> getString(R.string.tunnel_dns_formerr)
                !result.answered -> getString(R.string.tunnel_dns_no_records)
                else -> getString(R.string.tunnel_dns_latency_fmt, result.latencyMs)
            }
            view.setTextColor(
                if (result.reachable && result.answered) accentColor else bodyColor
            )
        }

        fun buildRows() {
            choices = dnsChoices()
            container.removeAllViews()
            rows.clear()
            val selectedId = settingsRepository.selectedDnsId
            for ((index, choice) in choices.withIndex()) {
                if (index > 0) {
                    container.addView(View(this).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            (1f * density).toInt().coerceAtLeast(1)
                        ).apply {
                            marginStart = (16f * density).toInt()
                            marginEnd = (16f * density).toInt()
                        }
                        setBackgroundColor(stroke)
                    })
                }
                val row = layoutInflater.inflate(R.layout.row_dns_server, container, false)
                val nameView = row.findViewById<TextView>(R.id.tvDnsServerName)
                val urlView = row.findViewById<TextView>(R.id.tvDnsServerUrl)
                val latencyView = row.findViewById<TextView>(R.id.tvDnsServerLatency)
                val removeView = row.findViewById<TextView>(R.id.btnDnsServerRemove)
                val isCustom = choice.server.id == XrayVpnController.CUSTOM_DNS_ID
                removeView.visibility = if (isCustom) View.VISIBLE else View.GONE
                if (isCustom) {
                    removeView.text = getString(R.string.tunnel_dns_remove)
                    removeView.setTextColor(bodyColor)
                    removeView.setPadding(
                        (12f * density).toInt(),
                        (6f * density).toInt(),
                        (12f * density).toInt(),
                        (6f * density).toInt()
                    )
                    removeView.background = GradientDrawable().apply {
                        cornerRadius = 12f * density
                        setColor(ColorUtils.setAlphaComponent(bodyColor, 26))
                        setStroke((1.2f * density).toInt(), stroke)
                    }
                    removeView.setOnClickListener {
                        AnimationHelper.bounceClick(removeView, minScale = 0.9f, durationMs = 140)
                        settingsRepository.customDnsUrl = ""
                        if (settingsRepository.selectedDnsId == XrayVpnController.CUSTOM_DNS_ID) {
                            settingsRepository.selectedDnsId = DnsServer.AUTO_ID
                        }
                        kept.remove(XrayVpnController.CUSTOM_DNS_ID)
                        refreshTunnelDnsRow()
                        Toast.makeText(this, R.string.tunnel_dns_removed, Toast.LENGTH_SHORT).show()
                        AnimationHelper.fadeAndSlideOut(row, toX = -70f, durationMs = 180) { buildRows() }
                    }
                }
                nameView.text = getString(choice.nameRes) +
                        if (choice.server.id == selectedId) "  ✓" else ""
                nameView.setTextColor(
                    if (choice.server.id == selectedId) accentColor else titleColor
                )
                urlView.text = choice.urlLine(this)
                urlView.setTextColor(bodyColor)
                latencyView.text = ""
                latencyView.setTextColor(bodyColor)
                row.setOnClickListener {
                    AnimationHelper.bounceClick(row, minScale = 0.98f, durationMs = 140)
                    settingsRepository.selectedDnsId = choice.server.id
                    if (choice.server.id == DnsServer.AUTO_ID) {
                        val picked = settingsRepository.autoDnsPickedId
                        val pickedName = DnsServer.byId(picked)?.let { getString(dnsNameRes(it.id)) }
                        Toast.makeText(
                            this,
                            pickedName?.let { getString(R.string.tunnel_dns_auto_chosen_fmt, it) }
                                ?: getString(R.string.tunnel_dns_auto_desc),
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        Toast.makeText(this, R.string.tunnel_dns_applied, Toast.LENGTH_SHORT).show()
                    }
                    refreshTunnelDnsRow()
                    buildRows()
                }
                rows[choice.server.id] = latencyView
                container.addView(row)
                kept[choice.server.id]?.let { renderLatency(it) }
                AnimationHelper.popIn(row, durationMs = 140)
            }
            AnimationHelper.attachPressAnimations(container)
        }
        buildRows()

        testBtn.setOnClickListener {
            AnimationHelper.bounceClick(it, minScale = 0.94f, durationMs = 140)
            progress.visibility = View.VISIBLE
            progress.text = getString(R.string.tunnel_dns_testing)
            testBtn.isEnabled = false
            val throughTunnel = MirageVpnService.vpnState.value == VpnState.CONNECTED
            val measurable = choices.filter { it.server.url.isNotBlank() }
            lifecycleScope.launch(Dispatchers.IO) {
                val outcomes = measurable.map { choice ->
                    async { DohTester.probe(choice.server, throughTunnel) }
                }.awaitAll()
                withContext(Dispatchers.Main) {
                    progress.visibility = View.GONE
                    testBtn.isEnabled = true
                    for (result in outcomes) {
                        kept[result.server.id] = result
                        renderLatency(result)
                        val view = rows[result.server.id]
                        if (view != null && result.server.id != DnsServer.AUTO_ID) {
                            AnimationHelper.popIn(view, durationMs = 160)
                        }
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
            showCustomDnsDialog(onSaved = { buildRows() })
        }
    }

    private fun lockDialogList(dialog: Dialog, root: View, scroll: ScrollView) {
        if (root !is ViewGroup || scroll.parent !== root) return
        root.post {
            val natural = root.height
            if (natural <= 0) return@post
            val ceiling = (resources.displayMetrics.heightPixels * 0.82f).toInt()
            val locked = minOf(natural, ceiling)
            if (locked < natural) {
                var fixed = root.paddingTop + root.paddingBottom
                for (i in 0 until root.childCount) {
                    val child = root.getChildAt(i)
                    if (child === scroll) continue
                    val lp = child.layoutParams as? ViewGroup.MarginLayoutParams
                    fixed += child.height + (lp?.topMargin ?: 0) + (lp?.bottomMargin ?: 0)
                }
                val params = scroll.layoutParams
                params.height = (locked - fixed).coerceAtLeast(0)
                scroll.layoutParams = params
            }
            dialog.window?.setLayout(
                (resources.displayMetrics.widthPixels * 0.92f).toInt(),
                locked
            )
        }
    }

    private fun showCustomDnsDialog(onSaved: (() -> Unit)? = null) {
        val dialog = Dialog(this)
        dialog.setContentView(R.layout.dialog_tunnel_dns_custom)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        val density = resources.displayMetrics.density
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

        val root = dialog.findViewById<LinearLayout>(R.id.dialogTunnelDnsCustomRoot)
        root.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 24f * density
            setColor(cardBg)
            setStroke((1.2f * density).toInt(), stroke)
        }
        dialog.findViewById<TextView>(R.id.tvTunnelDnsCustomDialogTitle).setTextColor(titleColor)
        dialog.findViewById<TextView>(R.id.tvTunnelDnsCustomDialogBody).setTextColor(bodyColor)
        val input = dialog.findViewById<EditText>(R.id.etTunnelDnsCustomUrl)
        input.setText(settingsRepository.customDnsUrl)
        input.hint = getString(R.string.tunnel_dns_custom_hint)
        input.setTextColor(titleColor)
        input.setHintTextColor(bodyColor)

        val cancel = dialog.findViewById<TextView>(R.id.btnTunnelDnsCustomCancel)
        val save = dialog.findViewById<TextView>(R.id.btnTunnelDnsCustomSave)
        cancel.setTextColor(bodyColor)
        save.setTextColor(accentColor)
        cancel.background = GradientDrawable().apply {
            cornerRadius = 14f * density
            setColor(ColorUtils.setAlphaComponent(bodyColor, 26))
            setStroke((1.2f * density).toInt(), stroke)
        }
        save.background = GradientDrawable().apply {
            cornerRadius = 14f * density
            setColor(ColorUtils.setAlphaComponent(accentColor, 34))
            setStroke((1.2f * density).toInt(), ColorUtils.setAlphaComponent(accentColor, 170))
        }

        cancel.setOnClickListener {
            AnimationHelper.bounceClick(it, minScale = 0.94f, durationMs = 140) {
                dialog.dismiss()
            }
        }
        save.setOnClickListener {
            AnimationHelper.bounceClick(it, minScale = 0.94f, durationMs = 140) {
                val value = input.text.toString().trim()
                if (!value.startsWith("https://")) {
                    Toast.makeText(this, R.string.tunnel_dns_custom_bad, Toast.LENGTH_LONG).show()
                    return@bounceClick
                }
                settingsRepository.customDnsUrl = value
                settingsRepository.selectedDnsId = XrayVpnController.CUSTOM_DNS_ID
                Toast.makeText(this, R.string.tunnel_dns_custom_saved, Toast.LENGTH_SHORT).show()
                refreshTunnelDnsRow()
                onSaved?.invoke()       // обновляем список в родительском диалоге
                dialog.dismiss()
            }
        }

        dialog.show()
        AnimationHelper.popIn(root, durationMs = 220)
        AnimationHelper.attachPressAnimations(root)
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
        if (settingsRepository.uiStyle == SettingsRepository.UI_STYLE_GLASS) {
            binding.viewSettings.animate().alpha(0f).setDuration(140L).withEndAction {
                binding.viewSettings.visibility = View.GONE
                binding.viewSettings.alpha = 1f
                selectGlassTab(GLASS_TAB_SETTINGS)
            }.start()
            return
        }
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


    // ------------------------------------------------------------------
    // Aurora shell: slow amber blobs under a liquid glass stage (design 1:1)
    // ------------------------------------------------------------------

    private val auroraAnimators = mutableListOf<Animator>()
    private val auroraTickHandler = Handler(Looper.getMainLooper())
    private var auroraTickRunnable: Runnable? = null
    private var auroraSessionSeconds = 0L
    private var auroraToastRunnable: Runnable? = null
    private var auroraInsetsTop = 0
    private var auroraInsetsBottom = 0
    private val auroraSwitches = mutableMapOf<String, MirageSwitch>()
    private var auroraSettingsScreen: View? = null
    private var auroraFaqScreen: View? = null
    private var auroraVersionScreen: View? = null
    private var auroraCheckRes: TextView? = null
    private var auroraAccStatus: TextView? = null
    private var auroraAccEmail: TextView? = null
    private var auroraAccDays: TextView? = null
    private var auroraAccCache: TextView? = null
    private var auroraAccLeave: TextView? = null
    private var auroraAccAction: TextView? = null
    private var auroraCoreChip: TextView? = null
    private var auroraFreeDesc: TextView? = null
    private var auroraPaidDesc: TextView? = null
    private var auroraGeoDesc: TextView? = null
    private var auroraPerAppDesc: TextView? = null
    private var auroraTunnelDnsDesc: TextView? = null
    private var auroraPrivateDnsDesc: TextView? = null
    private var auroraSpeedDesc: TextView? = null
    private var auroraPingDesc: TextView? = null
    private var auroraLogsDesc: TextView? = null
    private val auroraSourceRows = mutableMapOf<String, ViewGroup>()
    private val auroraMenuRows = mutableListOf<ViewGroup>()

    private fun setupGlassShell() {
        val shell = binding.auroraShell

        shell.auLangBtn.text = if (Locale.getDefault().language.startsWith("ru")) "RU" else "EN"
        shell.auLangBtn.setOnClickListener {
            AnimationHelper.bounceClick(shell.auLangBtn, minScale = 0.9f, durationMs = 140)
            binding.btnLanguage.performClick()
        }

        shell.auRefreshBtn.setOnClickListener {
            AnimationHelper.bounceClick(shell.auRefreshBtn, minScale = 0.9f, durationMs = 140)
            shell.auRefreshBtn.animate().rotation(360f).setDuration(650L)
                .withEndAction { shell.auRefreshBtn.rotation = 0f }.start()
            showAuroraToast(getString(R.string.au_toast_refreshed))
            binding.btnRefresh.performClick()
        }

        shell.auOrb.setOnClickListener {
            AnimationHelper.bounceClick(shell.auOrb, minScale = 0.94f, durationMs = 160)
            binding.btnConnect.performClick()
        }

        shell.auBtnFree.setOnClickListener {
            AnimationHelper.bounceClick(shell.auBtnFree, minScale = 0.96f, durationMs = 140)
            setAuroraCountryMenu(false)
            if (settingsRepository.isPaidPlan()) {
                binding.containerServerPlan.btnPlanFree.performClick()
            }
        }
        shell.auBtnPaid.setOnClickListener {
            AnimationHelper.bounceClick(shell.auBtnPaid, minScale = 0.96f, durationMs = 140)
            if (paidNodeChoices().isEmpty()) {
                requestPaidPlan(settingsRepository.lastPaidPlan.ifBlank { SettingsRepository.PLAN_PREMIUM_FRANCE })
            } else {
                setAuroraCountryMenu(shell.auCountryMenu.visibility != View.VISIBLE)
            }
        }

        shell.auDonateCard.setOnClickListener {
            AnimationHelper.bounceClick(shell.auDonateCard, minScale = 0.97f, durationMs = 160)
            showAuroraSheet("donate")
        }
        shell.auTelegramCard.setOnClickListener {
            AnimationHelper.bounceClick(shell.auTelegramCard, minScale = 0.97f, durationMs = 160)
            showAuroraSheet("tg")
        }

        shell.auDockHome.setOnClickListener { selectGlassTab(GLASS_TAB_HOME) }
        shell.auDockSettings.setOnClickListener { selectGlassTab(GLASS_TAB_SETTINGS) }
        shell.auDockFaq.setOnClickListener { selectGlassTab(GLASS_TAB_FAQ) }
        shell.auDockVersion.setOnClickListener { selectGlassTab(GLASS_TAB_VERSION) }

        buildAuroraScreens()
        buildAuroraCountryMenu()
        startAuroraAmbient()
        applyAuroraScreenPadding()

        // session timer comes straight from the service, like the classic UI
        lifecycleScope.launch {
            MirageVpnService.sessionSeconds.collect { auroraSessionSeconds = it }
        }

        selectGlassTab(GLASS_TAB_HOME, animate = false)
        syncGlassShell()
    }

    private fun auroraFont(fontRes: Int): Typeface =
        androidx.core.content.res.ResourcesCompat.getFont(this, fontRes) ?: Typeface.DEFAULT

    private fun auroraColor(@ColorRes res: Int): Int = ContextCompat.getColor(this, res)

    private fun startAuroraAmbient() {
        if (!settingsRepository.areAnimationsEnabled) return
        cancelAuroraAmbient()
        val d = resources.displayMetrics.density
        val shell = binding.auroraShell
        fun drift(view: View, dx: Float, dy: Float, scale: Float, duration: Long) {
            val anim = ValueAnimator.ofFloat(0f, 1f).apply {
                this.duration = duration
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                interpolator = LinearInterpolator()
                addUpdateListener { a ->
                    val t = a.animatedValue as Float
                    view.translationX = dx * d * t
                    view.translationY = dy * d * t
                    view.scaleX = 1f + (scale - 1f) * t
                    view.scaleY = 1f + (scale - 1f) * t
                }
            }
            anim.start()
            auroraAnimators.add(anim)
        }
        drift(shell.auBlob1, 110f, 130f, 1.12f, 24000L)
        drift(shell.auBlob2, -120f, -150f, 0.9f, 29000L)
        drift(shell.auBlob3, 130f, -90f, 1.15f, 33000L)
    }

    private fun cancelAuroraAmbient() {
        auroraAnimators.forEach { it.cancel() }
        auroraAnimators.clear()
    }

    private fun checkUpdatesFromGlass() {
        auroraCheckRes?.text = getString(R.string.au_checking, auroraSourceName())
        AppUpdateManager.checkForUpdates(this, settingsRepository, isManual = true) {
            runOnUiThread {
                auroraCheckRes?.text =
                    if (AppUpdateManager.hasUpdate()) {
                        getString(
                            R.string.au_check_available,
                            AppUpdateManager.latestUpdate?.versionName ?: ""
                        )
                    } else {
                        getString(R.string.au_check_latest, BuildConfig.VERSION_NAME)
                    }
            }
        }
    }

    private fun auroraSourceName(): String = when (settingsRepository.updateSource) {
        SettingsRepository.UPDATE_SOURCE_UPTODOWN -> getString(R.string.setting_updates_source_uptodown)
        else -> getString(R.string.setting_updates_source_github)
    }

    private fun selectGlassTab(tab: Int, animate: Boolean = true) {
        val shell = binding.auroraShell
        currentGlassTab = tab
        shell.auHomeScroll.visibility = if (tab == GLASS_TAB_HOME) View.VISIBLE else View.GONE
        auroraSettingsScreen?.visibility = if (tab == GLASS_TAB_SETTINGS) View.VISIBLE else View.GONE
        auroraFaqScreen?.visibility = if (tab == GLASS_TAB_FAQ) View.VISIBLE else View.GONE
        auroraVersionScreen?.visibility = if (tab == GLASS_TAB_VERSION) View.VISIBLE else View.GONE
        val target = when (tab) {
            GLASS_TAB_SETTINGS -> auroraSettingsScreen
            GLASS_TAB_FAQ -> auroraFaqScreen
            GLASS_TAB_VERSION -> auroraVersionScreen
            else -> shell.auHomeScroll
        }
        target?.scrollTo(0, 0)
        if (animate) {
            target?.alpha = 0f
            target?.animate()?.alpha(1f)?.setDuration(240L)?.start()
        } else {
            target?.alpha = 1f
        }
        updateGlassDock(animate)
    }

    private fun updateGlassDock(animate: Boolean) {
        val shell = binding.auroraShell
        if (animate) {
            TransitionManager.beginDelayedTransition(shell.auDock, AutoTransition().setDuration(300L))
        }
        val items = listOf(
            GLASS_TAB_HOME to Triple(shell.auDockHome, shell.auDockHomeIcon, shell.auDockHomeLabel),
            GLASS_TAB_SETTINGS to Triple(shell.auDockSettings, shell.auDockSettingsIcon, shell.auDockSettingsLabel),
            GLASS_TAB_FAQ to Triple(shell.auDockFaq, shell.auDockFaqIcon, shell.auDockFaqLabel),
            GLASS_TAB_VERSION to Triple(shell.auDockVersion, shell.auDockVersionIcon, shell.auDockVersionLabel)
        )
        val activeTint = ContextCompat.getColor(this, R.color.au_accent_text)
        val idleTint = ContextCompat.getColor(this, R.color.au_fg_2)
        items.forEach { (tab, tuple) ->
            val (container, icon, label) = tuple
            val active = tab == currentGlassTab
            container.setBackgroundResource(if (active) R.drawable.bg_au_tab_active else 0)
            icon.imageTintList = ColorStateList.valueOf(if (active) activeTint else idleTint)
            label.setTextColor(if (active) activeTint else idleTint)
            label.visibility = if (active) View.VISIBLE else View.GONE
        }
    }

    // ---------------- 700 ms state sync (driven by glassSyncRunnable) ----------------

    private fun syncGlassShell() {
        if (settingsRepository.uiStyle != SettingsRepository.UI_STYLE_GLASS) return
        val shell = binding.auroraShell
        val connected = currentVpnState == VpnState.CONNECTED
        val connecting = currentVpnState == VpnState.CONNECTING

        // orb core crossfade + icon tint
        val coreAlpha = when {
            connected -> 1f
            connecting -> 0.45f
            else -> 0f
        }
        if (Math.abs(shell.auCoreOn.alpha - coreAlpha) > 0.02f) {
            shell.auCoreOn.animate().alpha(coreAlpha).setDuration(700L).start()
            shell.auCoreOff.animate().alpha(1f - coreAlpha).setDuration(700L).start()
        }
        shell.auOrbIcon.setColorFilter(
            ContextCompat.getColor(
                this,
                if (connected) R.color.au_on_accent else R.color.au_fg_2
            )
        )

        // halo breathe / connect arc
        if (connected) {
            startAuroraBreathe()
            startAuroraPulses()
        } else {
            cancelAuroraBreathe()
            cancelAuroraPulses()
            shell.auHalo.alpha = if (connecting) 0.3f else 0f
        }
        val arc = shell.auArc
        if (connecting && settingsRepository.areAnimationsEnabled) {
            arc.start()
            arc.alpha = 1f
        } else {
            arc.stop()
            arc.alpha = 0f
        }

        // status label + meta
        shell.auStatusLabel.text = getString(
            when (currentVpnState) {
                VpnState.CONNECTED -> R.string.status_connected
                VpnState.DISCONNECTING -> R.string.status_disconnecting
                VpnState.CONNECTING -> R.string.status_connecting
                else -> R.string.status_disconnected
            }
        )
        if (connected) {
            shell.auStatusMetaRow.visibility = View.VISIBLE
            shell.auStatusMeta.visibility = View.GONE
            shell.auSessionText.text = auroraSessionText()
            shell.auPingText.text = extractPing(binding.tvStatusSub.text?.toString())
                ?: getString(R.string.glass_ping_placeholder)
        } else {
            shell.auStatusMetaRow.visibility = View.GONE
            shell.auStatusMeta.visibility = View.VISIBLE
            shell.auStatusMeta.text = if (connecting) {
                getString(R.string.au_status_meta_searching)
            } else {
                getString(R.string.au_status_meta_tap)
            }
        }

        // speed card mirrors the classic readouts
        val zeroSpeed = com.naua_security_mirage.app.vpn.VpnNotificationManager.formatSpeed(0L)
        shell.auSpeedDown.text = if (connected) binding.tvSpeedDown.text else zeroSpeed
        shell.auSpeedUp.text = if (connected) binding.tvSpeedUp.text else zeroSpeed
        shell.auSpark.setLive(
            auroraSpeedValue(shell.auSpeedDown.text?.toString()) ?: 0f,
            auroraSpeedValue(shell.auSpeedUp.text?.toString()) ?: 0f
        )

        // server selector
        val plan = settingsRepository.selectedServerPlan
        val paid = settingsRepository.isPaidPlan(plan) && AppConfigRepository.node(plan) != null
        val paidTarget = (if (paid) plan else paidNodePlanForLabel())
            .ifBlank { SettingsRepository.PLAN_PREMIUM_FRANCE }
        shell.auPaidName.text = paidNodeLabel(paidTarget)
        shell.auPaidFlagView.code = if (paidTarget == SettingsRepository.PLAN_PREMIUM_BELARUS) "by" else "fr"
        shell.auFreeName.setTextColor(
            ContextCompat.getColor(this, if (!paid) R.color.au_fg else R.color.au_fg_2)
        )
        shell.auPaidName.setTextColor(
            ContextCompat.getColor(this, if (paid) R.color.au_fg else R.color.au_fg_2)
        )
        updateAuroraSegment(paid)

        // aurora settings switches mirror the real preferences
        auroraSwitches.forEach { (key, switch) ->
            switch.setChecked(auroraToggleState(key), animate = false)
        }

        syncAuroraAccount()
        syncAuroraDiagnostics()
        syncAuroraVersion()

        if (shell.auCountryMenu.visibility == View.VISIBLE) {
            refreshAuroraCountryMenu()
        }
    }

    private fun auroraSessionText(): String {
        val total = auroraSessionSeconds
        val h = total / 3600
        val m = total % 3600 / 60
        val s = total % 60
        fun p(n: Long): String = String.format(Locale.US, "%02d", n)
        return if (h > 0) "$h:" + p(m) + ":" + p(s) else p(m) + ":" + p(s)
    }

    private fun auroraSpeedValue(raw: String?): Float? {
        if (raw.isNullOrBlank()) return null
        val m = Regex("([0-9]+(?:[.,][0-9]+)?)").find(raw) ?: return null
        return m.groupValues[1].replace(',', '.').toFloatOrNull()
    }

    private fun syncAuroraAccount() {
        val status = auroraAccStatus ?: return
        val email = auroraAccEmail ?: return
        val days = auroraAccDays ?: return
        val cache = auroraAccCache ?: return
        val leave = auroraAccLeave ?: return
        val action = auroraAccAction ?: return
        val loggedOut = binding.cardAccount.layoutAccountLoggedOut.visibility == View.VISIBLE
        if (loggedOut) {
            email.text = binding.cardAccount.tvAccountPrompt.text
            status.visibility = View.GONE
            days.visibility = View.GONE
            cache.visibility = View.GONE
            leave.visibility = View.GONE
            action.visibility = View.VISIBLE
            action.text = getString(R.string.auth_google_btn)
        } else {
            email.text = binding.cardAccount.tvAccountEmail.text
            status.visibility = View.VISIBLE
            status.text = binding.cardAccount.tvAccountSubscriptionStatus.text
            status.setTextColor(binding.cardAccount.tvAccountSubscriptionStatus.currentTextColor)
            days.visibility = View.VISIBLE
            val left = auroraDaysLeft()
            days.text = getString(R.string.au_sub_days_fmt, left)
            days.setTextColor(
                ContextCompat.getColor(this, if (left > 3) R.color.au_fg_2 else R.color.au_bad)
            )
            cache.visibility = binding.cardAccount.tvAccountCacheAge.visibility
            cache.text = binding.cardAccount.tvAccountCacheAge.text
            leave.visibility = View.VISIBLE
            action.visibility = View.VISIBLE
            action.text = getString(R.string.sub_renew_button)
        }
    }

    private fun auroraDaysLeft(): Long {
        val paidUntil = com.naua_security_mirage.app.data.supabase.SupabaseManager.instance
            .subscription.value?.paidUntil ?: return 0
        return try {
            val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US)
            fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
            val date = fmt.parse(paidUntil.take(10)) ?: return 0
            Math.round((date.time - System.currentTimeMillis()) / 86400000.0).coerceAtLeast(0)
        } catch (_: Throwable) {
            0
        }
    }

    private fun syncAuroraDiagnostics() {
        auroraFreeDesc?.text = nodeStatusCard
            ?.findViewById<TextView>(R.id.tvFreeNodesState)?.text
            ?: getString(R.string.free_nodes_tap_to_check)
        auroraPaidDesc?.text = nodeStatusCard
            ?.findViewById<TextView>(R.id.tvNodeStatusState)?.text
            ?: getString(R.string.node_status_checking)
        auroraGeoDesc?.text = binding.tvGeoStatus.text
        auroraPerAppDesc?.text = binding.tvPerAppSettingDesc.text
        auroraSpeedDesc?.text = binding.tvSpeedDesc.text
        auroraPingDesc?.text = binding.tvAutoPingDesc.text
        auroraLogsDesc?.text = binding.tvLogsSettingDesc.text
        auroraPrivateDnsDesc?.text = privateDnsCard
            ?.findViewById<TextView>(R.id.tvPrivateDnsState)?.text
        auroraTunnelDnsDesc?.text = privateDnsCard
            ?.findViewById<TextView>(R.id.tvTunnelDnsState)?.text
    }

    private fun syncAuroraVersion() {
        val chip = auroraCoreChip ?: return
        chip.text = lastNodeCore?.let { getString(R.string.glass_version_core, it) }
            ?: getString(R.string.glass_version_core_unknown)
    }

    // ---------------- one-second tick: sparkline + pushes ----------------

    private fun startAuroraTickLoop() {
        if (auroraTickRunnable != null) return
        val r = object : Runnable {
            override fun run() {
                if (settingsRepository.uiStyle != SettingsRepository.UI_STYLE_GLASS) return
                val shell = binding.auroraShell
                val connected = currentVpnState == VpnState.CONNECTED
                val dn = if (connected) auroraSpeedValue(shell.auSpeedDown.text?.toString()) ?: 0f else 0f
                val up = if (connected) auroraSpeedValue(shell.auSpeedUp.text?.toString()) ?: 0f else 0f
                shell.auSpark.pushTick(dn, up)
                auroraTickHandler.postDelayed(this, 1000L)
            }
        }
        auroraTickRunnable = r
        auroraTickHandler.post(r)
    }

    private fun stopAuroraTickLoop() {
        auroraTickRunnable?.let { auroraTickHandler.removeCallbacks(it) }
        auroraTickRunnable = null
    }

    // ---------------- orb breathe + connect pulses ----------------

    private var auroraBreathe: ValueAnimator? = null
    private val auroraPulses = mutableListOf<ValueAnimator>()

    private fun startAuroraBreathe() {
        if (auroraBreathe != null) return
        if (!settingsRepository.areAnimationsEnabled) return
        val shellRef = binding.auroraShell
        auroraBreathe = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 4500L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = LinearInterpolator()
            addUpdateListener { a ->
                val t = a.animatedValue as Float
                shellRef.auHalo.alpha = 0.46f - 0.18f * t
            }
            start()
        }
    }

    private fun cancelAuroraBreathe() {
        auroraBreathe?.cancel()
        auroraBreathe = null
    }

    private fun startAuroraPulses() {
        if (auroraPulses.isNotEmpty()) return
        if (!settingsRepository.areAnimationsEnabled) return
        val shellRef = binding.auroraShell
        fun pulse(view: View, delay: Long) {
            val anim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 3600L
                repeatCount = ValueAnimator.INFINITE
                startDelay = delay
                interpolator = LinearInterpolator()
                addUpdateListener { a ->
                    val t = a.animatedValue as Float
                    view.alpha = 0.5f * (1f - t)
                    view.scaleX = 1f + 0.5f * t
                    view.scaleY = 1f + 0.5f * t
                }
            }
            anim.start()
            auroraPulses.add(anim)
        }
        pulse(shellRef.auPulse1, 0L)
        pulse(shellRef.auPulse2, 1800L)
    }

    private fun cancelAuroraPulses() {
        auroraPulses.forEach { it.cancel() }
        auroraPulses.clear()
        val shell = binding.auroraShell
        shell.auPulse1.alpha = 0f
        shell.auPulse2.alpha = 0f
        shell.auPulse1.scaleX = 1f
        shell.auPulse1.scaleY = 1f
        shell.auPulse2.scaleX = 1f
        shell.auPulse2.scaleY = 1f
    }

    // ---------------- country dropdown (design .menu) ----------------

    private fun buildAuroraCountryMenu() {
        val menu = binding.auroraShell.auCountryMenu
        menu.removeAllViews()
        auroraMenuRows.clear()
        val paidNodeChoices().forEach { choice ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minHeight = gdp(52)
                setPadding(gdp(14), 0, gdp(14), 0)
                isClickable = true
                isFocusable = true
                val out = android.util.TypedValue()
                if (theme.resolveAttribute(android.R.attr.selectableItemBackground, out, true)) {
                    setBackgroundResource(out.resourceId)
                }
            }
            val flag = com.naua_security_mirage.app.ui.view.AuroraFlagView(this).apply {
                code = if (choice.plan == SettingsRepository.PLAN_PREMIUM_BELARUS) "by" else "fr"
                layoutParams = LinearLayout.LayoutParams(gdp(26), gdp(18))
            }
            val name = TextView(this).apply {
                text = choice.label
                textSize = 15f
                typeface = auroraFont(R.font.onest_600)
                setTextColor(auroraColor(R.color.au_fg))
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f
                ).apply { marginStart = gdp(12) }
            }
            val check = ImageView(this).apply {
                setImageResource(R.drawable.au_check)
                imageTintList = ColorStateList.valueOf(auroraColor(R.color.au_accent_text))
                visibility = View.INVISIBLE
                layoutParams = LinearLayout.LayoutParams(gdp(20), gdp(20))
            }
            row.addView(flag)
            row.addView(name)
            row.addView(check)
            row.setOnClickListener {
                AnimationHelper.bounceClick(it, minScale = 0.97f, durationMs = 130)
                setAuroraCountryMenu(false)
                requestPaidPlan(choice.plan)
                syncGlassShell()
            }
            if (auroraMenuRows.isNotEmpty()) {
                menu.addView(
                    View(this).apply { setBackgroundColor(auroraColor(R.color.au_sep)) },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, gdp(1))
                )
            }
            menu.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            row.tag = choice.plan
            auroraMenuRows.add(row)
        }
    }

    private fun refreshAuroraCountryMenu() {
        val selectedPlan = if (settingsRepository.isPaidPlan()) {
            settingsRepository.selectedServerPlan
        } else {
            settingsRepository.lastPaidPlan
        }
        auroraMenuRows.forEach { row ->
            val selected = row.tag == selectedPlan
            val name = row.getChildAt(1) as? TextView ?: return@forEach
            val check = row.getChildAt(2) as? ImageView ?: return@forEach
            name.setTextColor(
                ContextCompat.getColor(this, if (selected) R.color.au_accent_text else R.color.au_fg)
            )
            check.visibility = if (selected) View.VISIBLE else View.INVISIBLE
        }
    }

    private fun setAuroraCountryMenu(open: Boolean) {
        val shell = binding.auroraShell
        val menu = shell.auCountryMenu
        if ((menu.visibility == View.VISIBLE) == open) return
        if (open) {
            menu.alpha = 0f
            menu.translationY = -gdp(8).toFloat()
            menu.visibility = View.VISIBLE
            menu.animate().alpha(1f).translationY(0f).setDuration(250L).start()
        } else {
            menu.animate().alpha(0f).translationY(-gdp(8).toFloat()).setDuration(200L)
                .withEndAction {
                    menu.visibility = View.GONE
                    menu.translationY = 0f
                    menu.alpha = 1f
                }.start()
        }
        shell.auPaidChevron.animate().rotation(if (open) 180f else 0f).setDuration(400L).start()
    }

    // ---------------- seg thumb slide ----------------

    private fun updateAuroraSegment(paid: Boolean) {
        val shell = binding.auroraShell
        val seg = shell.auSeg
        val thumb = shell.auSegThumb
        seg.post {
            if (seg.width <= 0) return@post
            val d = resources.displayMetrics.density
            val pad = 4f * d
            val track = seg.width - pad * 2
            val freeW = track * 5f / 12f
            val paidW = track * 7f / 12f
            val targetLeft = if (paid) pad + freeW else pad
            val targetW = if (paid) paidW else freeW

            val lp = thumb.layoutParams as FrameLayout.LayoutParams
            if (!settingsRepository.areAnimationsEnabled) {
                lp.width = targetW.toInt().coerceAtLeast(1)
                lp.marginStart = targetLeft.toInt()
                thumb.requestLayout()
                return@post
            }
            val startLeft = lp.marginStart.toFloat()
            val startW = lp.width.takeIf { it > 0 }?.toFloat() ?: targetW
            if (Math.abs(startLeft - targetLeft) < 1f && Math.abs(startW - targetW) < 1f) {
                lp.width = targetW.toInt().coerceAtLeast(1)
                return@post
            }
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 550L
                interpolator = DecelerateInterpolator()
                addUpdateListener { a ->
                    val t = a.animatedValue as Float
                    val curLp = thumb.layoutParams as FrameLayout.LayoutParams
                    curLp.width = (startW + (targetW - startW) * t).toInt().coerceAtLeast(1)
                    curLp.marginStart = (startLeft + (targetLeft - startLeft) * t).toInt()
                    thumb.requestLayout()
                }
                start()
            }
        }
    }

    // ---------------- settings row toggles: state + handlers ----------------

    private fun auroraToggleState(key: String): Boolean = when (key) {
        "status" -> settingsRepository.isStatusNotificationEnabled
        "tile" -> settingsRepository.isQuickSettingsTileEnabled
        "payment" -> settingsRepository.isPaymentNotificationEnabled
        "kill" -> settingsRepository.isKillSwitchEnabled
        "screenshots" -> !settingsRepository.isScreenshotBlockingEnabled
        "autostart" -> settingsRepository.isAutoStartOnBootEnabled
        "telemetry" -> settingsRepository.isAnonymousTelemetryEnabled
        "animations" -> settingsRepository.areAnimationsEnabled
        "direct_ru" -> settingsRepository.isDirectRuEnabled
        else -> false
    }

    private fun auroraToggleChanged(key: String, checked: Boolean) {
        when (key) {
            "status" -> settingsRepository.isStatusNotificationEnabled = checked
            "tile" -> {
                settingsRepository.isQuickSettingsTileEnabled = checked
                MirageTileService.setTileEnabled(this, checked)
            }
            "payment" -> {
                settingsRepository.isPaymentNotificationEnabled = checked
                MirageNotifyTileService.requestUpdate(this)
            }
            "kill" -> {
                settingsRepository.isKillSwitchEnabled = checked
                refreshKillSwitchRow()
                showVpnActiveReconnectionNoticeIfNeeded()
            }
            "screenshots" -> {
                settingsRepository.isScreenshotBlockingEnabled = !checked
                applyScreenshotBlocking()
            }
            "autostart" -> settingsRepository.isAutoStartOnBootEnabled = checked
            "telemetry" -> {
                settingsRepository.isAnonymousTelemetryEnabled = checked
                com.naua_security_mirage.app.MirageApp.updateTelemetryState(this, checked)
            }
            "animations" -> {
                settingsRepository.areAnimationsEnabled = checked
                AnimationHelper.animationsEnabled = checked
                if (checked) {
                    startAuroraAmbient()
                } else {
                    cancelAuroraAmbient()
                    cancelAuroraBreathe()
                    cancelAuroraPulses()
                }
            }
            "direct_ru" -> {
                settingsRepository.isDirectRuEnabled = checked
                showVpnActiveReconnectionNoticeIfNeeded()
            }
        }
    }

    // ---------------- shared widget helpers ----------------

    private fun auroraGlassBg(radiusDp: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = gdp(radiusDp).toFloat()
            orientation = GradientDrawable.Orientation.TL_BR
            colors = intArrayOf(auroraColor(R.color.au_gf_a), auroraColor(R.color.au_gf_b))
            setStroke(gdp(1), auroraColor(R.color.au_edge_lo))
        }

    private fun auroraRadioBg(selected: Boolean): Drawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        if (selected) {
            setColor(auroraColor(R.color.au_accent))
            setStroke(gdp(2), auroraColor(R.color.au_accent_hi))
        } else {
            setColor(Color.TRANSPARENT)
            setStroke(gdp(2), auroraColor(R.color.au_edge_lo))
        }
    }

    private fun auroraRipple(): Drawable {
        val out = android.util.TypedValue()
        if (theme.resolveAttribute(android.R.attr.selectableItemBackground, out, true)) {
            return ContextCompat.getDrawable(this, out.resourceId)
                ?: GradientDrawable().apply { setColor(Color.TRANSPARENT) }
        }
        return GradientDrawable().apply { setColor(Color.TRANSPARENT) }
    }

    private fun auroraTileFrame(): FrameLayout = FrameLayout(this).apply {
        background = ContextCompat.getDrawable(context, R.drawable.bg_au_tile)
        layoutParams = LinearLayout.LayoutParams(gdp(40), gdp(40)).apply {
            gravity = Gravity.CENTER_VERTICAL
        }
    }

    private fun auroraTileChild(tile: FrameLayout, @DrawableRes iconRes: Int, tint: Int = R.color.au_fg_2) {
        val icon = ImageView(this).apply {
            setImageResource(iconRes)
            imageTintList = ColorStateList.valueOf(auroraColor(tint))
            layoutParams = FrameLayout.LayoutParams(gdp(22), gdp(22), Gravity.CENTER)
        }
        tile.addView(icon)
    }

    private class AuroraRowSpec(
        val kind: String, // "toggle" | "nav" | "btn" | "plain"
        val iconRes: Int,
        val title: String,
        val desc: String,
        val key: String,
        val onClick: (() -> Unit)? = null,
        val btnLabel: String? = null
    )

    private fun auroraAddRow(group: LinearLayout, spec: AuroraRowSpec, first: Boolean): View {
        if (!first) {
            group.addView(
                View(this).apply { setBackgroundColor(auroraColor(R.color.au_sep)) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, gdp(1))
            )
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minHeight = gdp(66)
            setPaddingRelative(gdp(16), gdp(12), gdp(16), gdp(12))
            background = auroraRipple()
        }
        if (spec.kind != "plain") {
            val tile = auroraTileFrame()
            auroraTileChild(tile, spec.iconRes)
            row.addView(tile)
        }

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (spec.title.isNotBlank()) {
            col.addView(TextView(this).apply {
                text = spec.title
                textSize = 15f
                typeface = auroraFont(R.font.onest_600)
                setTextColor(auroraColor(R.color.au_fg))
            })
        }
        if (spec.desc.isNotBlank()) {
            col.addView(TextView(this).apply {
                this.text = spec.desc
                textSize = 13f
                typeface = auroraFont(R.font.onest_400)
                setTextColor(auroraColor(R.color.au_fg_2))
                tag = "au_row_desc"
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = gdp(2) })
        }
        row.addView(
            col,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = if (spec.kind != "plain") gdp(14) else 0
                marginEnd = gdp(6)
            }
        )

        when (spec.kind) {
            "toggle" -> {
                val switchView = MirageSwitch(this).apply {
                    setStyle(true)
                    setChecked(auroraToggleState(spec.key), animate = false)
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        gravity = Gravity.CENTER_VERTICAL
                        marginEnd = gdp(2)
                    }
                    setOnCheckedChangeListener { _, checked ->
                        auroraToggleChanged(spec.key, checked)
                    }
                }
                auroraSwitches[spec.key] = switchView
                row.addView(switchView)
                row.setOnClickListener { switchView.toggle() }
            }
            "nav" -> {
                row.addView(ImageView(this).apply {
                    setImageResource(R.drawable.ic_chevron_right)
                    imageTintList = ColorStateList.valueOf(auroraColor(R.color.au_fg_3))
                    layoutParams = LinearLayout.LayoutParams(gdp(20), gdp(20))
                })
            }
            "btn" -> {
                row.addView(TextView(this).apply {
                    text = spec.btnLabel
                    textSize = 14f
                    typeface = auroraFont(R.font.onest_600)
                    setTextColor(auroraColor(R.color.au_accent_text))
                    background = ContextCompat.getDrawable(context, R.drawable.bg_au_btn_ghost)
                    setPaddingRelative(gdp(15), gdp(9), gdp(15), gdp(9))
                })
            }
        }

        if (spec.kind != "toggle" && spec.onClick != null) {
            row.setOnClickListener {
                AnimationHelper.bounceClick(row, minScale = 0.98f, durationMs = 140)
                spec.onClick.invoke()
            }
        }

        group.addView(
            row,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        return row
    }

    // ---------------- screens ----------------

    private fun buildAuroraScreens() {
        val host = binding.auroraShell.auScreens
        auroraSettingsScreen = buildAuroraSettingsScreen(host)
        auroraFaqScreen = buildAuroraFaqScreen(host)
        auroraVersionScreen = buildAuroraVersionScreen(host)
    }

    /** insets + dock margin for the aurora shell; idempotent, called on every insets pass */
    private fun applyAuroraScreenPadding() {
        if (auroraSettingsScreen == null) return
        val shell = binding.auroraShell
        val d = resources.displayMetrics.density
        val side = (16 * d).toInt()
        val top = auroraInsetsTop + (14 * d).toInt()
        val bottom = (132 * d).toInt()
        shell.auHomeScroll.setPadding(side, top, side, bottom)
        listOf(auroraSettingsScreen, auroraFaqScreen, auroraVersionScreen)
            .filterIsInstance<ScrollView>()
            .forEach { screen ->
                screen.isFillViewport = false
                screen.clipToPadding = false
                screen.setPadding(side, top, side, bottom)
            }
        (shell.auDock.layoutParams as FrameLayout.LayoutParams).let { lp ->
            lp.bottomMargin = auroraInsetsBottom + (16 * d).toInt()
            shell.auDock.layoutParams = lp
        }
    }

    private fun auroraSection(content: LinearLayout, title: String) {
        content.addView(TextView(this).apply {
            text = title.uppercase()
            textSize = 12f
            letterSpacing = 0.14f
            typeface = auroraFont(R.font.onest_600)
            setTextColor(auroraColor(R.color.au_fg_3))
            setPaddingRelative(gdp(6), 0, gdp(6), 0)
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, gdp(16), 0, -gdp(2)) })
    }

    private fun auroraGroupCard(content: LinearLayout): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = auroraGlassBg(28)
            clipToOutline = true
        }.also { card ->
            content.addView(card, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = gdp(14) })
        }

    private fun auroraGroupShell(content: LinearLayout, title: String): LinearLayout {
        auroraSection(content, title)
        val group = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = auroraGlassBg(28)
            clipToOutline = true
        }
        content.addView(group, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = gdp(14) })
        return group
    }

    private fun buildAuroraSettingsScreen(host: ViewGroup): ScrollView {
        val screen = ScrollView(this).apply {
            visibility = View.GONE
            isVerticalScrollBarEnabled = false
        }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        screen.addView(
            content,
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        host.addView(screen, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        content.addView(auroraHeader(
            getString(R.string.dock_settings),
            getString(R.string.app_version, BuildConfig.VERSION_NAME)
        ))

        // account card
        val account = auroraGroupCard(content).apply {
            setPaddingRelative(gdp(16), gdp(16), gdp(16), gdp(16))
        }
        val accTop = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val avatar = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                gradientType = GradientDrawable.RADIAL_GRADIENT
                gradientRadius = gdp(40).toFloat()
                setGradientCenter(0.32f, 0.24f)
                colors = intArrayOf(
                    auroraColor(R.color.au_accent_hi),
                    auroraColor(R.color.au_accent),
                    auroraColor(R.color.au_accent_lo)
                )
            }
            layoutParams = LinearLayout.LayoutParams(gdp(48), gdp(48))
        }
        avatar.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_user)
            imageTintList = ColorStateList.valueOf(auroraColor(R.color.au_on_accent))
            layoutParams = FrameLayout.LayoutParams(gdp(22), gdp(22), Gravity.CENTER)
        })
        accTop.addView(avatar)
        val email = TextView(this).apply {
            textSize = 16f
            typeface = auroraFont(R.font.onest_600)
            setTextColor(auroraColor(R.color.au_fg))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        auroraAccEmail = email
        accTop.addView(
            email,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = gdp(14)
                marginEnd = gdp(10)
            }
        )
        accTop.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_eye)
            imageTintList = ColorStateList.valueOf(auroraColor(R.color.au_fg_2))
            background = ContextCompat.getDrawable(context, R.drawable.bg_au_glass_circle)
            setPadding(gdp(10), gdp(10), gdp(10), gdp(10))
            layoutParams = LinearLayout.LayoutParams(gdp(40), gdp(40))
        }.also { eye ->
            eye.setOnClickListener {
                AnimationHelper.bounceClick(eye, minScale = 0.88f, durationMs = 140)
                settingsRepository.isEmailMasked = !settingsRepository.isEmailMasked
                updateAccountCardUI()
                syncAuroraAccount()
            }
        })
        account.addView(accTop)

        val subLine = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        subLine.addView(TextView(this).apply {
            textSize = 13f
            typeface = auroraFont(R.font.onest_600)
            background = ContextCompat.getDrawable(context, R.drawable.bg_au_pill_good)
            setTextColor(auroraColor(R.color.au_good))
        }.also { pill ->
            pill.setPaddingRelative(gdp(12), gdp(4), gdp(12), gdp(4))
            auroraAccStatus = pill
        })
        subLine.addView(TextView(this).apply {
            textSize = 13f
            typeface = auroraFont(R.font.onest_400)
            setTextColor(auroraColor(R.color.au_fg_2))
        }.also { days ->
            auroraAccDays = days
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = gdp(10) })
        account.addView(subLine, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = gdp(12) })

        val cache = TextView(this).apply {
            textSize = 13f
            typeface = auroraFont(R.font.onest_400)
            setTextColor(auroraColor(R.color.au_fg_2))
        }
        auroraAccCache = cache
        account.addView(cache, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = gdp(4) })

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(TextView(this).apply {
            gravity = Gravity.CENTER
            minHeight = gdp(46)
            textSize = 15f
            typeface = auroraFont(R.font.onest_600)
            setTextColor(auroraColor(R.color.au_on_accent))
            background = ContextCompat.getDrawable(context, R.drawable.bg_au_btn_accent)
        }.also { action ->
            auroraAccAction = action
            action.setOnClickListener {
                AnimationHelper.bounceClick(action, minScale = 0.96f, durationMs = 140)
                if (binding.cardAccount.layoutAccountLoggedOut.visibility == View.VISIBLE) {
                    showAuthDialog()
                } else {
                    binding.cardAccount.btnAccountSubscribe.performClick()
                }
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actions.addView(TextView(this).apply {
            gravity = Gravity.CENTER
            minHeight = gdp(46)
            textSize = 15f
            typeface = auroraFont(R.font.onest_600)
            setTextColor(auroraColor(R.color.au_bad))
            background = ContextCompat.getDrawable(context, R.drawable.bg_au_btn_out)
        }.also { leave ->
            auroraAccLeave = leave
            leave.setOnClickListener {
                AnimationHelper.bounceClick(leave, minScale = 0.96f, durationMs = 140)
                binding.cardAccount.btnAccountLogout.performClick()
                syncAuroraAccount()
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = gdp(10)
        })
        account.addView(actions, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = gdp(12) })
        content.addView(account, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        // notifications
        val notifGroup = auroraGroupShell(content, getString(R.string.section_notifications))
        var first = true
        fun spec(type: String, icon: Int, titleRes: Int, descRes: Int, key: String): AuroraRowSpec =
            AuroraRowSpec(type, icon, getString(titleRes), getString(descRes), key)
        auroraAddRow(notifGroup, spec("toggle", R.drawable.au_bell, R.string.setting_status_change, R.string.setting_status_change_desc, "status"), first).also { first = false }
        auroraAddRow(notifGroup, spec("toggle", R.drawable.ic_power_settings, R.string.setting_quick_tile, R.string.setting_quick_tile_desc, "tile"), first)
        auroraAddRow(notifGroup, spec("toggle", R.drawable.ic_shield, R.string.setting_payment_notification, R.string.setting_payment_notification_desc, "payment"), first)

        // privacy
        val privacyGroup = auroraGroupShell(content, getString(R.string.section_privacy))
        auroraAddRow(privacyGroup, spec("toggle", R.drawable.ic_lock, R.string.setting_kill_switch, R.string.setting_kill_switch_desc, "kill"), true)
        auroraAddRow(privacyGroup, AuroraRowSpec("plain", 0, "", getString(R.string.system_vpn_desc), ""), false)
        auroraAddRow(privacyGroup, AuroraRowSpec(
            "toggle", R.drawable.ic_image,
            getString(R.string.setting_screenshots),
            if (settingsRepository.isScreenshotBlockingEnabled) {
                getString(R.string.setting_screenshots_desc)
            } else {
                getString(R.string.setting_screenshots_allowed)
            },
            "screenshots"
        ), false)
        auroraAddRow(privacyGroup, spec("toggle", R.drawable.ic_power, R.string.setting_auto_start, R.string.setting_auto_start_desc, "autostart"), false)
        auroraAddRow(privacyGroup, spec("toggle", R.drawable.ic_pulse, R.string.setting_telemetry, R.string.setting_telemetry_desc, "telemetry"), false)

        // appearance
        val appearanceGroup = auroraGroupShell(content, getString(R.string.section_appearance))
        auroraAddRow(appearanceGroup, AuroraRowSpec(
            "toggle", R.drawable.ic_glass,
            getString(R.string.glass_settings_glass_title),
            getString(R.string.glass_settings_glass_desc),
            "glass"
        ), true)
        auroraAddRow(appearanceGroup, spec("toggle", R.drawable.ic_sparkle, R.string.setting_animations, R.string.setting_animations_desc, "animations"), false)
        auroraAddRow(appearanceGroup, spec("nav", R.drawable.ic_palette, R.string.setting_language, R.string.setting_language_desc, "") { binding.btnLanguage.performClick() }, false)
        auroraAddRow(appearanceGroup, spec("nav", R.drawable.ic_palette, R.string.setting_appearance, R.string.setting_appearance_desc, "") {
            selectGlassTab(GLASS_TAB_HOME, animate = false)
            openAppearance()
        }, false)
        auroraAddRow(appearanceGroup, spec("nav", R.drawable.ic_eye_off, R.string.setting_hide_sections, R.string.setting_hide_sections_desc, "") { showHideSectionsDialog() }, false)

        // theme mini segment
        appearanceGroup.addView(buildAuroraThemeRow(), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        // backup
        val backupGroup = auroraGroupShell(content, getString(R.string.section_backup))
        auroraAddRow(backupGroup, spec("nav", R.drawable.ic_download, R.string.backup_export, R.string.backup_export_desc, "") { exportSettings() }, true)
        auroraAddRow(backupGroup, spec("nav", R.drawable.au_upload, R.string.backup_import, R.string.backup_import_desc, "") { importSettingsLauncher.launch("application/json") }, false)

        // split tunneling + dns
        val tunnelGroup = auroraGroupShell(content, getString(R.string.section_per_app_proxy))
        auroraAddRow(tunnelGroup, spec("toggle", R.drawable.ic_globe, R.string.setting_direct_ru, R.string.setting_direct_ru_desc, "direct_ru"), true)

        // geo row: description + round refresh button
        tunnelGroup.addView(
            View(this).apply { setBackgroundColor(auroraColor(R.color.au_sep)) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, gdp(1))
        )
        val geoRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minHeight = gdp(56)
            setPaddingRelative(gdp(16), gdp(10), gdp(16), gdp(10))
            background = auroraRipple()
        }
        val geoDesc = TextView(this).apply {
            textSize = 13f
            typeface = auroraFont(R.font.onest_400)
            setTextColor(auroraColor(R.color.au_fg_2))
        }
        auroraGeoDesc = geoDesc
        geoRow.addView(geoDesc, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val geoBtn = ImageView(this).apply {
            setImageResource(R.drawable.ic_refresh)
            imageTintList = ColorStateList.valueOf(auroraColor(R.color.au_fg_2))
            background = ContextCompat.getDrawable(context, R.drawable.bg_au_glass_circle)
            setPadding(gdp(10), gdp(10), gdp(10), gdp(10))
            layoutParams = LinearLayout.LayoutParams(gdp(40), gdp(40))
        }
        geoBtn.setOnClickListener {
            AnimationHelper.bounceClick(geoBtn, minScale = 0.9f, durationMs = 140)
            updateGeoDatabasesManually()
        }
        geoRow.addView(geoBtn)
        tunnelGroup.addView(geoRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        auroraPerAppDesc = auroraAddRow(tunnelGroup, spec(
            "nav", R.drawable.au_grid, R.string.setting_per_app_proxy, R.string.per_app_all_enabled, ""
        ) {
            selectGlassTab(GLASS_TAB_HOME, animate = false)
            openPerAppProxy()
        }, false).findViewWithTag<TextView>("au_row_desc")?.also { auroraPerAppDesc = it }
        auroraAddRow(tunnelGroup, spec(
            "nav", R.drawable.ic_globe, R.string.setting_custom_websites_proxy, R.string.setting_custom_websites_proxy_desc, ""
        ) {
            selectGlassTab(GLASS_TAB_HOME, animate = false)
            openCustomWebsites()
        }, false)
        auroraPrivateDnsDesc = auroraAddRow(tunnelGroup, AuroraRowSpec(
            "btn", R.drawable.ic_shield,
            getString(R.string.private_dns_title),
            privateDnsCard?.findViewById<TextView>(R.id.tvPrivateDnsState)?.text?.toString().orEmpty(),
            "",
            { openPrivateDnsSettings() },
            getString(R.string.private_dns_open_settings)
        ), false).findViewWithTag<TextView>("au_row_desc")?.also { auroraPrivateDnsDesc = it }
        auroraTunnelDnsDesc = auroraAddRow(tunnelGroup, spec(
            "nav", R.drawable.ic_globe, R.string.tunnel_dns_title, R.string.tunnel_dns_default, ""
        ) { showTunnelDnsDialog() }, false).findViewWithTag<TextView>("au_row_desc")?.also { auroraTunnelDnsDesc = it }

        // diagnostics
        val diagGroup = auroraGroupShell(content, getString(R.string.section_diagnostics))
        auroraFreeDesc = auroraAddRow(diagGroup, AuroraRowSpec(
            "btn", R.drawable.ic_power,
            getString(R.string.free_nodes_title),
            getString(R.string.free_nodes_tap_to_check),
            "",
            {
                nodeStatusCard?.findViewById<TextView>(R.id.tvFreeNodesState)?.let { view ->
                    view.text = getString(R.string.free_nodes_checking)
                    measureFreeNodes(view)
                }
            },
            getString(R.string.node_status_refresh)
        ), true).findViewWithTag<TextView>("au_row_desc")?.also { auroraFreeDesc = it }
        auroraPaidDesc = auroraAddRow(diagGroup, AuroraRowSpec(
            "btn", R.drawable.ic_pulse,
            getString(R.string.node_status_title),
            getString(R.string.node_status_checking),
            "",
            { refreshNodeStatus(force = true) },
            getString(R.string.node_status_refresh)
        ), false).findViewWithTag<TextView>("au_row_desc")?.also { auroraPaidDesc = it }
        auroraAddRow(diagGroup, spec(
            "nav", R.drawable.ic_shield, R.string.session_history_title, R.string.session_history_hint, ""
        ) { showSessionHistoryDialog() }, false)
        auroraSpeedDesc = auroraAddRow(diagGroup, AuroraRowSpec(
            "nav", R.drawable.ic_speed,
            getString(R.string.setting_speed),
            "",
            ""
        ) {
            selectGlassTab(GLASS_TAB_HOME, animate = false)
            openSettings()
        }, false).findViewWithTag<TextView>("au_row_desc")?.also { auroraSpeedDesc = it }
        auroraPingDesc = auroraAddRow(diagGroup, AuroraRowSpec(
            "nav", R.drawable.ic_gauge,
            getString(R.string.setting_auto_ping),
            "",
            ""
        ) {
            selectGlassTab(GLASS_TAB_HOME, animate = false)
            openSettings()
        }, false).findViewWithTag<TextView>("au_row_desc")?.also { auroraPingDesc = it }
        auroraLogsDesc = auroraAddRow(diagGroup, AuroraRowSpec(
            "nav", R.drawable.ic_file_text,
            getString(R.string.setting_logs),
            "",
            ""
        ) { openLogs() }, false).findViewWithTag<TextView>("au_row_desc")?.also { auroraLogsDesc = it }

        content.addView(
            View(this),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, gdp(120))
        )
        return screen
    }

    private fun buildAuroraThemeRow(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPaddingRelative(gdp(16), gdp(12), gdp(16), gdp(16))
            tag = "au_theme_row"
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val tile = auroraTileFrame()
        auroraTileChild(tile, R.drawable.ic_palette)
        head.addView(tile)
        head.addView(TextView(this).apply {
            text = getString(R.string.au_theme_row_t)
            textSize = 15f
            typeface = auroraFont(R.font.onest_600)
            setTextColor(auroraColor(R.color.au_fg))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = gdp(14)
        })
        row.addView(head)

        val seg = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = ContextCompat.getDrawable(context, R.drawable.bg_au_mini_seg_bg)
            setPadding(gdp(3), gdp(3), gdp(3), gdp(3))
        }
        fun themeChip(id: String, labelRes: Int) {
            val chip = TextView(this).apply {
                text = getString(labelRes)
                textSize = 13f
                typeface = auroraFont(R.font.onest_600)
                minHeight = gdp(30)
                gravity = Gravity.CENTER
                setPaddingRelative(gdp(14), gdp(4), gdp(14), gdp(4))
            }
            chip.setOnClickListener {
                AnimationHelper.bounceClick(chip, minScale = 0.95f, durationMs = 120)
                if (id == "dark") binding.chipThemeDark.performClick() else binding.chipThemeLight.performClick()
                syncAuroraThemeRow()
            }
            seg.addView(chip, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            chip.tag = id
        }
        themeChip("dark", R.string.theme_dark)
        themeChip("light", R.string.theme_light)
        row.addView(seg, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = gdp(10) })
        return row
    }

    private fun syncAuroraThemeRow() {
        val row = (auroraSettingsScreen as? ViewGroup)
            ?.findViewWithTag<ViewGroup>("au_theme_row") ?: return
        val seg = row.getChildAt(1) as? LinearLayout ?: return
        val isLight = settingsRepository.themePreset == SettingsRepository.THEME_LIGHT
        for (i in 0 until seg.childCount) {
            val chip = seg.getChildAt(i) as? TextView ?: continue
            val selected = (chip.tag == "dark" && !isLight) || (chip.tag == "light" && isLight)
            chip.setTextColor(
                ContextCompat.getColor(
                    this,
                    if (selected) R.color.au_accent_text else R.color.au_fg_2
                )
            )
            chip.setBackgroundResource(if (selected) R.drawable.bg_au_mini_seg_item else 0)
        }
    }

    // ---------------- FAQ ----------------

    private fun buildAuroraFaqScreen(host: ViewGroup): ScrollView {
        val screen = ScrollView(this).apply {
            visibility = View.GONE
            isVerticalScrollBarEnabled = false
        }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        screen.addView(
            content,
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        host.addView(screen, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        content.addView(auroraHeader(
            getString(R.string.dock_faq),
            getString(R.string.glass_faq_subtitle)
        ))

        val faqs = listOf(
            R.string.au_faq_1_q to R.string.au_faq_1_a,
            R.string.au_faq_2_q to R.string.au_faq_2_a,
            R.string.au_faq_3_q to R.string.au_faq_3_a,
            R.string.au_faq_4_q to R.string.au_faq_4_a,
            R.string.au_faq_5_q to R.string.au_faq_5_a,
            R.string.au_faq_6_q to R.string.au_faq_6_a,
            R.string.au_faq_7_q to R.string.au_faq_7_a
        )
        faqs.forEachIndexed { index, (q, a) ->
            val acc = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = auroraGlassBg(24)
                clipToOutline = true
            }
            val head = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPaddingRelative(gdp(18), gdp(17), gdp(18), gdp(17))
                background = auroraRipple()
                isClickable = true
                isFocusable = true
            }
            head.addView(TextView(this).apply {
                text = getString(q)
                textSize = 15f
                typeface = auroraFont(R.font.onest_600)
                setTextColor(auroraColor(R.color.au_fg))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val chev = ImageView(this).apply {
                setImageResource(R.drawable.ic_chevron_right)
                imageTintList = ColorStateList.valueOf(auroraColor(R.color.au_fg_3))
            }
            head.addView(chev, LinearLayout.LayoutParams(gdp(20), gdp(20)))
            val body = TextView(this).apply {
                text = getString(a)
                textSize = 14.5f
                typeface = auroraFont(R.font.onest_400)
                setTextColor(auroraColor(R.color.au_fg_2))
                setPaddingRelative(gdp(18), 0, gdp(18), gdp(18))
                visibility = View.GONE
            }
            acc.addView(head)
            acc.addView(body)
            content.addView(acc, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = if (index == 0) 0 else gdp(14) })

            head.setOnClickListener {
                AnimationHelper.bounceClick(head, minScale = 0.98f, durationMs = 130)
                val opening = body.visibility != View.VISIBLE
                for (i in 0 until content.childCount) {
                    val other = content.getChildAt(i) as? LinearLayout ?: continue
                    val otherBody = other.getChildAt(1) as? TextView ?: continue
                    val otherHead = other.getChildAt(0) as? LinearLayout ?: continue
                    val otherChev = otherHead.getChildAt(otherHead.childCount - 1) as? ImageView ?: continue
                    otherBody.visibility = View.GONE
                    otherChev.animate().rotation(0f).setDuration(300L).start()
                    otherChev.imageTintList = ColorStateList.valueOf(auroraColor(R.color.au_fg_3))
                }
                if (opening) {
                    chev.animate().rotation(90f).setDuration(300L).start()
                    chev.imageTintList = ColorStateList.valueOf(auroraColor(R.color.au_accent_text))
                    body.visibility = View.VISIBLE
                    body.alpha = 0f
                    body.animate().alpha(1f).setDuration(220L).start()
                }
            }
        }

        val write = TextView(this).apply {
            text = getString(R.string.au_faq_contact)
            gravity = Gravity.CENTER
            minHeight = gdp(46)
            textSize = 15f
            typeface = auroraFont(R.font.onest_600)
            setTextColor(auroraColor(R.color.au_on_accent))
            background = ContextCompat.getDrawable(context, R.drawable.bg_au_btn_accent)
        }
        write.setOnClickListener {
            AnimationHelper.bounceClick(write, minScale = 0.96f, durationMs = 140)
            showAuroraSheet("tg")
        }
        content.addView(write, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = gdp(14) })
        content.addView(
            View(this),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, gdp(120))
        )
        return screen
    }

    // ---------------- version ----------------

    private fun buildAuroraVersionScreen(host: ViewGroup): ScrollView {
        val screen = ScrollView(this).apply {
            visibility = View.GONE
            isVerticalScrollBarEnabled = false
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        screen.addView(
            content,
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        host.addView(screen, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        content.addView(auroraHeader(
            getString(R.string.dock_version),
            getString(R.string.glass_version_subtitle)
        ))

        // hero mark
        val mark = FrameLayout(this)
        mark.addView(View(this).apply {
            background = ContextCompat.getDrawable(context, R.drawable.bg_au_glass_circle)
        }, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        mark.addView(View(this).apply {
            background = ContextCompat.getDrawable(context, R.drawable.bg_au_core_on)
        }, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ).apply { setMargins(gdp(14), gdp(14), gdp(14), gdp(14)) })
        mark.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_power)
            imageTintList = ColorStateList.valueOf(auroraColor(R.color.au_on_accent))
            setPadding(gdp(14), gdp(14), gdp(14), gdp(14))
        }, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        content.addView(mark, LinearLayout.LayoutParams(gdp(104), gdp(104)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = gdp(26)
        })

        content.addView(TextView(this).apply {
            text = getString(R.string.brand_name)
            textSize = 24f
            typeface = auroraFont(R.font.unbounded_600)
            letterSpacing = -0.015f
            setTextColor(auroraColor(R.color.au_fg))
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = gdp(14) })

        content.addView(TextView(this).apply {
            text = getString(
                R.string.glass_version_build,
                BuildConfig.VERSION_NAME,
                BuildConfig.VERSION_CODE
            )
            textSize = 15f
            typeface = auroraFont(R.font.jetbrains_mono_500)
            setTextColor(auroraColor(R.color.au_fg_2))
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = gdp(4) })

        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        chips.addView(TextView(this).apply {
            textSize = 12.5f
            typeface = auroraFont(R.font.jetbrains_mono_500)
            setTextColor(auroraColor(R.color.au_fg_2))
            background = auroraGlassBg(14)
            minHeight = gdp(28)
            gravity = Gravity.CENTER
            setPaddingRelative(gdp(12), gdp(4), gdp(12), gdp(4))
        }.also { coreChip ->
            auroraCoreChip = coreChip
        })
        chips.addView(TextView(this).apply {
            text = getString(R.string.brand_sub)
            textSize = 12.5f
            typeface = auroraFont(R.font.jetbrains_mono_500)
            setTextColor(auroraColor(R.color.au_fg_2))
            background = auroraGlassBg(14)
            minHeight = gdp(28)
            gravity = Gravity.CENTER
            setPaddingRelative(gdp(12), gdp(4), gdp(12), gdp(4))
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = gdp(8) })
        content.addView(chips, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = gdp(8) })

        // update sources
        content.addView(TextView(this).apply {
            text = getString(R.string.setting_updates).uppercase()
            textSize = 12f
            letterSpacing = 0.14f
            typeface = auroraFont(R.font.onest_600)
            setTextColor(auroraColor(R.color.au_fg_3))
            setPaddingRelative(gdp(6), 0, gdp(6), 0)
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, gdp(20), 0, 0) })

        val srcGroup = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = auroraGlassBg(28)
            clipToOutline = true
        }
        content.addView(srcGroup, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = gdp(14) })

        auroraSourceRows[SettingsRepository.UPDATE_SOURCE_GITHUB] = auroraAddSourceRow(
            srcGroup,
            SettingsRepository.UPDATE_SOURCE_GITHUB,
            R.drawable.ic_globe,
            getString(R.string.setting_updates_source_github),
            getString(R.string.badge_recommended),
            getString(R.string.source_github_desc)
        )
        auroraSourceRows[SettingsRepository.UPDATE_SOURCE_UPTODOWN] = auroraAddSourceRow(
            srcGroup,
            SettingsRepository.UPDATE_SOURCE_UPTODOWN,
            R.drawable.ic_download,
            getString(R.string.setting_updates_source_uptodown),
            getString(R.string.badge_moderation),
            getString(R.string.source_uptodown_desc)
        )
        syncAuroraSourceRadios()

        val check = TextView(this).apply {
            text = getString(R.string.btn_check_updates_now)
            gravity = Gravity.CENTER
            minHeight = gdp(46)
            textSize = 15f
            typeface = auroraFont(R.font.onest_600)
            setTextColor(auroraColor(R.color.au_on_accent))
            background = ContextCompat.getDrawable(context, R.drawable.bg_au_btn_accent)
        }
        check.setOnClickListener {
            AnimationHelper.bounceClick(check, minScale = 0.96f, durationMs = 140)
            checkUpdatesFromGlass()
        }
        content.addView(check, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = gdp(14) })

        val res = TextView(this).apply {
            textSize = 13.5f
            typeface = auroraFont(R.font.onest_400)
            setTextColor(auroraColor(R.color.au_fg_2))
            gravity = Gravity.CENTER
        }
        auroraCheckRes = res
        content.addView(res, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = gdp(6); minHeight = gdp(20) })

        // legal row
        val legal = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        fun legalBtn(textRes: Int, url: String) {
            val b = TextView(this).apply {
                setText(textRes)
                textSize = 13f
                setTextColor(auroraColor(R.color.au_accent_text))
                paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
                setPaddingRelative(gdp(7), gdp(3), gdp(7), gdp(3))
            }
            b.setOnClickListener { openExternalLink(url) }
            legal.addView(b)
        }
        legalBtn(R.string.legal_terms_of_service, TERMS_URL)
        legal.addView(TextView(this).apply {
            text = "  ·  "
            textSize = 13f
            setTextColor(auroraColor(R.color.au_fg_3))
        })
        legalBtn(R.string.legal_privacy_policy, PRIVACY_URL)
        content.addView(legal, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = gdp(8) })

        content.addView(
            View(this),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, gdp(120))
        )
        return screen
    }

    private fun auroraAddSourceRow(
        group: LinearLayout,
        source: String,
        iconRes: Int,
        title: String,
        badge: String,
        desc: String
    ): ViewGroup {
        if (group.childCount > 0) {
            group.addView(
                View(this).apply { setBackgroundColor(auroraColor(R.color.au_sep)) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, gdp(1))
            )
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minHeight = gdp(74)
            setPaddingRelative(gdp(16), gdp(14), gdp(16), gdp(14))
            background = auroraRipple()
        }
        val tile = auroraTileFrame()
        auroraTileChild(tile, iconRes)
        row.addView(tile)

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val titleLine = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleLine.addView(TextView(this).apply {
            text = title
            textSize = 15f
            typeface = auroraFont(R.font.onest_600)
            setTextColor(auroraColor(R.color.au_fg))
        })
        if (badge.isNotBlank()) {
            val recommended = badge == getString(R.string.badge_recommended)
            titleLine.addView(TextView(this).apply {
                text = badge
                textSize = 11f
                typeface = auroraFont(R.font.onest_600)
                letterSpacing = 0.04f
                setTextColor(
                    ContextCompat.getColor(
                        context,
                        if (recommended) R.color.au_accent_text else R.color.au_fg_2
                    )
                )
                background = ContextCompat.getDrawable(
                    context,
                    if (recommended) R.drawable.bg_au_badge_rec else R.drawable.bg_au_badge_mod
                )
                setPaddingRelative(gdp(8), gdp(2), gdp(8), gdp(2))
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = gdp(8) })
        }
        col.addView(titleLine)
        val descView = TextView(this).apply {
            this.text = desc
            textSize = 13f
            typeface = auroraFont(R.font.onest_400)
            setTextColor(auroraColor(R.color.au_fg_2))
            tag = "au_row_desc"
        }
        col.addView(descView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = gdp(2) })
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = gdp(14)
        })

        val radio = View(this).apply {
            background = auroraRadioBg(false)
            layoutParams = LinearLayout.LayoutParams(gdp(24), gdp(24))
            tag = "au_radio"
        }
        row.addView(radio)
        row.setOnClickListener {
            AnimationHelper.bounceClick(row, minScale = 0.98f, durationMs = 130)
            if (settingsRepository.updateSource != source) {
                settingsRepository.updateSource = source
                syncAuroraSourceRadios()
            }
        }
        group.addView(row, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        return row
    }

    private fun syncAuroraSourceRadios() {
        auroraSourceRows.forEach { (source, row) ->
            val radio = row.findViewWithTag<View>("au_radio") ?: return@forEach
            radio.background = auroraRadioBg(settingsRepository.updateSource == source)
        }
    }

    // ---------------- sheets + toast ----------------

    private fun showAuroraSheet(kind: String) {
        val container = binding.auroraShell.auSheetContainer
        val scrim = binding.auroraShell.auScrim
        container.removeAllViews()
        container.translationY = gdp(48).toFloat()
        container.alpha = 0f
        container.visibility = View.VISIBLE

        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(View(this).apply {
            background = GradientDrawable().apply {
                setColor(auroraColor(R.color.au_fg_3))
                cornerRadius = gdp(3).toFloat()
            }
        }, LinearLayout.LayoutParams(gdp(42), gdp(5)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        })

        if (kind == "donate") {
            content.addView(auroraSheetHead(
                getString(R.string.glass_donate_title),
                getString(R.string.glass_donate_sub),
                getString(R.string.support_ways_hint)
            ))
            auroraSheetItem(
                content,
                R.drawable.au_card, R.color.au_accent_text,
                getString(R.string.way_cloudtips_title), getString(R.string.way_cloudtips_desc),
                getString(R.string.cloudtips_url)
            )
            auroraSheetItem(
                content,
                R.drawable.au_star, R.color.au_accent_text,
                getString(R.string.way_tribute_tg_title), getString(R.string.way_tribute_tg_desc),
                getString(R.string.tribute_tg_url)
            )
            auroraSheetItem(
                content,
                R.drawable.ic_globe, R.color.au_accent_text,
                getString(R.string.way_tribute_web_title), getString(R.string.way_tribute_web_desc),
                getString(R.string.tribute_web_url)
            )
        } else {
            content.addView(auroraSheetHead(
                getString(R.string.glass_telegram_title),
                getString(R.string.au_tg_sub),
                getString(R.string.support_menu_hint)
            ))
            auroraSheetItem(
                content,
                R.drawable.au_send, R.color.au_cool,
                getString(R.string.way_channel_title), getString(R.string.way_channel_desc),
                getString(R.string.telegram_channel_url)
            )
            auroraSheetItem(
                content,
                R.drawable.au_message, R.color.au_cool,
                getString(R.string.way_support_title), getString(R.string.way_support_desc),
                getString(R.string.support_chat_url)
            )
            auroraSheetItem(
                content,
                R.drawable.au_bot, R.color.au_cool,
                getString(R.string.way_bot_title), getString(R.string.way_bot_desc),
                getString(R.string.support_bot_url)
            )
        }

        content.addView(
            View(this),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, gdp(8))
        )
        container.addView(content, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        scrim.isClickable = true
        scrim.visibility = View.VISIBLE
        scrim.setOnClickListener { dismissAuroraSheet() }
        scrim.animate().alpha(1f).setDuration(240L).start()
        container.animate().alpha(1f).translationY(0f).setDuration(320L)
            .setInterpolator(DecelerateInterpolator(1.1f)).start()
    }

    private fun auroraSheetHead(title: String, sub: String, intro: String): View {
        val head = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val textCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        textCol.addView(TextView(this).apply {
            text = title
            textSize = 19f
            typeface = auroraFont(R.font.unbounded_600)
            letterSpacing = -0.01f
            setTextColor(auroraColor(R.color.au_fg))
        })
        textCol.addView(TextView(this).apply {
            text = sub
            textSize = 13f
            typeface = auroraFont(R.font.onest_400)
            setTextColor(auroraColor(R.color.au_fg_2))
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = gdp(4) })
        top.addView(textCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val close = FrameLayout(this).apply {
            background = ContextCompat.getDrawable(context, R.drawable.bg_au_glass_circle)
        }
        close.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_close)
            imageTintList = ColorStateList.valueOf(auroraColor(R.color.au_fg_2))
            layoutParams = FrameLayout.LayoutParams(gdp(20), gdp(20), Gravity.CENTER)
        })
        close.setOnClickListener { dismissAuroraSheet() }
        top.addView(close, LinearLayout.LayoutParams(gdp(40), gdp(40)))

        head.addView(top, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = gdp(12) })
        head.addView(TextView(this).apply {
            text = intro
            textSize = 14f
            typeface = auroraFont(R.font.onest_400)
            setTextColor(auroraColor(R.color.au_fg_2))
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = gdp(10) })
        return head
    }

    private fun auroraSheetItem(
        content: LinearLayout,
        iconRes: Int,
        tint: Int,
        title: String,
        desc: String,
        url: String
    ) {
        val item = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minHeight = gdp(68)
            background = auroraGlassBg(24)
            clipToOutline = true
            setPaddingRelative(gdp(14), gdp(14), gdp(14), gdp(14))
        }
        val tile = auroraTileFrame()
        auroraTileChild(tile, iconRes, tint)
        item.addView(tile)

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(this).apply {
            text = title
            textSize = 15f
            typeface = auroraFont(R.font.onest_600)
            setTextColor(auroraColor(R.color.au_fg))
        })
        col.addView(TextView(this).apply {
            text = desc
            textSize = 13f
            typeface = auroraFont(R.font.onest_400)
            setTextColor(auroraColor(R.color.au_fg_2))
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = gdp(3) })
        item.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = gdp(14)
        })

        val go = TextView(this).apply {
            text = getString(R.string.action_go)
            textSize = 14f
            typeface = auroraFont(R.font.onest_600)
            setTextColor(auroraColor(R.color.au_accent_text))
            background = ContextCompat.getDrawable(context, R.drawable.bg_au_btn_ghost)
            setPaddingRelative(gdp(15), gdp(9), gdp(15), gdp(9))
        }
        go.setOnClickListener {
            AnimationHelper.bounceClick(go, minScale = 0.93f, durationMs = 130)
            dismissAuroraSheet()
            openExternalLink(url)
        }
        item.addView(go)
        content.addView(item, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = gdp(10) })
    }

    private fun dismissAuroraSheet() {
        val container = binding.auroraShell.auSheetContainer
        val scrim = binding.auroraShell.auScrim
        if (container.visibility != View.VISIBLE) return
        scrim.animate().alpha(0f).setDuration(200L).withEndAction {
            scrim.visibility = View.GONE
        }.start()
        container.animate().alpha(0f).translationY(gdp(48).toFloat()).setDuration(260L)
            .withEndAction {
                container.visibility = View.GONE
                container.removeAllViews()
            }.start()
    }

    /** true — close consumed the event (sheet or country menu was open) */
    private fun closeAuroraOverlay(): Boolean {
        if (settingsRepository.uiStyle != SettingsRepository.UI_STYLE_GLASS) return false
        val shell = binding.auroraShell
        var consumed = false
        if (shell.auSheetContainer.visibility == View.VISIBLE) {
            dismissAuroraSheet()
            consumed = true
        }
        if (shell.auCountryMenu.visibility == View.VISIBLE) {
            setAuroraCountryMenu(false)
            consumed = true
        }
        return consumed
    }

    private fun showAuroraToast(text: String) {
        val toast = binding.auroraShell.auToast
        toast.text = text
        auroraToastRunnable?.let { toast.removeCallbacks(it) }
        toast.animate().cancel()
        toast.alpha = 0f
        toast.translationY = gdp(16).toFloat()
        toast.scaleX = 0.9f
        toast.scaleY = 0.9f
        toast.visibility = View.VISIBLE
        toast.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).setDuration(260L).start()
        auroraToastRunnable = Runnable {
            toast.animate().alpha(0f).translationY(gdp(16).toFloat()).scaleX(0.9f).scaleY(0.9f)
                .setDuration(240L).withEndAction {
                    toast.visibility = View.GONE
                    toast.translationY = 0f
                    toast.scaleX = 1f
                    toast.scaleY = 1f
                }.start()
        }
        toast.postDelayed(auroraToastRunnable, 2200L)
    }

    // ---------------- small aurora building blocks ----------------

    private fun auroraHeader(title: String, eyebrow: String): View =
        LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }.also { head ->
            head.addView(TextView(this).apply {
                text = title
                textSize = 28f
                typeface = auroraFont(R.font.unbounded_600)
                letterSpacing = -0.015f
                setTextColor(auroraColor(R.color.au_fg))
            })
            head.addView(TextView(this).apply {
                text = eyebrow
                textSize = 13f
                typeface = auroraFont(R.font.onest_400)
                setTextColor(auroraColor(R.color.au_fg_2))
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = gdp(6) })
        }

    private fun extractPing(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val match = Regex("(\\d{1,4})\\s*(?:ms|мс)", RegexOption.IGNORE_CASE).find(raw) ?: return null
        return "${match.groupValues[1]} ms"
    }

    private fun gdp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun installUiStyleRow() {
        if (uiStyleRow != null) return
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            val out = android.util.TypedValue()
            if (theme.resolveAttribute(android.R.attr.selectableItemBackground, out, true)) {
                setBackgroundResource(out.resourceId)
            }
        }

        val icon = ImageView(this).apply {
            setImageResource(R.drawable.ic_glass)
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply {
                marginEnd = dp(14)
            }
            contentDescription = null
        }

        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val title = TextView(this).apply {
            setText(R.string.ui_style_title)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.ink))
            setTextSize(14f)
            setTypeface(typeface, Typeface.BOLD)
        }

        val desc = TextView(this).apply {
            setText(R.string.ui_style_desc)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.ink_soft))
            setTextSize(12f)
            setPadding(0, dp(3), 0, 0)
        }

        val styleSwitch = MirageSwitch(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(24)).apply {
                marginEnd = dp(10)
            }
            isChecked = settingsRepository.uiStyle == SettingsRepository.UI_STYLE_GLASS
            setLightMode(
                settingsRepository.uiStyle != SettingsRepository.UI_STYLE_GLASS &&
                    settingsRepository.themePreset == SettingsRepository.THEME_LIGHT
            )
            setOnCheckedChangeListener { _, checked ->
                applyUiStyle(if (checked) SettingsRepository.UI_STYLE_GLASS else SettingsRepository.UI_STYLE_CLASSIC)
            }
        }

        val chevron = ImageView(this).apply {
            setImageResource(R.drawable.ic_chevron_right)
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))
            contentDescription = null
        }

        texts.addView(title)
        texts.addView(desc)
        row.addView(icon)
        row.addView(texts)
        row.addView(styleSwitch)
        row.addView(chevron)
        row.setOnClickListener {
            AnimationHelper.bounceClick(row, minScale = 0.97f, durationMs = 180)
            showUiStyleDialog()
        }

        val index = binding.cardAppearance.indexOfChild(binding.rowAppearance) + 1
        binding.cardAppearance.addView(row, index)
        uiStyleRow = row
        uiStyleSwitch = styleSwitch
    }

    private fun updateUiStyleRow() {
        uiStyleSwitch?.isChecked = settingsRepository.uiStyle == SettingsRepository.UI_STYLE_GLASS
    }

    private fun applyUiStyle(style: String) {
        if (settingsRepository.uiStyle == style) return
        settingsRepository.uiStyle = style
        if (style == SettingsRepository.UI_STYLE_GLASS) {
            settingsRepository.themePreset = SettingsRepository.THEME_DARK
        }
        Toast.makeText(applicationContext, R.string.ui_style_applied, Toast.LENGTH_SHORT).show()
        recreate()
    }

    private fun showUiStyleDialog() {
        val dialog = Dialog(this)
        val dialogBinding =
            com.naua_security_mirage.app.databinding.DialogUiStyleBinding.inflate(layoutInflater)
        dialog.setContentView(dialogBinding.root)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.94f).toInt(),
            LinearLayout.LayoutParams.WRAP_CONTENT
        )

        fun renderSelection() {
            val glass = settingsRepository.uiStyle == SettingsRepository.UI_STYLE_GLASS
            dialogBinding.cardStyleClassic.setBackgroundResource(
                if (glass) R.drawable.bg_ui_style_option else R.drawable.bg_ui_style_option_selected
            )
            dialogBinding.cardStyleGlass.setBackgroundResource(
                if (glass) R.drawable.bg_ui_style_option_selected else R.drawable.bg_ui_style_option
            )
            val amber = ContextCompat.getColor(this, R.color.amber)
            val ink = ContextCompat.getColor(this, R.color.ink)
            dialogBinding.tvStyleClassicLabel.setTextColor(if (glass) ink else amber)
            dialogBinding.tvStyleGlassLabel.setTextColor(if (glass) amber else ink)
        }

        renderSelection()
        dialogBinding.btnCloseUiStyle.setOnClickListener { dialog.dismiss() }
        dialogBinding.cardStyleClassic.setOnClickListener {
            dialog.dismiss()
            applyUiStyle(SettingsRepository.UI_STYLE_CLASSIC)
        }
        dialogBinding.cardStyleGlass.setOnClickListener {
            dialog.dismiss()
            applyUiStyle(SettingsRepository.UI_STYLE_GLASS)
        }
        dialog.show()
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
        } else if (binding.auroraShell.root.visibility == View.VISIBLE) {
            when {
                closeAuroraOverlay() -> {}
                currentGlassTab != GLASS_TAB_HOME -> selectGlassTab(GLASS_TAB_HOME)
                else -> {
                    @Suppress("DEPRECATION")
                    super.onBackPressed()
                }
            }
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    private fun setupAppearanceUI() {
        installUiStyleRow()
        updateUiStyleRow()
        val isGlassStyle = settingsRepository.uiStyle == SettingsRepository.UI_STYLE_GLASS
        val themeChipAlpha = if (isGlassStyle) 0.45f else 1f
        binding.chipThemeDark.alpha = themeChipAlpha
        binding.chipThemeLight.alpha = themeChipAlpha

        binding.btnBackFromAppearance.setOnClickListener {
            AnimationHelper.bounceClick(binding.btnBackFromAppearance, minScale = 0.88f, durationMs = 180)
            closeAppearance()
        }

        updateThemeChipsState()
        binding.chipThemeDark.setOnClickListener {
            if (settingsRepository.uiStyle == SettingsRepository.UI_STYLE_GLASS) {
                Toast.makeText(this, R.string.ui_style_theme_note, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
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
            if (settingsRepository.uiStyle == SettingsRepository.UI_STYLE_GLASS) {
                Toast.makeText(this, R.string.ui_style_theme_note, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
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

        withLegacyStoragePermission(::performDownloadLogs)
    }

    private fun performDownloadLogs() {
        val level = settingsRepository.logLevel
        val timeStamp = java.text.SimpleDateFormat("dd-MM-yyyy_HH-mm-ss", java.util.Locale.getDefault()).format(java.util.Date())
        val fileName = "Log-NAUA-Security-Mirage-${timeStamp}-$level.txt"

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Логи собираем в фоне: файл журнала Xray бывает огромным, и
                // чтение на главном потоке вешало интерфейс и роняло приложение
                // с OutOfMemoryError.
                val content = AppLogger.getAllLogsFormatted() + "\n\n" +
                        com.naua_security_mirage.app.util.LogHelper.collectLogs(this@MainActivity).readText()
                ensureLogoExported()
                val savedPath = savePublicFile(
                    subDir = "logs",
                    fileName = fileName,
                    mimeType = "text/plain",
                    content = content.toByteArray(Charsets.UTF_8)
                )

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

    private fun savePublicFile(subDir: String, fileName: String, mimeType: String, content: ByteArray): String {
        val relativeDir = "${Environment.DIRECTORY_DOWNLOADS}/$PUBLIC_FOLDER/$subDir"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativeDir)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException(getString(R.string.log_mediastore_error))
            try {
                contentResolver.openOutputStream(uri)?.use { it.write(content) }
                    ?: throw IllegalStateException(getString(R.string.log_mediastore_error))
                contentResolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    null,
                    null
                )
            } catch (e: Throwable) {
                contentResolver.delete(uri, null, null)
                throw e
            }
            "Downloads/$PUBLIC_FOLDER/$subDir/$fileName"
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "$PUBLIC_FOLDER/$subDir")
            if (!dir.exists() && !dir.mkdirs()) throw IllegalStateException(getString(R.string.log_mediastore_error))
            File(dir, fileName).apply { writeBytes(content) }.absolutePath
        }
    }

    private fun publicFileExists(subDir: String, fileName: String): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?",
                arrayOf("${Environment.DIRECTORY_DOWNLOADS}/$PUBLIC_FOLDER/$subDir/", fileName),
                null
            )?.use { it.count > 0 } ?: false
        } else {
            @Suppress("DEPRECATION")
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "$PUBLIC_FOLDER/$subDir/$fileName").exists()
        }
    }

    private fun ensureLogoExported() {
        try {
            val fileName = "NAUA-Security-Mirage.png"
            if (publicFileExists("logo", fileName)) return
            val drawable = androidx.appcompat.content.res.AppCompatResources.getDrawable(this, R.drawable.ic_launcher) ?: return
            val size = 512
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            drawable.setBounds(0, 0, size, size)
            drawable.draw(android.graphics.Canvas(bitmap))
            val out = java.io.ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            savePublicFile("logo", fileName, "image/png", out.toByteArray())
        } catch (e: Throwable) {
            AppLogger.w("Storage", "Не удалось сохранить логотип: ${e.message}")
        }
    }

    private fun exportSettings() {
        withLegacyStoragePermission(::performExportSettings)
    }

    private fun performExportSettings() {
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
                    .put("version", 2)
                    .put("settings", json)
                val wallpaper = File(filesDir, "custom_wallpaper.jpg")
                if (wallpaper.exists() && wallpaper.length() in 1..MAX_BACKUP_WALLPAPER_BYTES) {
                    wrapper.put(
                        KEY_BACKUP_WALLPAPER,
                        android.util.Base64.encodeToString(
                            wallpaper.readBytes(),
                            android.util.Base64.NO_WRAP
                        )
                    )
                    AppLogger.i(
                        "Backup",
                        "В файл настроек включено фото обоев, ${wallpaper.length()} байт"
                    )
                } else if (wallpaper.exists()) {
                    AppLogger.w(
                        "Backup",
                        "Фото обоев крупнее лимита и не попало в файл: ${wallpaper.length()} байт"
                    )
                }
                val content = wrapper.toString(2)
                val timeStamp = java.text.SimpleDateFormat("dd-MM-yyyy_HH-mm-ss", java.util.Locale.getDefault())
                    .format(java.util.Date())
                val fileName = "Mirage-Settings-$timeStamp.json"
                ensureLogoExported()
                savePublicFile(
                    subDir = "backup",
                    fileName = fileName,
                    mimeType = "application/json",
                    content = content.toByteArray(Charsets.UTF_8)
                )
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

    private fun restoreWallpaperFromBackup(
        base64: String,
        editor: android.content.SharedPreferences.Editor
    ): Boolean {
        if (base64.isBlank()) {
            editor.remove(SettingsRepository.KEY_CUSTOM_BG_IMAGE)
            return false
        }
        return try {
            val image = android.util.Base64.decode(
                base64,
                android.util.Base64.DEFAULT
            )
            val destFile = File(filesDir, "custom_wallpaper.jpg")
            FileOutputStream(destFile).use { it.write(image) }
            editor.putString(SettingsRepository.KEY_CUSTOM_BG_IMAGE, destFile.absolutePath)
            AppLogger.i("Backup", "Фото обоев из файла настроек сохранено, ${image.size} байт")
            true
        } catch (e: Throwable) {
            AppLogger.w("Backup", "Фото обоев из файла настроек не читается: ${e.message}")
            editor.remove(SettingsRepository.KEY_CUSTOM_BG_IMAGE)
            false
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
                val wrapper = org.json.JSONObject(text)
                val settings = wrapper.optJSONObject("settings")
                    ?: throw IllegalStateException("no settings")
                val wallpaperBase64 = wrapper.optString(KEY_BACKUP_WALLPAPER, "")
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
                val wallpaperRestored = restoreWallpaperFromBackup(wallpaperBase64, editor)
                val wallpaperDropped = !wallpaperRestored &&
                        settings.has(SettingsRepository.KEY_CUSTOM_BG_IMAGE)
                editor.apply()
                AppLogger.i(
                    "Backup",
                    "Настройки загружены: $count записей, фото восстановлено=$wallpaperRestored, " +
                            "фото отброшено=$wallpaperDropped"
                )
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        if (wallpaperRestored) R.string.backup_restored_with_wallpaper
                        else R.string.backup_restored,
                        Toast.LENGTH_LONG
                    ).show()
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

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Сбор логов уводим в фон: журнал Xray бывает огромным, и его
                // чтение на главном потоке вешало интерфейс и роняло приложение.
                val content = AppLogger.getAllLogsFormatted() + "\n\n" +
                        com.naua_security_mirage.app.util.LogHelper.collectLogs(this@MainActivity).readText()
                if (content.isBlank() || AppLogger.getAllLogs().isEmpty()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, R.string.toast_logs_empty, Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }
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
        AnimationHelper.excludeFromPress(holder.switch)
        AnimationHelper.attachPressAnimations(row)
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
            R.string.setting_screenshots_allowed,
            anchor = binding.rowSystemVpn
        ).also { holder ->
            var syncing = false
            fun render() {
                val blocking = settingsRepository.isScreenshotBlockingEnabled
                syncing = true
                holder.switch.isChecked = !blocking
                syncing = false
                holder.desc.setText(
                    if (blocking) R.string.setting_screenshots_desc
                    else R.string.setting_screenshots_allowed
                )
            }
            render()
            holder.switch.setOnCheckedChangeListener { _, allowed ->
                if (syncing) return@setOnCheckedChangeListener
                if (allowed) {
                    showScreenshotWarningDialog(onAllow = {
                        settingsRepository.isScreenshotBlockingEnabled = false
                        applyScreenshotBlocking()
                        render()
                        Toast.makeText(
                            this,
                            R.string.screenshot_warning_allowed_toast,
                            Toast.LENGTH_LONG
                        ).show()
                    }, onKeep = {
                        settingsRepository.isScreenshotBlockingEnabled = true
                        applyScreenshotBlocking()
                        render()
                    })
                    render()
                } else {
                    settingsRepository.isScreenshotBlockingEnabled = true
                    applyScreenshotBlocking()
                    render()
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
            SectionSub("sub_dns", R.string.private_dns_title),
            SectionSub("sub_tunnel_dns", R.string.tunnel_dns_title)
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
        "sub_dns" -> listOfNotNull(privateDnsCard?.findViewById(R.id.rowPrivateDns))
        "sub_tunnel_dns" -> listOfNotNull(privateDnsCard?.findViewById(R.id.rowTunnelDns))
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
        val blocking = settingsRepository.isScreenshotBlockingEnabled
        if (blocking) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                setRecentsScreenshotEnabled(false)
            }
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                setRecentsScreenshotEnabled(true)
            }
        }
        AppLogger.i(
            "Screenshot",
            "Блокировка скриншотов: ${if (blocking) "включена" else "выключена"}, " +
                "FLAG_SECURE=${window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0}"
        )
    }

    private fun styleDialogAction(
        dialog: Dialog,
        id: Int,
        textColor: Int,
        accent: Boolean,
        light: Boolean,
        density: Float
    ) {
        val button = dialog.findViewById<TextView>(id)
        button.setTextColor(textColor)
        button.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 14f * density
            setColor(Color.TRANSPARENT)
            setStroke(
                (1.5f * density).toInt(),
                if (accent) ContextCompat.getColor(this@MainActivity, R.color.accent)
                else if (light) Color.parseColor("#CBD5E1")
                else Color.parseColor("#4DFFFFFF")
            )
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

        styleDialogAction(
            dialog,
            R.id.btnScreenshotKeepBlocked,
            bodyColor,
            accent = false,
            light = isLightContext,
            density = density
        )
        styleDialogAction(
            dialog,
            R.id.btnScreenshotAllow,
            ContextCompat.getColor(this, R.color.accent),
            accent = true,
            light = isLightContext,
            density = density
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
        val scroll = dialog.findViewById<ScrollView>(R.id.scrollSessionHistory)
        if (scroll != null) {
            lockDialogList(dialog, root, scroll)
        }
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
        styleDialogAction(
            dialog,
            R.id.btnConsentDecline,
            bodyColor,
            accent = false,
            light = isLightContext,
            density = density
        )
        styleDialogAction(
            dialog,
            R.id.btnConsentAccept,
            accentColor,
            accent = true,
            light = isLightContext,
            density = density
        )

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


    /**
     * Меню поддержки.
     *
     * [showDonations] = true — окно «Поблагодарить разработчика»: только способы
     * доната. [showDonations] = false — окно кнопки Telegram: канал, поддержка и
     * бот. Оба окна живут в одном макете, лишние строки просто скрываются.
     */
    private fun showSupportWaysDialog(showDonations: Boolean = true) {
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

        dialog.findViewById<TextView>(R.id.tvSupportWaysTitle).apply {
            setText(if (showDonations) R.string.support_ways_title else R.string.support_menu_title)
            setTextColor(titleTextColor)
        }
        dialog.findViewById<TextView>(R.id.tvSupportWaysSubtitle).apply {
            setText(if (showDonations) R.string.support_ways_subtitle else R.string.support_menu_subtitle)
            setTextColor(subtitleTextColor)
        }
        dialog.findViewById<TextView>(R.id.tvSupportWaysHint).apply {
            setText(if (showDonations) R.string.support_ways_hint else R.string.support_menu_hint)
            setTextColor(subtitleTextColor)
        }
        dialog.findViewById<TextView>(R.id.tvSupportWaysHint).background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 12f * density
            setColor(cardBgColor)
            setStroke((1f * density).toInt(), strokeColor)
        }
        dialog.findViewById<ImageView>(R.id.ivSupportWaysClose).imageTintList =
            ColorStateList.valueOf(subtitleTextColor)

        val donationRows = listOf(
            Triple(R.id.rowWayCloudtips, R.id.tvWayCloudtipsTitle, R.id.tvWayCloudtipsDesc),
            Triple(R.id.rowWayTributeTelegram, R.id.tvWayTributeTelegramTitle, R.id.tvWayTributeTelegramDesc),
            Triple(R.id.rowWayTributeWeb, R.id.tvWayTributeWebTitle, R.id.tvWayTributeWebDesc),
        )
        val contactRows = listOf(
            Triple(R.id.rowWayChannel, R.id.tvWayChannelTitle, R.id.tvWayChannelDesc),
            Triple(R.id.rowWaySupport, R.id.tvWaySupportTitle, R.id.tvWaySupportDesc),
            Triple(R.id.rowWayBot, R.id.tvWayBotTitle, R.id.tvWayBotDesc),
        )
        val rows = donationRows + contactRows

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
        listOf(
            R.id.tvWayCloudtipsGo,
            R.id.tvWayTributeTelegramGo,
            R.id.tvWayTributeWebGo,
            R.id.tvWayChannelGo,
            R.id.tvWaySupportGo,
            R.id.tvWayBotGo
        ).forEach {
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
        open(R.id.rowWayChannel, getString(R.string.telegram_channel_url))
        open(R.id.rowWaySupport, getString(R.string.support_chat_url))
        open(R.id.rowWayBot, getString(R.string.support_bot_url))

        // В окне донатов показываем только способы поддержать разработчика,
        // в окне Telegram — только канал, поддержку и бота.
        val hidden = if (showDonations) contactRows else donationRows
        hidden.forEach { (rowId, _, _) ->
            dialog.findViewById<View>(rowId).visibility = View.GONE
        }

        dialog.findViewById<View>(R.id.ivSupportWaysClose).setOnClickListener { dialog.dismiss() }

        dialog.show()
        AnimationHelper.popIn(root, durationMs = 240)
        AnimationHelper.attachPressAnimations(root)
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
        val isGlass = settingsRepository.uiStyle == SettingsRepository.UI_STYLE_GLASS
        if (isGlass && settingsRepository.themePreset != SettingsRepository.THEME_DARK) {
            settingsRepository.themePreset = SettingsRepository.THEME_DARK
        }
        val preset = settingsRepository.themePreset
        val isLightPreset = preset == SettingsRepository.THEME_LIGHT
        val customBg = if (isGlass) 0 else settingsRepository.customBgColor
        val customBgPath = settingsRepository.customBgImagePath
        val customConnect = if (isGlass) 0 else settingsRepository.customConnectBtnColor
        val customAction = if (isGlass) 0 else settingsRepository.customActionBtnColor
        val customText = if (isGlass) 0 else settingsRepository.customTextColor
        val customSwitch = if (isGlass) 0 else settingsRepository.customSwitchColor

        val hasWallpaper = !customBgPath.isNullOrEmpty() && File(customBgPath).exists()
        val isLightContext: Boolean

        if (hasWallpaper) {
            val bitmap = loadWallpaperBitmap(customBgPath)
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

            if (isGlass) {
                binding.rootContainer.setBackgroundColor(Color.parseColor("#06070B"))
                isLightContext = false
            } else {
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
            binding.btnConnect.background = if (isGlass) {
                ContextCompat.getDrawable(this, R.drawable.bg_connect_btn_glass)
            } else {
                connectBg
            }
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
            it.setTextColor(finalActionColor)
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

        styleInjectedRows(isLightContext, titleTextColor, subtitleTextColor, finalActionColor, sectionHeaderColor)

        binding.hintCard.background = getCardDrawable(isLightContext, 18f)
        binding.tvHint.setTextColor(subtitleTextColor)
        binding.ivHintIcon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.accent))

        binding.tvSettingsTitle.setTextColor(titleTextColor)
        binding.cardAccount.root.background = getCardDrawable(isLightContext, 18f)
        binding.cardAccount.tvAccountPrompt.setTextColor(subtitleTextColor)
        binding.cardAccount.tvAccountLoggedOutTitle.setTextColor(titleTextColor)
        binding.cardAccount.ivAccountLoggedOutIcon.imageTintList = ColorStateList.valueOf(finalActionColor)
        binding.cardAccount.ivAccountAvatar.imageTintList = ColorStateList.valueOf(finalActionColor)
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

        binding.auroraShell.root.visibility = if (isGlass) View.VISIBLE else View.GONE
        binding.viewMain.visibility = if (isGlass) View.INVISIBLE else View.VISIBLE
        if (isGlass) {
            selectGlassTab(currentGlassTab, animate = false)
            syncGlassShell()
        }
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
                val saved = shrinkWallpaper(uri, destFile)
                withContext(Dispatchers.Main) {
                    settingsRepository.customBgColor = 0
                    settingsRepository.customBgImagePath = if (saved) destFile.absolutePath else ""
                    if (saved) settingsRepository.themePreset = SettingsRepository.THEME_CUSTOM
                    applyCurrentAppearance()
                    setupAppearanceUI()
                    Toast.makeText(
                        this@MainActivity,
                        if (saved) R.string.toast_wallpaper_set else R.string.toast_wallpaper_failed,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, R.string.toast_wallpaper_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun shrinkWallpaper(uri: Uri, destFile: File): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val opened = contentResolver.openInputStream(uri) ?: return false
        opened.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
        val width = resources.displayMetrics.widthPixels.coerceAtLeast(1080)
        val height = resources.displayMetrics.heightPixels.coerceAtLeast(1920)
        val sample = calculateInSampleSize(bounds, width, height)
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: return false
        return try {
            FileOutputStream(destFile).use { output ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, output)
            }
            AppLogger.i(
                "Wallpaper",
                "Обои сохранены ${destFile.length()} байт, исходник ${bounds.outWidth}x${bounds.outHeight}, " +
                        "sample=$sample"
            )
            true
        } finally {
            bitmap.recycle()
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
        private const val PUBLIC_FOLDER = "NAUA Security Mirage"
        const val ACTION_QUICK_CONNECT = "com.naua_security_mirage.app.ACTION_QUICK_CONNECT"
        const val EXTRA_QUICK_CONNECT_TOKEN = "quick_connect_token"
        private const val STATUS_STALE_MS = 10 * 60 * 1000L
        private const val MAX_IMPORT_BYTES = 4 * 1024 * 1024
        private const val MAX_BACKUP_WALLPAPER_BYTES = 900 * 1024
        private const val KEY_BACKUP_WALLPAPER = "wallpaper_base64"
        private const val LOG_FLUSH_MS = 250L
        private const val GLASS_TAB_HOME = 0
        private const val GLASS_TAB_SETTINGS = 1
        private const val GLASS_TAB_FAQ = 2
        private const val GLASS_TAB_VERSION = 3
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
