# deploy-bot.ps1 — деплой Telegram-бота на Supabase Edge Functions.
#
# Бот живёт в проекте Supabase вместе с приложением NAUA Security Mirage,
# поэтому подписка, Google-аккаунт и платные узлы общие.
#
# Что делает скрипт:
#   1) проверяет, что CLI установлен и вы авторизованы;
#   2) применяет миграции (Management API — пароль от БД не нужен);
#   3) ставит секреты функций (supabase secrets set);
#   4) деплоит функции telegram-auth, telegram-subscription, telegram-bot;
#   5) регистрирует вебхук Telegram и проверяет, что он встал.
#
# Запуск из корня репозитория:
#   powershell -ExecutionPolicy Bypass -File .\scripts\deploy-bot.ps1
#
# Авторизация: переменная окружения SUPABASE_ACCESS_TOKEN
# (Personal Access Token: https://supabase.com/dashboard/account/tokens)
# или выполненный ранее `npx supabase login`.
#
# Секреты берутся из supabase\.temp\bot-secrets.local (создаётся скриптом
# подготовки). Отсутствующие значения запрашиваются в консоли.

param(
    [string]$ProjectRef = "",
    [switch]$SkipMigrations,
    [switch]$SkipSecrets,
    [switch]$SkipWebhook
)

$ErrorActionPreference = "Stop"
Set-Location -LiteralPath (Split-Path -Parent $PSScriptRoot)

function Step([string]$title) {
    Write-Host ""
    Write-Host ("=" * 64) -ForegroundColor Cyan
    Write-Host $title -ForegroundColor Cyan
    Write-Host ("=" * 64) -ForegroundColor Cyan
}

function Read-Secret([string]$Name, [string]$Prompt) {
    $value = [Environment]::GetEnvironmentVariable($Name)
    if ([string]::IsNullOrWhiteSpace($value)) {
        $secure = Read-Host -Prompt "$Prompt" -AsSecureString
        $value = [System.Runtime.InteropServices.Marshal]::PtrToStringAuto(
            [System.Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure))
    }
    if ([string]::IsNullOrWhiteSpace($value)) {
        throw "Значение $Name не задано — деплой прерван."
    }
    return $value
}

# Секреты из локального файла (если есть) + из окружения.
$secretFile = Join-Path $PSScriptRoot "..\supabase\.temp\bot-secrets.local"
$fileSecrets = @{}
if (Test-Path $secretFile) {
    foreach ($line in Get-Content $secretFile) {
        if ($line -match '^([A-Z_]+)=(.*)$') { $fileSecrets[$Matches[1]] = $Matches[2] }
    }
}

Step ">>> 1/5: Проверка CLI и авторизации"

if (-not $ProjectRef) {
    $refFile = Join-Path $PSScriptRoot "..\supabase\.temp\project-ref"
    if (Test-Path $refFile) { $ProjectRef = (Get-Content $refFile -Raw).Trim() }
}
if (-not $ProjectRef) {
    $ProjectRef = Read-Secret "SUPABASE_PROJECT_REF" "Project ref Supabase"
}
$env:SUPABASE_PROJECT_REF = $ProjectRef
Write-Host "Project ref: $ProjectRef" -ForegroundColor DarkGray

$probe = npx.cmd -y supabase projects list --output json 2>&1
if ($LASTEXITCODE -ne 0) {
    Write-Host $probe -ForegroundColor Red
    Write-Host "Нужен доступ к Supabase: задай SUPABASE_ACCESS_TOKEN или выполни 'npx supabase login'." -ForegroundColor Red
    exit 1
}

Step ">>> 2/5: Миграции базы"
if ($SkipMigrations) {
    Write-Host "skipped" -ForegroundColor Yellow
} else {
    $migration = Join-Path $PSScriptRoot "..\supabase\migrations\20261020000000_telegram_bot.sql"
    if (Test-Path $migration) {
        python (Join-Path $PSScriptRoot "apply-migration.py") $migration
        if ($LASTEXITCODE -ne 0) {
            Write-Host "МИГРАЦИЯ НЕ ПРИМЕНЕНА" -ForegroundColor Red
            exit 1
        }
    } else {
        Write-Host "Файл миграции не найден, пропускаю" -ForegroundColor Yellow
    }
}

Step ">>> 3/5: Секреты функций"
if ($SkipSecrets) {
    Write-Host "skipped" -ForegroundColor Yellow
} else {
    $telegramToken = if ($fileSecrets.ContainsKey("TELEGRAM_TOKEN")) { $fileSecrets["TELEGRAM_TOKEN"] } else { Read-Secret "TELEGRAM_TOKEN" "Токен Telegram-бота" }
    $secretToken = if ($fileSecrets.ContainsKey("TELEGRAM_SECRET_TOKEN")) { $fileSecrets["TELEGRAM_SECRET_TOKEN"] } else { Read-Secret "TELEGRAM_SECRET_TOKEN" "Секрет вебхука Telegram (любой случайный)" }
    $adminId = if ($fileSecrets.ContainsKey("ADMIN_ID")) { $fileSecrets["ADMIN_ID"] } else { Read-Secret "ADMIN_ID" "Telegram ID администратора" }
    $subscriptionSecret = if ($fileSecrets.ContainsKey("SUBSCRIPTION_SECRET")) { $fileSecrets["SUBSCRIPTION_SECRET"] } else { Read-Secret "SUBSCRIPTION_SECRET" "Секрет подписи ссылки подписки" }
    $subSecret = if ($fileSecrets.ContainsKey("SUB_SECRET")) { $fileSecrets["SUB_SECRET"] } else { Read-Secret "SUB_SECRET" "Секрет крон-запроса" }
    $botUsername = if ($fileSecrets.ContainsKey("BOT_USERNAME")) { $fileSecrets["BOT_USERNAME"] } else { "NAUA_Security_Mirage_Bot" }

    npx.cmd -y supabase secrets set `
        --project-ref $ProjectRef `
        "TELEGRAM_TOKEN=$telegramToken" `
        "TELEGRAM_SECRET_TOKEN=$secretToken" `
        "ADMIN_ID=$adminId" `
        "BOT_USERNAME=$botUsername" `
        "SUBSCRIPTION_SECRET=$subscriptionSecret" `
        "SUB_SECRET=$subSecret"
    if ($LASTEXITCODE -ne 0) {
        Write-Host "СЕКРЕТЫ НЕ УСТАНОВЛЕНЫ" -ForegroundColor Red
        exit 1
    }
}

Step ">>> 4/5: Деплой функций"
foreach ($fn in @("telegram-auth", "telegram-subscription", "telegram-bot")) {
    Write-Host "-> $fn" -ForegroundColor DarkGray
    # --no-verify-jwt обязателен: Telegram, Google и VPN-клиенты не умеют
    # слать заголовок apikey. Без него платформа отвечает 401 на любой
    # запрос. Своя проверка внутри функций: секрет вебхука, PKCE-состояние
    # и подпись ссылки подписки.
    npx.cmd -y supabase functions deploy $fn --project-ref $ProjectRef --no-verify-jwt
    if ($LASTEXITCODE -ne 0) {
        Write-Host "ДЕПЛОЙ $fn НЕ УДАЛСЯ" -ForegroundColor Red
        exit 1
    }
}

Step ">>> 5/5: Регистрация вебхука Telegram"
$webhookUrl = "https://$ProjectRef.supabase.co/functions/v1/telegram-bot"
if ($SkipWebhook) {
    Write-Host "skipped" -ForegroundColor Yellow
} else {
    # Метка вебхука: бот игнорирует апдейты без ?tag=. Без неё старый
    # тестовый бот отвечал бы через новый токен. Не сбрасываем её.
    $webhookTag = if ($fileSecrets.ContainsKey("WEBHOOK_TAG")) { $fileSecrets["WEBHOOK_TAG"] } else { [Environment]::GetEnvironmentVariable("WEBHOOK_TAG") }
    $botToken = if ($fileSecrets.ContainsKey("TELEGRAM_TOKEN")) { $fileSecrets["TELEGRAM_TOKEN"] } else { [Environment]::GetEnvironmentVariable("TELEGRAM_TOKEN") }
    $secretToken = if ($fileSecrets.ContainsKey("TELEGRAM_SECRET_TOKEN")) { $fileSecrets["TELEGRAM_SECRET_TOKEN"] } else { [Environment]::GetEnvironmentVariable("TELEGRAM_SECRET_TOKEN") }
    if ([string]::IsNullOrWhiteSpace($webhookTag)) {
        Write-Host "WEBHOOK_TAG не задан — вебхук не трогаю, иначе бот перестанет отвечать." -ForegroundColor Yellow
    } elseif ([string]::IsNullOrWhiteSpace($botToken) -or [string]::IsNullOrWhiteSpace($secretToken)) {
        Write-Host "Нет токена/секрета — регистрацию пропускаю." -ForegroundColor Yellow
    } else {
        $url = "https://api.telegram.org/bot$botToken/setWebhook?url=$webhookUrl%3Ftag%3D$webhookTag&secret_token=$secretToken&allowed_updates=%5B%22message%22,%22edited_message%22,%22callback_query%22%5D"
        try {
            $result = Invoke-RestMethod -Uri $url -TimeoutSec 30
            Write-Host ("setWebhook: {0} — {1}" -f $result.ok, $result.description) -ForegroundColor Green
            $info = Invoke-RestMethod -Uri "https://api.telegram.org/bot$botToken/getWebhookInfo" -TimeoutSec 30
            Write-Host ("url:      {0}" -f $info.result.url)
            Write-Host ("secret:   {0}" -f $(if ($info.result.secret_token) { "установлен" } else { "НЕТ" }))
            Write-Host ("pending:  {0}" -f $info.result.pending_update_count)
            if ($info.result.last_error_message) {
                Write-Host ("last error: {0}" -f $info.result.last_error_message) -ForegroundColor Yellow
            }
        } catch {
            Write-Host ("Не удалось зарегистрировать вебхук: {0}" -f $_.Exception.Message) -ForegroundColor Yellow
        }
    }
}

Write-Host ""
Write-Host "Важно: добавь в Supabase -> Authentication -> URL Configuration:" -ForegroundColor Cyan
Write-Host "  Redirect URL:  https://$ProjectRef.supabase.co/functions/v1/telegram-auth" -ForegroundColor Cyan
Write-Host "  Site URL:      https://$ProjectRef.supabase.co" -ForegroundColor Cyan
Write-Host ""
Write-Host "DONE." -ForegroundColor Green
exit 0
