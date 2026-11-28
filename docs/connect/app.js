(function () {
  var params = new URLSearchParams(location.search);
  var url = params.get("u") || "";
  var urlLang = params.get("lang");

  // ---------- MODE 1: TELEGRAM BOT 1-TAP CONNECT (?u=...) ----------
  if (url) {
    var metaRobots = document.querySelector('meta[name="robots"]');
    if (metaRobots) metaRobots.setAttribute("content", "noindex, nofollow");

    document.body.classList.add("connect-mode");
    document.getElementById("connectView").classList.remove("hidden");

    var cLang = urlLang === "en" ? "en" : "ru";
    if (cLang === "en") document.documentElement.lang = "en";

    if (url.startsWith("http://")) url = "https://" + url.slice(7);
    if (url.includes(".supabase.co/telegram-subscription")) {
      url = url.replace(".supabase.co/telegram-subscription", ".supabase.co/functions/v1/telegram-subscription");
    }

    var cTexts = {
      ru: {
        subtitle: "Подключение к VPN",
        hint: "<b>Выбери приложение</b> — подписка добавится в один тап. Оба платных узла (Нидерланды и Турция, протоколы XHTTP и Hysteria 2) уже внутри.",
        happ: "Открыть в Happ",
        hiddify: "Открыть в Hiddify",
        v2rayng: "Открыть в v2rayTun",
        copy: "📋 Скопировать ссылку",
        copied: "Ссылка скопирована ✓",
        copyFail: "Не получилось скопировать — выдели ссылку вручную.",
        note: "Если приложение не открылось — скопируй ссылку и добавь её в приложении вручную.",
        appHint: "Приложение не открылось? <a href=\"{store}\" target=\"_blank\" rel=\"noreferrer\">Установи его</a> или <a href=\"#\" id=\"appHintCopy\">скопируй ссылку</a> и добавь вручную.",
        tag: "рекомендуем",
        verification: "NAUA Security Mirage · PLAT · <a href=\"https://t.me/NAUA_Security_Mirage_Bot\" target=\"_blank\" rel=\"noreferrer\">Бот</a> · <a href=\"https://t.me/News_And_Updates_Apps?direct\" target=\"_blank\" rel=\"noreferrer\">Поддержка</a>"
      },
      en: {
        subtitle: "VPN connection",
        hint: "<b>Pick an app</b> — the subscription is added in one tap. Both paid nodes (Netherlands and Turkey, XHTTP and Hysteria 2) are already inside.",
        happ: "Open in Happ",
        hiddify: "Open in Hiddify",
        v2rayng: "Open in v2rayTun",
        copy: "📋 Copy the link",
        copied: "Link copied ✓",
        copyFail: "Could not copy — select the link manually.",
        note: "If the app did not open, copy the link and add it inside the app manually.",
        appHint: "The app did not open? <a href=\"{store}\" target=\"_blank\" rel=\"noreferrer\">Install it</a> or <a href=\"#\" id=\"appHintCopy\">copy the link</a> and add it manually.",
        tag: "recommended",
        verification: "NAUA Security Mirage · PLAT · <a href=\"https://t.me/NAUA_Security_Mirage_Bot\" target=\"_blank\" rel=\"noreferrer\">Bot</a> · <a href=\"https://t.me/News_And_Updates_Apps?direct\" target=\"_blank\" rel=\"noreferrer\">Support</a>"
      }
    };
    var t = cTexts[cLang];
    document.getElementById("subtitle").textContent = t.subtitle;
    document.getElementById("hint").innerHTML = t.hint;
    document.querySelector("#happ span").textContent = t.happ;
    document.querySelector("#hiddify span").textContent = t.hiddify;
    document.querySelector("#v2rayng span").textContent = t.v2rayng;
    document.querySelector("#copy span").textContent = t.copy;
    document.querySelector("#happ .tag").textContent = t.tag;
    document.getElementById("note").textContent = t.note;
    document.getElementById("verificationNote").innerHTML = t.verification;

    var raw = encodeURI(url);
    document.getElementById("happ").href = "happ://add/" + raw;
    document.getElementById("hiddify").href = "hiddify://import/" + raw;
    document.getElementById("v2rayng").href = "v2raytun://import/" + raw;

    var appHint = document.getElementById("appHint");
    var appHintTimer = null;
    var watchApp = function (btn, storeUrl) {
      if (!btn) return;
      btn.addEventListener("click", function () {
        clearTimeout(appHintTimer);
        appHintTimer = setTimeout(function () {
          if (document.visibilityState !== "visible") return;
          appHint.innerHTML = t.appHint.replace("{store}", storeUrl);
          appHint.classList.remove("hidden");
          var qc = document.getElementById("appHintCopy");
          if (qc) qc.addEventListener("click", function (ev) { ev.preventDefault(); document.getElementById("copy").click(); });
        }, 1600);
      });
    };
    document.addEventListener("visibilitychange", function () { if (document.hidden) clearTimeout(appHintTimer); });
    watchApp(document.getElementById("happ"), "https://www.happ.su/main");
    watchApp(document.getElementById("hiddify"), "https://hiddify.com/");
    watchApp(document.getElementById("v2rayng"), "https://v2raytun.com/");

    document.getElementById("copy").addEventListener("click", function () {
      var done = function () {
        document.getElementById("note").textContent = t.copied;
        document.getElementById("note").style.color = "#7ee2a8";
      };
      if (navigator.clipboard && navigator.clipboard.writeText) {
        navigator.clipboard.writeText(url).then(done).catch(fallback);
      } else { fallback(); }
      function fallback() {
        try {
          var a = document.createElement("textarea");
          a.value = url; a.style.position = "fixed"; a.style.opacity = "0";
          document.body.appendChild(a); a.select(); document.execCommand("copy"); document.body.removeChild(a);
          done();
        } catch (e) { document.getElementById("note").textContent = t.copyFail; }
      }
    });
    return;
  }

  // ---------- MODE 2: LANDING PAGE ----------
  document.getElementById("landingView").classList.remove("hidden");

  // Theme management
  var themePref = localStorage.getItem("mirage_theme") || "dark";
  var mql = window.matchMedia("(prefers-color-scheme: dark)");
  var applyTheme = function (pref) {
    themePref = pref;
    localStorage.setItem("mirage_theme", pref);
    var resolved = pref === "system" ? (mql.matches ? "dark" : "light") : pref;
    document.documentElement.setAttribute("data-theme", resolved);
    document.querySelectorAll(".theme-opt").forEach(function (el) {
      el.classList.toggle("active", el.dataset.theme === pref);
    });
    var icon = pref === "system" ? "◐" : (pref === "dark" ? "🌙" : "☀️");
    document.getElementById("themeBtn").textContent = icon;
  };
  applyTheme(themePref);
  mql.addEventListener("change", function () { if (themePref === "system") applyTheme("system"); });

  var themeMenu = document.getElementById("themeMenu");
  document.getElementById("themeBtn").addEventListener("click", function (e) {
    e.stopPropagation();
    themeMenu.classList.toggle("hidden");
  });
  document.querySelectorAll(".theme-opt").forEach(function (el) {
    el.addEventListener("click", function () {
      applyTheme(el.dataset.theme);
      themeMenu.classList.add("hidden");
    });
  });
  document.addEventListener("click", function () { themeMenu.classList.add("hidden"); });

  // i18n dictionary
  var lang = urlLang || localStorage.getItem("mirage_lang") || (navigator.language && navigator.language.startsWith("en") ? "en" : "ru");
  var i18n = {
    ru: {
      nav_highlights: "Возможности",
      nav_styles: "Режимы",
      nav_plans: "Подписка",
      nav_download: "Скачать",
      theme_sys: "Как в системе",
      theme_dark: "Тёмная",
      theme_light: "Светлая",
      eyebrow: "VLESS + Reality · Android 6.0+",
      hero_title: "VPN, настроенный <span class=\"hl\">под вас</span>",
      hero_lead: "Нативный Android-клиент на ядре Xray. Трафик неотличим от обычного HTTPS, туннель поднимается в один тап, а рекламы нет вообще.",
      cta_dl: "Скачать APK v1.4.0",
      cta_gh: "Исходники на GitHub",
      fact_1: "Без рекламы и трекеров",
      fact_2: "Пробный день без карты",
      fact_3: "1 подписка на все устройства",
      vpn_off: "Отключено",
      vpn_conn: "Подключение...",
      vpn_on: "Подключено",
      meta_off: "Нажми, чтобы проверить подключение",
      meta_conn: "Поиск наилучшего сервера...",
      meta_on_fmt: "Время сессии {time} • 18 ms",
      hc_v1: "Маскировка под HTTPS",
      hc_v2: ".ru сайты напрямую",
      hc_v3: "Без рекламы",
      k_modes: "Режимы работы",
      h_modes: "Бесплатный пул или выделенные серверы",
      s_modes: "Пользуйтесь бесплатно без регистрации или подключите подписку для максимальной скорости и всех устройств.",
      free_h: "Старт в один тап",
      paid_h: "Максимум возможностей",
      k_high: "Возможности",
      h_high: "Почему выбирают нас",
      s_high: "От маскировки трафика под обычный HTTPS до самозащиты APK и единой подписки.",
      b1_t: "Маскировка от DPI и ТСПУ",
      b1_d: "VLESS + Reality имитирует обычную TLS 1.3 сессию. В ветке 1.4.1-beta тестируются Hysteria 2 и XHTTP.",
      b2_t: "Автовыбор узла",
      b2_d: "Клиент замеряет пинг, потери и джиттер до всех узлов, проверяет реальный проход трафика и сам переключается при сбое.",
      b3_t: "Split-tunneling",
      b3_d: "Весь .ru-трафик, банки и выбранные приложения идут напрямую на полной скорости.",
      b4_t: "Безопасный старт",
      b4_d: "AppShield проверяет SHA-256 подписи APK, блокирует отладчик, Frida, эмулятор и подделанные сборки.",
      b5_t: "DNS в туннеле и FLAG_SECURE",
      b5_d: "DNS не утекает к провайдеру. При включённом «Частном DNS» Android появляется подсказка, а экран защищён от скриншотов.",
      b6_t: "1 подписка на все устройства",
      b6_d: "Экспорт VLESS-ключей и QR для ПК (Happ, v2rayN, Nekoray), iPhone/iPad (Happ, Shadowrocket) и Smart TV.",
      k_styles: "Кастомизация и тарифы",
      h_styles: "5 стилей кнопки и режимы работы",
      s_styles: "Покрути стили главной кнопки прямо из приложения и сравни бесплатный пул с подпиской.",
      plan_free: "Бесплатный режим",
      plan_paid: "Платная подписка",
      k_all: "Под капотом",
      h_all: "Что именно работает в приложении",
      s_all: "Нажмите на любой блок, чтобы посмотреть технические детали реализации.",
      f1_t: "Ядро и сеть", f1_s: "VpnService + libXray (libgojni.so)",
      f1_l1: "Автовыбор узла по <span class=\"opt\">пинг</span> <span class=\"opt\">потери</span> <span class=\"opt\">джиттер</span>",
      f1_l2: "Проверка реального трафика после старта ядра",
      f1_l3: "<span class=\"opt\">2 Quick Settings</span> плитки и автозапуск при загрузке",
      f1_det: "Если выбранный сервер перестал пропускать пакеты, контроллер перебирает следующий узел автоматически.",
      f2_t: "Протоколы и маршруты", f2_s: "Маскировка и обход .ru",
      f2_l1: "<span class=\"opt\">VLESS + Reality</span> (в тесте — <span class=\"opt\">Hysteria 2</span> и <span class=\"opt\">XHTTP</span>)",
      f2_l2: "Обновляемый <span class=\"opt\">geosite.dat</span> пускает .ru мимо VPN",
      f2_l3: "Per-App Proxy без <span class=\"opt\">QUERY_ALL_PACKAGES</span>",
      f2_det: "Список приложений запрашивается точечно через &lt;queries&gt; в манифесте, а пользовательские домены настраиваются отдельно.",
      f3_t: "Безопасность и приватность", f3_s: "Без логов трафика и рекламы",
      f3_l1: "<span class=\"opt\">AD_ID</span> вырезан; телеметрия Firebase отключается",
      f3_l2: "Токены в <span class=\"opt\">EncryptedSharedPreferences</span>, <span class=\"opt\">Kill Switch</span>",
      f3_l3: "Обновления с проверкой подписи <span class=\"opt\">v2/v3</span>",
      f3_det: "На старте сверяется SHA-256 подписи APK, блокируются отладчик, Frida, root и эмулятор.",
      f4_t: "Внешний вид", f4_s: "Своё оформление экрана",
      f4_l1: "5 стилей кнопки: <span class=\"opt\">Стандарт</span> <span class=\"opt\">Cyber Earth</span> <span class=\"opt\">Quantum Core</span> <span class=\"opt\">Holo-Shield</span> <span class=\"opt\">Realistic Earth</span>",
      f4_l2: "Цвета для «Стандарт», фото из галереи и анимации",
      f4_l3: "Скрытие разделов, бэкап, <span class=\"opt\">RU / EN</span> (618 строк)",
      f4_det: "Настройки интерфейса можно сохранить в резервную копию и перенести, а язык переключается без перезапуска.",
      f5_t: "Диагностика и журнал", f5_s: "Что происходит в туннеле",
      f5_l1: "Скорость, пинг и время сессии на экране и в шторке",
      f5_l2: "Карточка узлов (<span class=\"opt\">отвечают N из M</span>) и история сбоев",
      f5_l3: "Журнал событий с фильтрами и экспортом",
      f5_det: "При выгрузке журнала токены сессии и ключи автоматически вырезаются, чтобы их нельзя было случайно слить.",
      f6_t: "Аккаунт, бот и оплата", f6_s: "Общая подписка с Telegram",
      f6_l1: "Вход через <span class=\"opt\">Google</span> внутри приложения или email",
      f6_l2: "Telegram-бот для ключей, поддержки и рефералки",
      f6_l3: "Разовая оплата через <span class=\"opt\">Cashera</span> без автосписаний",
      f6_det: "Аккаунт единый для Android-клиента и Telegram-бота: оплата в одном месте сразу открывает доступ в другом.",
      p_k: "Подписка и пробный день",
      p_h: "24 часа бесплатно, дальше — от 1.12 ₽/день",
      p_d: "Бесплатный пул узлов работает без регистрации. Если нужна высокая скорость и ключи для ПК или iOS — есть выделенные серверы с разовой оплатой через Cashera.",
      p_b1: "Открыть Telegram-бота",
      p_b2: "Тарифы на GitHub",
      pk1_t: "Пробный день", pk1_d: "24 часа платных узлов без привязки банковской карты.",
      pk2_t: "Без автосписаний", pk2_d: "30 дней — 50 ₽ · 3 мес. — 140 ₽ · 6 мес. — 250 ₽ · 1 год — 410 ₽.",
      pk3_t: "Все устройства", pk3_d: "Android, Windows/macOS (Happ, v2rayN), iOS (Happ, Streisand), TV.",
      pk4_t: "Реферальные бонусы", pk4_d: "+2 дня за приглашённого друга и +10 дней за каждые 5 оплат.",
      pk5_t: "Динамический каталог узлов", pk5_d: "Новые страны и протоколы появляются в приложении через app-config без переустановки APK.",
      k_dl: "Скачать",
      h_dl: "Откуда скачать",
      s_dl: "Устанавливайте APK только из релизов GitHub или Telegram — все сборки подписаны и проверяются SHA-256.",
      dl1_t: "GitHub Releases", dl1_d: "Основной источник APK: arm64-v8a (≈ 17 МБ), armeabi-v7a и Universal.", dl1_go: "Открыть релизы ↗",
      dl2_t: "Telegram-канал", dl2_d: "Анонсы обновлений, статус узлов и новости проекта.", dl2_go: "Открыть канал ↗",
      dl3_t: "Telegram-бот", dl3_d: "Пробный день, оплата подписки, ссылки для ПК/iOS и поддержка.", dl3_go: "Запустить бота ↗",
      warn_html: "<b>Проверка подлинности:</b> встроенное обновление принимает APK только с совпадающей подписью v2/v3. Не ставьте сборки со сторонних сайтов.",
      k_comm: "Сообщество",
      h_comm: "Где задать вопрос и посмотреть код",
      c1_t: "Telegram-канал", c1_d: "Обновления, статус узлов и важные объявления.",
      c2_t: "GitHub Discussions", c2_d: "Вопросы, голосование за новые страны и обсуждение идей.",
      c3_t: "Исходный код и аудит", c3_d: "Полный код клиента, Edge-функций и SECURITY.md на GitHub.",
      foot_note: "© 2026 Core Dev Support · NAUA Security Mirage · Открытый код для аудита",
      foot_sec: "Безопасность", foot_lic: "Лицензия", foot_priv: "Приватность"
    },
    en: {
      nav_highlights: "Features",
      nav_styles: "Modes",
      nav_plans: "Subscription",
      nav_download: "Download",
      theme_sys: "System",
      theme_dark: "Dark",
      theme_light: "Light",
      eyebrow: "VLESS + Reality · Android 6.0+",
      hero_title: "VPN built <span class=\"hl\">your way</span>",
      hero_lead: "Native Android client powered by Xray-core. Traffic looks like ordinary HTTPS, the tunnel starts in one tap, and there are zero ads.",
      cta_dl: "Download APK v1.4.0",
      cta_gh: "Source on GitHub",
      fact_1: "Zero ads & trackers",
      fact_2: "Free trial without card",
      fact_3: "1 subscription for all devices",
      vpn_off: "Disconnected",
      vpn_conn: "Connecting...",
      vpn_on: "Connected",
      meta_off: "Tap to test connection",
      meta_conn: "Finding best server...",
      meta_on_fmt: "Session time {time} • 18 ms",
      hc_v1: "HTTPS masquerade",
      hc_v2: ".ru sites direct",
      hc_v3: "Zero ads",
      k_modes: "Modes",
      h_modes: "Free node pool or dedicated servers",
      s_modes: "Use the app for free without sign-up, or subscribe for maximum speed and all devices.",
      free_h: "1-Tap Start",
      paid_h: "Full Power",
      k_high: "Highlights",
      h_high: "Why people choose us",
      s_high: "From HTTPS-grade DPI masquerade to APK self-protection and cross-platform access.",
      b1_t: "DPI & TSPU Masquerade",
      b1_d: "VLESS + Reality mimics a standard TLS 1.3 session. Hysteria 2 and XHTTP are in testing on 1.4.1-beta.",
      b2_t: "Auto-Failover",
      b2_d: "Measures latency, packet loss, and jitter across nodes, verifies real traffic flow, and switches automatically on drops.",
      b3_t: "Split-tunneling",
      b3_d: "All .ru sites, banking apps, and custom domains go direct at full ISP speed.",
      b4_t: "Guarded Start",
      b4_d: "AppShield verifies APK SHA-256 signature and blocks debuggers, Frida, emulators, and tampered builds.",
      b5_t: "In-tunnel DNS & FLAG_SECURE",
      b5_d: "DNS stays inside the tunnel. Warns if Android Private DNS breaks routing and blocks screenshots.",
      b6_t: "1 Subscription, All Devices",
      b6_d: "Export VLESS keys & QR codes for PC (Happ, v2rayN, Nekoray), iPhone/iPad (Happ, Shadowrocket), and Smart TV.",
      k_styles: "Customization & Plans",
      h_styles: "5 button styles & plan comparison",
      s_styles: "Preview all 5 button styles from the app and compare the free pool with dedicated nodes.",
      plan_free: "Free Mode",
      plan_paid: "Paid Subscription",
      k_all: "Under the hood",
      h_all: "How the app works inside",
      s_all: "Tap any card to see implementation details.",
      f1_t: "Core & Network", f1_s: "VpnService + libXray (libgojni.so)",
      f1_l1: "Auto node selection by <span class=\"opt\">latency</span> <span class=\"opt\">loss</span> <span class=\"opt\">jitter</span>",
      f1_l2: "Verifies real traffic flow after core startup",
      f1_l3: "<span class=\"opt\">2 Quick Settings</span> tiles and boot autostart",
      f1_det: "If the active server stops passing packets, the controller fails over to the next candidate automatically.",
      f2_t: "Protocols & Routing", f2_s: "DPI bypass & direct .ru",
      f2_l1: "<span class=\"opt\">VLESS + Reality</span> (<span class=\"opt\">Hysteria 2</span> & <span class=\"opt\">XHTTP</span> in testing)",
      f2_l2: "Updatable <span class=\"opt\">geosite.dat</span> routes .ru outside VPN",
      f2_l3: "Per-App Proxy without <span class=\"opt\">QUERY_ALL_PACKAGES</span>",
      f2_det: "Installed apps are queried selectively via manifest &lt;queries&gt;, and custom domains can be bypassed directly.",
      f3_t: "Security & Privacy", f3_s: "No traffic logs or ads",
      f3_l1: "<span class=\"opt\">AD_ID</span> stripped; Firebase diagnostics can be turned off",
      f3_l2: "Tokens in <span class=\"opt\">EncryptedSharedPreferences</span>, <span class=\"opt\">Kill Switch</span>",
      f3_l3: "Updates verified against <span class=\"opt\">v2/v3</span> APK signature",
      f3_det: "On launch the app verifies its APK SHA-256 signature and blocks debuggers, Frida, root, and emulators.",
      f4_t: "Appearance", f4_s: "Custom home screen",
      f4_l1: "5 button styles: <span class=\"opt\">Standard</span> <span class=\"opt\">Cyber Earth</span> <span class=\"opt\">Quantum Core</span> <span class=\"opt\">Holo-Shield</span> <span class=\"opt\">Realistic Earth</span>",
      f4_l2: "Custom colors for Standard, gallery background & animations",
      f4_l3: "Hide sections, settings backup, <span class=\"opt\">RU / EN</span> (618 strings)",
      f4_det: "UI settings can be exported to a backup and restored later; language switches without restarting.",
      f5_t: "Diagnostics & Journal", f5_s: "What happens in the tunnel",
      f5_l1: "Speed, ping, and session time on screen and notification",
      f5_l2: "Node health card (<span class=\"opt\">N of M responding</span>) & outage history",
      f5_l3: "Filterable event log with export",
      f5_det: "Session tokens and keys are automatically stripped when exporting logs so nothing sensitive leaks.",
      f6_t: "Account, Bot & Billing", f6_s: "Shared subscription with Telegram",
      f6_l1: "In-app <span class=\"opt\">Google</span> Sign-In or email auth",
      f6_l2: "Telegram bot for keys, support tickets, and referrals",
      f6_l3: "One-time <span class=\"opt\">Cashera</span> payments, no auto-renewals",
      f6_det: "One account works across the Android app and Telegram bot — paying in either unlocks both.",
      p_k: "Subscription & Trial",
      p_h: "24h free trial, then from 1.12 ₽/day",
      p_d: "The free node pool works without registration. For higher speed and PC/iOS keys, dedicated servers are available via one-time Cashera payments.",
      p_b1: "Open Telegram Bot",
      p_b2: "View plans on GitHub",
      pk1_t: "24h Free Trial", pk1_d: "Full access to paid nodes with zero credit card required.",
      pk2_t: "No Auto-Renewal", pk2_d: "30d — 50 ₽ · 3mo — 140 ₽ · 6mo — 250 ₽ · 1yr — 410 ₽.",
      pk3_t: "All Devices", pk3_d: "Android, Windows/macOS (Happ, v2rayN), iOS (Happ, Streisand), TV.",
      pk4_t: "Referral Bonuses", pk4_d: "+2 days per invited friend and +10 days for every 5 paid referrals.",
      pk5_t: "Dynamic Node Catalog", pk5_d: "New countries and protocols appear via app-config without reinstalling the APK.",
      k_dl: "Download",
      h_dl: "Where to download",
      s_dl: "Install APKs only from GitHub Releases or Telegram — every build is signed and SHA-256 verified.",
      dl1_t: "GitHub Releases", dl1_d: "Primary APK source: arm64-v8a (≈ 17 MB), armeabi-v7a, and Universal.", dl1_go: "View releases ↗",
      dl2_t: "Telegram Channel", dl2_d: "Release announcements, node status, and project news.", dl2_go: "Open channel ↗",
      dl3_t: "Telegram Bot", dl3_d: "Free trial, subscription billing, PC/iOS links, and support.", dl3_go: "Start bot ↗",
      warn_html: "<b>Authenticity check:</b> the built-in updater only accepts APKs with a matching v2/v3 signature. Avoid third-party mirror sites.",
      k_comm: "Community",
      h_comm: "Questions, ideas & source code",
      c1_t: "Telegram Channel", c1_d: "Updates, node status, and announcements.",
      c2_t: "GitHub Discussions", c2_d: "Q&A, voting for new countries, and feature ideas.",
      c3_t: "Source Code & Audit", c3_d: "Full client & Edge Function source plus SECURITY.md.",
      foot_note: "© 2026 Core Dev Support · NAUA Security Mirage · Open for security audit",
      foot_sec: "Security", foot_lic: "License", foot_priv: "Privacy"
    }
  };

  var stylesData = [
    { id: "standard", ru: "Стандарт", en: "Standard", descRu: "Классический шар", descEn: "Classic sphere", glow: "rgba(232, 163, 61, 0.34)", color: "#e8a33d" },
    { id: "cyber", ru: "3D Cyber Earth", en: "3D Cyber Earth", descRu: "Неоновый глобус", descEn: "Neon globe", glow: "rgba(0, 240, 255, 0.35)", color: "#00f0ff" },
    { id: "quantum", ru: "3D Quantum Core", en: "3D Quantum Core", descRu: "Квантовый реактор", descEn: "Quantum reactor", glow: "rgba(255, 145, 0, 0.35)", color: "#ff9100" },
    { id: "holo", ru: "3D Holo-Shield", en: "3D Holo-Shield", descRu: "Голографический щит", descEn: "Holographic shield", glow: "rgba(0, 229, 255, 0.30)", color: "#00e5ff" },
    { id: "earth", ru: "3D Realistic Earth", en: "3D Realistic Earth", descRu: "Планета Земля", descEn: "Planet Earth", glow: "rgba(56, 189, 248, 0.35)", color: "#38bdf8" }
  ];
  var activeStyleIdx = 0;
  var activePlanTab = "free";

  var compareData = {
    free: {
      ru: [
        ["Узлы", "Пул бесплатных узлов"],
        ["Протокол", "VLESS + Reality"],
        ["Split-tunneling & .ru", "✓ Включено"],
        ["Пробный день VIP", "24 часа бесплатно"],
        ["Экспорт на ПК / iOS / TV", "Только платные узлы"]
      ],
      en: [
        ["Nodes", "Free node pool"],
        ["Protocol", "VLESS + Reality"],
        ["Split-tunneling & .ru", "✓ Included"],
        ["VIP Free Trial", "24h free"],
        ["PC / iOS / TV Export", "Paid nodes only"]
      ]
    },
    paid: {
      ru: [
        ["Узлы", "Выделенные (Нидерланды, Турция и др.)"],
        ["Протокол", "VLESS + Reality"],
        ["Split-tunneling & .ru", "✓ Включено"],
        ["Экспорт на ПК / iOS / TV", "✓ Happ, v2rayN, Shadowrocket, TV"],
        ["Цена (без автосписаний)", "от 50 ₽ / 30 дн. (1.12 ₽/день за год)"]
      ],
      en: [
        ["Nodes", "Dedicated (Netherlands, Turkey, etc.)"],
        ["Protocol", "VLESS + Reality"],
        ["Split-tunneling & .ru", "✓ Included"],
        ["PC / iOS / TV Export", "✓ Happ, v2rayN, Shadowrocket, TV"],
        ["Price (no auto-renewal)", "from 50 ₽ / 30d (1.12 ₽/day annual)"]
      ]
    }
  };

  var renderCompare = function () {
    var mapRows = function (rows) {
      return rows.map(function (r) {
        return "<div class=\"compare-row\"><span>" + r[0] + "</span><span class=\"val\">" + r[1] + "</span></div>";
      }).join("");
    };
    document.getElementById("compareFree").innerHTML = mapRows(compareData.free[lang]);
    document.getElementById("comparePaid").innerHTML = mapRows(compareData.paid[lang]);
  };

  // Hero VPN button state
  var heroState = { connected: false, connecting: false, sec: 0 };
  var sessionTimer = null;
  var fmtTime = function (s) {
    var m = Math.floor(s / 60), r = s % 60;
    return (m < 10 ? "0" : "") + m + ":" + (r < 10 ? "0" : "") + r;
  };
  var updateHeroUI = function () {
    var dict = i18n[lang];
    var card = document.querySelector(".hero-card");
    if (card) card.classList.toggle("connected", heroState.connected);
    document.getElementById("vpnStatus").textContent = heroState.connecting ? dict.vpn_conn : (heroState.connected ? dict.vpn_on : dict.vpn_off);
    document.getElementById("vpnMeta").textContent = heroState.connecting
      ? dict.meta_conn
      : (heroState.connected ? dict.meta_on_fmt.replace("{time}", fmtTime(heroState.sec)) : dict.meta_off);
  };

  document.getElementById("heroVpnBtn").addEventListener("click", function () {
    if (heroState.connecting) return;
    if (heroState.connected) {
      clearInterval(sessionTimer);
      heroState.connected = false;
      heroState.sec = 0;
      updateHeroUI();
    } else {
      heroState.connecting = true;
      updateHeroUI();
      setTimeout(function () {
        heroState.connecting = false;
        heroState.connected = true;
        heroState.sec = 1;
        updateHeroUI();
        clearInterval(sessionTimer);
        sessionTimer = setInterval(function () {
          heroState.sec++;
          if (heroState.connected) updateHeroUI();
        }, 1000);
      }, 650);
    }
  });

  var applyLang = function (nextLang) {
    lang = nextLang === "en" ? "en" : "ru";
    localStorage.setItem("mirage_lang", lang);
    document.documentElement.lang = lang;
    var dict = i18n[lang];
    document.querySelectorAll("[data-i18n]").forEach(function (el) {
      var k = el.dataset.i18n;
      if (dict[k] !== undefined) el.innerHTML = dict[k];
    });
    document.querySelectorAll(".lang-pill button").forEach(function (b) {
      b.classList.toggle("active", b.dataset.lang === lang);
    });
    updateHeroUI();
    renderCompare();
  };

  document.querySelectorAll(".lang-pill button").forEach(function (b) {
    b.addEventListener("click", function () { applyLang(b.dataset.lang); });
  });

  applyLang(lang);

  // Interactive detail toggle inside feature cards
  document.querySelectorAll("#featuresMatrix .f-cell").forEach(function (cell) {
    cell.addEventListener("click", function () {
      cell.classList.toggle("open");
    });
    cell.addEventListener("keydown", function (e) {
      if (e.key === "Enter" || e.key === " ") {
        e.preventDefault();
        cell.classList.toggle("open");
      }
    });
  });

  // Scroll reveal animations
  var revealEls = document.querySelectorAll(".b-card, .panel, .f-cell, .promo-banner, .route-card, .comm-card");
  if ("IntersectionObserver" in window) {
    revealEls.forEach(function (el) { el.classList.add("reveal"); });
    var io = new IntersectionObserver(function (entries) {
      entries.forEach(function (ent) {
        if (ent.isIntersecting) {
          ent.target.classList.add("in");
          io.unobserve(ent.target);
        }
      });
    }, { threshold: 0.08 });
    revealEls.forEach(function (el) { io.observe(el); });
  }
})();
