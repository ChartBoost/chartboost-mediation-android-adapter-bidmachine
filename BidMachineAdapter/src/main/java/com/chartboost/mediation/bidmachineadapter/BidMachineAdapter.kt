/*
 * Copyright 2022-2023 Chartboost, Inc.
 * 
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE file.
 */

package com.chartboost.mediation.bidmachineadapter

import android.content.Context
import android.util.Size
import com.chartboost.heliumsdk.domain.AdFormat
import com.chartboost.heliumsdk.domain.ChartboostMediationAdException
import com.chartboost.heliumsdk.domain.ChartboostMediationError
import com.chartboost.heliumsdk.domain.GdprConsentStatus
import com.chartboost.heliumsdk.domain.PartnerAd
import com.chartboost.heliumsdk.domain.PartnerAdListener
import com.chartboost.heliumsdk.domain.PartnerAdLoadRequest
import com.chartboost.heliumsdk.domain.PartnerAdapter
import com.chartboost.heliumsdk.domain.PartnerConfiguration
import com.chartboost.heliumsdk.domain.PreBidRequest
import com.chartboost.heliumsdk.utils.PartnerLogController
import com.chartboost.heliumsdk.utils.PartnerLogController.PartnerAdapterEvents.*
import io.bidmachine.AdsFormat
import io.bidmachine.BidMachine
import io.bidmachine.InitializationCallback
import io.bidmachine.banner.BannerListener
import io.bidmachine.banner.BannerRequest
import io.bidmachine.banner.BannerSize
import io.bidmachine.banner.BannerView
import io.bidmachine.interstitial.InterstitialAd
import io.bidmachine.interstitial.InterstitialListener
import io.bidmachine.interstitial.InterstitialRequest
import io.bidmachine.rewarded.RewardedAd
import io.bidmachine.rewarded.RewardedListener
import io.bidmachine.rewarded.RewardedRequest
import io.bidmachine.utils.BMError
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlin.coroutines.resume

/**
 * The Chartboost Mediation BidMachine Ads SDK adapter.
 */
class BidMachineAdapter : PartnerAdapter {
    companion object {
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
                    CUSTOM,
                    "BidMachine test mode is ${
                        if (value) "enabled. Remember to disable it before publishing."
                        else "disabled."
                    }"
                )
            }

        /**
         * Log level option that can be set to alter the output verbosity of the BidMachine Ads SDK.
         */
        var isLoggingEnabled = false
            set(value) {
                field = value
                BidMachine.setLoggingEnabled(value)
                PartnerLogController.log(CUSTOM, "BidMachine logging ${ if(value) "enabled" else "disabled" }.")
            }

        /**
         * Key for parsing the BidMachine SDK source ID.
         */
        private const val SOURCE_ID_KEY = "source_id"
    }

    /**
     * A lambda to call for failed BidMachine ad shows.
     */
    private var onShowError: (error: BMError) -> Unit =
        { _: BMError -> }

    /**
     * Get the BidMachine Ads SDK version.
     */
    override val partnerSdkVersion: String
        get() = BidMachine.VERSION

    /**
     * Get the BidMachine adapter version.
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
    override val adapterVersion: String
        get() = BuildConfig.CHARTBOOST_MEDIATION_BIDMACHINE_ADAPTER_VERSION

    /**
     * Get the partner name for internal uses.
     */
    override val partnerId: String
        get() = "bidmachine"

    /**
     * Get the partner name for external uses.
     */
    override val partnerDisplayName: String
        get() = "BidMachine"

    /**
     * Initialize the BidMachine Ads SDK so that it is ready to request ads.
     *
     * @param context The current [Context].
     * @param partnerConfiguration Configuration object containing relevant data to initialize BidMachine.
     *
     * @return Result.success(Unit) if BidMachine successfully initialized, Result.failure(Exception) otherwise.
     */
    override suspend fun setUp(
        context: Context,
        partnerConfiguration: PartnerConfiguration
    ): Result<Unit> {
        PartnerLogController.log(SETUP_STARTED)

        return suspendCancellableCoroutine { continuation ->
            Json.decodeFromJsonElement<String>(
                (partnerConfiguration.credentials as JsonObject).getValue(SOURCE_ID_KEY)
            ).trim()
                .takeIf { it.isNotEmpty() }?.let { sourceId ->
                    BidMachine.initialize(context, sourceId, object : InitializationCallback {
                        fun resumeOnce(result: Result<Unit>) {
                            if (continuation.isActive) {
                                continuation.resume(result)
                            }
                        }

                        override fun onInitialized() {
                            if (BidMachine.isInitialized()) {
                                resumeOnce(Result.success(PartnerLogController.log(SETUP_SUCCEEDED)))
                            } else {
                                PartnerLogController.log(SETUP_FAILED)
                                resumeOnce(
                                    Result.failure(
                                        ChartboostMediationAdException(ChartboostMediationError.CM_INITIALIZATION_FAILURE_UNKNOWN)
                                    )
                                )
                            }
                        }
                    })
                } ?: run {
                PartnerLogController.log(SETUP_FAILED, "Missing source ID.")
                continuation.resumeWith(Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_INITIALIZATION_FAILURE_INVALID_CREDENTIALS)))
            }
        }
    }

    /**
     * Get a bid token if network bidding is supported.
     *
     * @param context The current [Context].
     * @param request The [PreBidRequest] instance containing relevant data for the current bid request.
     *
     * @return A Map of biddable token Strings.
     */
    override suspend fun fetchBidderInformation(
        context: Context,
        request: PreBidRequest
    ): Map<String, String> {
        PartnerLogController.log(BIDDER_INFO_FETCH_STARTED)

        return suspendCancellableCoroutine { continuation ->
            val adFormat = when(request.format) {
                AdFormat.BANNER -> AdsFormat.Banner
                AdFormat.INTERSTITIAL -> AdsFormat.Interstitial
                AdFormat.REWARDED -> AdsFormat.Rewarded
                AdFormat.REWARDED_INTERSTITIAL -> AdsFormat.RewardedStatic
                else -> return@suspendCancellableCoroutine
            }

            BidMachine.getBidToken(context, adFormat) { token ->
                if (continuation.isActive) {
                    PartnerLogController.log(if (token.isEmpty()) BIDDER_INFO_FETCH_FAILED else BIDDER_INFO_FETCH_SUCCEEDED)
                    continuation.resume(mapOf("token" to token))
                }
            }
        }
    }

    /**
     * Attempt to load a BidMachine ad.
     *
     * @param context The current [Context].
     * @param request An [PartnerAdLoadRequest] instance containing relevant data for the current ad load call.
     * @param partnerAdListener A [PartnerAdListener] to notify Chartboost Mediation of ad events.
     *
     * @return Result.success(PartnerAd) if the ad was successfully loaded, Result.failure(Exception) otherwise.
     */
    override suspend fun load(
        context: Context,
        request: PartnerAdLoadRequest,
        partnerAdListener: PartnerAdListener
    ): Result<PartnerAd> {
        PartnerLogController.log(LOAD_STARTED)

        return when (request.format) {
            AdFormat.BANNER -> loadBannerAd(context, request, partnerAdListener)
            AdFormat.INTERSTITIAL -> loadInterstitialAd(context, request, partnerAdListener)
            AdFormat.REWARDED -> loadRewardedAd(context, request, partnerAdListener)
            else -> {
                PartnerLogController.log(LOAD_FAILED)
                Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_LOAD_FAILURE_UNSUPPORTED_AD_FORMAT))
            }
        }
    }

    /**
     * Attempt to show the currently loaded BidMachine ad.
     *
     * @param context The current [Context]
     * @param partnerAd The [PartnerAd] object containing the BidMachine ad to be shown.
     *
     * @return Result.success(PartnerAd) if the ad was successfully shown, Result.failure(Exception) otherwise.
     */
    override suspend fun show(context: Context, partnerAd: PartnerAd): Result<PartnerAd> {
        PartnerLogController.log(SHOW_STARTED)
        return when (partnerAd.request.format) {
            AdFormat.BANNER -> {
                // Banner ads do not have a separate "show" mechanism.
                PartnerLogController.log(SHOW_SUCCEEDED)
                Result.success(partnerAd)
            }
            AdFormat.INTERSTITIAL, AdFormat.REWARDED -> {
                onShowError = { error ->
                    PartnerLogController.log(
                        SHOW_FAILED,
                        "Placement: ${partnerAd.request.partnerPlacement}. Error: ${error.code}"
                    )
                }
                showFullscreenAd(partnerAd)
            }
            else -> {
                PartnerLogController.log(SHOW_FAILED)
                Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_SHOW_FAILURE_UNSUPPORTED_AD_FORMAT))
            }
        }
    }

    /**
     * Discard unnecessary BidMachine ad objects and release resources.
     *
     * @param partnerAd The [PartnerAd] object containing the BidMachine ad to be discarded.
     *
     * @return Result.success(PartnerAd) if the ad was successfully discarded, Result.failure(Exception) otherwise.
     */
    override suspend fun invalidate(partnerAd: PartnerAd): Result<PartnerAd> {
        PartnerLogController.log(INVALIDATE_STARTED)
        return destroyBidMachineAd(partnerAd)
    }

    /**
     * Notify the BidMachine SDK of the GDPR applicability and consent status.
     *
     * @param context The current [Context].
     * @param applies True if GDPR applies, false otherwise.
     * @param gdprConsentStatus The user's GDPR consent status.
     */
    override fun setGdpr(
        context: Context,
        applies: Boolean?,
        gdprConsentStatus: GdprConsentStatus
    ) {
        PartnerLogController.log(
            when (applies) {
                true -> GDPR_APPLICABLE
                false -> GDPR_NOT_APPLICABLE
                else -> GDPR_UNKNOWN
            }
        )

        PartnerLogController.log(
            when (gdprConsentStatus) {
                GdprConsentStatus.GDPR_CONSENT_UNKNOWN -> GDPR_CONSENT_UNKNOWN
                GdprConsentStatus.GDPR_CONSENT_GRANTED -> GDPR_CONSENT_GRANTED
                GdprConsentStatus.GDPR_CONSENT_DENIED -> GDPR_CONSENT_DENIED
            }
        )

        BidMachine.setSubjectToGDPR(applies)

        val userConsented = gdprConsentStatus == GdprConsentStatus.GDPR_CONSENT_GRANTED
        // Chartboost Mediation does not currently have support for consent string.
        BidMachine.setConsentConfig(userConsented, null)
    }

    /**
     * Notify BidMachine of the CCPA compliance.
     * @param context The current [Context].
     * @param hasGrantedCcpaConsent True if the user has granted CCPA consent, false otherwise.
     * @param privacyString The CCPA privacy String.
     */
    override fun setCcpaConsent(
        context: Context,
        hasGrantedCcpaConsent: Boolean,
        privacyString: String
    ) {
        when (hasGrantedCcpaConsent) {
            true -> {
                PartnerLogController.log(CCPA_CONSENT_GRANTED)
            }
            false -> {
                PartnerLogController.log(CCPA_CONSENT_DENIED)
            }
        }

        BidMachine.setUSPrivacyString(privacyString)
    }

    /**
     * Notify BidMachine of the COPPA subjectivity.
     *
     * @param context The current [Context].
     * @param isSubjectToCoppa True if the user is subject to COPPA, false otherwise.
     */
    override fun setUserSubjectToCoppa(context: Context, isSubjectToCoppa: Boolean) {
        PartnerLogController.log(
            if (isSubjectToCoppa) COPPA_SUBJECT
            else COPPA_NOT_SUBJECT
        )

        BidMachine.setCoppa(isSubjectToCoppa)
    }

    /**
     * Attempt to load a BidMachine banner ad.
     *
     * @param context The current [Context].
     * @param request An [PartnerAdLoadRequest] instance containing relevant data for the current ad load call.
     * @param partnerAdListener A [PartnerAdListener] to notify Chartboost Mediation of ad events.
     *
     * @return Result.success(PartnerAd) if the ad was successfully loaded, Result.failure(Exception) otherwise.
     */
    private suspend fun loadBannerAd(
        context: Context,
        request: PartnerAdLoadRequest,
        partnerAdListener: PartnerAdListener,
    ): Result<PartnerAd> {
        return suspendCancellableCoroutine { continuation ->
            val bannerView = BannerView(context)
            bannerView.setListener( object : BannerListener {
                fun resumeOnce(result: Result<PartnerAd>) {
                    if (continuation.isActive) {
                        continuation.resume(result)
                    }
                }

                override fun onAdLoaded(banner: BannerView) {
                    PartnerLogController.log(LOAD_SUCCEEDED)
                    resumeOnce(
                        Result.success(
                            PartnerAd(
                                ad = banner,
                                details = emptyMap(),
                                request = request
                            )
                        )
                    )
                }

                override fun onAdLoadFailed(banner: BannerView, error: BMError) {
                    PartnerLogController.log(LOAD_FAILED, ChartboostMediationError.CM_LOAD_FAILURE_NO_FILL.cause)
                    resumeOnce(Result.failure(ChartboostMediationAdException(getChartboostMediationError(error))))
                }

                override fun onAdImpression(banner: BannerView) {
                    PartnerLogController.log(DID_TRACK_IMPRESSION)
                    partnerAdListener.onPartnerAdImpression(
                        PartnerAd(
                            ad = banner,
                            details = emptyMap(),
                            request = request,
                        )
                    )
                }

                override fun onAdShowFailed(banner: BannerView, error: BMError) {
                    PartnerLogController.log(SHOW_FAILED)
                    resumeOnce(
                        Result.failure(ChartboostMediationAdException(getChartboostMediationError(error)))
                    )
                }

                override fun onAdClicked(banner: BannerView) {
                    PartnerLogController.log(DID_CLICK)
                    partnerAdListener.onPartnerAdClicked(
                        PartnerAd(
                            ad = banner,
                            details = emptyMap(),
                            request = request,
                        )
                    )
                }

                override fun onAdExpired(banner: BannerView) {
                    PartnerLogController.log(CUSTOM, "onAdExpired")
                }
            })

            val adm = request.adm ?: ""
            val bannerRequest = BannerRequest.Builder().setSize(getBidMachineBannerAdSize(request.size))
            if (adm.isNotEmpty()) {
                bannerRequest.setBidPayload(request.adm)
            } else {
                bannerRequest.setPlacementId(request.partnerPlacement)
            }

            bannerView.load(bannerRequest.build())
        }
    }

    /**
     * Find the most appropriate BidMachine ad size for the given screen area based on height.
     *
     * @param size The [Size] to parse for conversion.
     *
     * @return The BidMachine ad size that best matches the given [Size].
     */
    private fun getBidMachineBannerAdSize(size: Size?): BannerSize {
        val height = size?.height ?: return BannerSize.Size_320x50

        return when {
            height in 50 until 90 -> BannerSize.Size_320x50
            height in 90 until 250 -> BannerSize.Size_728x90
            height >= 250 -> BannerSize.Size_300x250
            else -> BannerSize.Size_320x50
        }
    }

    /**
     * Attempt to load a BidMachine interstitial ad.
     *
     * @param context The current [Context].
     * @param request An [PartnerAdLoadRequest] instance containing data to load the ad with.
     * @param partnerAdListener A [PartnerAdListener] to notify Chartboost Mediation of ad events.
     *
     * @return Result.success(PartnerAd) if the ad was successfully loaded, Result.failure(Exception) otherwise.
     */
    private suspend fun loadInterstitialAd(
        context: Context,
        request: PartnerAdLoadRequest,
        partnerAdListener: PartnerAdListener,
    ): Result<PartnerAd> {
        return suspendCancellableCoroutine { continuation ->
            val interstitialAd = InterstitialAd(context)
            interstitialAd.setListener(object : InterstitialListener {
                override fun onAdLoaded(ad: InterstitialAd) {
                    PartnerLogController.log(LOAD_SUCCEEDED)
                    continuation.resume(
                        Result.success(
                            PartnerAd(
                                ad = ad,
                                details = emptyMap(),
                                request = request,
                            )
                        )
                    )
                }

                override fun onAdLoadFailed(ad: InterstitialAd, error: BMError) {
                    PartnerLogController.log(LOAD_FAILED, error.message)
                    continuation.resume(
                        Result.failure(
                            ChartboostMediationAdException(getChartboostMediationError(error))
                        )
                    )

                }

                override fun onAdImpression(ad: InterstitialAd) {
                    PartnerLogController.log(DID_TRACK_IMPRESSION)
                    partnerAdListener.onPartnerAdImpression(
                        PartnerAd(
                            ad = ad,
                            details = emptyMap(),
                            request = request,
                        )
                    )
                }

                override fun onAdShowFailed(ad: InterstitialAd, error: BMError) {
                    PartnerLogController.log(SHOW_FAILED)
                    onShowError(error)
                }

                override fun onAdClicked(ad: InterstitialAd) {
                    PartnerLogController.log(DID_CLICK)
                    partnerAdListener.onPartnerAdClicked(
                        PartnerAd(
                            ad = ad,
                            details = emptyMap(),
                            request = request,
                        )
                    )
                }

                override fun onAdExpired(ad: InterstitialAd) {
                    PartnerLogController.log(DID_EXPIRE)
                }

                override fun onAdClosed(ad: InterstitialAd, isClosed: Boolean) {
                    PartnerLogController.log(DID_DISMISS)
                    partnerAdListener.onPartnerAdDismissed(
                        PartnerAd(
                            ad = ad,
                            details = emptyMap(),
                            request = request,
                        ), null
                    )
                }
            })

            val adm = request.adm ?: ""
            val interstitialRequest = InterstitialRequest.Builder()
            if (adm.isNotEmpty()) {
                interstitialRequest.setBidPayload(request.adm)
            } else {
                interstitialRequest.setPlacementId(request.partnerPlacement)
            }

            interstitialAd.load(interstitialRequest.build())
        }
    }

    /**
     * Attempt to load a BidMachine rewarded ad.
     *
     * @param context The current [Context].
     * @param request The [PartnerAdLoadRequest] containing relevant data for the current ad load call.
     * @param partnerAdListener A [PartnerAdListener] to notify Chartboost Mediation of ad events.
     *
     * @return Result.success(PartnerAd) if the ad was successfully loaded, Result.failure(Exception) otherwise.
     */
    private suspend fun loadRewardedAd(
        context: Context,
        request: PartnerAdLoadRequest,
        partnerAdListener: PartnerAdListener
    ): Result<PartnerAd> {
        return suspendCancellableCoroutine { continuation ->
            val rewardedAd = RewardedAd(context)
            rewardedAd.setListener(object : RewardedListener {
                override fun onAdLoaded(ad: RewardedAd) {
                    PartnerLogController.log(LOAD_SUCCEEDED)
                    continuation.resume(
                        Result.success(
                            PartnerAd(
                                ad = ad,
                                details = emptyMap(),
                                request = request,
                            )
                        )
                    )
                }

                override fun onAdLoadFailed(ad: RewardedAd, error: BMError) {
                    PartnerLogController.log(LOAD_FAILED)
                    continuation.resume(
                        Result.failure(
                            ChartboostMediationAdException(getChartboostMediationError(error))
                        )
                    )
                }

                override fun onAdImpression(ad: RewardedAd) {
                    PartnerLogController.log(DID_TRACK_IMPRESSION)
                    partnerAdListener.onPartnerAdImpression(
                        PartnerAd(
                            ad = ad,
                            details = emptyMap(),
                            request = request,
                        )
                    )
                }

                override fun onAdShowFailed(ad: RewardedAd, error: BMError) {
                    PartnerLogController.log(SHOW_FAILED)
                    onShowError(error)
                }

                override fun onAdClicked(ad: RewardedAd) {
                    PartnerLogController.log(DID_CLICK)
                    partnerAdListener.onPartnerAdClicked(
                        PartnerAd(
                            ad = ad,
                            details = emptyMap(),
                            request = request,
                        )
                    )
                }

                override fun onAdExpired(ad: RewardedAd) {
                    PartnerLogController.log(DID_EXPIRE)
                }

                override fun onAdClosed(ad: RewardedAd, isClosed: Boolean) {
                    PartnerLogController.log(DID_DISMISS)
                    partnerAdListener.onPartnerAdDismissed(
                        PartnerAd(
                            ad = ad,
                            details = emptyMap(),
                            request = request,
                        ), null
                    )
                }

                override fun onAdRewarded(ad: RewardedAd) {
                    PartnerLogController.log(DID_REWARD)
                    partnerAdListener.onPartnerAdRewarded(
                        PartnerAd(
                            ad = ad,
                            details = emptyMap(),
                            request = request,
                        )
                    )
                }
            })

            val adm = request.adm ?: ""
            val rewardedRequest = RewardedRequest.Builder()
            if (adm.isNotEmpty()) {
                rewardedRequest.setBidPayload(request.adm)
            } else {
                rewardedRequest.setPlacementId(request.partnerPlacement)
            }

            rewardedAd.load(rewardedRequest.build())
        }
    }

    /**
     * Attempt to show a BidMachine fullscreen ad.
     *
     * @param partnerAd The [PartnerAd] object containing the BidMachine ad to be shown.
     *
     * @return Result.success(PartnerAd) if the ad was successfully shown, Result.failure(Exception) otherwise.
     */
    private fun showFullscreenAd(
        partnerAd: PartnerAd
    ): Result<PartnerAd> {
        fun canShowAd(canShow: () -> Boolean, show: () -> Unit): Result<PartnerAd> {
            return if (canShow()) {
                show()
                Result.success(partnerAd)
            } else {
                PartnerLogController.log(SHOW_FAILED)
                Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_SHOW_FAILURE_AD_NOT_READY))
            }
        }

        return when (val ad = partnerAd.ad) {
            null -> {
                PartnerLogController.log(SHOW_FAILED, "Ad is null.")
                Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_SHOW_FAILURE_AD_NOT_FOUND))
            }

            is InterstitialAd -> canShowAd(ad::isLoaded, ad::show)
            is RewardedAd -> canShowAd(ad::isLoaded, ad::show)

            else -> {
                PartnerLogController.log(
                    SHOW_FAILED,
                    "Ad is not an instance of InterstitialAd or RewardedAd."
                )
                Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_SHOW_FAILURE_WRONG_RESOURCE_TYPE))
            }
        }
    }

    /**
     * Attempt to destroy BidMachine ad.
     *
     * @param partnerAd The [PartnerAd] instance containing the ad to be destroyed.
     *
     * @return Result.success(PartnerAd) if the ad was successfully destroyed, Result.failure(Exception) otherwise.
     */
    private fun destroyBidMachineAd(partnerAd: PartnerAd): Result<PartnerAd> {
        fun destroyAd(destroy: () -> Unit): Result<PartnerAd> {
            destroy()
            PartnerLogController.log(INVALIDATE_SUCCEEDED)
            return Result.success(partnerAd)
        }

        return when (val ad = partnerAd.ad) {
            null -> {
                PartnerLogController.log(INVALIDATE_SUCCEEDED)
                Result.success(partnerAd)
            }
            is BannerView -> destroyAd(ad::destroy)
            is InterstitialAd -> destroyAd(ad::destroy)
            is RewardedAd -> destroyAd(ad::destroy)

            else -> {
                PartnerLogController.log(INVALIDATE_FAILED, "Ad is not an instance of BannerView, InterstitialAd, or RewardedAd.")
                Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_INVALIDATE_FAILURE_WRONG_RESOURCE_TYPE))
            }
        }
    }

    /**
     * Convert a given BidMachine error into a [ChartboostMediationError].
     *
     * @param error The BidMachine error code to convert.
     *
     * @return The corresponding [ChartboostMediationError].
     */
    private fun getChartboostMediationError(error: BMError?) = when (error) {
        BMError.NoConnection -> ChartboostMediationError.CM_NO_CONNECTIVITY
        BMError.Request -> ChartboostMediationError.CM_LOAD_FAILURE_INVALID_AD_REQUEST
        BMError.Server -> ChartboostMediationError.CM_AD_SERVER_ERROR
        BMError.AlreadyShown -> ChartboostMediationError.CM_SHOW_FAILURE_SHOW_IN_PROGRESS
        BMError.Expired, BMError.Destroyed -> ChartboostMediationError.CM_SHOW_FAILURE_AD_EXPIRED
        BMError.NoFill -> ChartboostMediationError.CM_LOAD_FAILURE_NO_FILL
        BMError.TimeoutError -> ChartboostMediationError.CM_LOAD_FAILURE_TIMEOUT
        BMError.InternalUnknownError -> ChartboostMediationError.CM_INTERNAL_ERROR

        else -> ChartboostMediationError.CM_PARTNER_ERROR
    }
}
