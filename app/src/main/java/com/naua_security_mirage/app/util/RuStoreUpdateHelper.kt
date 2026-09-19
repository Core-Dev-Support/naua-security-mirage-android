package com.naua_security_mirage.app.util

import android.app.Activity
import android.content.Context
import android.widget.Toast
import ru.rustore.sdk.appupdate.listener.InstallStateUpdateListener
import ru.rustore.sdk.appupdate.manager.RuStoreAppUpdateManager
import ru.rustore.sdk.appupdate.manager.factory.RuStoreAppUpdateManagerFactory
import ru.rustore.sdk.appupdate.model.AppUpdateInfo
import ru.rustore.sdk.appupdate.model.AppUpdateOptions
import ru.rustore.sdk.appupdate.model.AppUpdateType
import ru.rustore.sdk.appupdate.model.InstallStatus
import ru.rustore.sdk.appupdate.model.UpdateAvailability

/**
 * RuStoreUpdateHelper: Менеджер обновлений приложения через RuStore In-App Updates SDK.
 * Поддерживает автоматическую проверку при запуске и ручную проверку из настроек.
 */
object RuStoreUpdateHelper {

    private const val TAG = "RuStoreUpdateHelper"

    private var updateManager: RuStoreAppUpdateManager? = null
    private var installStateListener: InstallStateUpdateListener? = null
    private var currentUpdateInfo: AppUpdateInfo? = null

    fun init(context: Context) {
        if (updateManager == null) {
            try {
                updateManager = RuStoreAppUpdateManagerFactory.create(context.applicationContext)
            } catch (e: Throwable) {
                AppLogger.w(TAG, "Инициализация RuStoreAppUpdateManager: ${e.message}")
            }
        }
    }

    /**
     * Проверка наличия обновлений.
     * @param activity Текущая активность
     * @param isManual true если вызов инициирован пользователем вручную (кнопка в настройках)
     * @param onUpdateAvailable Пользовательский обработчик при наличии обновления
     */
    fun checkForUpdates(
        activity: Activity,
        isManual: Boolean = false,
        onUpdateAvailable: ((AppUpdateInfo) -> Unit)? = null
    ) {
        val manager = updateManager ?: try {
            RuStoreAppUpdateManagerFactory.create(activity.applicationContext).also {
                updateManager = it
            }
        } catch (t: Throwable) {
            AppLogger.w(TAG, "Не удалось создать RuStoreAppUpdateManager: ${t.message}")
            if (isManual) {
                Toast.makeText(activity, "RuStore SDK недоступен", Toast.LENGTH_SHORT).show()
            }
            return
        }

        manager.getAppUpdateInfo()
            .addOnSuccessListener { info ->
                currentUpdateInfo = info
                AppLogger.i(TAG, "RuStore getAppUpdateInfo: availability=${info.updateAvailability}, installStatus=${info.installStatus}")

                when (info.updateAvailability) {
                    UpdateAvailability.UPDATE_AVAILABLE -> {
                        if (onUpdateAvailable != null) {
                            onUpdateAvailable(info)
                        } else {
                            startFlexibleUpdate(activity, info)
                        }
                    }
                    UpdateAvailability.UPDATE_NOT_AVAILABLE -> {
                        if (isManual) {
                            Toast.makeText(activity, "У вас установлена последняя версия", Toast.LENGTH_SHORT).show()
                        }
                    }
                    else -> {
                        if (isManual) {
                            Toast.makeText(activity, "Обновлений не найдено", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .addOnFailureListener { throwable ->
                AppLogger.w(TAG, "Проверка обновлений RuStore: ${throwable.message}")
                if (isManual) {
                    val rawMsg = throwable.message.orEmpty()
                    val msg = when {
                        rawMsg.contains("404") || rawMsg.contains("not found", ignoreCase = true) ->
                            "Приложение ожидает публикации в RuStore"
                        throwable.javaClass.simpleName.contains("NotInstalled", ignoreCase = true) ->
                            "Магазин RuStore не установлен на устройстве"
                        throwable.javaClass.simpleName.contains("Outdated", ignoreCase = true) ->
                            "Требуется обновить приложение RuStore"
                        throwable.javaClass.simpleName.contains("Unauthorized", ignoreCase = true) ->
                            "Требуется авторизация в приложении RuStore"
                        else ->
                            "Установлена актуальная версия"
                    }
                    Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
                }
            }
    }

    /**
     * Запуск сценария фоновой/мягкой загрузки обновления (FLEXIBLE).
     */
    fun startFlexibleUpdate(activity: Activity, appUpdateInfo: AppUpdateInfo) {
        val manager = updateManager ?: return

        registerInstallListener(activity.applicationContext, manager)

        val options = AppUpdateOptions.Builder()
            .appUpdateType(AppUpdateType.FLEXIBLE)
            .build()

        manager.startUpdateFlow(appUpdateInfo, options)
            .addOnSuccessListener { resultCode ->
                if (resultCode == Activity.RESULT_OK) {
                    AppLogger.i(TAG, "Пользователь подтвердил обновление приложения")
                } else if (resultCode == Activity.RESULT_CANCELED) {
                    AppLogger.i(TAG, "Пользователь отклонил обновление приложения")
                }
            }
            .addOnFailureListener { throwable ->
                AppLogger.e(TAG, "Ошибка startUpdateFlow: ${throwable.message}")
            }
    }

    private fun registerInstallListener(context: Context, manager: RuStoreAppUpdateManager) {
        if (installStateListener != null) return

        val listener = InstallStateUpdateListener { state ->
            when (state.installStatus) {
                InstallStatus.DOWNLOADING -> {
                    val total = state.totalBytesToDownload
                    val downloaded = state.bytesDownloaded
                    if (total > 0) {
                        val percent = (downloaded * 100 / total).toInt()
                        AppLogger.d(TAG, "Загрузка обновления RuStore: $percent% ($downloaded/$total)")
                    }
                }
                InstallStatus.DOWNLOADED -> {
                    AppLogger.i(TAG, "Обновление RuStore успешно загружено и готово к установке")
                    Toast.makeText(context, "Обновление загружено. Перезапуск для завершения...", Toast.LENGTH_LONG).show()
                    completeUpdate()
                }
                InstallStatus.FAILED -> {
                    AppLogger.e(TAG, "Ошибка скачивания обновления: код=${state.installErrorCode}")
                }
                else -> {}
            }
        }
        installStateListener = listener
        manager.registerListener(listener)
    }

    /**
     * Завершение установки обновления и перезапуск приложения.
     */
    fun completeUpdate() {
        val manager = updateManager ?: return
        try {
            val options = AppUpdateOptions.Builder()
                .appUpdateType(AppUpdateType.FLEXIBLE)
                .build()
            manager.completeUpdate(options)
                .addOnFailureListener { throwable ->
                    AppLogger.e(TAG, "Ошибка completeUpdate: ${throwable.message}")
                }
        } catch (e: Throwable) {
            AppLogger.e(TAG, "Исключение completeUpdate: ${e.message}")
        }
    }

    fun onDestroy() {
        installStateListener?.let {
            try {
                updateManager?.unregisterListener(it)
            } catch (_: Throwable) {}
            installStateListener = null
        }
    }
}
