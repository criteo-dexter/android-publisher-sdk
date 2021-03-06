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

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.UiThread;

/**
 * All callbacks are invoked on the UI thread, so it is safe to execute any UI operations in the
 * implementation.
 */
@Keep
public interface CriteoInterstitialAdListener extends CriteoAdListener {

  /**
   * Callback invoked when an interstitial ad is requested and valid bid is answered and creative is
   * successfully received.
   * <p>
   * From this notification, publisher are able to display the interstitial ad call by calling
   * {@link CriteoInterstitial#show()}. It can be done directly in the implementation of this
   * callback, or later.
   */
  @UiThread
  void onAdReceived(@NonNull CriteoInterstitial interstitial);

  /**
   * Callback invoked when an interstitial ad is requested but none may be provided by the SDK.
   *
   * @param code error code indicating the reason of the failure
   */
  @UiThread
  @Override
  default void onAdFailedToReceive(@NonNull CriteoErrorCode code) {
    // no-op by default
  }

  /**
   * Callback invoked when an user clicks anywhere on the interstitial ad.
   */
  @UiThread
  @Override
  default void onAdClicked() {
    // no-op by default
  }

  /**
   * Callback invoked when an ad is opened and the user is redirected outside the application, to
   * the product web page for instance.
   */
  @UiThread
  @Override
  default void onAdLeftApplication() {
    // no-op by default
  }

  /**
   * Callback invoked when an interstitial ad is opened via {@link CriteoInterstitial#show()}.
   */
  @UiThread
  @Override
  default void onAdOpened() {
    // no-op by default
  }

  /**
   * Callback invoked when the user is back from the Ad. This happens generally when the user
   * presses the back button after being redirected to an ad.
   */
  @UiThread
  @Override
  default void onAdClosed() {
    // no-op by default
  }
}

