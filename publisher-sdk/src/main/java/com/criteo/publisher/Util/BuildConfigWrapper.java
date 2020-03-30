package com.criteo.publisher.Util;

import android.support.annotation.NonNull;
import com.criteo.publisher.BuildConfig;

/**
 * Simple wrapper around {@link BuildConfig} constants to ease testing
 */
public class BuildConfigWrapper {

  @NonNull
  public String getSdkVersion() {
    return BuildConfig.VERSION_NAME;
  }

  @NonNull
  public String getCdbUrl() {
    return BuildConfig.cdbUrl;
  }

  @NonNull
  public String getRemoteConfigUrl() {
    return BuildConfig.remoteConfigUrl;
  }

  @NonNull
  public String getEventUrl() {
    return BuildConfig.eventUrl;
  }

  public boolean isDebug() {
    return BuildConfig.DEBUG;
  }

  /**
   * Profile ID used by the SDK, so CDB and the Supply chain can recognize that the request comes
   * from the PublisherSDK.
   */
  public int getProfileId() {
    return BuildConfig.profileId;
  }

  public int getCsmBatchSize() {
    return BuildConfig.csmBatchSize;
  }
}