package com.doring.android.permissions.demo

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.result.ActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.doring.android.permissions.Permissions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.LocationSettingsStatusCodes
import kotlinx.android.synthetic.main.activity_main.*

object RequirePermissions {
    const val CUSTOM_PERMISSION_OVERLAY_DRAW = "overlay_draw"
    const val CUSTOM_PERMISSION_GPS_SETTING = "gps_setting"

    fun getManifestMultiplePermissions() = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.WRITE_EXTERNAL_STORAGE
    )
}

class MainActivity : AppCompatActivity() {

    private val permissions = Permissions(
        activity = this,
        manifestPermissions = RequirePermissions.getManifestMultiplePermissions(),
        customPermissions = arrayOf(
            Permissions.CustomPermission<Intent, ActivityResult>(
                name = RequirePermissions.CUSTOM_PERMISSION_OVERLAY_DRAW,
                permissionCheckGuide = this::showOverlayDrawPermissionDialog,
                permissionCheckResultCallback = { Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this) },
                activityResultContract = ActivityResultContracts.StartActivityForResult()
            ),
            Permissions.CustomPermission<IntentSenderRequest, ActivityResult>(
                name = RequirePermissions.CUSTOM_PERMISSION_GPS_SETTING,
                permissionCheckGuide = this::checkGpsSetting,
                permissionCheckResultCallback = { DummyGpsManager.isLocationEnabled() },
                activityResultContract = ActivityResultContracts.StartIntentSenderForResult()
            ),
        )
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        DummyGpsManager.initialize(this)

        setContentView(R.layout.activity_main)
        updatePermissionResult()

        btnRequest.setOnClickListener {
            permissions.requestPermission()
        }

        permissions.resultLiveData.observe(this) {
            updatePermissionResult()

            if (!permissions.isShownAnyManifestPermission() &&
                permissions.getDeniedPermissions().isNotEmpty()
            ) {
                // Denied twice for the same request for permission.
                // request pop up would be never shown
                askGotoAppSettingScreen()
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun updatePermissionResult() {
        result.text = "GRANTED : \n${permissions.getGrantedPermissions().joinToString("\n")}\n\n" +
                "DENIED : \n${permissions.getDeniedPermissions().joinToString("\n")}"
    }

    private fun askGotoAppSettingScreen() {
        val dialogBuilder = AlertDialog.Builder(this)

        dialogBuilder.setMessage(R.string.ask_goto_app_setting)
        dialogBuilder.setPositiveButton(R.string.goto_app_setting) { _, _ ->
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
            startActivity(intent)
        }
        dialogBuilder.show()
    }

    private fun checkGpsSetting(callback: (IntentSenderRequest?) -> Unit) {
        DummyGpsManager.getLocationSettingTask()
            .addOnSuccessListener {
                callback(null)
            }.addOnFailureListener { e ->
                when ((e as? ApiException)?.statusCode) {
                    LocationSettingsStatusCodes.RESOLUTION_REQUIRED -> {
                        val intentSender = (e as? ResolvableApiException)?.resolution?.intentSender
                        if (intentSender != null) {
                            val req = IntentSenderRequest.Builder(intentSender).build()
                            callback(req)
                        } else {
                            callback(null)
                        }
                    }
                    else -> {
                        callback(null)
                    }
                }
            }
    }

    private fun showOverlayDrawPermissionDialog(callback: (Intent?) -> Unit) {
        val dialogBuilder = AlertDialog.Builder(this)
        var requestIntent: Intent? = null

        dialogBuilder.setMessage(R.string.request_overlay_draw)
        dialogBuilder.setPositiveButton(android.R.string.ok) { _, _ ->
            requestIntent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.fromParts("package", packageName, null))
        }
        dialogBuilder.setNegativeButton(android.R.string.cancel, null)

        val dialog = dialogBuilder.create()
        dialog.setOnDismissListener {
            callback(requestIntent)
        }
        dialog.show()
    }
}