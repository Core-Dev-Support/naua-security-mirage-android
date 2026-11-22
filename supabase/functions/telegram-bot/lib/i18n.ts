// Языки и переводы бота.
//
// Пользователь выбирает язык один раз (при первом входе), дальше выбор
// хранится в bot_users.lang. Функция t() умеет подстановку {placeholders}
// и откатывается на русский, если ключа в выбранном языке нет — так
// незаведённые строки не ломают бота.

export type Lang = "ru" | "en";

export const LANGS: Lang[] = ["ru", "en"];

export const LANG_LABEL: Record<Lang, string> = {
  ru: "🇷🇺 Русский",
  en: "🇬🇧 English",
};

type Vars = Record<string, string | number>;

const ru: Record<string, string> = {
  // --- выбор языка ---
  "lang.title": "🌐 <b>Язык бота</b>\n\n<b>Что это:</b> язык всех экранов, кнопок и уведомлений бота. Ответ сохраняется — поменять можно в профиле в любой момент.",
  "lang.ru": "🇷🇺 Русский",
  "lang.en": "🇬🇧 English",

  // --- согласие с документами ---
  "consent.title": "📄 <b>Документы и согласие</b>",
  "consent.text":
    "<b>Что это:</b> чтобы пользоваться ботом, прими <a href=\"{license}\">лицензионное соглашение</a> " +
    "и <a href=\"{privacy}\">политику конфиденциальности</a>. Мы обрабатываем Telegram ID, данные " +
    "Google-аккаунта и подписки — только для работы сервиса.",
  "consent.note": "Нажимая «Принять», ты подтверждаешь согласие с документами.",
  "consent.accept": "✅ Принять",
  "consent.decline": "❌ Отклонить",
  "consent.change_lang": "🌐 Сменить язык",
  "consent.declined.title": "🚫 <b>Без согласия нельзя продолжить</b>",
  "consent.declined.text":
    "Бот не может работать без принятия <a href=\"{license}\">лицензионного соглашения</a> и " +
    "<a href=\"{privacy}\">политики конфиденциальности</a>. Прочитай их и прими — или смени язык.",
  "docs.title": "📄 <b>Документы</b>",
  "docs.intro":
    "<b>Что это:</b> условия использования бота и правила обработки данных. " +
    "Ничего лишнего — только то, с чем ты соглашаешься.",
  "docs.license": "📄 Лицензионное соглашение",
  "docs.privacy": "🔒 Политика конфиденциальности",

  // --- приветствие ---
  "welcome.title": "👋 <b>Добро пожаловать в NAUA Security Mirage!</b>",
  "welcome.quote":
    "<b>Что это такое?</b>\n\n" +
    "<b>Личный VPN-сервис: надежное шифрование интернет-соединения, безопасность в публичных сетях Wi-Fi и защита данных. Стабильное соединение на высокой скорости без задержек.</b>\n\n" +
    "<b>🇳🇱 Нидерланды и 🇹🇷 Турция в одной подписке. Протоколы XHTTP и Hysteria 2. Безлимитный трафик.\n" +
    "Одна подписка — и для бота, и для приложения.</b>",
  "welcome.hint":
    "🔐 Войди через Google — это привяжет подписку к этому Telegram. " +
    "Ссылку входа никому не пересылай.",
  "welcome.login": "🔐 Войти в Google-аккаунт",

  // --- главное меню ---
  "menu.greeting": "👋 <b>Привет{comma} {name}!</b> ✌️",
  "menu.greeting.plain": "👋 <b>Привет!</b> ✌️",
  "menu.body":
    "🔗 Два узла — Нидерланды и Турция (XHTTP и Hysteria 2). Одна подписка на бота и приложение.\n\n" +
    "🆔 Твой ID: <code>{id}</code>\n\n{status}",
  "menu.choose": "Выбери нужное действие:",
  "menu.status.none": "💳 Статус: 🔴 Нет подписки",
  "menu.status.active": "💳 Статус: 🟢 Активна — 🗓 <b>{rem}</b>",
  "menu.status.expired": "💳 Статус: 🔴 Истекла",
  "menu.connect": "🚀 Подключиться",
  "menu.profile": "👤 Мой кабинет",
  "menu.support": "🆘 Поддержка",
  "menu.referral": "🎁 Пригласить друзей",
  "menu.admin": "⚙️ Админка",
  "menu.docs": "📄 Документы",

  // --- профиль ---
  "profile.title": "👤 <b>Твой профиль{comma} {name}</b>",
  "profile.title.plain": "👤 <b>Твой профиль</b>",
  "profile.lead":
    "<blockquote><b>Что это:</b> твой статус, Google-аккаунт и срок подписки. " +
    "Здесь же вход и выход.</blockquote>",
  "profile.status.none": "⚡️ Подписки нет 🔴\n\n🗓 Оформи подписку — и откроются узлы.",
  "profile.status.active":
    "⚡️ Подписка активна до <b>{date}</b> (UTC) 🟢\n\n⏳ Осталось: <b>{days}</b> дн.",
  "profile.status.expired": "⚡️ Подписка истекла 🔴\n\n🗓 Продли — и вернёшь узлы.",
  "profile.account.linked": "🔐 Google-аккаунт: <b>{email}</b>",
  "profile.id": "🆔 Telegram ID: <code>{id}</code>",
  "profile.since": "📅 В боте с {date}",
  "profile.referrals": "🎁 Пригласил: {count} · заработано: {days} дн.",
  "profile.payments.title": "💳 Последние платежи:",
  "profile.payments.item": "• {date} — {amount}",
  "profile.payments.screen":
    "💳 <b>История платежей</b>\n\n" +
    "<blockquote><b>Что это:</b> все начисленные платежи — сколько и когда.</blockquote>",
  "profile.payments.button": "💳 История платежей",
  "profile.payments.empty": "💳 Платежей пока не было.",

  "time.now": "только что",
  "time.minutes": "{count} мин назад",
  "time.hours": "{count} ч назад",
  "time.days": "{count} дн. назад",
  "settings.title": "⚙️ <b>Настройки</b>",
  "settings.lead":
    "<blockquote>Здесь меняется язык бота. Смена доступна всегда — и здесь, " +
    "и на экране входа: нажал не туда — вернись и выбери язык.</blockquote>",
  "settings.language": "🌐 Сменить язык",
  "profile.refresh": "🔄 Обновить",
  "profile.account.unlinked": "🔐 Google-аккаунт <b>не привязан</b>",
  "profile.login": "🔐 Войти в Google-аккаунт",
  "profile.logout": "🚪 Выйти из Google-аккаунта",
  "profile.language": "🌐 Язык",
  "profile.language.change": "🌐 Сменить язык",

  // --- вход через Google ---
  "google.start":
    "🔐 <b>Вход через Google</b>\n\n" +
    "<blockquote><b>Что это:</b> вход через Google-аккаунт. Он связывает бота и приложение: один вход — и подписка с узлами везде.</blockquote>\n\n" +
    "Нажми «Войти в Google-аккаунт» — и подписка с узлами (Нидерланды и Турция) приедет сюда же.\n\n<i>Тем же аккаунтом потом войдёшь в приложение — всё подтянется само.</i>",
  "google.continue": "🔐 Войти в Google-аккаунт",
  "google.linked": "✅ <b>Готово!</b> Аккаунт {email} привязан.",
  "google.linked.app":
    "📱 Этим же аккаунтом войди в приложение — подписка и узлы подтянутся сами.",
  "google.error.title": "⚠️ <b>Вход через Google не удался</b>",
  "google.error.denied":
    "Ты не дал доступ к аккаунту Google. Нажми «Войти в Google-аккаунт» и разреши доступ.",
  "google.error.expired":
    "Ссылка для входа устарела — она живёт 10 минут. Нажми «Войти в Google-аккаунт», чтобы получить свежую.",
  "google.error.state":
    "Ссылка входа не подходит к этой кнопке. Начни вход заново.",
  "google.error.exchange":
    "Google не отдал код доступа. Попробуй ещё раз.",
  "google.error.no_code":
    "Google вернул пустую страницу. Попробуй войти ещё раз.",
  "google.error.internal":
    "Что-то пошло не так на нашей стороне. Попробуй ещё раз через минуту.",
  "google.error.default":
    "Не получилось войти через Google. Попробуй ещё раз.",
  "google.logout.question":
    "🚪 <b>Выйти из Google-аккаунта?</b>\n\nБот перестанет видеть твои узлы (Нидерланды и Турция) и подписку.\n\nСам Google-аккаунт не пострадает: в приложении этим же аккаунтом по-прежнему можно войти.",
  "google.logout.done.short": "🚪 <b>Готово.</b>\n\nАккаунт {email} отвязан от Telegram.",
  "google.logout.absent": "🚪 Google-аккаунт и так не был привязан.",
  "google.already": "🔐 Google-аккаунт и так не привязан.",

  // --- подключение ---
  "connect.title": "🔗 <b>Подключение</b>",
  "connect.need_login":
    "Сначала войди через Google — тогда подтянутся твои узлы (Нидерланды и Турция) и действующая подписка.",
  "connect.text":
    "<blockquote><b>Что это:</b> одна ссылка с твоими VPN-узлами. Открываешь её в Hiddify, Happ или в приложении — и трафик идёт через личный узел.\n\n" +
    "Сейчас в ссылке узлы с двух локаций: 🇳🇱 Нидерланды и 🇹🇷 Турция (протоколы XHTTP и Hysteria 2). " +
    "Подписка одна и та же для бота и приложения.</blockquote>",
  "connect.open": "🚀 Открыть подписку (Happ / Hiddify)",
  "connect.copy": "📋 Скопировать ссылку",
  "connect.hint":
    "Нажми «Открыть подписку» — откроется страница с кнопками приложений: Happ, Hiddify или v2rayTun. Выбираешь приложение — подписка добавляется сама.",

  // --- подписка и оплата ---
  "sub.title": "💳 <b>Подписка NAUA Security Mirage</b>",
  "sub.title_short": "💳 Подписка",
  "sub.intro":
    "<b>📦 Что входит:</b>\n\n" +
    "• 🇳🇱 Нидерланды и 🇹🇷 Турция — оба платных узла, протоколы XHTTP и Hysteria 2;\n" +
    "• ♾ безлимитный трафик и высокая стабильная скорость;\n" +
    "• 🔗 одна ссылка на узлы: работает в Hiddify, Happ и в приложении;\n" +
    "• 📱 подписка общая для бота и приложения — платишь один раз, пользуешься везде.",
  "sub.status.active":
    "✅ Подписка активна до <b>{date}</b>\n\n⏳ Осталось: <b>{days}</b> дн.",
  "sub.status.expired": "❌ Подписка истекла. Продли, чтобы вернуть узлы.",
  "sub.choose": "Выбери срок подписки:",
  "sub.choose.hint":
    "🔥 Годовой тариф выгоднее на {saving} %, чем месячный.",
  "sub.pay.hint":
    "Оплати по ссылке, потом вернись сюда и нажми «✅ Я оплатил» — бот проверит оплату и продлит подписку.",
  "sub.badge": "🔥 −{saving} %",
  "sub.saving": "−{saving} %",
  "sub.pay": "💳 Оплатить {period} — {amount} рублей",
  "sub.renew": "🔄 Продлить подписку",
  "sub.renewFor": "🔄 Продлить на {period} — {amount} рублей",
  "sub.check": "✅ Я оплатил",
  "sub.paid":
    "🎉 <b>Готово!</b>\n\nПодписка активна до <b>{date}</b>.\n\n" +
    "Если ты только что оплатил, а срок не изменился — подожди минуту и нажми «✅ Я оплатил» ещё раз.\n\n" +
    "🔗 Подписка с узлами — по кнопке ниже.",
  "sub.not_paid":
    "⏳ Пока не вижу оплату. Обычно YooMoney проводит платёж 1–2 минуты — попробуй ещё раз через минуту.",
  "sub.need_login":
    "Сначала войди через Google — к аккаунту привязывается подписка, и оплата её продлит.",
  "sub.pay_title": "💳 <b>Оплата подписки</b>",
  "sub.pay_body":
    "Перейди на оплату по ссылке. После оплаты вернись в бот и нажми «✅ Я оплатил» — подписка активируется автоматически.",
  "sub.faq":
    "<b>❓ Частые вопросы</b>\n\n" +
    "<b>Нужна ли отдельная подписка на приложение?</b>\nНет — одна подписка на оба продукта.\n\n" +
    "<b>Что будет, если пропустить оплату?</b>\nУзлы просто перестанут обновляться до следующей оплаты.\n\n" +
    "<b>Можно ли вернуть деньги?</b>\nНапиши в поддержку — разберём.",
  "sub.granted": "Вам выдана подписка на <b>{days}</b>!",
  "sub.revoked":
    "❌ <b>Подписка отозвана</b>\n\nДоступ к платным узлам закрыт. Если это ошибка — напиши в поддержку.",
  "sub.shortened":
    "✂️ <b>Срок подписки изменён</b>\n\nТеперь она действует до <b>{date}</b>.",
  "sub.support": "💬 Поддержка NAUA",
  "sub.app": "📱 Приложение NAUA Security Mirage",

  // --- поддержка ---
  "support.title": "🆘 <b>Поддержка</b>",
  "support.chat": "💬 Чат поддержки",
  "support.channel": "📢 Канал проекта",
  "support.docs": "📖 База знаний",
  "thanks.button": "🎁 Поблагодарить разработчика",
  "thanks.title": "🎁 <b>Поблагодарить разработчика</b>",
  "thanks.subtitle": "3 способа",
  "thanks.hint":
    "<blockquote>Выбери удобный способ. CloudTips — привычные донаты в рублях, с карты или через СБП, деньги сразу на карту. Tribute — сервис от Telegram: карта из любой страны, можно криптой, оплата прямо в мессенджере.</blockquote>",
  "thanks.cloudtips": "💳 CloudTips — карта, СБП, Т-Pay или Яндекс Пэй. Деньги сразу приходят на карту.",
  "thanks.tributeTg":
    "🎁 Tribute в Telegram — донат прямо в мессенджере, без сайта. Карта из любой страны, можно USDT, BTC, TON.",
  "thanks.tributeWeb":
    "🌐 Tribute в браузере — тот же Tribute, но через сайт. Подойдёт, если нет Telegram или ссылка не открывается.",
  "support.text":
    "<blockquote><b>Служба поддержки NAUA Security Mirage</b>\n\n" +
    "Возникли проблемы или есть вопрос по настройке? Напиши прямо сюда — поддержка ответит в этом же диалоге. К сообщению можно прикрепить скриншот или видео.</blockquote>",
  "support.cooldown":
    "⏳ Ты только что отправил обращение. Дай минуту — и можно будет написать ещё.",
  "support.stats": "📩 Заявок: <b>{total}</b> · в обработке: <b>{open}</b>",
  "support.list.title": "Твои заявки:",
  "support.list.item": "• #{id} от {date} — {status}",
  "support.status.open": "🟡 в обработке",
  "support.status.done": "✅ закрыто",
  "support.open": "💬 Ответить админу",
  "support.reply.head": "💬 <b>Ответ поддержки:</b>",
  "support.reply.answer": "💬 Ответить",
  "support.reply.close": "✅ Закрыть",
  "support.reply.closed":
    "✅ <b>Заявка #{id} закрыта.</b>\n\nЕсли понадобится помощь — просто напиши снова.",
  "support.thread": "📩 <b>Заявка #{id}</b>",
  "support.replied": "✅ Ответ отправлен — админ увидит его в панели.",
  "support.attachment": "📎 Прикреплено: {what}",
  "support.attachment.photo": "скрин",
  "support.attachment.video": "видео",
  "support.attachment.document": "файл",
  "support.you": "Ты:",
  "support.admin": "Админ:",
  "support.showFile": "📎 Показать файл",
  "sub.pay.unavailable":
    "⚠️ <b>Оплата временно недоступна</b>\n\nНапиши в поддержку — поможем оформить подписку.",
  "support.draft.title": "📩 <b>Подтвердить отправку:</b>",
  "support.draft": "Обращение уйдёт в поддержку.",
  "support.reply.hint": "💬 Напиши ответ — он уйдёт прямо в заявку.",
  "support.sent":
    "✅ <b>Обращение отправлено</b>\n\nСтатус: 🟡 В обработке. Админ ответит здесь же, в этом чате.",
  "support.confirm": "✅ Отправить",
  "support.cancel": "◀️ Отмена",

  // --- рефералка ---
  "referral.title": "🎁 <b>Пригласить друзей</b>",
  "referral.text":
    "<blockquote><b>Что это:</b> приводишь друга по своей ссылке. " +
    "Друг получает <b>1 день</b> пробника, а ты — <b>+2 дня</b> подписки после " +
    "его первой оплаты. Каждые 5 оплативших — ещё <b>+10 дней</b>.</blockquote>\n\n" +
    "Твоя ссылка:\n<code>{link}</code>",
  "referral.stats": "🎁 Пригласил: <b>{count}</b> · заработано: <b>{days}</b> дн.",
  "referral.progress": "🔥 Ещё <b>{left}</b> до +10 дней",
  "referral.list.title": "👥 Приглашённые:",
  "referral.list.item": "• {name} — {status}",
  "referral.list.paid": "оплатил ✅",
  "referral.list.waiting": "ждёт оплаты",
  "referral.list.empty": "Пока никто не пришёл по ссылке.",
  "referral.copy": "📋 Скопировать ссылку",
  "referral.invite": "👥 Пригласить друга",
  "referral.share": "🚀 Личный VPN для надежного шифрования и безопасности в сети. Забирай!",
  "referral.joined":
    "🎉 Новый пользователь {name} пришёл по вашей ссылке!\n\nБонус +2 дня начислим после его первой оплаты.",
  "referral.joined.you":
    "🎉 Ты пришёл по ссылке! Тебе <b>1 день</b> пробника — попробуй узлы. " +
    "А пригласившему добавим 2 дня после твоей первой оплаты.",

  // --- подписи нижнего (текстового) меню ---
  "textmenu.profile": "👤 Мой кабинет",
  "textmenu.connect": "🚀 Подключиться",
  "textmenu.sub": "💳 Подписка",
  "textmenu.support": "🆘 Поддержка",
  "textmenu.referral": "🤝 Пригласить друзей",
  "textmenu.status": "⚡ Статус серверов",
  "textmenu.guide": "📖 Инструкция",
  "textmenu.home": "🏠 Главное меню",
  "menu.keyboard_hint": "⌨️ Меню быстрого доступа закреплено внизу чата.",

  // --- общее ---
  "common.days": "{days} дн.",
  "common.back": "◀️ Назад",
  "common.cancel": "❌ Действие отменено.",
  "common.hint": "👇 Используй кнопки меню ниже.",

  // --- статус серверов ---
  "connect.server_status": "⚡ Статус серверов",
  "status.title": "⚡ <b>Статус узлов</b>",
  "status.intro": "<blockquote><b>Что это:</b> статус работы выделенных узлов и протоколов шифрования.</blockquote>",
  "status.body":
    "🇳🇱 <b>Нидерланды</b> — 🟢 Онлайн\n<i>Протоколы: XHTTP (Reality), Hysteria 2</i>\n\n" +
    "🇹🇷 <b>Турция</b> — 🟢 Онлайн\n<i>Протоколы: XHTTP (Reality), Hysteria 2</i>\n\n" +
    "✅ Выделенные узлы работают в штатном режиме. Доступны оба протокола.",
  "status.refresh": "🔄 Обновить статус",

  // --- инструкции по подключению ---
  "connect.setup_guide": "📖 Инструкция по настройке",
  "guide.title": "📖 <b>Инструкция по подключению</b>",
  "guide.intro": "<blockquote><b>Что это:</b> как быстро настроить подключение на твоем устройстве.</blockquote>",
  "guide.body":
    "<b>1. Happ (Android и iOS — рекомендуем)</b>:\n" +
    "Нажми «🚀 Открыть подписку» в разделе «Подключиться», выбери Happ — подписка добавится в один клик.\n\n" +
    "<b>2. Hiddify (Windows PC / Mac / Android / iOS)</b>:\n" +
    "Скопируй ссылку по кнопке «📋 Скопировать ссылку», открой Hiddify, нажми ➕ (Новый профиль) ➔ «Импорт из буфера».\n\n" +
    "<b>3. Приложение NAUA Security Mirage (Android и ПК)</b>:\n" +
    "Войди через тот же Google-аккаунт в приложении — подписка и узлы подтянутся автоматически!",
  "guide.open_connect": "🚀 Перейти к подключению",

  // --- qr-код ---
  "connect.qr": "📷 Показать QR-код",
  "connect.qr_title": "📷 <b>QR-код для подключения</b>",
  "connect.qr_hint": "Отсканируй этот код камерой телефона или в приложении Happ / Hiddify:",
  "connect.qr_open_browser": "🌐 Открыть изображение",
  "connect.qr_happ_title": "📱 <b>QR-код для приложения Happ</b>",
  "connect.qr_happ_desc": "Отсканируйте этот QR-код <b>внутри приложения Happ</b> (кнопка ➕ ➔ <b>Сканировать QR-код</b>).\n\nПодписка добавится в приложение автоматически!",
  "connect.qr_web_title": "🌐 <b>QR-код для камеры смартфона</b>",
  "connect.qr_web_desc": "Наведите <b>обычную камеру телефона</b> на этот QR-код.\n\nОткроется страница подключения в браузере, где в 1 клик можно запустить Happ или Hiddify (без скачивания файлов).",
  "connect.qr_switch_web": "🌐 QR для обычной камеры",
  "connect.qr_switch_happ": "📱 QR для сканера Happ",
  "connect.qr_universal_hint":
    "📱 <b>Для Happ и Hiddify:</b> отсканируйте этот код внутри приложения (кнопка ➕ ➔ <b>Сканировать QR</b>).\n\n" +
    "🌐 <b>Для обычной камеры телефона:</b> наведите камеру на код — откроется страница быстрого подключения (без скачивания файлов).",

  // --- напоминания cron ---
  "cron.expiry_3d": "⏳ <b>Подписка истекает через 3 дня</b>\n\nНе забудь продлить, чтобы не потерять доступ к выделенным узлам!",
  "cron.expiry_1d": "⚠️ <b>Подписка истекает завтра</b>\n\nПродли подписку сейчас, чтобы оставаться на связи!",
  "cron.expiry_0d": "❗ <b>Подписка истекает сегодня!</b>\n\nПродли подписку сейчас, чтобы не потерять доступ к сети.",
  "cron.renew_button": "💳 Продлить подписку",
};

const en: Record<string, string> = {
  // --- language ---
  "lang.title": "🌐 <b>Bot language</b>\n\n<b>What is this:</b> the language of every screen, button and notification. The choice sticks — you can change it later in your profile.",
  "lang.ru": "🇷🇺 Русский",
  "lang.en": "🇬🇧 English",

  // --- documents and consent ---
  "consent.title": "📄 <b>Documents and consent</b>",
  "consent.text":
    "<b>What is this:</b> to use the bot, accept the <a href=\"{license}\">license agreement</a> " +
    "and the <a href=\"{privacy}\">privacy policy</a>. We process your Telegram ID, Google account " +
    "and subscription data only to run the service.",
  "consent.note": "By tapping “Accept”, you confirm that you agree to the documents.",
  "consent.accept": "✅ Accept",
  "consent.decline": "❌ Decline",
  "consent.change_lang": "🌐 Change language",
  "consent.declined.title": "🚫 <b>You cannot continue without consent</b>",
  "consent.declined.text":
    "The bot cannot work without accepting the <a href=\"{license}\">license agreement</a> and " +
    "the <a href=\"{privacy}\">privacy policy</a>. Read them and accept — or change the language.",
  "docs.title": "📄 <b>Documents</b>",
  "docs.intro":
    "<b>What is this:</b> the terms of using the bot and the rules for processing data. " +
    "Nothing extra — only what you agree to.",
  "docs.license": "📄 License agreement",
  "docs.privacy": "🔒 Privacy policy",

  // --- welcome ---
  "welcome.title": "👋 <b>Welcome to NAUA Security Mirage!</b>",
  "welcome.quote":
    "<b>What is this?</b>\n\n" +
    "<b>Personal VPN service: strong traffic encryption, personal data protection and cybersecurity on public Wi-Fi networks. Stable high-speed connection without delays.</b>\n\n" +
    "<b>🇳🇱 Netherlands and 🇹🇷 Turkey in one subscription. XHTTP and Hysteria 2 protocols. Unlimited traffic.\n" +
    "One subscription — for the bot and for the app.</b>",
  "welcome.hint":
    "🔐 Sign in with Google — this links the subscription to this Telegram. " +
    "Never forward the sign-in link.",
  "welcome.login": "🔐 Sign in with Google",

  // --- main menu ---
  "menu.greeting": "👋 <b>Hi{comma} {name}!</b> ✌️",
  "menu.greeting.plain": "👋 <b>Hi!</b> ✌️",
  "menu.body":
    "🔗 Two nodes — Netherlands and Turkey (XHTTP and Hysteria 2). One subscription for the bot and the app.\n\n" +
    "🆔 Your ID: <code>{id}</code>\n\n{status}",
  "menu.choose": "Pick what you need:",
  "menu.status.none": "💳 Status: 🔴 No subscription",
  "menu.status.active": "💳 Status: 🟢 Active — until <b>{rem}</b>",
  "menu.status.expired": "💳 Status: 🔴 Expired",
  "menu.connect": "🚀 Connect",
  "menu.profile": "👤 My Account",
  "menu.support": "🆘 Support",
  "menu.referral": "🎁 Invite friends",
  "menu.admin": "⚙️ Admin",
  "menu.docs": "📄 Documents",

  // --- profile ---
  "profile.title": "👤 <b>Your profile{comma} {name}</b>",
  "profile.title.plain": "👤 <b>Your profile</b>",
  "profile.lead":
    "<blockquote><b>What is this:</b> your status, Google account and the subscription expiry. " +
    "Sign-in and sign-out live here too.</blockquote>",
  "profile.status.none": "⚡️ No subscription 🔴\n\n🗓 Get one and the nodes open up.",
  "profile.status.active":
    "⚡️ Subscription is active until <b>{date}</b> (UTC) 🟢\n\n⏳ Left: <b>{days}</b> days.",
  "profile.status.expired": "⚡️ Subscription expired 🔴\n\n🗓 Renew it and the nodes are back.",
  "profile.account.linked": "🔐 Google account: <b>{email}</b>",
  "profile.id": "🆔 Telegram ID: <code>{id}</code>",
  "profile.since": "📅 With us since {date}",
  "profile.referrals": "🎁 Invited: {count} · earned: {days} days",
  "profile.payments.title": "💳 Recent payments:",
  "profile.payments.item": "• {date} — {amount}",
  "profile.payments.screen":
    "💳 <b>Payment history</b>\n\n" +
    "<blockquote><b>What is this:</b> every credited payment — how much and when.</blockquote>",
  "profile.payments.button": "💳 Payment history",
  "profile.payments.empty": "💳 No payments yet.",

  "time.now": "just now",
  "time.minutes": "{count} min ago",
  "time.hours": "{count} h ago",
  "time.days": "{count} d ago",
  "settings.title": "⚙️ <b>Settings</b>",
  "settings.lead":
    "<blockquote>The bot language is changed here. It is always available — " +
    "here and on the sign-in screen: pressed the wrong thing? Come back and " +
    "pick the language.</blockquote>",
  "settings.language": "🌐 Change language",
  "profile.refresh": "🔄 Refresh",
  "profile.account.unlinked": "🔐 Google account <b>not linked</b>",
  "profile.login": "🔐 Sign in with Google",
  "profile.logout": "🚪 Sign out of Google account",
  "profile.language": "🌐 Language",
  "profile.language.change": "🌐 Change language",

  // --- Google sign-in ---
  "google.start":
    "🔐 <b>Sign in with Google</b>\n\n" +
    "<blockquote><b>What is this:</b> signing in with a Google account. It links the bot and the app: one sign-in — and your subscription with its nodes everywhere.</blockquote>\n\n" +
    "Tap “Sign in with Google” and your subscription with its nodes (Netherlands and Turkey) will show up right here.\n\n<i>Later you sign in to the app with the same account — everything pulls in on its own.</i>",
  "google.continue": "🔐 Sign in with Google",
  "google.linked": "✅ <b>All set!</b> Account {email} is linked.",
  "google.linked.app":
    "📱 Sign in to the app with the same account — your subscription and nodes will be there.",
  "google.error.title": "⚠️ <b>Google sign-in failed</b>",
  "google.error.denied":
    "You did not grant access to your Google account. Tap “Sign in with Google” and allow access.",
  "google.error.expired":
    "The sign-in link has expired — it lives for 10 minutes. Tap “Sign in with Google” for a fresh one.",
  "google.error.state":
    "This sign-in link does not match the button. Start the sign-in again.",
  "google.error.exchange":
    "Google did not return an access code. Please try again.",
  "google.error.no_code":
    "Google returned an empty page. Please try signing in again.",
  "google.error.internal":
    "Something went wrong on our side. Please try again in a minute.",
  "google.error.default":
    "We could not sign you in with Google. Please try again.",
  "google.logout.question":
    "🚪 <b>Sign out of the Google account?</b>\n\nThe bot will no longer see your nodes (Netherlands and Turkey) or your subscription.\n\nYour Google account itself is not affected: you can still sign in to the app with it.",
  "google.logout.done.short": "🚪 <b>All set.</b>\n\nAccount {email} is unlinked from Telegram.",
  "google.logout.absent": "🚪 No Google account was linked in the first place.",
  "google.already": "🔐 No Google account is linked.",

  // --- connect ---
  "connect.title": "🔗 <b>Connect</b>",
  "connect.need_login":
    "Sign in with Google first — that pulls in your nodes (Netherlands and Turkey) and your active subscription.",
  "connect.text":
    "<blockquote><b>What is this:</b> a single link with your VPN nodes. Open it in Hiddify, Happ or the app and your traffic goes through a private node.\n\n" +
    "The link holds nodes from two locations: 🇳🇱 Netherlands and 🇹🇷 Turkey (XHTTP and Hysteria 2 protocols). " +
    "The bot and the app share the same subscription.</blockquote>",
  "connect.open": "🚀 Open subscription (Happ / Hiddify)",
  "connect.copy": "📋 Copy the link",
  "connect.hint":
    "Tap “Open subscription” — a page opens with app buttons: Happ, Hiddify or v2rayTun. Pick the app and the subscription is added automatically.",

  // --- subscription and payment ---
  "sub.title": "💳 <b>NAUA Security Mirage subscription</b>",
  "sub.title_short": "💳 Subscription",
  "sub.intro":
    "<b>What you get:</b>\n\n" +
    "• 🇳🇱 Netherlands and 🇹🇷 Turkey — both paid nodes (XHTTP and Hysteria 2);\n" +
    "• ♾ unlimited traffic and high-speed stable connection;\n" +
    "• 🔗 a single node link that works in Hiddify, Happ and the app;\n" +
    "• 📱 one subscription for the bot and the app — pay once, use it everywhere.",
  "sub.status.active":
    "✅ Subscription is active until <b>{date}</b>\n\n⏳ Left: <b>{days}</b> days.",
  "sub.status.expired": "❌ Your subscription has expired. Renew to get the nodes back.",
  "sub.choose": "Choose the subscription period:",
  "sub.choose.hint":
    "🔥 The yearly plan is {saving} % cheaper per day than the monthly one.",
  "sub.pay.hint":
    "Pay via the link, then come back here and tap “✅ I paid” — the bot checks the payment and extends your subscription.",
  "sub.badge": "🔥 −{saving} %",
  "sub.saving": "−{saving} %",
  "sub.pay": "💳 Pay {period} — {amount} rubles",
  "sub.renew": "🔄 Renew subscription",
  "sub.renewFor": "🔄 Renew for {period} — {amount} rubles",
  "sub.check": "✅ I paid",
  "sub.paid":
    "🎉 <b>All set!</b>\n\nYour subscription is active until <b>{date}</b>.\n\n" +
    "If you have just paid and the date has not changed yet, give it a minute and tap “✅ I paid” again.\n\n" +
    "🔗 Your node subscription — use the button below.",
  "sub.not_paid":
    "⏳ I don’t see the payment yet. YooMoney usually takes 1–2 minutes — try again in a minute.",
  "sub.need_login":
    "Sign in with Google first — the subscription is attached to that account, and the payment renews it.",
  "sub.pay_title": "💳 <b>Subscription payment</b>",
  "sub.pay_body":
    "Follow the payment link. When you are done, come back to the bot and tap “✅ I paid” — the subscription activates automatically.",
  "sub.faq":
    "<b>❓ Questions</b>\n\n" +
    "<b>Do I need a separate app subscription?</b>\nNo — one subscription covers both products.\n\n" +
    "<b>What if I skip a payment?</b>\nThe nodes simply stop renewing until you pay again.\n\n" +
    "<b>Can I get a refund?</b>\nAsk support — we will sort it out.",
  "sub.granted": "A subscription of <b>{days}</b> was granted to you!",
  "sub.revoked":
    "❌ <b>Subscription revoked</b>\n\nThe paid nodes are no longer available. If this is a mistake, contact support.",
  "sub.shortened":
    "✂️ <b>Subscription period changed</b>\n\nIt is now valid until <b>{date}</b>.",
  "sub.support": "💬 NAUA support",
  "sub.app": "📱 NAUA Security Mirage app",

  // --- support ---
  "support.title": "🆘 <b>Support</b>",
  "support.chat": "💬 Support chat",
  "support.channel": "📢 Channel",
  "support.docs": "📖 Knowledge base",
  "thanks.button": "🎁 Thank the developer",
  "thanks.title": "🎁 <b>Thank the developer</b>",
  "thanks.subtitle": "3 ways",
  "thanks.hint":
    "<blockquote>Pick the way that suits you. CloudTips — familiar donations in rubles, by card or SBP, the money goes straight to the card. Tribute — a service by Telegram: a card from any country, crypto works too, paid right inside the messenger.</blockquote>",
  "thanks.cloudtips": "💳 CloudTips — card, SBP, T-Pay or Yandex Pay. The money goes straight to the card.",
  "thanks.tributeTg":
    "🎁 Tribute in Telegram — donate right in the messenger, no website. A card from any country, USDT, BTC and TON work too.",
  "thanks.tributeWeb":
    "🌐 Tribute in a browser — the same Tribute, but through the website. Handy if you have no Telegram or the link does not open.",
  "support.text":
    "<blockquote><b>NAUA Security Mirage Support</b>\n\n" +
    "Have questions or need help with setup? Write right here — support will reply in this chat. You can attach a screenshot or video.</blockquote>",
  "support.cooldown":
    "⏳ You just sent a message. Give it a minute — then you can write again.",
  "support.stats": "📩 Tickets: <b>{total}</b> · in progress: <b>{open}</b>",
  "support.list.title": "Your tickets:",
  "support.list.item": "• #{id} from {date} — {status}",
  "support.status.open": "🟡 in progress",
  "support.status.done": "✅ closed",
  "support.open": "💬 Reply to support",
  "support.reply.head": "💬 <b>Support reply:</b>",
  "support.reply.answer": "💬 Reply",
  "support.reply.close": "✅ Close",
  "support.reply.closed":
    "✅ <b>Ticket #{id} is closed.</b>\n\nIf you need help again, just write to me.",
  "support.thread": "📩 <b>Ticket #{id}</b>",
  "support.replied": "✅ Reply sent — support will see it in the panel.",
  "support.attachment": "📎 Attached: {what}",
  "support.attachment.photo": "screenshot",
  "support.attachment.video": "video",
  "support.attachment.document": "file",
  "support.you": "You:",
  "support.admin": "Support:",
  "support.showFile": "📎 Show the file",
  "sub.pay.unavailable":
    "⚠️ <b>Payment is temporarily unavailable</b>\n\nWrite to support — we will help you subscribe.",
  "support.draft.title": "📩 <b>Confirm sending:</b>",
  "support.draft": "Your message goes to support.",
  "support.reply.hint": "💬 Write a reply — it goes straight into the ticket.",
  "support.sent":
    "✅ <b>Message sent</b>\n\nStatus: 🟡 Being handled. An admin will reply here, in this chat.",
  "support.confirm": "✅ Send",
  "support.cancel": "◀️ Cancel",

  // --- referral ---
  "referral.title": "🎁 <b>Invite friends</b>",
  "referral.text":
    "<blockquote><b>What is this:</b> you bring a friend through your link. " +
    "Your friend gets a <b>1 day</b> trial, and you get <b>+2 days</b> of " +
    "subscription after their first payment. Every 5 who paid — another " +
    "<b>+10 days</b>.</blockquote>\n\n" +
    "Your link:\n<code>{link}</code>",
  "referral.stats": "🎁 Invited: <b>{count}</b> · earned: <b>{days}</b> days",
  "referral.progress": "🔥 <b>{left}</b> more until +10 days",
  "referral.list.title": "👥 Invited:",
  "referral.list.item": "• {name} — {status}",
  "referral.list.paid": "paid ✅",
  "referral.list.waiting": "waiting for payment",
  "referral.list.empty": "Nobody has joined through your link yet.",
  "referral.copy": "📋 Copy the link",
  "referral.invite": "👥 Invite a friend",
  "referral.share": "🚀 Personal VPN for secure encryption and privacy. Join in!",
  "referral.joined":
    "🎉 A new user — {name} — came through your link!\n\nThe +2 day bonus is credited after their first payment.",
  "referral.joined.you":
    "🎉 You came through a link! You get a <b>1 day</b> trial — try the nodes. " +
    "And your inviter gets 2 days after your first payment.",

  // --- reply-menu labels ---
  "textmenu.profile": "👤 My Account",
  "textmenu.connect": "🚀 Connect",
  "textmenu.sub": "💳 Subscription",
  "textmenu.support": "🆘 Support",
  "textmenu.referral": "🎁 Invite friends",
  "textmenu.status": "⚡ Server Status",
  "textmenu.guide": "📖 Setup Guide",
  "textmenu.home": "🏠 Main menu",
  "menu.keyboard_hint": "⌨️ Quick access keyboard pinned below.",

  // --- common ---
  "common.days": "{days} days",
  "common.back": "◀️ Back",
  "common.cancel": "❌ Action cancelled.",
  "common.hint": "👇 Use the buttons below.",

  // --- server status ---
  "connect.server_status": "⚡ Server Status",
  "status.title": "⚡ <b>Node Status</b>",
  "status.intro": "<blockquote><b>What is this:</b> live health check of dedicated nodes and encryption protocols.</blockquote>",
  "status.body":
    "🇳🇱 <b>Netherlands</b> — 🟢 Online\n<i>Protocols: XHTTP (Reality), Hysteria 2</i>\n\n" +
    "🇹🇷 <b>Turkey</b> — 🟢 Online\n<i>Protocols: XHTTP (Reality), Hysteria 2</i>\n\n" +
    "✅ Dedicated nodes are operating normally. Both protocols are active.",
  "status.refresh": "🔄 Refresh Status",

  // --- connection guide ---
  "connect.setup_guide": "📖 Setup Guide",
  "guide.title": "📖 <b>Connection Guide</b>",
  "guide.intro": "<blockquote><b>What is this:</b> how to quickly configure the VPN on your device.</blockquote>",
  "guide.body":
    "<b>1. Happ (Android & iOS — recommended)</b>:\n" +
    "Tap “🚀 Open subscription” in Connect section, choose Happ — subscription imports in one tap.\n\n" +
    "<b>2. Hiddify (Windows PC / Mac / Android / iOS)</b>:\n" +
    "Copy link with “📋 Copy the link”, open Hiddify, tap ➕ (New profile) ➔ “Import from clipboard”.\n\n" +
    "<b>3. NAUA Security Mirage App (Android & PC)</b>:\n" +
    "Sign in with the same Google account in the app — your subscription and nodes load automatically!",
  "guide.open_connect": "🚀 Go to Connect",

  // --- qr code ---
  "connect.qr": "📷 Show QR Code",
  "connect.qr_title": "📷 <b>Connection QR Code</b>",
  "connect.qr_hint": "Scan this code with your phone camera or inside Happ / Hiddify:",
  "connect.qr_open_browser": "🌐 Open Image",
  "connect.qr_happ_title": "📱 <b>QR code for Happ App</b>",
  "connect.qr_happ_desc": "Scan this QR code <b>inside the Happ app</b> (➕ button ➔ <b>Scan QR code</b>).\n\nYour subscription will be added automatically!",
  "connect.qr_web_title": "🌐 <b>QR code for Phone Camera</b>",
  "connect.qr_web_desc": "Point your <b>regular phone camera</b> at this QR code.\n\nA connect page will open in your browser with one-tap import into Happ or Hiddify (no file download).",
  "connect.qr_switch_web": "🌐 QR for phone camera",
  "connect.qr_switch_happ": "📱 QR for Happ scanner",
  "connect.qr_universal_hint":
    "📱 <b>For Happ & Hiddify:</b> scan this code inside the app (➕ button ➔ <b>Scan QR code</b>).\n\n" +
    "🌐 <b>For Phone Camera:</b> point your phone camera at the code — the quick connect page will open (no file downloads).",

  // --- cron reminders ---
  "cron.expiry_3d": "⏳ <b>Subscription expires in 3 days</b>\n\nDo not forget to renew to keep uninterrupted access to dedicated nodes!",
  "cron.expiry_1d": "⚠️ <b>Subscription expires tomorrow</b>\n\nRenew your subscription now to stay connected!",
  "cron.expiry_0d": "❗ <b>Subscription expires today!</b>\n\nRenew your subscription now to avoid disconnection.",
  "cron.renew_button": "💳 Renew Subscription",
};

const TABLES: Record<Lang, Record<string, string>> = { ru, en };

// t — перевод строки с подстановкой {placeholders}.
// Если ключа нет в выбранном языке — берётся русский, иначе сам ключ.
export function t(lang: Lang | null | undefined, key: string, vars?: Vars): string {
  const table = TABLES[lang ?? "ru"] ?? ru;
  let text = table[key] ?? ru[key] ?? key;
  if (vars) {
    for (const [name, value] of Object.entries(vars)) {
      text = text.split(`{${name}}`).join(String(value));
    }
  }
  return text;
}

// hasKey — есть ли ключ в словаре конкретного языка.
// Нужен для проверок: ключ должен быть и по-русски, и по-английски,
// иначе пользователь другого языка увидит сырой ключ.
export function hasKey(lang: Lang, key: string): boolean {
  return Object.prototype.hasOwnProperty.call(TABLES[lang], key);
}

// normalizeLang — приводит произвольное значение к поддерживаемому языку.
export function normalizeLang(value: string | null | undefined): Lang | null {
  if (!value) return null;
  const lower = value.toLowerCase();
  if (lower === "ru" || lower.startsWith("ru-")) return "ru";
  if (lower === "en" || lower.startsWith("en-")) return "en";
  return null;
}
