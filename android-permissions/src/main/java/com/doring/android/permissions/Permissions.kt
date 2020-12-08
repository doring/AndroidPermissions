package com.doring.android.permissions

import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.asLiveData
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * This *must* be called unconditionally, as part of initialization path,
 * typically as a field initializer of an Activity
 */
class Permissions(
    private val activity: ComponentActivity,
    private val manifestPermissions: Array<String>,
    private val customPermissions: Array<CustomPermission<*, *>>,
) {
    companion object {
        const val THRESHOLD_REQUEST_RESULT_PERIOD_TIME = 300L
    }

    class CustomPermission<I, O>(
        val name: String,
        // Need to assign a guide screen to show before requesting permission.
        // It is nullable because there are cases where you only want to check the granted permission.
        val permissionCheckGuide: (((I?) -> Unit) -> Unit)? = null,
        // Function to check for permission
        val permissionCheckResultCallback: () -> Boolean,
        val activityResultContract: ActivityResultContract<I, O>,
    )

    // Mutex for request custom permission sequentially
    private val mutex = Mutex()

    private var currentRequestCustomPermission: CustomPermission<*, *>? = null
    private var requestStartTime = 0L
    private var requestAndResultTimeDiff = 0L

    // Data to be put in the flow after all permission requests are completed
    private val mutableResultMap = mutableMapOf<String, Boolean>()

    // Flow that can get the result of requesting permission
    private val mutableResultFlow = MutableSharedFlow<Map<String, Boolean>>(replay = 1)
    val resultFlow: SharedFlow<Map<String, Boolean>> = mutableResultFlow

    // Activity should use LiveData to get results
    // Flow only used for special purposes
    val resultLiveData: LiveData<Map<String, Boolean>> = resultFlow.asLiveData()

    private val requestManifestPermissionLauncher =
        activity.registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) {
            mutableResultMap += it
            requestAndResultTimeDiff = System.currentTimeMillis() - requestStartTime
            requestCustomPermissions()
        }

    private val customPermissionLauncherMap = customPermissions.associate {
        val launcher = activity.registerForActivityResult(it.activityResultContract) {
            currentRequestCustomPermission?.let { permission ->
                mutableResultMap[permission.name] = permission.permissionCheckResultCallback()
            }
            mutex.unlock()
        }
        @Suppress("UNCHECKED_CAST")
        it.name to (launcher as? ActivityResultLauncher<Any?>)
    }

    private fun requestCustomPermissions() {
        activity.lifecycleScope.launch {
            customPermissions.forEach {
                if (!it.permissionCheckResultCallback()) {
                    val launchInput = suspendCoroutine<Any?> { cont ->
                        val callback = { launchRequested: Any? ->
                            cont.resume(launchRequested)
                        }
                        it.permissionCheckGuide?.invoke(callback) ?: cont.resume(false)
                    }
                    if (launchInput != null) {
                        mutex.lock()
                        currentRequestCustomPermission = it
                        val failedIfNull: Unit? =
                            customPermissionLauncherMap[it.name]?.runCatching {
                                launch(launchInput)
                            }?.getOrNull()

                        // When launcher is null or start activity fails due to handling intent
                        if (failedIfNull == null) {
                            mutex.unlock()
                            mutableResultMap[it.name] = it.permissionCheckResultCallback()
                        }
                    } else {
                        // User closes or denies permission request dialog.
                        mutableResultMap[it.name] = false
                    }
                } else {
                    // Already granted permission.
                    mutableResultMap[it.name] = true
                }
            }

            //Wait until all the requested permissions are terminated and then emit them.
            mutex.withLock {
                // suspending on buffer overflow. mutableResultFlow has no buffer
                mutableResultFlow.emit(mutableResultMap)
            }
        }
    }

    fun isShownAnyManifestPermission(): Boolean {
        // If the permission request is denied twice, the pop-up no longer appears
        // it takes 50ms for Pixel 4 from request to response.
        // The permission request popup is regarded as popping up only when it takes more than the limit time
        return requestAndResultTimeDiff > THRESHOLD_REQUEST_RESULT_PERIOD_TIME
    }

    fun getDeniedPermissions(): List<String> {
        return manifestPermissions.filter { ContextCompat.checkSelfPermission(activity, it) == PackageManager.PERMISSION_DENIED } +
                customPermissions.filter { !it.permissionCheckResultCallback() }.map { it.name }
    }

    fun getGrantedPermissions(): List<String> {
        return manifestPermissions.filter { ContextCompat.checkSelfPermission(activity, it) == PackageManager.PERMISSION_GRANTED } +
                customPermissions.filter { it.permissionCheckResultCallback() }.map { it.name }
    }

    fun requestPermission() {
        mutableResultMap.clear()
        currentRequestCustomPermission = null

        val countDeniedManifestPermissions = manifestPermissions.count {
            ContextCompat.checkSelfPermission(activity, it) == PackageManager.PERMISSION_DENIED
        }

        if (countDeniedManifestPermissions == 0) {
            mutableResultMap += manifestPermissions.associate { it to true }
            requestCustomPermissions()
        } else {
            requestAndResultTimeDiff = 0L
            requestStartTime = System.currentTimeMillis()
            requestManifestPermissionLauncher.launch(manifestPermissions)
        }
    }

}