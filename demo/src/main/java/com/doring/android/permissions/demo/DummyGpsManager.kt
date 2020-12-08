package com.doring.android.permissions.demo

import android.annotation.SuppressLint
import android.content.Context
import android.location.LocationManager
import androidx.annotation.MainThread
import androidx.core.location.LocationManagerCompat
import com.google.android.gms.location.*
import com.google.android.gms.tasks.Task

object DummyGpsManager {

    /**
     * Stores parameters for requests to the FusedLocationProviderApi.
     */
    private val locationRequest: LocationRequest = LocationRequest.create().apply {
        interval = 5000L
        fastestInterval = 1000L
        priority = LocationRequest.PRIORITY_HIGH_ACCURACY
    }

    /**
     * Stores the types of location services the client is interested in using. Used for checking
     * settings to determine if the device has optimal location settings.
     */
    private val locationSettingsRequest: LocationSettingsRequest = LocationSettingsRequest.Builder()
        .addLocationRequest(locationRequest)
        .build()

    /**
     * Provides access to the Location Settings API.
     */
    private lateinit var settingsClient: SettingsClient


    private var context: Context? = null

    @SuppressLint("MissingPermission")
    fun initialize(context: Context) {
        this.context = context
        settingsClient = LocationServices.getSettingsClient(context)
    }


    fun isLocationEnabled(): Boolean {
        val locationManager = context?.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        return locationManager?.let { LocationManagerCompat.isLocationEnabled(it) } ?: false
    }

    @MainThread
    fun getLocationSettingTask(): Task<LocationSettingsResponse> {
        // Begin by checking if the device has the necessary location settings.
        return settingsClient.checkLocationSettings(locationSettingsRequest)
    }
}