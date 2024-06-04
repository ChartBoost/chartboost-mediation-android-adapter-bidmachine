/*
 * Copyright 2023-2024 Chartboost, Inc.
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE file.
 */

package com.chartboost.mediation.bidmachineadapter

import android.app.Activity
import android.content.Context
import android.util.Size
import com.chartboost.chartboostmediationsdk.domain.*
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.BIDDER_INFO_FETCH_FAILED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.BIDDER_INFO_FETCH_STARTED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.BIDDER_INFO_FETCH_SUCCEEDED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.CUSTOM
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.DID_CLICK
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.DID_DISMISS
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.DID_EXPIRE
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.DID_REWARD
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.DID_TRACK_IMPRESSION
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.GDPR_CONSENT_DENIED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.GDPR_CONSENT_GRANTED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.GDPR_CONSENT_UNKNOWN
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.INVALIDATE_FAILED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.INVALIDATE_STARTED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.INVALIDATE_SUCCEEDED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.LOAD_FAILED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.LOAD_STARTED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.LOAD_SUCCEEDED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.SETUP_FAILED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.SETUP_STARTED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.SETUP_SUCCEEDED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.SHOW_FAILED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.SHOW_STARTED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.SHOW_SUCCEEDED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.USER_IS_NOT_UNDERAGE
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.USER_IS_UNDERAGE
import com.chartboost.core.consent.ConsentKey
import com.chartboost.core.consent.ConsentKeys
import com.chartboost.core.consent.ConsentValue
import com.chartboost.core.consent.ConsentValues
import io.bidmachine.AdsFormat
import io.bidmachine.BidMachine
import io.bidmachine.banner.BannerListener
import io.bidmachine.banner.BannerRequest
import io.bidmachine.banner.BannerSize
import io.bidmachine.banner.BannerView
import io.bidmachine.interstitial.InterstitialAd
import io.bidmachine.interstitial.InterstitialListener
import io.bidmachine.interstitial.InterstitialRequest
import io.bidmachine.models.RequestBuilder
import io.bidmachine.rewarded.RewardedAd
import io.bidmachine.rewarded.RewardedListener
import io.bidmachine.rewarded.RewardedRequest
import io.bidmachine.utils.BMError
import kotlinx.coroutines.CancellableContinuation
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
         * Key for parsing the BidMachine SDK source ID.
         */
        private const val SOURCE_ID_KEY = "source_id"
    }

    /**
     * The BidMachine adapter configuration.
     */
    override var configuration: PartnerAdapterConfiguration = BidMachineAdapterConfiguration

    /**
     * A map of BidMachine interstitial ads keyed by a request identifier.
     */
    private val bidMachineInterstitialAds = mutableMapOf<String, InterstitialAd>()

    /**
     * A map of BidMachine rewarded ads keyed by a request identifier.
     */
    private val bidMachineRewardedAds = mutableMapOf<String, RewardedAd>()

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
        partnerConfiguration: PartnerConfiguration,
    ): Result<Map<String, Any>> {
        PartnerLogController.log(SETUP_STARTED)

        return suspendCancellableCoroutine { continuation ->
            fun resumeOnce(result: Result<Map<String, Any>>) {
                if (continuation.isActive) {
                    continuation.resume(result)
                }
            }

            Json.decodeFromJsonElement<String>(
                (partnerConfiguration.credentials as JsonObject).getValue(SOURCE_ID_KEY),
            ).trim()
                .takeIf { it.isNotEmpty() }?.let { sourceId ->
                    bidMachineInterstitialAds.clear()
                    bidMachineRewardedAds.clear()
                    BidMachine.initialize(context, sourceId) {
                        if (BidMachine.isInitialized()) {
                            PartnerLogController.log(SETUP_SUCCEEDED)
                            resumeOnce(Result.success(emptyMap()))
                        } else {
                            PartnerLogController.log(SETUP_FAILED)
                            resumeOnce(
                                Result.failure(
                                    ChartboostMediationAdException(ChartboostMediationError.InitializationError.Unknown),
                                ),
                            )
                        }
                    }
                } ?: run {
                PartnerLogController.log(SETUP_FAILED, "Missing source ID.")
                resumeOnce(
                    Result.failure(ChartboostMediationAdException(ChartboostMediationError.InitializationError.InvalidCredentials)),
                )
            }
        }
    }

    /**
     * Get a bid token if network bidding is supported.
     *
     * @param context The current [Context].
     * @param request The [PartnerAdPreBidRequest] instance containing relevant data for the current bid request.
     *
     * @return A Map of biddable token Strings.
     */
    override suspend fun fetchBidderInformation(
        context: Context,
        request: PartnerAdPreBidRequest,
    ): Result<Map<String, String>> {
        PartnerLogController.log(BIDDER_INFO_FETCH_STARTED)

        val adFormat =
            when (request.format) {
                PartnerAdFormats.BANNER -> AdsFormat.Banner
                PartnerAdFormats.INTERSTITIAL -> AdsFormat.Interstitial
                PartnerAdFormats.REWARDED, PartnerAdFormats.REWARDED_INTERSTITIAL -> AdsFormat.Rewarded
                else -> return Result.success(emptyMap())
            }

        return suspendCancellableCoroutine { continuation ->
            BidMachine.getBidToken(context, adFormat) { token ->
                if (continuation.isActive) {
                    PartnerLogController.log(if (token.isEmpty()) BIDDER_INFO_FETCH_FAILED else BIDDER_INFO_FETCH_SUCCEEDED)
                    continuation.resume(Result.success(mapOf("token" to token)))
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
        partnerAdListener: PartnerAdListener,
    ): Result<PartnerAd> {
        PartnerLogController.log(LOAD_STARTED)

        return suspendCancellableCoroutine { continuation ->
            fun resumeOnce(result: Result<PartnerAd>) {
                if (continuation.isActive) {
                    continuation.resume(result)
                }
            }

            when (request.format) {
                PartnerAdFormats.BANNER -> {
                    val bannerBuilder =
                        BannerRequest.Builder().setSize(getBidMachineBannerAdSize(request.bannerSize?.size))
                    val bannerRequest =
                        buildBidMachineAdRequest<BannerRequest>(request, bannerBuilder)

                    attachListener<BannerView>(
                        ad = BannerView(context),
                        request = request,
                        partnerAdListener = partnerAdListener,
                        continuation = continuation,
                    ).load(bannerRequest)
                }
                PartnerAdFormats.INTERSTITIAL -> {
                    val interstitialRequest =
                        buildBidMachineAdRequest<InterstitialRequest>(request, InterstitialRequest.Builder())

                    bidMachineInterstitialAds[request.identifier] =
                        attachListener<InterstitialAd>(
                            ad = InterstitialAd(context),
                            request = request,
                            partnerAdListener = partnerAdListener,
                            continuation = continuation,
                        ).load(interstitialRequest)
                }
                PartnerAdFormats.REWARDED, PartnerAdFormats.REWARDED_INTERSTITIAL -> {
                    val rewardedRequest =
                        buildBidMachineAdRequest<RewardedRequest>(request, RewardedRequest.Builder())

                    bidMachineRewardedAds[request.identifier] =
                        attachListener<RewardedAd>(
                            ad = RewardedAd(context),
                            request = request,
                            partnerAdListener = partnerAdListener,
                            continuation = continuation,
                        ).load(rewardedRequest)
                }
                else -> {
                    PartnerLogController.log(LOAD_FAILED)
                    resumeOnce(
                        Result.failure(ChartboostMediationAdException(ChartboostMediationError.LoadError.UnsupportedAdFormat)),
                    )
                }
            }
        }
    }

    /**
     * Attempt to show the currently loaded BidMachine ad.
     *
     * @param activity The current [Activity]
     * @param partnerAd The [PartnerAd] object containing the BidMachine ad to be shown.
     *
     * @return Result.success(PartnerAd) if the ad was successfully shown, Result.failure(Exception) otherwise.
     */
    override suspend fun show(
        activity: Activity,
        partnerAd: PartnerAd,
    ): Result<PartnerAd> {
        PartnerLogController.log(SHOW_STARTED)

        return when (partnerAd.request.format) {
            PartnerAdFormats.BANNER -> {
                // Banner ads do not have a separate "show" mechanism.
                PartnerLogController.log(SHOW_SUCCEEDED)
                Result.success(partnerAd)
            }
            PartnerAdFormats.INTERSTITIAL, PartnerAdFormats.REWARDED, PartnerAdFormats.REWARDED_INTERSTITIAL ->
                showFullscreenAd(
                    partnerAd,
                )
            else -> {
                PartnerLogController.log(SHOW_FAILED)
                Result.failure(ChartboostMediationAdException(ChartboostMediationError.ShowError.UnsupportedAdFormat))
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

    override fun setConsents(
        context: Context,
        consents: Map<ConsentKey, ConsentValue>,
        modifiedKeys: Set<ConsentKey>,
    ) {
        var userConsented = false
        consents[ConsentKeys.GDPR_CONSENT_GIVEN]?.let {
            PartnerLogController.log(
                when (it) {
                    ConsentValues.GRANTED -> GDPR_CONSENT_GRANTED
                    ConsentValues.DENIED -> GDPR_CONSENT_DENIED
                    else -> GDPR_CONSENT_UNKNOWN
                },
            )
            userConsented = it == ConsentValues.GRANTED
        }
        consents[ConsentKeys.TCF]?.let {
            BidMachine.setConsentConfig(userConsented, it)
        }

        consents[ConsentKeys.USP]?.let {
            PartnerLogController.log(CUSTOM, "US Privacy String: $it")

            BidMachine.setUSPrivacyString(it)
        }
    }

    /**
     * Attach the corresponding listener to the passed BidMachine ad.
     *
     * @param ad An [Any] instance of a BidMachine ad.
     * @param request An [PartnerAdLoadRequest] instance containing relevant data for the current ad load call.
     * @param partnerAdListener A [PartnerAdListener] to notify Chartboost Mediation of ad events.
     * @param continuation A [CancellableContinuation] to notify when the [Result] has succeeded or failed.
     */
    private inline fun <reified T> attachListener(
        ad: Any,
        request: PartnerAdLoadRequest,
        partnerAdListener: PartnerAdListener,
        continuation: CancellableContinuation<Result<PartnerAd>>,
    ): T =
        when (ad) {
            is BannerView -> {
                ad.setListener(
                    createBannerViewListener(
                        request = request,
                        partnerAdListener = partnerAdListener,
                        continuation = continuation,
                    ),
                ) as T
            }
            is InterstitialAd -> {
                ad.setListener(
                    createInterstitialAdListener(
                        request = request,
                        partnerAdListener = partnerAdListener,
                        continuation = continuation,
                    ),
                ) as T
            }
            is RewardedAd -> {
                ad.setListener(
                    createRewardedAdListener(
                        request = request,
                        partnerAdListener = partnerAdListener,
                        continuation = continuation,
                    ),
                ) as T
            }
            else -> {
                ad as T
            }
        }

    /**
     * Notify BidMachine of the COPPA subjectivity.
     *
     * @param context The current [Context].
     * @param isUserUnderage True if the user is subject to COPPA, false otherwise.
     */
    override fun setIsUserUnderage(
        context: Context,
        isUserUnderage: Boolean,
    ) {
        PartnerLogController.log(
            if (isUserUnderage) {
                USER_IS_UNDERAGE
            } else {
                USER_IS_NOT_UNDERAGE
            },
        )

        BidMachine.setCoppa(isUserUnderage)
    }

    /**
     * Build a BidMachine ad request.
     *
     * @param request An [PartnerAdLoadRequest] instance containing relevant data for the current ad load call.
     * @param bidMachineBuilder A [RequestBuilder] instance to
     */
    private inline fun <reified T> buildBidMachineAdRequest(
        request: PartnerAdLoadRequest,
        bidMachineBuilder: RequestBuilder<*, *>,
    ): T {
        val adm = request.adm ?: ""
        return if (adm.isNotEmpty()) {
            bidMachineBuilder.setBidPayload(adm).build() as T
        } else {
            bidMachineBuilder.setPlacementId(request.partnerPlacement).build() as T
        }
    }

    /**
     * Attempt to load a BidMachine banner ad.
     *
     * @param request An [PartnerAdLoadRequest] instance containing relevant data for the current ad load call.
     * @param partnerAdListener A [PartnerAdListener] to notify Chartboost Mediation of ad events.
     * @param continuation A [CancellableContinuation] to notify when the [Result] has succeeded or failed.
     *
     * @return Result.success(PartnerAd) if the ad was successfully loaded, Result.failure(Exception) otherwise.
     */
    private fun createBannerViewListener(
        request: PartnerAdLoadRequest,
        partnerAdListener: PartnerAdListener,
        continuation: CancellableContinuation<Result<PartnerAd>>,
    ) = object : BannerListener {
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
                        request = request,
                    ),
                ),
            )
        }

        override fun onAdLoadFailed(
            banner: BannerView,
            error: BMError,
        ) {
            PartnerLogController.log(LOAD_FAILED, ChartboostMediationError.LoadError.NoFill.cause.toString())
            banner.destroy()
            resumeOnce(Result.failure(ChartboostMediationAdException(getChartboostMediationError(error))))
        }

        override fun onAdImpression(banner: BannerView) {
            PartnerLogController.log(DID_TRACK_IMPRESSION)
            partnerAdListener.onPartnerAdImpression(
                PartnerAd(
                    ad = banner,
                    details = emptyMap(),
                    request = request,
                ),
            )
        }

        override fun onAdShowFailed(
            banner: BannerView,
            error: BMError,
        ) {
            PartnerLogController.log(
                SHOW_FAILED,
                "Failed to show banner ${getChartboostMediationError(error)}",
            )
            banner.destroy()
        }

        override fun onAdClicked(banner: BannerView) {
            PartnerLogController.log(DID_CLICK)
            partnerAdListener.onPartnerAdClicked(
                PartnerAd(
                    ad = banner,
                    details = emptyMap(),
                    request = request,
                ),
            )
        }

        override fun onAdExpired(banner: BannerView) {
            PartnerLogController.log(DID_EXPIRE)
            partnerAdListener.onPartnerAdExpired(
                PartnerAd(
                    ad = banner,
                    details = emptyMap(),
                    request = request,
                ),
            )
        }
    }

    /**
     * Find the most appropriate BidMachine ad size for the given screen area based on height.
     *
     * @param size The [Size] to parse for conversion.
     *
     * @return The BidMachine ad size that best matches the given [Size].
     */
    private fun getBidMachineBannerAdSize(size: Size?) =
        size?.height?.let {
            when {
                it in 50 until 90 -> BannerSize.Size_320x50
                it in 90 until 250 -> BannerSize.Size_728x90
                it >= 250 -> BannerSize.Size_300x250
                else -> BannerSize.Size_320x50
            }
        } ?: BannerSize.Size_320x50

    /**
     * Attempt to load a BidMachine interstitial ad.
     *
     * @param request An [PartnerAdLoadRequest] instance containing data to load the ad with.
     * @param partnerAdListener A [PartnerAdListener] to notify Chartboost Mediation of ad events.
     *
     * @return Result.success(PartnerAd) if the ad was successfully loaded, Result.failure(Exception) otherwise.
     */
    private fun createInterstitialAdListener(
        request: PartnerAdLoadRequest,
        partnerAdListener: PartnerAdListener,
        continuation: CancellableContinuation<Result<PartnerAd>>,
    ) = object : InterstitialListener {
        fun resumeOnce(result: Result<PartnerAd>) {
            if (continuation.isActive) {
                continuation.resume(result)
            }
        }

        override fun onAdLoaded(ad: InterstitialAd) {
            PartnerLogController.log(LOAD_SUCCEEDED)
            resumeOnce(
                Result.success(
                    PartnerAd(
                        ad = ad,
                        details = emptyMap(),
                        request = request,
                    ),
                ),
            )
        }

        override fun onAdLoadFailed(
            ad: InterstitialAd,
            error: BMError,
        ) {
            PartnerLogController.log(LOAD_FAILED, error.message)
            bidMachineInterstitialAds.remove(request.identifier)
            resumeOnce(
                Result.failure(
                    ChartboostMediationAdException(getChartboostMediationError(error)),
                ),
            )
        }

        override fun onAdImpression(ad: InterstitialAd) {
            PartnerLogController.log(DID_TRACK_IMPRESSION)
            partnerAdListener.onPartnerAdImpression(
                PartnerAd(
                    ad = ad,
                    details = emptyMap(),
                    request = request,
                ),
            )
        }

        override fun onAdShowFailed(
            ad: InterstitialAd,
            error: BMError,
        ) {
            PartnerLogController.log(
                SHOW_FAILED,
                "Failed to show banner ${getChartboostMediationError(error)}",
            )
            ad.destroy()
            bidMachineInterstitialAds.remove(request.identifier)
        }

        override fun onAdClicked(ad: InterstitialAd) {
            PartnerLogController.log(DID_CLICK)
            partnerAdListener.onPartnerAdClicked(
                PartnerAd(
                    ad = ad,
                    details = emptyMap(),
                    request = request,
                ),
            )
        }

        override fun onAdExpired(ad: InterstitialAd) {
            PartnerLogController.log(DID_EXPIRE)
            partnerAdListener.onPartnerAdExpired(
                PartnerAd(
                    ad = ad,
                    details = emptyMap(),
                    request = request,
                ),
            )
            bidMachineInterstitialAds.remove(request.identifier)
        }

        override fun onAdClosed(
            ad: InterstitialAd,
            isFinished: Boolean,
        ) {
            PartnerLogController.log(DID_DISMISS)
            partnerAdListener.onPartnerAdDismissed(
                PartnerAd(
                    ad = ad,
                    details = emptyMap(),
                    request = request,
                ),
                null,
            )
            ad.destroy()
            bidMachineInterstitialAds.remove(request.identifier)
        }
    }

    /**
     * Attempt to load a BidMachine rewarded ad.
     *
     * @param request The [PartnerAdLoadRequest] containing relevant data for the current ad load call.
     * @param partnerAdListener A [PartnerAdListener] to notify Chartboost Mediation of ad events.
     *
     * @return Result.success(PartnerAd) if the ad was successfully loaded, Result.failure(Exception) otherwise.
     */
    private fun createRewardedAdListener(
        request: PartnerAdLoadRequest,
        partnerAdListener: PartnerAdListener,
        continuation: CancellableContinuation<Result<PartnerAd>>,
    ) = object : RewardedListener {
        fun resumeOnce(result: Result<PartnerAd>) {
            if (continuation.isActive) {
                continuation.resume(result)
            }
        }

        override fun onAdLoaded(ad: RewardedAd) {
            PartnerLogController.log(LOAD_SUCCEEDED)
            resumeOnce(
                Result.success(
                    PartnerAd(
                        ad = ad,
                        details = emptyMap(),
                        request = request,
                    ),
                ),
            )
        }

        override fun onAdLoadFailed(
            ad: RewardedAd,
            error: BMError,
        ) {
            PartnerLogController.log(LOAD_FAILED)
            bidMachineRewardedAds.remove(request.identifier)
            resumeOnce(
                Result.failure(
                    ChartboostMediationAdException(getChartboostMediationError(error)),
                ),
            )
        }

        override fun onAdImpression(ad: RewardedAd) {
            PartnerLogController.log(DID_TRACK_IMPRESSION)
            partnerAdListener.onPartnerAdImpression(
                PartnerAd(
                    ad = ad,
                    details = emptyMap(),
                    request = request,
                ),
            )
        }

        override fun onAdShowFailed(
            ad: RewardedAd,
            error: BMError,
        ) {
            PartnerLogController.log(
                SHOW_FAILED,
                "Failed to show banner ${getChartboostMediationError(error)}",
            )
            ad.destroy()
            bidMachineRewardedAds.remove(request.identifier)
        }

        override fun onAdClicked(ad: RewardedAd) {
            PartnerLogController.log(DID_CLICK)
            partnerAdListener.onPartnerAdClicked(
                PartnerAd(
                    ad = ad,
                    details = emptyMap(),
                    request = request,
                ),
            )
        }

        override fun onAdExpired(ad: RewardedAd) {
            PartnerLogController.log(DID_EXPIRE)
            partnerAdListener.onPartnerAdExpired(
                PartnerAd(
                    ad = ad,
                    details = emptyMap(),
                    request = request,
                ),
            )
            bidMachineRewardedAds.remove(request.identifier)
        }

        override fun onAdClosed(
            ad: RewardedAd,
            isFinished: Boolean,
        ) {
            PartnerLogController.log(DID_DISMISS)
            partnerAdListener.onPartnerAdDismissed(
                PartnerAd(
                    ad = ad,
                    details = emptyMap(),
                    request = request,
                ),
                null,
            )
            ad.destroy()
            bidMachineRewardedAds.remove(request.identifier)
        }

        override fun onAdRewarded(ad: RewardedAd) {
            PartnerLogController.log(DID_REWARD)
            partnerAdListener.onPartnerAdRewarded(
                PartnerAd(
                    ad = ad,
                    details = emptyMap(),
                    request = request,
                ),
            )
        }
    }

    /**
     * Attempt to show a BidMachine fullscreen ad.
     *
     * @param partnerAd The [PartnerAd] object containing the BidMachine ad to be shown.
     *
     * @return Result.success(PartnerAd) if the ad was successfully shown, Result.failure(Exception) otherwise.
     */
    private fun showFullscreenAd(partnerAd: PartnerAd): Result<PartnerAd> {
        fun canShowAd(
            canShow: () -> Boolean,
            show: () -> Unit,
        ): Result<PartnerAd> {
            return if (canShow()) {
                show()
                Result.success(partnerAd)
            } else {
                PartnerLogController.log(SHOW_FAILED)
                Result.failure(ChartboostMediationAdException(ChartboostMediationError.ShowError.AdNotReady))
            }
        }

        return when (val ad = partnerAd.ad) {
            null -> {
                PartnerLogController.log(SHOW_FAILED, "Ad is null.")
                Result.failure(ChartboostMediationAdException(ChartboostMediationError.ShowError.AdNotFound))
            }

            is InterstitialAd -> canShowAd(ad::canShow, ad::show)
            is RewardedAd -> canShowAd(ad::canShow, ad::show)

            else -> {
                PartnerLogController.log(
                    SHOW_FAILED,
                    "Ad is not an instance of InterstitialAd or RewardedAd.",
                )
                Result.failure(ChartboostMediationAdException(ChartboostMediationError.ShowError.WrongResourceType))
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
                Result.failure(ChartboostMediationAdException(ChartboostMediationError.InvalidateError.WrongResourceType))
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
    private fun getChartboostMediationError(error: BMError?) =
        when (error) {
            BMError.NoConnection -> ChartboostMediationError.OtherError.NoConnectivity
            BMError.Request -> ChartboostMediationError.LoadError.InvalidAdRequest
            BMError.Server -> ChartboostMediationError.OtherError.AdServerError
            BMError.AlreadyShown -> ChartboostMediationError.ShowError.ShowInProgress
            BMError.Expired, BMError.Destroyed -> ChartboostMediationError.ShowError.AdExpired
            BMError.NoFill -> ChartboostMediationError.LoadError.NoFill
            BMError.TimeoutError -> ChartboostMediationError.LoadError.AdRequestTimeout
            BMError.InternalUnknownError -> ChartboostMediationError.OtherError.InternalError

            else -> ChartboostMediationError.OtherError.PartnerError
        }
}
