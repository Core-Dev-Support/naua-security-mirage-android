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
      nav_styles: "Кастомизация",
      nav_plans: "Подписка",
      nav_download: "Скачать",
      theme_sys: "Как в системе",
      theme_dark: "Тёмная",
      theme_light: "Светлая",
      eyebrow: "VLESS + Reality · Android 6.0+",
      hero_title: "VPN, настроенный <span class=\"hl\">под вас</span>",
      hero_lead: "Нативный Android-клиент на ядре Xray. Трафик неотличим от обычного HTTPS, туннель поднимается в один тап, а рекламы нет вообще.",
      cta_dl: "⬇ Скачать APK v1.4.0",
      cta_gh: "Исходники на GitHub",
      fact_1: "Без рекламы и трекеров",
      fact_2: "Пробный день без карты",
      fact_3: "1 подписка на все устройства",
      phone_hint: "Живой макет приложения. Нажми на кнопку или тумблеры.",
      vpn_off: "Отключено",
      vpn_conn: "Подключение...",
      vpn_on: "Подключено",
      meta_off: "Нажми, чтобы включить защиту",
      meta_conn: "Поиск наилучшего сервера...",
      meta_on_fmt: "Время сессии {time} • 18 ms",
      split_chip: "⚡ .ru и выбранные сайты напрямую",
      sec_pill: "🔒 FLAG_SECURE",
      sw_tunnel: "Подключить VPN",
      sw_style: "3D-стиль (Cyber Earth)",
      sw_glass: "Стиль: Жидкое стекло",
      sw_split: "Split-tunneling (.ru)",
      sw_secure: "Снимки экрана запрещены",
      k_high: "Возможности",
      h_high: "Почему выбирают Mirage",
      s_high: "От маскировки трафика под обычный HTTPS до самозащиты APK и единой подписки.",
      b1_t: "Маскировка от DPI и ТСПУ",
      b1_d: "VLESS + Reality имитирует обычную TLS 1.3 сессию. В ветке 1.4.1-beta тестируются Hysteria 2 и XHTTP.",
      b2_t: "Умный автовыбор",
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
      k_all: "Всё остальное",
      h_all: "Полный контроль без лишнего шума",
      s_all: "Всё необходимое для стабильного соединения, диагностики и настройки интерфейса под себя.",
      f1_t: "Ядро и сеть", f1_s: "Нативный VpnService + libXray",
      f1_l1: "Автовыбор лучшего узла по пингу, потерям и джиттеру",
      f1_l2: "Проверка реального прохода трафика, а не просто старта ядра",
      f1_l3: "2 плитки Quick Settings и автозапуск при старте системы",
      f2_t: "Протоколы и маршруты", f2_s: "Гибкий обход блокировок",
      f2_l1: "VLESS + Reality (в бете — Hysteria 2 и XHTTP)",
      f2_l2: "Обновляемый geosite.dat для .ru-доменов",
      f2_l3: "Per-App Proxy без разрешения QUERY_ALL_PACKAGES",
      f3_t: "Безопасность и приватность", f3_s: "Zero logs и защита контура",
      f3_l1: "AD_ID и рекламные сервисы вырезаны; диагностика Firebase отключается",
      f3_l2: "Токены в EncryptedSharedPreferences, allowBackup=false, Kill Switch Android",
      f3_l3: "Самообновление из GitHub Releases с проверкой подписи v2/v3",
      f4_t: "Внешний вид", f4_s: "Классический или Жидкое стекло",
      f4_l1: "5 стилей кнопки: Стандарт, Cyber Earth, Quantum Core, Holo-Shield, Realistic Earth",
      f4_l2: "Свои цвета для «Стандарт», своё фото на фон и анимации интерфейса",
      f4_l3: "Скрытие и перестановка разделов, резервные копии, RU / EN (618 строк)",
      f5_t: "Диагностика и журнал", f5_s: "Прозрачность каждого соединения",
      f5_l1: "Live-скорость, пинг и время сессии на экране и в шторке",
      f5_l2: "Карточка узлов («отвечают N из M») и история сбоев",
      f5_l3: "Журнал событий с фильтрами, экспортом и очисткой токенов",
      f6_t: "Аккаунт, бот и оплата", f6_s: "Единая база Supabase",
      f6_l1: "Вход через Google (внутри приложения) или email",
      f6_l2: "Telegram-бот для привязки, ключей, поддержки и рефералки",
      f6_l3: "Оплата через Cashera без автосписаний",
      p_k: "Подписка и пробный день",
      p_h: "24 часа бесплатно, дальше — от 1.12 ₽/день",
      p_d: "Бесплатный пул узлов работает всегда. А для максимальной скорости и мультиплатформы есть выделенные серверы — с честной разовой оплатой через Cashera.",
      p_b1: "🤖 Открыть Telegram-бота",
      p_b2: "Тарифы на GitHub",
      pk1_t: "🎁 Пробный день", pk1_d: "24 часа платных узлов без привязки банковской карты.",
      pk2_t: "⚡ Без автосписаний", pk2_d: "30 дней — 50 ₽ · 3 мес. — 140 ₽ · 6 мес. — 250 ₽ · 1 год — 410 ₽.",
      pk3_t: "📺 Все устройства", pk3_d: "Android, Windows/macOS (Happ, v2rayN), iOS (Happ, Streisand), TV.",
      pk4_t: "🤝 Реферальные бонусы", pk4_d: "+2 дня за приглашённого друга и +10 дней за каждые 5 оплат.",
      pk5_t: "🌍 Динамический каталог узлов", pk5_d: "Новые страны и протоколы появляются в приложении через app-config без переустановки APK.",
      k_dl: "Скачать",
      h_dl: "Официальные источники",
      s_dl: "Устанавливайте APK только из официальных релизов — все сборки подписаны и проверяются SHA-256.",
      dl1_t: "GitHub Releases", dl1_d: "Основной источник APK: arm64-v8a (≈ 17 МБ), armeabi-v7a и Universal.", dl1_go: "Открыть релизы ↗",
      dl2_t: "Telegram-канал", dl2_d: "Анонсы обновлений, новые узлы и прямые новости проекта.", dl2_go: "Открыть канал ↗",
      dl3_t: "Telegram-бот", dl3_d: "Пробный день, оплата подписки, ссылки для ПК/iOS и поддержка.", dl3_go: "Запустить бота ↗",
      warn_html: "<b>Проверка подлинности:</b> встроенный механизм обновления принимает APK только с совпадающей подписью v2/v3. Не скачивайте сборки со сторонних каталогов.",
      k_comm: "Сообщество",
      h_comm: "Помощь, идеи и исходный код",
      c1_t: "Telegram-канал", c1_d: "Обновления, статус узлов и важные объявления.",
      c2_t: "GitHub Discussions", c2_d: "Вопросы, голосование за новые страны и обсуждение идей.",
      c3_t: "Исходный код и аудит", c3_d: "Полный код клиента, Edge-функций и SECURITY.md на GitHub.",
      foot_note: "© 2026 Core Dev Support · NAUA Security Mirage · Открытый код для аудита",
      foot_sec: "Безопасность", foot_lic: "Лицензия", foot_priv: "Приватность"
    },
    en: {
      nav_highlights: "Features",
      nav_styles: "Customization",
      nav_plans: "Subscription",
      nav_download: "Download",
      theme_sys: "System",
      theme_dark: "Dark",
      theme_light: "Light",
      eyebrow: "VLESS + Reality · Android 6.0+",
      hero_title: "VPN built <span class=\"hl\">your way</span>",
      hero_lead: "Native Android client powered by Xray-core. Traffic looks like ordinary HTTPS, the tunnel starts in one tap, and there are zero ads.",
      cta_dl: "⬇ Download APK v1.4.0",
      cta_gh: "Source on GitHub",
      fact_1: "Zero ads & trackers",
      fact_2: "Free trial without card",
      fact_3: "1 subscription for all devices",
      phone_hint: "Interactive app mockup. Tap the button or toggles.",
      vpn_off: "Disconnected",
      vpn_conn: "Connecting...",
      vpn_on: "Connected",
      meta_off: "Tap to enable protection",
      meta_conn: "Finding best server...",
      meta_on_fmt: "Session time {time} • 18 ms",
      split_chip: "⚡ .ru & custom sites direct",
      sec_pill: "🔒 FLAG_SECURE",
      sw_tunnel: "Connect VPN",
      sw_style: "3D style (Cyber Earth)",
      sw_glass: "UI style: Liquid Glass",
      sw_split: "Split-tunneling (.ru)",
      sw_secure: "Screenshots blocked",
      k_high: "Highlights",
      h_high: "Why people choose Mirage",
      s_high: "From HTTPS-grade DPI masquerade to APK self-protection and cross-platform access.",
      b1_t: "DPI & TSPU Masquerade",
      b1_d: "VLESS + Reality mimics a standard TLS 1.3 session. Hysteria 2 and XHTTP are in testing on 1.4.1-beta.",
      b2_t: "Smart Auto-Failover",
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
      k_all: "Everything else",
      h_all: "Full control without bloat",
      s_all: "Everything you need for stable routing, diagnostics, and UI personalization.",
      f1_t: "Core & Network", f1_s: "Native VpnService + libXray",
      f1_l1: "Best node selection by latency, loss, and jitter",
      f1_l2: "Verifies real traffic flow instead of trusting core start",
      f1_l3: "2 Quick Settings tiles and boot autostart",
      f2_t: "Protocols & Routing", f2_s: "Flexible censorship bypass",
      f2_l1: "VLESS + Reality (Hysteria 2 & XHTTP in beta)",
      f2_l2: "Updatable geosite.dat for direct .ru routing",
      f2_l3: "Per-App Proxy without QUERY_ALL_PACKAGES",
      f3_t: "Security & Privacy", f3_s: "Zero logs & hardened runtime",
      f3_l1: "AD_ID & ad services stripped; Firebase diagnostics can be turned off",
      f3_l2: "Tokens in EncryptedSharedPreferences, allowBackup=false, Android Kill Switch",
      f3_l3: "Self-updates from GitHub Releases with v2/v3 signature check",
      f4_t: "Appearance", f4_s: "Classic or Liquid Glass UI",
      f4_l1: "5 button styles: Standard, Cyber Earth, Quantum Core, Holo-Shield, Realistic Earth",
      f4_l2: "Custom colors for Standard, gallery photo background & UI animations",
      f4_l3: "Reorder & hide sections, settings backup, instant RU / EN (618 strings)",
      f5_t: "Diagnostics & Journal", f5_s: "Full connection visibility",
      f5_l1: "Live speed, ping, and session timer on screen and notification",
      f5_l2: "Node health card (N of M responding) and outage history",
      f5_l3: "Filterable event log with export and automatic token redaction",
      f6_t: "Account, Bot & Billing", f6_s: "Unified Supabase backend",
      f6_l1: "Native Google Sign-In or email authentication",
      f6_l2: "Telegram bot for linking, keys, support tickets, and referrals",
      f6_l3: "One-time Cashera payments with zero auto-renewals",
      p_k: "Subscription & Trial",
      p_h: "24h free trial, then from 1.12 ₽/day",
      p_d: "The free node pool is always free. For maximum speed and multi-device support, dedicated nodes are available via one-time Cashera payments.",
      p_b1: "🤖 Open Telegram Bot",
      p_b2: "View plans on GitHub",
      pk1_t: "🎁 24h Free Trial", pk1_d: "Full access to paid nodes with zero credit card required.",
      pk2_t: "⚡ No Auto-Renewal", pk2_d: "30d — 50 ₽ · 3mo — 140 ₽ · 6mo — 250 ₽ · 1yr — 410 ₽.",
      pk3_t: "📺 All Devices", pk3_d: "Android, Windows/macOS (Happ, v2rayN), iOS (Happ, Streisand), TV.",
      pk4_t: "🤝 Referral Bonuses", pk4_d: "+2 days per invited friend and +10 days for every 5 paid referrals.",
      pk5_t: "🌍 Dynamic Node Catalog", pk5_d: "New countries and protocols appear via app-config without reinstalling the APK.",
      k_dl: "Download",
      h_dl: "Official sources",
      s_dl: "Install APKs only from official releases — every build is signed and SHA-256 verified.",
      dl1_t: "GitHub Releases", dl1_d: "Primary APK source: arm64-v8a (≈ 17 MB), armeabi-v7a, and Universal.", dl1_go: "View releases ↗",
      dl2_t: "Telegram Channel", dl2_d: "Release announcements, node updates, and project news.", dl2_go: "Open channel ↗",
      dl3_t: "Telegram Bot", dl3_d: "Free trial, subscription billing, PC/iOS links, and support.", dl3_go: "Start bot ↗",
      warn_html: "<b>Authenticity check:</b> the built-in updater only accepts APKs with a matching v2/v3 signature. Avoid third-party mirror sites.",
      k_comm: "Community",
      h_comm: "Support, ideas & source code",
      c1_t: "Telegram Channel", c1_d: "Updates, node status, and announcements.",
      c2_t: "GitHub Discussions", c2_d: "Q&A, voting for new countries, and feature ideas.",
      c3_t: "Source Code & Audit", c3_d: "Full client & Edge Function source plus SECURITY.md.",
      foot_note: "© 2026 Core Dev Support · NAUA Security Mirage · Open for security audit",
      foot_sec: "Security", foot_lic: "License", foot_priv: "Privacy"
    }
  };

  var stylesData = [
    { id: "standard", ru: "Стандарт", en: "Standard", descRu: "Классический шар", descEn: "Classic sphere", glow: "rgba(232, 163, 61, 0.34)", color: "#e8a33d" },
    { id: "cyber", ru: "3D Cyber Earth", en: "3D Cyber Earth", descRu: "Неоновый глобус", descEn: "Neon globe", glow: "rgba(245, 185, 81, 0.38)", color: "#f5b951" },
    { id: "quantum", ru: "3D Quantum Core", en: "3D Quantum Core", descRu: "Квантовый реактор", descEn: "Quantum reactor", glow: "rgba(77, 232, 163, 0.32)", color: "#4de8a3" },
    { id: "holo", ru: "3D Holo-Shield", en: "3D Holo-Shield", descRu: "Голографический щит", descEn: "Holographic shield", glow: "rgba(232, 163, 61, 0.30)", color: "#e8a33d" },
    { id: "earth", ru: "3D Realistic Earth", en: "3D Realistic Earth", descRu: "Планета Земля", descEn: "Planet Earth", glow: "rgba(59, 130, 246, 0.32)", color: "#3b82f6" }
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
        ["Протоколы", "VLESS + Reality (Hysteria 2 & XHTTP в бете)"],
        ["Split-tunneling & .ru", "✓ Включено"],
        ["Экспорт на ПК / iOS / TV", "✓ Happ, v2rayN, Shadowrocket, TV"],
        ["Цена (без автосписаний)", "от 50 ₽ / 30 дн. (1.12 ₽/день за год)"]
      ],
      en: [
        ["Nodes", "Dedicated (Netherlands, Turkey, etc.)"],
        ["Protocols", "VLESS + Reality (Hysteria 2 & XHTTP in beta)"],
        ["Split-tunneling & .ru", "✓ Included"],
        ["PC / iOS / TV Export", "✓ Happ, v2rayN, Shadowrocket, TV"],
        ["Price (no auto-renewal)", "from 50 ₽ / 30d (1.12 ₽/day annual)"]
      ]
    }
  };

  var renderCompare = function () {
    var rows = compareData[activePlanTab][lang];
    var box = document.getElementById("compareList");
    box.innerHTML = rows.map(function (r) {
      return "<div class=\"compare-row\"><span>" + r[0] + "</span><span class=\"val\">" + r[1] + "</span></div>";
    }).join("");
  };

  var renderStyles = function () {
    var st = stylesData[activeStyleIdx];
    document.getElementById("styleName").textContent = lang === "en" ? st.en : st.ru;
    document.getElementById("styleDesc").textContent = lang === "en" ? st.descEn : st.descRu;
    document.getElementById("styleGlow").style.setProperty("--glow", st.glow);
    document.querySelectorAll(".style-pill").forEach(function (el, idx) {
      el.classList.toggle("active", idx === activeStyleIdx);
      el.textContent = lang === "en" ? stylesData[idx].en : stylesData[idx].ru;
    });
  };

  // Phone mockup state
  var phoneState = { connected: false, connecting: false, style3d: false, glass: true, split: true, secure: true, sec: 0 };
  var sessionTimer = null;
  var fmtTime = function (s) {
    var m = Math.floor(s / 60), r = s % 60;
    return (m < 10 ? "0" : "") + m + ":" + (r < 10 ? "0" : "") + r;
  };
  var updatePhoneUI = function () {
    var dict = i18n[lang];
    var scr = document.getElementById("phoneScreen");
    var btn = document.getElementById("phoneVpnBtn");
    scr.classList.toggle("connected", phoneState.connected);
    scr.classList.toggle("aurora-on", true);
    scr.classList.toggle("glass-on", phoneState.glass);
    scr.classList.toggle("split-on", phoneState.split);
    scr.classList.toggle("secure-on", phoneState.secure);

    btn.className = "vpn-btn" + (phoneState.style3d ? " style-cyber" : "");
    document.getElementById("vpnStatus").textContent = phoneState.connecting ? dict.vpn_conn : (phoneState.connected ? dict.vpn_on : dict.vpn_off);
    document.getElementById("vpnMeta").textContent = phoneState.connecting
      ? dict.meta_conn
      : (phoneState.connected ? dict.meta_on_fmt.replace("{time}", fmtTime(phoneState.sec)) : dict.meta_off);

    document.getElementById("rowTunnel").classList.toggle("on", phoneState.connected || phoneState.connecting);
    document.getElementById("rowStyle").classList.toggle("on", phoneState.style3d);
    document.getElementById("rowGlass").classList.toggle("on", phoneState.glass);
    document.getElementById("rowSplit").classList.toggle("on", phoneState.split);
    document.getElementById("rowSecure").classList.toggle("on", phoneState.secure);
  };

  var toggleVpn = function () {
    if (phoneState.connecting) return;
    if (phoneState.connected) {
      clearInterval(sessionTimer);
      phoneState.connected = false;
      phoneState.sec = 0;
      updatePhoneUI();
    } else {
      phoneState.connecting = true;
      updatePhoneUI();
      setTimeout(function () {
        phoneState.connecting = false;
        phoneState.connected = true;
        phoneState.sec = 1;
        updatePhoneUI();
        clearInterval(sessionTimer);
        sessionTimer = setInterval(function () {
          phoneState.sec++;
          if (phoneState.connected) updatePhoneUI();
        }, 1000);
      }, 650);
    }
  };

  document.getElementById("phoneVpnBtn").addEventListener("click", toggleVpn);
  document.getElementById("rowTunnel").addEventListener("click", toggleVpn);
  document.getElementById("rowStyle").addEventListener("click", function () { phoneState.style3d = !phoneState.style3d; updatePhoneUI(); });
  document.getElementById("rowGlass").addEventListener("click", function () { phoneState.glass = !phoneState.glass; updatePhoneUI(); });
  document.getElementById("rowSplit").addEventListener("click", function () { phoneState.split = !phoneState.split; updatePhoneUI(); });
  document.getElementById("rowSecure").addEventListener("click", function () { phoneState.secure = !phoneState.secure; updatePhoneUI(); });

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
    updatePhoneUI();
    renderStyles();
    renderCompare();
  };

  document.querySelectorAll(".lang-pill button").forEach(function (b) {
    b.addEventListener("click", function () { applyLang(b.dataset.lang); });
  });

  document.querySelectorAll(".style-pill").forEach(function (b, idx) {
    b.addEventListener("click", function () { activeStyleIdx = idx; renderStyles(); });
  });

  document.querySelectorAll(".plan-toggle button").forEach(function (b) {
    b.addEventListener("click", function () {
      activePlanTab = b.dataset.plan;
      document.querySelectorAll(".plan-toggle button").forEach(function (x) { x.classList.toggle("active", x === b); });
      renderCompare();
    });
  });

  // Canvas animation for 5 app button styles
  var canvas = document.getElementById("styleCanvas");
  var ctx = canvas ? canvas.getContext("2d") : null;
  var angle = 0;
  var drawStyle = function () {
    if (!ctx) return;
    var w = canvas.width, h = canvas.height, cx = w / 2, cy = h / 2, r = 52;
    ctx.clearRect(0, 0, w, h);
    var st = stylesData[activeStyleIdx];
    angle += 0.015;

    if (st.id === "standard") {
      var g = ctx.createRadialGradient(cx - 16, cy - 18, 6, cx, cy, r);
      g.addColorStop(0, "#fde68a"); g.addColorStop(0.55, "#e8a33d"); g.addColorStop(1, "#8f4712");
      ctx.beginPath(); ctx.arc(cx, cy, r, 0, Math.PI * 2); ctx.fillStyle = g; ctx.fill();
    } else if (st.id === "cyber" || st.id === "earth") {
      ctx.beginPath(); ctx.arc(cx, cy, r, 0, Math.PI * 2);
      ctx.fillStyle = st.id === "earth" ? "#0f294a" : "#11131b";
      ctx.fill();
      ctx.strokeStyle = st.color; ctx.lineWidth = 2; ctx.stroke();
      for (var i = -2; i <= 2; i++) {
        ctx.beginPath();
        ctx.ellipse(cx, cy, Math.abs(Math.cos(angle + i * 0.5)) * r, r, 0, 0, Math.PI * 2);
        ctx.strokeStyle = st.id === "earth" ? "rgba(96,165,250,0.45)" : "rgba(245,185,81,0.45)";
        ctx.lineWidth = 1.2; ctx.stroke();
      }
      for (var j = -1; j <= 1; j++) {
        ctx.beginPath();
        ctx.ellipse(cx, cy + j * 18, r * Math.cos(j * 0.35), 7, 0, 0, Math.PI * 2);
        ctx.stroke();
      }
    } else if (st.id === "quantum") {
      for (var k = 0; k < 3; k++) {
        ctx.save(); ctx.translate(cx, cy); ctx.rotate(angle + k * Math.PI / 3);
        ctx.beginPath(); ctx.ellipse(0, 0, r, 20, 0, 0, Math.PI * 2);
        ctx.strokeStyle = k === 0 ? "#4de8a3" : "#e8a33d"; ctx.lineWidth = 2; ctx.stroke();
        ctx.restore();
      }
      ctx.beginPath(); ctx.arc(cx, cy, 16, 0, Math.PI * 2); ctx.fillStyle = "#4de8a3"; ctx.fill();
    } else if (st.id === "holo") {
      ctx.save(); ctx.translate(cx, cy);
      ctx.beginPath();
      ctx.moveTo(0, -r); ctx.lineTo(r * 0.85, -r * 0.48); ctx.lineTo(r * 0.85, r * 0.25);
      ctx.quadraticCurveTo(0, r * 1.05, 0, r * 1.05);
      ctx.quadraticCurveTo(0, r * 1.05, -r * 0.85, r * 0.25);
      ctx.lineTo(-r * 0.85, -r * 0.48); ctx.closePath();
      ctx.fillStyle = "rgba(232, 163, 61, 0.16)"; ctx.fill();
      ctx.strokeStyle = "#f5b951"; ctx.lineWidth = 2.4; ctx.stroke();
      ctx.restore();
    }
    requestAnimationFrame(drawStyle);
  };

  applyLang(lang);
  drawStyle();
})();
