/*
 *    Copyright 2020 Criteo
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package com.criteo.publisher;

import android.util.Log;
import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import com.criteo.publisher.concurrent.RunOnUiThreadExecutor;
import com.criteo.publisher.integration.Integration;
import com.criteo.publisher.integration.IntegrationRegistry;
import com.criteo.publisher.model.InterstitialAdUnit;
import com.criteo.publisher.model.WebViewData;
import com.criteo.publisher.network.PubSdkApi;
import com.criteo.publisher.tasks.InterstitialListenerNotifier;

public class CriteoInterstitial {

  private static final String TAG = CriteoInterstitial.class.getSimpleName();

  @Nullable
  private final InterstitialAdUnit interstitialAdUnit;

  /**
   * Null means that the singleton Criteo should be used.
   * <p>
   * {@link Criteo#getInstance()} is fetched lazily so publishers may call the constructor without
   * having to init the SDK before.
   */
  @Nullable
  private final Criteo criteo;

  @Nullable
  private CriteoInterstitialEventController criteoInterstitialEventController;

  @Nullable
  private CriteoInterstitialAdListener criteoInterstitialAdListener;

  /**
   * Used by server side bidding and in-house auction
   */
  public CriteoInterstitial() {
    this(null, null);
  }

  /**
   * Used by Standalone
   */
  public CriteoInterstitial(@NonNull InterstitialAdUnit interstitialAdUnit) {
    this(interstitialAdUnit, null);
  }

  @VisibleForTesting
  CriteoInterstitial(
      @Nullable InterstitialAdUnit interstitialAdUnit,
      @Nullable Criteo criteo
  ) {
    this.interstitialAdUnit = interstitialAdUnit;
    this.criteo = criteo;
  }

  public void setCriteoInterstitialAdListener(
      @Nullable CriteoInterstitialAdListener criteoInterstitialAdListener
  ) {
    this.criteoInterstitialAdListener = criteoInterstitialAdListener;
  }

  public void loadAd() {
    if (!DependencyProvider.getInstance().isApplicationSet()) {
      Log.w(TAG, "Calling CriteoInterstitial#loadAd with a null application");
      return;
    }

    try {
      doLoadAd();
    } catch (Throwable tr) {
      Log.e(TAG, "Internal error while loading interstitial.", tr);
    }
  }

  private void doLoadAd() {
    getIntegrationRegistry().declare(Integration.STANDALONE);
    getOrCreateController().fetchAdAsync(interstitialAdUnit);
  }

  public void loadAd(@Nullable Bid bid) {
    if (!DependencyProvider.getInstance().isApplicationSet()) {
      Log.w(TAG, "Calling CriteoInterstitial#loadAd(bidToken) with a null application");
      return;
    }

    try {
      doLoadAd(bid);
    } catch (Throwable tr) {
      Log.e(TAG, "Internal error while loading interstitial from bid token.", tr);
    }
  }

  @Keep
  public void loadAdWithDisplayData(@NonNull String displayData) {
    if (!DependencyProvider.getInstance().isApplicationSet()) {
      Log.w(TAG, "Calling CriteoInterstitial#loadAdWithDisplayData with a null application");
      return;
    }

    getOrCreateController().fetchCreativeAsync(displayData);
  }

  private void doLoadAd(@Nullable Bid bid) {
    getIntegrationRegistry().declare(Integration.IN_HOUSE);
    getOrCreateController().fetchAdAsync(bid);
  }

  public boolean isAdLoaded() {
    try {
      return getOrCreateController().isAdLoaded();
    } catch (Throwable tr) {
      Log.e(TAG, "Internal error while detecting interstitial load state.", tr);
      return false;
    }
  }

  public void show() {
    if (!DependencyProvider.getInstance().isApplicationSet()) {
      Log.w(TAG, "Calling CriteoInterstitial#show with a null application");
      return;
    }

    try {
      doShow();
    } catch (Throwable tr) {
      Log.e(TAG, "Internal error while showing interstitial.", tr);
    }
  }

  private void doShow() {
    getOrCreateController().show();
  }

  @NonNull
  @VisibleForTesting
  CriteoInterstitialEventController getOrCreateController() {
    if (criteoInterstitialEventController == null) {
      Criteo criteo = getCriteo();

      InterstitialListenerNotifier listenerNotifier = new InterstitialListenerNotifier(
          this,
          criteoInterstitialAdListener,
          getRunOnUiThreadExecutor()
      );

      criteoInterstitialEventController = new CriteoInterstitialEventController(
          new WebViewData(criteo.getConfig(), getPubSdkApi()),
          criteo.getInterstitialActivityHelper(),
          criteo,
          listenerNotifier
      );
    }
    return criteoInterstitialEventController;
  }

  @NonNull
  private Criteo getCriteo() {
    return criteo == null ? Criteo.getInstance() : criteo;
  }

  @NonNull
  private IntegrationRegistry getIntegrationRegistry() {
    return DependencyProvider.getInstance().provideIntegrationRegistry();
  }

  @NonNull
  private PubSdkApi getPubSdkApi() {
    return DependencyProvider.getInstance().providePubSdkApi();
  }

  @NonNull
  private RunOnUiThreadExecutor getRunOnUiThreadExecutor() {
    return DependencyProvider.getInstance().provideRunOnUiThreadExecutor();
  }

}
