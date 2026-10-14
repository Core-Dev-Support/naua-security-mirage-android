# Contributing

Спасибо, что хочешь помочь NAUA Security Mirage! Проект станет лучше именно благодаря тебе.

## ⭐ Быстрые способы помочь (без кода)

- Поставь ⭐ репозиторию.
- Сообщи о баге через [Bug Report](https://github.com/Core-Dev-Support/naua-security-mirage-android/issues/new/choose).
- Предложи фичу через [Feature Request](https://github.com/Core-Dev-Support/naua-security-mirage-android/issues/new/choose).
- Расскажи о проекте друзьям, подпишись на [Telegram-канал](https://t.me/News_And_Updates_Apps).

## 🔧 Как контрибьютить в код

1. **Fork** репозитория и создай ветку от `main`:
   ```bash
   git checkout -b feature/my-cool-thing
   ```
2. Собери и проверь локально:
   ```bash
   ./gradlew assembleDebug
   ./gradlew testDebugUnitTest
   ```
3. Правила кода:
   - Kotlin, осмысленные имена, без секретов в коде (CI `secret-scan` это проверяет и уронит PR).
   - Не коммить `*.jks`, `key.properties`, `google-services.json`, `local.properties` — они уже в `.gitignore`.
   - Маленькие focused-коммиты на русском или английском, внятные сообщения.
4. Открой **Pull Request** в `main`:
   - что сделано и зачем;
   - как проверял (команды, устройство/эмулятор, скриншоты UI-изменений);
   - связанные Issues (`Closes #123`).

## 🛡 Безопасность

Нашёл уязвимость? **Не открывай публичный Issue/PR.** Напиши по [SECURITY.md](SECURITY.md).

## 📜 Лицензия и бренд

Контрибьютя, ты соглашаешься, что твой код попадает под [LICENSE](LICENSE) проекта.
Логотипы и названия «NAUA» / «Mirage» использовать в форках без письменного согласия нельзя (см. LICENSE, п. 2).

## 💬 Связь

- Email: coredevsupport@gmail.com
- Telegram: https://t.me/News_And_Updates_Apps
