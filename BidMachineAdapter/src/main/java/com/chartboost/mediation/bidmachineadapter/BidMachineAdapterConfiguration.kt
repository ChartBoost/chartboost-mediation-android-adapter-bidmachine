package com.chartboost.mediation.bidmachineadapter

import com.chartboost.chartboostmediationsdk.domain.PartnerAdapterConfiguration
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController
import io.bidmachine.BidMachine
import io.bidmachine.Publisher
import io.bidmachine.TargetingParams

object BidMachineAdapterConfiguration : PartnerAdapterConfiguration {
    /**
     * The partner ID for internal uses.
     */
    override val partnerId = "bidmachine"

    /**
     * The partner name for external uses.
     */
    override val partnerDisplayName = "BidMachine"

    /**
     * The partner SDK version.
     */
    override val partnerSdkVersion = BidMachine.VERSION

    /**
     * The partner adapter version.
     *
     * You may version the adapter using any preferred convention, but it is recommended to apply the
     * following format if the adapter will be published by Chartboost Mediation:
     *
     * Chartboost Mediation.Partner.Adapter
     *
     * "Chartboost Mediation" represents the Chartboost Mediation SDK’s major version that is compatible with this adapter. This must be 1 digit.
     * "Partner" represents the partner SDK’s major.minor.patch.x (where x is optional) version that is compatible with this adapter. This can be 3-4 digits.
     * "Adapter" represents this adapter’s version (starting with 0), which resets to 0 when the partner SDK’s version changes. This must be 1 digit.
     */
    override val adapterVersion = BuildConfig.CHARTBOOST_MEDIATION_BIDMACHINE_ADAPTER_VERSION

    /**
     * Test mode flag that can optionally be set to true to enable test ads. It can be set at any
     * time and it will take effect for the next ad request. Remember to set this to false in
     * production.
     */
    var testModeEnabled = false
        set(value) {
            field = value
            BidMachine.setTestMode(value)
            PartnerLogController.log(
                    PartnerLogController.PartnerAdapterEvents.CUSTOM,
                    "BidMachine test mode is ${
                        if (value) {
                            "enabled. Remember to disable it before publishing."
                        } else {
                            "disabled."
                        }
                    }",
            )
        }

    /**
     * Enable/disable logging for the BidMachine Ads SDK.
     */
    var isLoggingEnabled = false
        set(value) {
            field = value
            BidMachine.setLoggingEnabled(value)
            PartnerLogController.log(
                    PartnerLogController.PartnerAdapterEvents.CUSTOM,
                    "BidMachine logging ${if (value) "enabled" else "disabled"}.",
            )
        }

    /**
     * Globally set targeting parameters.
     */
    fun setTargetingParams(targetingParams: TargetingParams) {
        BidMachine.setTargetingParams(targetingParams)
        PartnerLogController.log(
                PartnerLogController.PartnerAdapterEvents.CUSTOM,
                "BidMachine targeting parameters set with $targetingParams",
        )
    }

    /**
     * Set Publisher information.
     */
    fun setPublisher(publisher: Publisher) {
        BidMachine.setPublisher(publisher)
        PartnerLogController.log(
                PartnerLogController.PartnerAdapterEvents.CUSTOM,
                "BidMachine publisher information set with: $publisher",
        )
    }
}
