/**
 * Copyright 2019 Salesforce, Inc
 * <p>
 * Redistribution and use in source and binary forms, with or without modification, are permitted
 * provided that the following conditions are met:
 * <p>
 * 1. Redistributions of source code must retain the above copyright notice, this list of
 * conditions and the following disclaimer.
 * <p>
 * 2. Redistributions in binary form must reproduce the above copyright notice, this list of
 * conditions and the following disclaimer in the documentation and/or other materials provided
 * with the distribution.
 * <p>
 * 3. Neither the name of the copyright holder nor the names of its contributors may be used to
 * endorse or promote products derived from this software without specific prior written permission.
 * <p>
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR
 * IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND
 * FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN
 * ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.salesforce.marketingcloud.learningapp

import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.google.android.datatransport.BuildConfig
import com.google.android.gms.common.GoogleApiAvailability
import com.salesforce.marketingcloud.InitializationStatus.Status.*
import com.salesforce.marketingcloud.MCLogListener
import com.salesforce.marketingcloud.MarketingCloudConfig
import com.salesforce.marketingcloud.MarketingCloudSdk
import com.salesforce.marketingcloud.UrlHandler
import com.salesforce.marketingcloud.messages.iam.InAppMessage
import com.salesforce.marketingcloud.messages.iam.InAppMessageManager
import java.io.File

const val LOG_TAG = "MCSDK"

abstract class BaseLearningApplication : Application(), UrlHandler {

    internal abstract val configBuilder: MarketingCloudConfig.Builder

    companion object {
        private const val PREF_KEY = "MarketingCloudSDK_PrivacyModeOverridden_1"
    }

    /*
      This MUST be executed BEFORE initializing the SDK and must only be run once, ever.  Failing to execute
      this code prior to the SDK's initialization will result in the contact remaining blocked.  Failing to
      prevent subsequent executions of this code could result in the inability for a consumer to be GDPR'd.
   */
    private fun checkAndUpdateContactPushInfo(application: Application, applicationId: String) {
        application?.let {

            // Your application's shared preferences file to store a flag
            val appPrefs = it.getSharedPreferences("CUSTOM_PREFERENCE", Context.MODE_PRIVATE)
            val prefKey = "MarketingCloudSDK_PrivacyModeOverridden_1"
            val privacyModeIsNotOverridden = !appPrefs.getBoolean(prefKey, false)

            // Only run this if we have not previously succeeded
            if (true) {

                try {
                    var overridden = false

                    // Look for the current privacy mode file
                    val fileName = applicationId + "_SFMC_PrivacyMode"
                    val gdprFile = File(it.noBackupFilesDir, fileName)
                    if (gdprFile.exists()) {
                        try {
                            gdprFile.delete()
                            overridden = true

                            // tried this code provided from the customer START
                            val uuidFile =  File(it.noBackupFilesDir, "SFMCDeviceUUID")
                            if (uuidFile.exists()) {
                                uuidFile.delete()
                            }
                            // tried this code provided from the customer END

                        } catch (e: Exception) {
                            // NO_OP
                        }
                    } else {
                        // If the file didn't exist check preferences
                        val sdkPrefs = it.getSharedPreferences(
                            "mcsdk_" + "d8eaa92d-0167-4f07-b3e4-392bbac6e106",
                            Context.MODE_PRIVATE
                        ) // example: mcsdk_5752ca49-57fe-48f8-b06c-a301bd7dab30
                        if (appPrefs.contains("cc_state")) {
                            sdkPrefs.edit().remove("cc_state").apply()
                            overridden = true
                        } else {
                            // If the file didn't exist nor was the flag in preferences, check pre-lollipop location
                            val preLollipopFile = File(it.filesDir, fileName)
                            if (preLollipopFile.exists()) {
                                try {
                                    preLollipopFile.delete()
                                    overridden = true
                                } catch (e: Exception) {
                                    // NO_OP
                                }
                            }
                        }
                    }
                    if (overridden) {
                        appPrefs.edit().putBoolean(prefKey, true).apply()
                        MarketingCloudSdk.requestSdk { sdk -> sdk.pushMessageManager.enablePush() }
                    }
                } catch (e: Exception) {
                    // NO_OP
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        if (BuildConfig.DEBUG) {
            MarketingCloudSdk.setLogLevel(MCLogListener.VERBOSE)
            MarketingCloudSdk.setLogListener(MCLogListener.AndroidLogListener())
        }

        // Reset the Privacy Flag before initializing the SDK
        checkAndUpdateContactPushInfo(this@BaseLearningApplication, "d8eaa92d-0167-4f07-b3e4-392bbac6e106")

        // You MUST initialize the SDK in your Application's onCreate to ensure correct
        // functionality when the app is launched from a background service (receiving push message,
        // entering a geofence, ...)
        MarketingCloudSdk.init(this, configBuilder.build(this)) { initStatus ->
            when (initStatus.status) {
                SUCCESS -> Log.v(LOG_TAG, "Marketing Cloud initialization successful.")
                COMPLETED_WITH_DEGRADED_FUNCTIONALITY -> {
                    Log.v(
                        LOG_TAG,
                        "Marketing Cloud initialization completed with recoverable errors."
                    )
                    if (initStatus.locationsError && GoogleApiAvailability.getInstance().isUserResolvableError(
                            initStatus.playServicesStatus
                        )
                    ) {
                        // User will likely need to update GooglePlayServices through the Play Store.
                        GoogleApiAvailability.getInstance()
                            .showErrorNotification(this, initStatus.playServicesStatus)
                    }
                }
                FAILED -> {
                    // Given that this app is used to show SDK functionality we will hard exit if SDK init outright failed.
                    Log.e(
                        LOG_TAG,
                        "Marketing Cloud initialization failed.  Exiting Learning App with exception."
                    )
                    throw initStatus.unrecoverableException ?: RuntimeException("Init failed")

                }
            }
        }

        MarketingCloudSdk.requestSdk { sdk ->
            sdk.inAppMessageManager.run {

                // Set the status bar color to be used when displaying an In App Message.
                setStatusBarColor(
                    ContextCompat.getColor(
                        this@BaseLearningApplication,
                        R.color.primaryColor
                    )
                )
                // Set the font to be used when an In App Message is rendered by the SDK
                setTypeface(ResourcesCompat.getFont(this@BaseLearningApplication, R.font.fira_sans))

                setInAppMessageListener(object : InAppMessageManager.EventListener {
                    override fun shouldShowMessage(message: InAppMessage): Boolean {
                        // This method will be called before a in app message is presented.  You can return `false` to
                        // prevent the message from being displayed.  You can later use call `InAppMessageManager#showMessage`
                        // to display the message if the message is still on the device and active.
                        return true
                    }

                    override fun didShowMessage(message: InAppMessage) {
                        Log.v(LOG_TAG, "${message.id} was displayed.")
                    }

                    override fun didCloseMessage(message: InAppMessage) {
                        Log.v(LOG_TAG, "${message.id} was closed.")
                    }
                })
            }


        }
    }

    override fun handleUrl(context: Context, url: String, urlSource: String): PendingIntent? {
        return PendingIntent.getActivity(
            context,
            1,
            Intent(Intent.ACTION_VIEW, Uri.parse(url)),
            PendingIntent.FLAG_UPDATE_CURRENT
        )
    }
}
