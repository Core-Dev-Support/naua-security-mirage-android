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
import com.naua_security_mirage.app.util.AppLogger
import com.naua_security_mirage.app.util.AppShield
import com.naua_security_mirage.app.util.AnimationHelper
import com.naua_security_mirage.app.util.RuStoreUpdateHelper
import com.naua_security_mirage.app.util.AppUpdateManager
import android.widget.RadioButton
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.View
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
        // Proceed regardless of result
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
        AppUpdateManager.addUpdateListener { hasUpdate, _ ->
            runOnUiThread {
                binding.containerUpdateBadge.visibility = if (hasUpdate) View.VISIBLE else View.GONE
            }
        }
        AppUpdateManager.checkForUpdates(this, settingsRepository, isManual = false)
        AppUpdateManager.showWhatsNewDialog(this, settingsRepository)

        if (intent?.action == ACTION_QUICK_CONNECT) {
            handleConnectButtonClick()
        }
        handleAuthDeepLink(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent?.action == ACTION_QUICK_CONNECT) {
            handleConnectButtonClick()
        }
        handleAuthDeepLink(intent)
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

    private fun handlePaymentReturn() {
        val user = com.naua_security_mirage.app.data.supabase.SupabaseManager.instance.currentUser.value
        if (user == null) {
            return
        }
        lifecycleScope.launch {
            com.naua_security_mirage.app.data.supabase.SupabaseManager.instance.refreshSubscription()
            val success = com.naua_security_mirage.app.data.supabase.SupabaseManager.instance.hasActiveSubscription()
            if (success) {
                isWaitingForPayment = false
                settingsRepository.selectedServerPlan = SettingsRepository.PLAN_PREMIUM_FRANCE
                updateServerPlanSelectorUI()
                updateAccountCardUI()
                Toast.makeText(this@MainActivity, "Подписка активна! Выбран сервер во Франции 🇫🇷", Toast.LENGTH_LONG).show()
            }
        }
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

    private fun setupUI() {
        // Formatted hint card text with bold part preserving exact spacing
        binding.tvHint.text = androidx.core.text.HtmlCompat.fromHtml(
            getString(R.string.hint_text),
            androidx.core.text.HtmlCompat.FROM_HTML_MODE_LEGACY
        )

        // App Updates button (sources or update dialog)
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

        // Refresh servers & ping without connecting
        binding.btnRefresh.setOnClickListener {
            AnimationHelper.bounceClick(binding.btnRefresh, minScale = 0.88f, durationMs = 180)
            performRefresh()
        }

        // Main Connect Button click
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

        // Support Project button -> opens Cloudtips
        binding.btnSupport.setOnClickListener {
            AnimationHelper.bounceClick(binding.btnSupport, minScale = 0.93f, durationMs = 180)
            val url = getString(R.string.cloudtips_url)
            try {
                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                startActivity(browserIntent)
            } catch (e: Exception) {
                Toast.makeText(this, "Не удалось открыть браузер", Toast.LENGTH_SHORT).show()
            }
        }

        // Settings navigation
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
                showDocumentReaderDialog(isPrivacyPolicy = false)
            }
        }
        binding.btnPrivacyPolicy.setOnClickListener {
            AnimationHelper.bounceClick(binding.btnPrivacyPolicy, minScale = 0.94f, durationMs = 140) {
                showDocumentReaderDialog(isPrivacyPolicy = true)
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
                handleServerPlanChanged(isFrance = false)
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
                            handleServerPlanChanged(isFrance = true)
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
                handleServerPlanChanged(isFrance = true)
            }
        }
    }

    private fun animateServerPlanSwitch(isFrance: Boolean) {
        val activeView = if (isFrance) binding.containerServerPlan.btnPlanFrance else binding.containerServerPlan.btnPlanFree
        val inactiveView = if (isFrance) binding.containerServerPlan.btnPlanFree else binding.containerServerPlan.btnPlanFrance

        // Update backgrounds and text colors immediately
        activeView.background = ContextCompat.getDrawable(this, R.drawable.bg_pill_active)
        activeView.setTextColor(Color.WHITE)
        inactiveView.background = ColorDrawable(Color.TRANSPARENT)
        inactiveView.setTextColor(ContextCompat.getColor(this, R.color.ink_soft))

        // Direct hardware vsync frame animation (runs on Choreographer, works even if system animations are disabled)
        // 1. Spring overshoot scale & alpha on newly selected tab
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

        // 2. Smooth settle on inactive tab
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

        // 3. Subtle elastic container nudge
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

    private fun handleServerPlanChanged(isFrance: Boolean) {
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
                    handleServerPlanChanged(isFrance = false)
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
                        handleServerPlanChanged(isFrance = false)
                    }
                }
            }
        }
        lifecycleScope.launch {
            com.naua_security_mirage.app.data.supabase.SupabaseManager.instance.subscription.collect { sub ->
                updateAccountCardUI()
                if (sub != null) {
                    val hasSub = com.naua_security_mirage.app.data.supabase.SupabaseManager.instance.hasActiveSubscription()
                    if (!hasSub && settingsRepository.selectedServerPlan == SettingsRepository.PLAN_PREMIUM_FRANCE) {
                        settingsRepository.selectedServerPlan = SettingsRepository.PLAN_FREE
                        updateServerPlanSelectorUI()
                        if (MirageVpnService.vpnState.value == VpnState.CONNECTED) {
                            handleServerPlanChanged(isFrance = false)
                        }
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
                val untilStr = sub?.paidUntil?.take(10) ?: ""
                val text = if (untilStr.isNotEmpty()) "Премиум активен (до $untilStr)" else "Премиум активен"
                binding.cardAccount.tvAccountSubscriptionStatus.text = text
                binding.cardAccount.tvAccountSubscriptionStatus.setTextColor(Color.parseColor("#10B981"))
                binding.cardAccount.btnAccountSubscribe.visibility = View.GONE
            } else {
                binding.cardAccount.tvAccountSubscriptionStatus.text = "Базовый план (Бесплатный)"
                binding.cardAccount.tvAccountSubscriptionStatus.setTextColor(Color.parseColor("#F59E0B"))
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

        dialogBinding.btnSubPay.setOnClickListener {
            isWaitingForPayment = true
            val opened = com.naua_security_mirage.app.data.supabase.PaymentManager.openPaymentBrowser(this, user.id)
            if (!opened) {
                isWaitingForPayment = false
                Toast.makeText(this, "Не удалось открыть браузер для оплаты", Toast.LENGTH_SHORT).show()
            }
        }

        dialogBinding.btnSubCheck.setOnClickListener {
            dialogBinding.btnSubCheck.isEnabled = false
            lifecycleScope.launch {
                com.naua_security_mirage.app.data.supabase.SupabaseManager.instance.refreshSubscription()
                dialogBinding.btnSubCheck.isEnabled = true
                if (com.naua_security_mirage.app.data.supabase.SupabaseManager.instance.hasActiveSubscription()) {
                    isWaitingForPayment = false
                    settingsRepository.selectedServerPlan = SettingsRepository.PLAN_PREMIUM_FRANCE
                    Toast.makeText(this@MainActivity, "Подписка активна! Доступ к Франции открыт.", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                    updateAccountCardUI()
                    updateServerPlanSelectorUI()
                } else {
                    Toast.makeText(this@MainActivity, "Оплата ещё обрабатывается. Попробуйте через пару минут.", Toast.LENGTH_LONG).show()
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

                // Celebratory pop!
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

        // Continuous direct rotation that works even if system animations are disabled in Developer/Accessibility settings
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
    }

    private fun closeSettings() {
        val slideDist = 42f * resources.displayMetrics.density
        AnimationHelper.fadeAndSlideOut(binding.viewSettings, toX = slideDist, durationMs = 210)
        AnimationHelper.fadeAndSlideIn(binding.viewMain, fromX = -slideDist, durationMs = 230)
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

        // Theme Presets
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

        // Gallery Photo
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

        // Color Palettes
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

        // Reset Appearance
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
        val fileName = "Log-NAUA-Security-Mirage-$level.txt"
        val content = AppLogger.getAllLogsFormatted() + "\n\n" + com.naua_security_mirage.app.util.LogHelper.collectLogs(this).readText()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val savedPath = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                        put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    }
                    val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    if (uri != null) {
                        contentResolver.openOutputStream(uri)?.use { os ->
                            os.write(content.toByteArray(Charsets.UTF_8))
                        }
                        "Downloads/$fileName"
                    } else {
                        throw Exception("Не удалось создать запись в MediaStore")
                    }
                } else {
                    val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
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
        val content = AppLogger.getAllLogsFormatted()
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

        // Custom Palette Button
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

        // Dialog card background styling
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

        // Text colors
        val primaryText = if (isLightContext) Color.parseColor("#0F172A") else ContextCompat.getColor(this, R.color.ink)
        val secondaryText = if (isLightContext) Color.parseColor("#64748B") else ContextCompat.getColor(this, R.color.ink_soft)

        tvTitle.setTextColor(primaryText)
        tvHexLabel.setTextColor(secondaryText)
        tvHueLabel.setTextColor(secondaryText)
        tvSatLabel.setTextColor(secondaryText)
        tvValLabel.setTextColor(secondaryText)
        tvQuickLabel.setTextColor(secondaryText)
        btnCancel.setTextColor(secondaryText)

        // Hex Input styling
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

        // Apply button styling
        val applyBtnBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 14f * density
            val accentColor = if (settingsRepository.customConnectBtnColor != 0) settingsRepository.customConnectBtnColor else Color.parseColor("#E8A33D")
            setColor(accentColor)
        }
        btnApply.background = applyBtnBg
        btnApply.setTextColor(Color.WHITE)

        // Thumb generator
        fun createThumb(): GradientDrawable {
            val thumbSize = (20 * density).toInt()
            return GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setSize(thumbSize, thumbSize)
                setColor(Color.WHITE)
                setStroke((2.5f * density).toInt(), Color.parseColor("#333333"))
            }
        }

        // HSV state
        val hsv = FloatArray(3)
        Color.colorToHSV(initialColor, hsv)

        // Hue track
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

            // Preview
            val previewDrawable = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 14f * density
                setColor(currentColor)
                val strokeColor = if (ColorUtils.calculateLuminance(currentColor) > 0.6) Color.parseColor("#33000000") else Color.parseColor("#33FFFFFF")
                setStroke((1.5f * density).toInt(), strokeColor)
            }
            vPreview.background = previewDrawable

            // Hex code
            val hexStr = String.format("#%06X", (0xFFFFFF and currentColor))
            if (etHexCode.text.toString() != hexStr) {
                isUpdating = true
                etHexCode.setText(hexStr)
                etHexCode.setSelection(hexStr.length)
                isUpdating = false
            }

            // Saturation track: from gray/white at current V to pure hue at current V
            val pureColorAtV = Color.HSVToColor(floatArrayOf(hsv[0], 1f, hsv[2]))
            val desatColorAtV = Color.HSVToColor(floatArrayOf(hsv[0], 0f, hsv[2]))
            sbSat.progressDrawable = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(desatColorAtV, pureColorAtV)).apply {
                cornerRadius = 6f * density
            }

            // Brightness track: from black to color at full brightness with current H and S
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

        // Quick Swatches
        val quickColors = listOf(
            0xFFEF4444.toInt(), // Red
            0xFFF97316.toInt(), // Orange
            0xFFE8A33D.toInt(), // Amber
            0xFF10B981.toInt(), // Emerald
            0xFF06B6D4.toInt(), // Cyan
            0xFF3B82F6.toInt(), // Blue
            0xFF8B5CF6.toInt(), // Purple
            0xFFEC4899.toInt(), // Pink
            0xFFFFFFFF.toInt(), // White
            0xFF94A3B8.toInt(), // Slate
            0xFF0F172A.toInt(), // Navy Dark
            0xFF000000.toInt()  // Black
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

    private fun showDocumentReaderDialog(isPrivacyPolicy: Boolean) {
        val dialog = Dialog(this)
        dialog.setContentView(R.layout.dialog_document_reader)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.94f).toInt(),
            (resources.displayMetrics.heightPixels * 0.85f).toInt()
        )

        val density = resources.displayMetrics.density
        val hasWallpaper = !settingsRepository.customBgImagePath.isNullOrEmpty() && File(settingsRepository.customBgImagePath ?: "").exists()
        val isLightContext = !hasWallpaper && (settingsRepository.themePreset == SettingsRepository.THEME_LIGHT || (settingsRepository.customBgColor != 0 && ColorUtils.calculateLuminance(settingsRepository.customBgColor) > 0.5))

        val root = dialog.findViewById<LinearLayout>(R.id.dialogReaderRoot)
        val tvTitle = dialog.findViewById<TextView>(R.id.tvReaderTitle)
        val tvMeta = dialog.findViewById<TextView>(R.id.tvReaderMeta)
        val ivClose = dialog.findViewById<ImageView>(R.id.ivReaderClose)
        val vDivider = dialog.findViewById<View>(R.id.vReaderDivider)
        val tvContent = dialog.findViewById<TextView>(R.id.tvReaderContent)
        val cardAuthor = dialog.findViewById<LinearLayout>(R.id.cardReaderAuthor)
        val tvAuthorLabel = dialog.findViewById<TextView>(R.id.tvReaderAuthorLabel)
        val tvAuthorName = dialog.findViewById<TextView>(R.id.tvReaderAuthorName)
        val tvAuthorEmail = dialog.findViewById<TextView>(R.id.tvReaderAuthorEmail)
        val btnDone = dialog.findViewById<TextView>(R.id.btnReaderDone)

        val customAction = settingsRepository.customActionBtnColor
        val customText = settingsRepository.customTextColor

        val cardBgColor = if (isLightContext) Color.WHITE else ContextCompat.getColor(this, R.color.card)
        val strokeColor = if (isLightContext) Color.parseColor("#E2E8F0") else ContextCompat.getColor(this, R.color.card_stroke)
        val titleTextColor = if (customText != 0) customText else (if (isLightContext) Color.parseColor("#0F172A") else Color.parseColor("#F8FAFC"))
        val subtitleTextColor = if (customText != 0) ColorUtils.setAlphaComponent(customText, 190) else (if (isLightContext) Color.parseColor("#475569") else Color.parseColor("#94A3B8"))
        val authorBgColor = if (isLightContext) Color.parseColor("#F8FAFC") else Color.parseColor("#181B22")
        val accentColor = if (customAction != 0) customAction else (if (isLightContext) Color.parseColor("#B45309") else Color.parseColor("#F59E0B"))

        val closeIconColor = if (customAction != 0) {
            customAction
        } else if (customText != 0) {
            customText
        } else {
            subtitleTextColor
        }

        root.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 24f * density
            setColor(cardBgColor)
            setStroke((1.2f * density).toInt(), strokeColor)
        }

        vDivider.setBackgroundColor(strokeColor)
        tvTitle.setTextColor(titleTextColor)
        tvMeta.setTextColor(subtitleTextColor)
        ivClose.imageTintList = ColorStateList.valueOf(closeIconColor)
        tvContent.setTextColor(titleTextColor)

        cardAuthor.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 14f * density
            setColor(authorBgColor)
            setStroke((1f * density).toInt(), strokeColor)
        }
        tvAuthorLabel.setTextColor(subtitleTextColor)
        tvAuthorName.setTextColor(titleTextColor)
        tvAuthorEmail.setTextColor(accentColor)

        btnDone.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 14f * density
            setColor(if (isLightContext) Color.parseColor("#F1F5F9") else Color.parseColor("#222734"))
            setStroke((1f * density).toInt(), strokeColor)
        }
        btnDone.setTextColor(titleTextColor)

        if (isPrivacyPolicy) {
            tvTitle.text = "Политика конфиденциальности"
            tvMeta.text = "Core Dev Support • 17 сентября 2026 г."
            tvContent.text = HtmlCompat.fromHtml(getPrivacyPolicyHtml(), HtmlCompat.FROM_HTML_MODE_LEGACY)
        } else {
            tvTitle.text = "Лицензионное соглашение"
            tvMeta.text = "Core Dev Support • 17 сентября 2026 г."
            tvContent.text = HtmlCompat.fromHtml(getTermsOfServiceHtml(), HtmlCompat.FROM_HTML_MODE_LEGACY)
        }

        ivClose.setOnClickListener {
            AnimationHelper.bounceClick(ivClose, minScale = 0.88f, durationMs = 120) {
                dialog.dismiss()
            }
        }

        btnDone.setOnClickListener {
            AnimationHelper.bounceClick(btnDone, minScale = 0.92f, durationMs = 150) {
                dialog.dismiss()
            }
        }

        tvAuthorEmail.setOnClickListener {
            AnimationHelper.bounceClick(tvAuthorEmail, minScale = 0.96f, durationMs = 120) {
                try {
                    val emailIntent = Intent(Intent.ACTION_SENDTO).apply {
                        data = Uri.parse("mailto:coredevsupport@gmail.com")
                        putExtra(Intent.EXTRA_SUBJECT, "NAUA Security Mirage Support")
                    }
                    startActivity(emailIntent)
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "coredevsupport@gmail.com", Toast.LENGTH_SHORT).show()
                }
            }
        }

        val tvWebLink = dialog.findViewById<TextView>(R.id.tvReaderWebLink)
        tvWebLink.setTextColor(accentColor)
        tvWebLink.visibility = View.VISIBLE
        tvWebLink.text = "Открыть оригинал в Google Docs ↗"

        val docUrl = if (isPrivacyPolicy) {
            "https://docs.google.com/document/d/1FrmDpGS3sC_kQYyv1feNO2G2XMQr_ZV4_GAS-Qm4y1I/edit?usp=sharing"
        } else {
            "https://docs.google.com/document/d/1Q0_MpGF5D1GGoFu2HcdTle0kvLb3S7L52x2XIc47eLw/edit?usp=sharing"
        }

        tvWebLink.setOnClickListener {
            AnimationHelper.bounceClick(tvWebLink, minScale = 0.96f, durationMs = 120) {
                try {
                    val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(docUrl))
                    startActivity(browserIntent)
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, docUrl, Toast.LENGTH_SHORT).show()
                }
            }
        }

        dialog.show()
        AnimationHelper.popIn(root, durationMs = 240)
    }

    private fun updateUpdateSourceUI() {
        val descText = when (settingsRepository.updateSource) {
            SettingsRepository.UPDATE_SOURCE_GITHUB -> getString(R.string.setting_updates_source_github)
            SettingsRepository.UPDATE_SOURCE_RUSTORE -> getString(R.string.setting_updates_source_rustore)
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

        val rowRuStore = dialog.findViewById<LinearLayout>(R.id.rowSourceRuStore)
        val tvRuStoreTitle = dialog.findViewById<TextView>(R.id.tvSourceRuStoreTitle)
        val tvRuStoreDesc = dialog.findViewById<TextView>(R.id.tvSourceRuStoreDesc)
        val badgeRuStore = dialog.findViewById<TextView>(R.id.badgeSourceRuStore)
        val rbRuStore = dialog.findViewById<RadioButton>(R.id.rbSourceRuStore)
        val ivRuStore = dialog.findViewById<ImageView>(R.id.ivSourceRuStoreIcon)

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
        tvRuStoreTitle.setTextColor(titleTextColor)
        tvRuStoreDesc.setTextColor(subtitleTextColor)
        tvUptodownTitle.setTextColor(titleTextColor)
        tvUptodownDesc.setTextColor(subtitleTextColor)

        ivGithub.imageTintList = ColorStateList.valueOf(accentColor)
        ivRuStore.imageTintList = ColorStateList.valueOf(subtitleTextColor)
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
        badgeRuStore.background = badgeModBg
        badgeRuStore.setTextColor(Color.parseColor("#94A3B8"))
        badgeUptodown.background = badgeModBg
        badgeUptodown.setTextColor(Color.parseColor("#94A3B8"))

        rbGithub.buttonTintList = ColorStateList.valueOf(accentColor)
        rbRuStore.buttonTintList = ColorStateList.valueOf(accentColor)
        rbUptodown.buttonTintList = ColorStateList.valueOf(accentColor)

        rowGithub.background = getCardDrawable(isLightContext, 14f)
        rowRuStore.background = getCardDrawable(isLightContext, 14f)
        rowUptodown.background = getCardDrawable(isLightContext, 14f)

        val btnCheckNow = dialog.findViewById<LinearLayout>(R.id.btnDialogCheckUpdatesNow)
        val ivSpinner = dialog.findViewById<ImageView>(R.id.ivDialogCheckUpdatesSpinner)
        val tvCheckNow = dialog.findViewById<TextView>(R.id.tvDialogCheckUpdatesNow)

        btnCheckNow.background = getCardDrawable(isLightContext, 14f)
        tvCheckNow.setTextColor(titleTextColor)
        ivSpinner.imageTintList = ColorStateList.valueOf(accentColor)

        fun updateRadios(source: String) {
            rbGithub.isChecked = (source == SettingsRepository.UPDATE_SOURCE_GITHUB)
            rbRuStore.isChecked = (source == SettingsRepository.UPDATE_SOURCE_RUSTORE)
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

        rowRuStore.setOnClickListener {
            AnimationHelper.bounceClick(rowRuStore, minScale = 0.96f, durationMs = 150) {
                selectSource(SettingsRepository.UPDATE_SOURCE_RUSTORE, "RuStore")
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

    private fun getPrivacyPolicyHtml(): String {
        return """
            <h3>1. Общие положения</h3>
            <p>Настоящая Политика конфиденциальности определяет порядок обработки и защиты информации пользователей при использовании мобильного приложения <b>NAUA Security Mirage</b>, разработанного <b>Core Dev Support</b>.<br/>
            Принцип минимизации данных (Data Minimization) и приоритет приватности лежат в основе всей архитектуры приложения.</p>
            <br/>
            <h3>2. Использование системного сервиса Android VPNService</h3>
            <p>В соответствии с требованиями платформ распространения (Google Play, RuStore):<br/>
            1. <b>Цель использования:</b> Приложение использует системный компонент <code>android.net.VpnService</code> исключительно для выполнения своей основной функции — создания локального защищенного сквозного криптографического туннеля от устройства до выбранного узла маршрутизации.<br/>
            2. <b>Шифрование данных:</b> Весь сетевой трафик защищается с использованием современных протоколов сквозного криптографического шифрования транспортного уровня.<br/>
            3. <b>Отсутствие профилирования:</b> Сетевой трафик не перехватывается с целью анализа поведения, не модифицируется и не продается третьим лицам или рекламным сетям.</p>
            <br/>
            <h3>3. Политика отсутствия логов (No-Logs Policy)</h3>
            <p>Приложение придерживается строгой политики отказа от фиксации сетевой активности. Мы строго <b>НЕ собираем и НЕ сохраняет</b>:<br/>
            &#8226; Историю посещенных веб-сайтов и сетевых адресов;<br/>
            &#8226; Содержимое сетевых пакетов;<br/>
            &#8226; Журналы DNS-запросов;<br/>
            &#8226; Соответствие между реальным IP-адресом пользователя и временем сетевой сессии.<br/>
            <b>Локальная обработка:</b> списки раздельного туннелирования (Per-App Proxy), исключения для сайтов и правила распределения трафика обрабатываются и хранятся <b>исключительно локально на вашем устройстве</b>.</p>
            <br/>
            <h3>4. Обработка технических данных</h3>
            <p>&#8226; <b>Локальный идентификатор сессии (Device UUID):</b> Для технического получения сетевой конфигурации генерируется случайный псевдоним (UUID v4). Приложение <b>НЕ запрашивает и НЕ собирает</b> персональные или аппаратные идентификаторы (IMEI, MAC-адрес, серийный номер, контакты, номер телефона).<br/>
            &#8226; <b>Сетевое взаимодействие:</b> Запрос сетевой конфигурации выполняется по защищенному протоколу HTTPS к служебному API без передачи персональных сведений.</p>
            <br/>
            <h3>5. Диагностика и телеметрия (Firebase)</h3>
            <p>Для высокой надежности и оптимизации энергопотребления используются официальные сервисы Google Firebase:<br/>
            1. <b>Firebase Crashlytics:</b> фиксирует падения приложения (модель, версия ОС, стек ошибки);<br/>
            2. <b>Firebase Performance:</b> замеры технической скорости запуска компонентов;<br/>
            3. <b>Firebase Analytics:</b> базовые обезличенные события взаимодействия (переключение тем, клик подключения).<br/>
            <b>Гарантии:</b> доступ к отчетам имеет исключительно разработчик <b>Core Dev Support</b>. Вы можете в любой момент <b>полностью отключить</b> сбор переключателем «Анонимная отправка данных» в Настройках.</p>
            <br/>
            <h3>6. Локальные журналы работы (Логи)</h3>
            <p>Журналы событий формируются исключительно в оперативной памяти устройства для локальной диагностики. Логи не отправляются автоматически во внешние сервисы и передаются только по прямому указанию пользователя через системную функцию «Поделиться».</p>
            <br/>
            <h3>7. Системные разрешения Android</h3>
            <p>&#8226; <code>INTERNET / ACCESS_NETWORK_STATE</code> — проверка сети и сетевое взаимодействие;<br/>
            &#8226; <code>POST_NOTIFICATIONS</code> — отображение статуса защиты в шторке Android;<br/>
            &#8226; <code>RECEIVE_BOOT_COMPLETED</code> — автоматический запуск защиты при старте устройства (если включен);<br/>
            &#8226; <code>QUERY_ALL_PACKAGES</code> — получение списка приложений для раздельного туннелирования (обрабатывается исключительно локально).</p>
            <br/>
            <h3>8. Защита данных и сроки хранения</h3>
            <p>Настройки приложения хранятся в изолированной песочнице операционной системы (Sandboxing), недоступной сторонним программам. Временные сессионные данные на узлах конфигурации очищаются автоматически.</p>
            <br/>
            <h3>9. Изменения в Политике конфиденциальности</h3>
            <p>Действующая редакция документа всегда доступна в интерфейсе приложения и в официальном Google Документе.</p>
            <br/>
            <h3>10. Контактная информация</h3>
            <p>Разработчик: <b>Core Dev Support</b><br/>Email: <b>coredevsupport@gmail.com</b></p>
        """.trimIndent()
    }

    private fun getTermsOfServiceHtml(): String {
        return """
            <h3>1. Общие положения и порядок принятия</h3>
            <p>1.1. <b>Юридическая сила:</b> Настоящее Лицензионное соглашение представляет собой договор между Пользователем и правообладателем мобильного приложения <b>NAUA Security Mirage</b> (<b>Core Dev Support</b>).<br/>
            1.2. <b>Акцепт условий:</b> Использование или установка Приложения означает полное и безоговорочное согласие со всеми пунктами настоящего Соглашения и Политики конфиденциальности.<br/>
            1.3. <b>Отказ:</b> В случае несогласия Пользователь обязан немедленно прекратить использование Приложения и удалить его.</p>
            <br/>
            <h3>2. Предмет и условия лицензии</h3>
            <p>2.1. <b>Предоставление лицензии:</b> Разработчик предоставляет Пользователю ограниченную, неисключительную лицензию на использование Приложения в личных некоммерческих целях на устройствах под управлением Android.<br/>
            2.2. <b>Назначение ПО:</b> Приложение является клиентской программной оболочкой для управления локальной маршрутизацией сетевого трафика, настройки правил раздельного туннелирования (Split Tunneling) и установки защищенных сетевых сессий по поддерживаемым протоколам.</p>
            <br/>
            <h3>3. Статус инфраструктуры и отказ от гарантий (AS IS)</h3>
            <p>3.1. <b>Сторонние узлы:</b> Пользователь признает, что сетевые узлы в текущей версии являются внешними публичными узлами связи сторонних операторов. Разработчик <b>не гарантирует</b> постоянную доступность, бесперебойность работы, высокую скорость или минимальный пинг.<br/>
            3.2. <b>Принцип «КАК ЕСТЬ» (AS IS):</b> Доступ к сетевой инфраструктуре предоставляется на условиях «AS IS» («Как есть») и «AS AVAILABLE» («По мере доступности»). Серверы могут быть временно ограничены, перегружены или отключены поставщиками без предупреждения.<br/>
            3.3. <b>Развитие сервиса:</b> Разработчик оставляет за собой право в будущих версиях внедрять собственные выделенные высокоскоростные серверы с платными тарифами и подписками.</p>
            <br/>
            <h3>4. Правила допустимого использования</h3>
            <p>4.1. <b>Соблюдение законодательства:</b> Пользователь обязуется соблюдать нормативно-правовые акты и законы при использовании Приложения.<br/>
            4.2. <b>Запрещенная деятельность:</b> Категорически запрещается использовать Приложение для:<br/>
            &#8226; Сетевых атак, сканирования портов, организации DDoS-атак;<br/>
            &#8226; Несанкционированного доступа (взлом), перехвата данных, подбора паролей;<br/>
            &#8226; Распространения вредоносных программ, троянов, вирусов;<br/>
            &#8226; Спама, проведения фишинговых и кардинговых операций;<br/>
            &#8226; Распространения запрещенных законом материалов и экстремизма;<br/>
            &#8226; Нарушения авторских и смежных прав третьих лиц.<br/>
            4.3. <b>Ограничение доступа:</b> В случае обнаружения нарушений Разработчик вправе заблокировать доступ Пользователя к служебным конфигурациям без предупреждения.</p>
            <br/>
            <h3>5. Ограничение ответственности</h3>
            <p>5.1. <b>Персональная ответственность:</b> Пользователь самостоятельно несет ответственность за характер передаваемой информации и действия, совершаемые им с использованием Приложения.<br/>
            5.2. <b>Исключение ответственности:</b> Разработчик не несет ответственности за:<br/>
            &#8226; Прямые, косвенные убытки, упущенную выгоду или потерю данных;<br/>
            &#8226; Блокировку учетных записей Пользователя на сторонних сервисах;<br/>
            &#8226; Действия интернет-провайдеров, операторов связи и государственных регуляторов по ограничению доступа к IP-адресам или протоколам;<br/>
            &#8226; Проблемы совместимости с кастомными модификациями ОС Android.</p>
            <br/>
            <h3>6. Интеллектуальная собственность</h3>
            <p>6.1. Все элементы графического интерфейса (UI), товарные знаки, название «NAUA Security Mirage» и исходные кодовые структуры принадлежат Правообладателю.<br/>
            6.2. Входящие в состав открытые библиотеки (AndroidX, Firebase SDK, ядро Xray) распространяются на условиях соответствующих лицензий.</p>
            <br/>
            <h3>7. Срок действия и расторжение</h3>
            <p>Соглашение действует бессрочно. Разработчик вправе вносить изменения в одностороннем порядке. Пользователь может расторгнуть Соглашение в любой момент путем удаления Приложения.</p>
            <br/>
            <h3>8. Контактная информация</h3>
            <p>Разработчик: <b>Core Dev Support</b><br/>Email: <b>coredevsupport@gmail.com</b></p>
        """.trimIndent()
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

        // 1. Determine background mode and whether context is light or dark
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

        // 2. Full-Screen Edge-to-Edge Status Bar & Navigation Bar icons
        val insetsController = WindowInsetsControllerCompat(window, window.decorView)
        insetsController.isAppearanceLightStatusBars = isLightContext
        insetsController.isAppearanceLightNavigationBars = isLightContext

        // 3. Connect Button & Glow Ring
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

        // 4. Action Buttons (Refresh, Settings, Back from Settings, Back from Appearance)
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

        // 5. Text & Card Colors
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

        // Brand & Status
        binding.tvBrandName.setTextColor(titleTextColor)
        binding.tvBrandSub.setTextColor(subtitleTextColor)
        binding.tvStatus.setTextColor(titleTextColor)
        binding.tvStatusSub.setTextColor(subtitleTextColor)

        // Live Speed display on viewMain
        binding.containerSpeedLive.background = getButtonChipDrawable(isLightContext, 16f)
        binding.ivSpeedDown.imageTintList = ColorStateList.valueOf(Color.parseColor("#10B981"))
        binding.ivSpeedUp.imageTintList = ColorStateList.valueOf(Color.parseColor("#00D2FF"))
        binding.tvSpeedDown.setTextColor(titleTextColor)
        binding.tvSpeedUp.setTextColor(titleTextColor)

        // Support Project button
        binding.btnSupport.background = getCardDrawable(isLightContext, 30f)
        binding.tvSupport.setTextColor(titleTextColor)
        binding.ivSupportIcon.imageTintList = ColorStateList.valueOf(Color.parseColor("#EF4444"))

        // Hint Card
        binding.hintCard.background = getCardDrawable(isLightContext, 18f)
        binding.tvHint.setTextColor(subtitleTextColor)
        binding.ivHintIcon.imageTintList = ColorStateList.valueOf(subtitleTextColor)

        // Settings View Elements
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

        // Per-App Proxy & Geo Routing Section in Settings
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

        // Logs Section in Settings
        binding.tvSectionLogs.setTextColor(sectionHeaderColor)

        val autoPingDensity = resources.displayMetrics.density

        // Speed Card in Settings
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

        // Appearance View Elements
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

        // Logs View Elements
        binding.tvLogsTitle.setTextColor(titleTextColor)
        binding.tvSectionLogLevel.setTextColor(sectionHeaderColor)
        binding.cardLogViewer.background = getCardDrawable(isLightContext, 18f)
        binding.tvLogStatusHeader.setTextColor(subtitleTextColor)
        binding.tvLogOutput.setTextColor(titleTextColor)

        // Bottom Download & Share buttons styled as theme chips (14dp corner radius, centered)
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

        // Per-App Proxy View Elements
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

        // Custom Websites View Elements
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

        // 6. Switches
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

        // Update Theme chips, Log level chips, Style chips & Per-App filter chips highlight
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
        RuStoreUpdateHelper.onDestroy()
    }

    companion object {
        const val ACTION_QUICK_CONNECT = "com.naua_security_mirage.app.ACTION_QUICK_CONNECT"

        private val AUTO_PING_STEPS = intArrayOf(0, 3, 5, 10, 15, 20, 30, 45, 60)
        private val SPEED_STEPS = intArrayOf(0, 1, 2, 3, 5)

        private const val FILTER_ALL = 0
        private const val FILTER_PROXIED = 1
        private const val FILTER_BYPASSED = 2

        private val BG_COLORS = listOf(
            0xFF0A0B10.toInt(), // Default Dark
            0xFF000000.toInt(), // AMOLED Black
            0xFF0F172A.toInt(), // Slate Navy
            0xFF1E1B4B.toInt(), // Deep Indigo
            0xFF14221A.toInt(), // Forest Emerald
            0xFF2A0845.toInt(), // Midnight Purple
            0xFF1C1917.toInt(), // Warm Charcoal
            0xFFF8F9FA.toInt()  // Soft Light
        )

        private val CONNECT_BTN_COLORS = listOf(
            0xFFE8A33D.toInt(), // Amber
            0xFF00D2FF.toInt(), // Neon Cyan
            0xFF10B981.toInt(), // Emerald
            0xFF8B5CF6.toInt(), // Electric Violet
            0xFFEF4444.toInt(), // Ruby Red
            0xFFEC4899.toInt(), // Hot Pink
            0xFF3B82F6.toInt(), // Royal Blue
            0xFFE2E8F0.toInt()  // Metallic Silver
        )

        private val ACTION_BTN_COLORS = listOf(
            0xFFF3F1EA.toInt(), // Default Ink
            0xFFE8A33D.toInt(), // Amber
            0xFF00D2FF.toInt(), // Cyan
            0xFF10B981.toInt(), // Green
            0xFF8B5CF6.toInt(), // Purple
            0xFFEF4444.toInt(), // Red
            0xFF94A3B8.toInt()  // Slate
        )

        private val TEXT_COLORS = listOf(
            0xFFF3F1EA.toInt(), // Classic White
            0xFFE2E8F0.toInt(), // Clean Slate
            0xFFFDE68A.toInt(), // Warm Gold
            0xFFA7F3D0.toInt(), // Mint Ice
            0xFFC4B5FD.toInt(), // Soft Lavender
            0xFFFCA5A5.toInt(), // Soft Coral
            0xFF0F172A.toInt()  // Dark Slate
        )

        private val SWITCH_COLORS = listOf(
            0xFFE67E22.toInt(), // Warm Amber
            0xFF00D2FF.toInt(), // Neon Cyan
            0xFF10B981.toInt(), // Emerald
            0xFF8B5CF6.toInt(), // Violet
            0xFFEF4444.toInt(), // Crimson
            0xFFF59E0B.toInt(), // Bright Gold
            0xFFEC4899.toInt()  // Pink
        )
    }
}
