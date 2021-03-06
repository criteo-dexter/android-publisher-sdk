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

import static com.criteo.publisher.concurrent.ThreadingUtil.waitForMessageQueueToBeIdle;
import static com.criteo.publisher.util.AdUnitType.CRITEO_BANNER;
import static com.criteo.publisher.util.CompletableFuture.completedFuture;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.AdditionalAnswers.answerVoid;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import androidx.annotation.NonNull;
import com.criteo.publisher.bid.BidLifecycleListener;
import com.criteo.publisher.cache.SdkCache;
import com.criteo.publisher.concurrent.TrackingCommandsExecutorWithDelay;
import com.criteo.publisher.csm.MetricSendingQueueConsumer;
import com.criteo.publisher.integration.IntegrationRegistry;
import com.criteo.publisher.mock.MockBean;
import com.criteo.publisher.mock.MockedDependenciesRule;
import com.criteo.publisher.mock.SpyBean;
import com.criteo.publisher.model.AdSize;
import com.criteo.publisher.model.AdUnit;
import com.criteo.publisher.model.AdUnitMapper;
import com.criteo.publisher.model.CacheAdUnit;
import com.criteo.publisher.model.CdbRequest;
import com.criteo.publisher.model.CdbRequestSlot;
import com.criteo.publisher.model.CdbResponse;
import com.criteo.publisher.model.CdbResponseSlot;
import com.criteo.publisher.model.Config;
import com.criteo.publisher.model.DeviceInfo;
import com.criteo.publisher.model.Publisher;
import com.criteo.publisher.model.RemoteConfigResponse;
import com.criteo.publisher.model.User;
import com.criteo.publisher.network.LiveBidRequestSender;
import com.criteo.publisher.network.PubSdkApi;
import com.criteo.publisher.privacy.UserPrivacyUtil;
import com.criteo.publisher.privacy.gdpr.GdprData;
import com.criteo.publisher.util.AdUnitType;
import com.criteo.publisher.util.AdvertisingInfo;
import com.criteo.publisher.util.BuildConfigWrapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import javax.inject.Inject;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

public class BidManagerFunctionalTest {

  /**
   * Default TTL (in seconds) overridden on immediate bids (CPM > 0, TTL = 0)
   */
  private static final int DEFAULT_TTL_IN_SECONDS = 900;

  @Rule
  public MockedDependenciesRule mockedDependenciesRule = new MockedDependenciesRule();

  private DependencyProvider dependencyProvider;

  @MockBean
  private Publisher publisher;

  private SdkCache cache;

  @MockBean
  private Config config;

  @MockBean
  private PubSdkApi api;

  @MockBean
  private Clock clock;

  @SpyBean
  private LiveBidRequestSender liveBidRequestSender;

  @MockBean
  private BidLifecycleListener bidLifecycleListener;

  @SpyBean
  private BuildConfigWrapper buildConfigWrapper;

  @SpyBean
  private IntegrationRegistry integrationRegistry;

  @MockBean
  private MetricSendingQueueConsumer metricSendingQueueConsumer;

  @SpyBean
  private UserPrivacyUtil userPrivacyUtil;

  @SpyBean
  private DeviceInfo deviceInfo;

  @Inject
  private AdvertisingInfo advertisingInfo;

  private int adUnitId = 0;

  @Before
  public void setUp() throws Exception {
    MockitoAnnotations.initMocks(this);

    dependencyProvider = mockedDependenciesRule.getDependencyProvider();

    cache = spy(new SdkCache(dependencyProvider.provideDeviceUtil()));

    when(publisher.getCriteoPublisherId()).thenReturn("cpId");
    when(publisher.getBundleId()).thenReturn("bundle.id");
    when(config.isPrefetchOnInitEnabled()).thenReturn(true);

    // Should be set to at least 1 because user-level silent mode is set the 0 included
    givenMockedClockSetTo(1);

    // Given unrelated ad units in the cache, the tests should ignore them
    givenNotExpiredValidCachedBid(sampleAdUnit());
    givenExpiredValidCachedBid(sampleAdUnit());
    givenNotExpiredSilentModeBidCached(sampleAdUnit());
    givenExpiredSilentModeBidCached(sampleAdUnit());
    givenNoLastBid(sampleAdUnit());
    givenTimeBudgetRespectedWhenFetchingLiveBids();
  }

  @Test
  public void prefetch_GivenAdUnitsAndPrefetchDisabled_ShouldCallRemoteConfigButNotCdb() throws Exception {
    when(config.isPrefetchOnInitEnabled()).thenReturn(false);

    RemoteConfigResponse response = mock(RemoteConfigResponse.class);
    when(api.loadConfig(any())).thenReturn(response);

    List<AdUnit> prefetchAdUnits = Arrays.asList(
        mock(AdUnit.class),
        mock(AdUnit.class),
        mock(AdUnit.class)
    );

    AdUnitMapper mapper = givenMockedAdUnitMapper();

    BidManager bidManager = createBidManager();
    bidManager.prefetch(prefetchAdUnits);
    waitForIdleState();

    verifyNoInteractions(mapper);
    assertShouldNotCallCdbAndNotPopulateCache();
    verify(config).refreshConfig(response);
  }

  @Test
  public void prefetch_GivenNoAdUnit_ShouldNotCallCdbAndPopulateCache() throws Exception {
    BidManager bidManager = createBidManager();
    bidManager.prefetch(emptyList());
    waitForIdleState();

    assertShouldNotCallCdbAndNotPopulateCache();
  }

  @Test
  public void prefetch_GivenNoAdUnit_ShouldUpdateConfig() throws Exception {
    RemoteConfigResponse response = mock(RemoteConfigResponse.class);
    when(api.loadConfig(any())).thenReturn(response);

    BidManager bidManager = spy(createBidManager());
    bidManager.prefetch(emptyList());
    waitForIdleState();

    verify(config).refreshConfig(response);
    verify(api, never()).loadCdb(any(), any());
  }

  @Test
  public void prefetch_GivenAdUnits_ShouldCallCdbAndPopulateCache() throws Exception {
    List<AdUnit> prefetchAdUnits = Arrays.asList(
        mock(AdUnit.class),
        mock(AdUnit.class),
        mock(AdUnit.class)
    );

    List<List<CacheAdUnit>> mappedAdUnitsChunks = singletonList(Arrays.asList(
        sampleAdUnit(),
        sampleAdUnit()
    ));

    AdUnitMapper mapper = givenMockedAdUnitMapper();

    CdbResponseSlot slot = givenMockedCdbRespondingSlot();

    when(mapper.mapToChunks(prefetchAdUnits)).thenReturn(mappedAdUnitsChunks);

    BidManager bidManager = createBidManager();
    bidManager.prefetch(prefetchAdUnits);
    waitForIdleState();

    assertShouldCallCdbAndPopulateCacheOnlyOnce(mappedAdUnitsChunks.get(0), slot);
  }

  @Test
  public void prefetch_GivenMapperSplittingIntoChunks_ExecuteChunksIndependently()
      throws Exception {
    // Remove concurrency. This would make this test really hard to follow.
    // We should wait for idle state of main thread every time because the async task post execution
    // is running on it.
    when(dependencyProvider.provideThreadPoolExecutor()).thenReturn(runnable -> {
      runnable.run();
      waitForMessageQueueToBeIdle();
    });

    List<AdUnit> prefetchAdUnits = Arrays.asList(
        mock(AdUnit.class),
        mock(AdUnit.class),
        mock(AdUnit.class)
    );

    List<CacheAdUnit> requestedAdUnits1 = singletonList(sampleAdUnit());
    List<CacheAdUnit> requestedAdUnits2 = singletonList(sampleAdUnit());
    List<CacheAdUnit> requestedAdUnits3 = singletonList(sampleAdUnit());
    List<List<CacheAdUnit>> mappedAdUnitsChunks = Arrays.asList(
        requestedAdUnits1,
        requestedAdUnits2,
        requestedAdUnits3
    );

    AdUnitMapper mapper = givenMockedAdUnitMapper();
    when(mapper.mapToChunks(prefetchAdUnits)).thenReturn(mappedAdUnitsChunks);

    CdbResponse response1 = givenMockedCdbResponseWithValidSlot(1);
    CdbResponse response3 = givenMockedCdbResponseWithValidSlot(3);
    RemoteConfigResponse remoteConfigResponse = mock(RemoteConfigResponse.class);

    when(api.loadCdb(any(), any()))
        .thenReturn(response1)
        .thenThrow(IOException.class)
        .thenReturn(response3);
    when(api.loadConfig(any())).thenReturn(remoteConfigResponse);

    BidManager bidManager = spy(createBidManager());
    bidManager.prefetch(prefetchAdUnits);
    waitForIdleState();

    InOrder inOrder = inOrder(bidManager, cache, api, config);

    // First call with only config call
    inOrder.verify(config).refreshConfig(remoteConfigResponse);

    // First call to CDB
    inOrder.verify(config, never()).refreshConfig(any());
    inOrder.verify(api)
        .loadCdb(argThat(cdb -> requestedAdUnits1.equals(getRequestedAdUnits(cdb))), any());
    response1.getSlots().forEach(inOrder.verify(cache)::add);
    inOrder.verify(bidManager).setTimeToNextCall(1);

    // Second call with error
    inOrder.verify(api)
        .loadCdb(argThat(cdb -> requestedAdUnits2.equals(getRequestedAdUnits(cdb))), any());

    // Third call in success but without the config call
    inOrder.verify(config, never()).refreshConfig(any());
    inOrder.verify(api)
        .loadCdb(argThat(cdb -> requestedAdUnits3.equals(getRequestedAdUnits(cdb))), any());
    response3.getSlots().forEach(inOrder.verify(cache)::add);
    inOrder.verify(bidManager).setTimeToNextCall(3);

    inOrder.verifyNoMoreInteractions();
  }

  private CdbResponse givenMockedCdbResponseWithValidSlot(int timeToNextCall) {
    CdbResponseSlot slot = mock(CdbResponseSlot.class);
    when(slot.isValid()).thenReturn(true);

    CdbResponse response = mock(CdbResponse.class);
    when(response.getSlots()).thenReturn(singletonList(slot));
    when(response.getTimeToNextCall()).thenReturn(timeToNextCall);
    return response;
  }

  @Test
  public void prefetch_GivenKillSwitchIsEnabled_ShouldNotCallCdbAndNotPopulateCache()
      throws Exception {
    CacheAdUnit cacheAdUnit = sampleAdUnit();
    AdUnit adUnit = givenMockedAdUnitMappingTo(cacheAdUnit);
    givenKillSwitchIs(true);

    BidManager bidManager = createBidManager();
    bidManager.prefetch(singletonList(adUnit));
    waitForIdleState();

    assertShouldNotCallCdbAndNotPopulateCache();
  }

  @Test
  public void prefetch_GivenRemoteConfigWithKillSwitchEnabled_WhenGettingBidShouldNotCallCdbAndNotPopulateCacheAndReturnNull()
      throws Exception {
    givenKillSwitchIs(false);
    doAnswer(answerVoid((RemoteConfigResponse response) -> {
      Boolean killSwitch = response.getKillSwitch();
      when(config.isKillSwitchEnabled()).thenReturn(killSwitch);
    })).when(config).refreshConfig(any());

    CacheAdUnit cacheAdUnit = sampleAdUnit();
    AdUnit adUnit = givenMockedAdUnitMappingTo(cacheAdUnit);
    givenRemoteConfigWithKillSwitchEnabled();

    BidManager bidManager = createBidManager();
    bidManager.prefetch(singletonList(adUnit));
    waitForIdleState();

    clearInvocations(cache);
    clearInvocations(api);
    clearInvocations(bidLifecycleListener);

    CdbResponseSlot bid = bidManager.getBidForAdUnitAndPrefetch(adUnit);

    assertShouldNotCallCdbAndNotPopulateCache();
    assertNull(bid);
  }

  @Test
  public void prefetch_GivenAdUnitAndGlobalInformation_ShouldCallCdbWithExpectedInfo()
      throws Exception {
    callingCdb_GivenAdUnitAndGlobalInformation_ShouldCallCdbWithExpectedInfo(adUnit -> {
      BidManager bidManager = createBidManager();
      bidManager.prefetch(singletonList(adUnit));
    });
  }

  @Test
  public void getBidForAdUnitAndPrefetch_GivenAdUnitAndGlobalInformation_ShouldCallCdbWithExpectedInfo()
      throws Exception {
    callingCdb_GivenAdUnitAndGlobalInformation_ShouldCallCdbWithExpectedInfo(adUnit -> {
      BidManager bidManager = createBidManager();
      bidManager.getBidForAdUnitAndPrefetch(adUnit);
    });
  }

  private void callingCdb_GivenAdUnitAndGlobalInformation_ShouldCallCdbWithExpectedInfo(
      Consumer<AdUnit> callingCdb
  ) throws Exception {
    doReturn(completedFuture("expectedUserAgent")).when(deviceInfo).getUserAgent();

    GdprData expectedGdpr = mock(GdprData.class);
    when(userPrivacyUtil.getGdprData()).thenReturn(expectedGdpr);
    when(userPrivacyUtil.getUsPrivacyOptout()).thenReturn("");
    when(userPrivacyUtil.getIabUsPrivacyString()).thenReturn("");
    when(userPrivacyUtil.getMopubConsent()).thenReturn("");

    when(buildConfigWrapper.getSdkVersion()).thenReturn("1.2.3");
    when(integrationRegistry.getProfileId()).thenReturn(42);

    CacheAdUnit cacheAdUnit = sampleAdUnit();
    AdUnit adUnit = givenMockedAdUnitMappingTo(cacheAdUnit);

    callingCdb.accept(adUnit);
    waitForIdleState();

    User expectedUser = User.create(
        advertisingInfo.getAdvertisingId(),
        null,
        null,
        null
    );

    verify(api).loadCdb(argThat(cdb -> {
      assertThat(cdb.getPublisher()).isEqualTo(publisher);
      assertThat(cdb.getUser()).isEqualTo(expectedUser);
      assertThat(getRequestedAdUnits(cdb)).containsExactly(cacheAdUnit);
      assertThat(cdb.getSdkVersion()).isEqualTo("1.2.3");
      assertThat(cdb.getProfileId()).isEqualTo(42);
      assertThat(cdb.getGdprData()).isEqualTo(expectedGdpr);

      return true;
    }), eq("expectedUserAgent"));
  }

  @Test
  public void getBidForAdUnitAndPrefetch_GivenNotValidAdUnit_ReturnNullAndDoNotCallCdb()
      throws Exception {
    AdUnit adUnit = givenMockedAdUnitMappingTo(null);

    BidManager bidManager = createBidManager();
    CdbResponseSlot bid = bidManager.getBidForAdUnitAndPrefetch(adUnit);
    waitForIdleState();

    assertNull(bid);
    verify(api, never()).loadCdb(any(), any());
  }

  @Test
  public void getBidForAdUnitAndPrefetch_GivenNullAdUnit_ReturnNullAndDoNotCallCdb()
      throws Exception {
    BidManager bidManager = createBidManager();
    CdbResponseSlot bid = bidManager.getBidForAdUnitAndPrefetch(null);
    waitForIdleState();

    assertNull(bid);
    verify(api, never()).loadCdb(any(), any());
  }

  @Test
  public void getBidForAdUnitAndPrefetch_GivenNotExpiredValidCachedBid_ReturnItAndRemoveItFromCache()
      throws Exception {
    CacheAdUnit cacheAdUnit = sampleAdUnit();
    AdUnit adUnit = givenMockedAdUnitMappingTo(cacheAdUnit);
    CdbResponseSlot slot = givenNotExpiredValidCachedBid(cacheAdUnit);

    BidManager bidManager = createBidManager();
    CdbResponseSlot bid = bidManager.getBidForAdUnitAndPrefetch(adUnit);

    assertEquals(slot, bid);
    verify(cache).remove(cacheAdUnit);
    assertListenerIsNotifyForBidConsumed(cacheAdUnit, bid);
  }

  @Test
  public void getBidForAdUnitAndPrefetch_GivenNotExpiredValidCachedBid_ShouldCallCdbAndPopulateCache()
      throws Exception {
    CacheAdUnit cacheAdUnit = sampleAdUnit();
    AdUnit adUnit = givenMockedAdUnitMappingTo(cacheAdUnit);
    givenNotExpiredValidCachedBid(cacheAdUnit);
    CdbResponseSlot slot = givenMockedCdbRespondingSlot();

    BidManager bidManager = createBidManager();
    bidManager.getBidForAdUnitAndPrefetch(adUnit);
    waitForIdleState();

    assertShouldCallCdbAndPopulateCacheOnlyOnce(singletonList(cacheAdUnit), slot);
  }

  @Test
  public void getBidForAdUnitAndPrefetch_GivenAdUnitBeingLoaded_ShouldCallCdbAndPopulateCacheOnlyOnceForThePendingCall()
      throws Exception {
    CacheAdUnit cacheAdUnit = sampleAdUnit();
    AdUnit adUnit = givenMockedAdUnitMappingTo(cacheAdUnit);

    CdbResponse response = mock(CdbResponse.class);
    CdbResponseSlot slot = mock(CdbResponseSlot.class);
    when(slot.isValid()).thenReturn(true);
    when(response.getSlots()).thenReturn(singletonList(slot));

    // We force a synchronization here to make the test deterministic.
    // Hence we can predict that the second bid manager call is done after the cdb call.
    // The test should also work in the other way (see the other "given ad unit being loaded" test).
    CountDownLatch cdbRequestHasStarted = new CountDownLatch(1);

    CountDownLatch cdbRequestIsPending = new CountDownLatch(1);
    when(api.loadCdb(any(), any())).thenAnswer(invocation -> {
      cdbRequestHasStarted.countDown();
      cdbRequestIsPending.await();
      return response;
    });

    BidManager bidManager = spy(createBidManager());
    bidManager.getBidForAdUnitAndPrefetch(adUnit);
    cdbRequestHasStarted.await();
    bidManager.getBidForAdUnitAndPrefetch(adUnit);
    cdbRequestIsPending.countDown();
    waitForIdleState();

    // It is expected, with those two calls to the bid manager, that only one CDB call and only one
    // cache update is done. Indeed, the only CDB call correspond to the one mocked above with the
    // latch "slowing the network call". The only cache update is the one done after this single CDB
    // call. Hence, the second bid manager call, which happen between the CDB call and the cache
    // update should do nothing.

    InOrder inOrder = inOrder(bidManager, api, cache);
    inOrder.verify(bidManager).getBidForAdUnitAndPrefetch(adUnit);
    inOrder.verify(api).loadCdb(any(), any());
    inOrder.verify(bidManager).getBidForAdUnitAndPrefetch(adUnit);
    inOrder.verify(cache).add(slot);
    inOrder.verify(bidManager).setTimeToNextCall(anyInt());
    inOrder.verifyNoMoreInteractions();
  }

  @Test
  public void getBidForAdUnitAndPrefetch_GivenAdUnitBeingLoaded_ShouldCallCdbAndPopulateCacheOnlyOnceForThePendingCall2()
      throws Exception {
    CacheAdUnit cacheAdUnit = sampleAdUnit();
    AdUnit adUnit = givenMockedAdUnitMappingTo(cacheAdUnit);
    CdbResponseSlot slot = givenMockedCdbRespondingSlot();

    // We force the CDB call to be after the second bid manager call to make the test deterministic.
    CountDownLatch bidManagerIsCalledASecondTime = givenExecutorWaitingOn();

    BidManager bidManager = spy(createBidManager());
    bidManager.getBidForAdUnitAndPrefetch(adUnit);
    bidManager.getBidForAdUnitAndPrefetch(adUnit);
    bidManagerIsCalledASecondTime.countDown();
    waitForIdleState();

    // It is expected, with those two calls to the bid manager, that only one CDB call and only one
    // cache update is done. Indeed, the only CDB call correspond to the one triggered by the first
    // bid manager call but run after the second bid manager call. The only cache update is the one
    // done after this single CDB call. Hence, the second bid manager call, which happen before the
    // CDB call and the cache update should do nothing.

    InOrder inOrder = inOrder(bidManager, api, cache);
    inOrder.verify(bidManager).getBidForAdUnitAndPrefetch(adUnit);
    inOrder.verify(bidManager).getBidForAdUnitAndPrefetch(adUnit);
    inOrder.verify(api, timeout(1000)).loadCdb(any(), any());
    inOrder.verify(cache).add(slot);
    inOrder.verify(bidManager).setTimeToNextCall(anyInt());
    inOrder.verifyNoMoreInteractions();
  }

  @Test
  public void getBidForAdUnitAndPrefetch_GivenEmptyCache_ReturnNull() throws Exception {
    CacheAdUnit cacheAdUnit = sampleAdUnit();
    AdUnit adUnit = givenMockedAdUnitMappingTo(cacheAdUnit);

    givenNoLastBid(cacheAdUnit);

    BidManager bidManager = createBidManager();
    CdbResponseSlot bid = bidManager.getBidForAdUnitAndPrefetch(adUnit);

    assertNull(bid);
    assertListenerIsNotNotifyForBidConsumed();
  }

  @Test
  public void getBidForAdUnitAndPrefetch_GivenEmptyCache_ShouldCallCdbAndPopulateCache()
      throws Exception {
    CacheAdUnit cacheAdUnit = sampleAdUnit();
    AdUnit adUnit = givenMockedAdUnitMappingTo(cacheAdUnit);
    CdbResponseSlot slot = givenMockedCdbRespondingSlot();

    givenNoLastBid(cacheAdUnit);

    BidManager bidManager = createBidManager();
    bidManager.getBidForAdUnitAndPrefetch(adUnit);
    waitForIdleState();

    assertShouldCallCdbAndPopulateCacheOnlyOnce(singletonList(cacheAdUnit), slot);
  }

  @Test
  public void getBidForAdUnitAndPrefetch_GivenEmptyCacheAndApiError_ShouldNotifyListener()
      throws Exception {
    CacheAdUnit cacheAdUnit = sampleAdUnit();
    AdUnit adUnit = givenMockedAdUnitMappingTo(cacheAdUnit);

    givenNoLastBid(cacheAdUnit);

    when(api.loadCdb(any(), any())).thenThrow(IOException.class);

    BidManager bidManager = createBidManager();
    bidManager.getBidForAdUnitAndPrefetch(adUnit);
    waitForIdleState();

    verify(bidLifecycleListener).onCdbCallStarted(any());
    verify(bidLifecycleListener).onCdbCallFailed(any(), any());
  }

  @Test
  public void getBidForAdUnitAndPrefetch_GivenClockAtFixedTime_CacheShouldContainATimestampedBid()
      throws Exception {
    CacheAdUnit cacheAdUnit = sampleAdUnit();
    AdUnit adUnit = givenMockedAdUnitMappingTo(cacheAdUnit);
    CdbResponseSlot slot = givenMockedCdbRespondingSlot();
    givenMockedClockSetTo(42);

    BidManager bidManager = createBidManager();
    bidManager.getBidForAdUnitAndPrefetch(adUnit);
    waitForIdleState();

    InOrder inOrder = inOrder(cache, slot);
    inOrder.verify(slot).setTimeOfDownload(42);
    inOrder.verify(cache).add(slot);
  }

  @Test
  public void getBidForAdUnitAndPrefetch_GivenExpiredValidCachedBid_ReturnNull() throws Exception {
    CacheAdUnit cacheAdUnit = sampleAdUnit();
    AdUnit adUnit = givenMockedAdUnitMappingTo(cacheAdUnit);
    CdbResponseSlot internalBid = givenExpiredValidCachedBid(cacheAdUnit);

    BidManager bidManager = createBidManager();
    CdbResponseSlot bid = bidManager.getBidForAdUnitAndPrefetch(adUnit);

    assertNull(bid);
    assertListenerIsNotifyForBidConsumed(cacheAdUnit, internalBid);
  }

  @Test
  public void getBidForAdUnitAndPrefetch_GivenExpiredValidCachedBid_ShouldCallCdbAndPopulateCache()
      throws Exception {
    CacheAdUnit cacheAdUnit = sampleAdUnit();
    AdUnit adUnit = givenMockedAdUnitMappingTo(cacheAdUnit);
    givenExpiredValidCachedBid(cacheAdUnit);
    CdbResponseSlot slot = givenMockedCdbRespondingSlot();

    BidManager bidManager = createBidManager();
    bidManager.getBidForAdUnitAndPrefetch(adUnit);
    waitForIdleState();

    assertShouldCallCdbAndPopulateCacheOnlyOnce(singletonList(cacheAdUnit), slot);
  }

  @Test
  public void getBidForAdUnitAndPrefetch_GivenNoBidFetched_ShouldNotPopulateCache() throws Exception {
    CacheAdUnit cacheAdUnit = sampleAdUnit();
    AdUnit adUnit = givenMockedAdUnitMappingTo(cacheAdUnit);
    CdbResponseSlot slot = givenMockedCdbRespondingSlot();
    when(slot.getCpmAsNumber()).thenReturn(0.);
    when(slot.getTtlInSeconds()).thenReturn(0);

    BidManager bidManager = createBidManager();
    bidManager.getBidForAdUnitAndPrefetch(adUnit);

    assertNoLiveBidIsCached();
  }

  @Test
  public void getBidForAdUnitAndPrefetch_GivenNotExpiredSilentModeBidCached_ReturnNullAndDoNotRemoveIt()
      throws Exception {
    CacheAdUnit cacheAdUnit = sampleAdUnit();
    AdUnit adUnit = givenMockedAdUnitMappingTo(cacheAdUnit);
    givenNotExpiredSilentModeBidCached(cacheAdUnit);

    BidManager bidManager = createBidManager();
    CdbResponseSlot bid = bidManager.getBidForAdUnitAndPrefetch(adUnit);

    assertNull(bid);
    verify(cache, never()).remove(cacheAdUnit);
    assertListenerIsNotNotifyForBidConsumed();
  }

  @Test
  public void getBidForAdUnitAndPrefetch_GivenNotExpiredSilentModeBidCached_ShouldNotCallCdbAndNotPopulateCache()
      throws Exception {
    CacheAdUnit cacheAdUnit = sampleAdUnit();
    AdUnit adUnit = givenMockedAdUnitMappingTo(cacheAdUnit);
    givenNotExpiredSilentModeBidCached(cacheAdUnit);

    BidManager bidManager = createBidManager();
    bidManager.getBidForAdUnitAndPrefetch(adUnit);

    assertShouldNotCallCdbAndNotPopulateCache();
  }

  @Test
  public void getBidForAdUnitAndPrefetch_GivenExpiredSilentModeBidCached_ReturnNull()
      throws Exception {
    CacheAdUnit cacheAdUnit = sampleAdUnit();
    AdUnit adUnit = givenMockedAdUnitMappingTo(cacheAdUnit);
    CdbResponseSlot internalBid = givenExpiredSilentModeBidCached(cacheAdUnit);

    BidManager bidManager = createBidManager();
    CdbResponseSlot bid = bidManager.getBidForAdUnitAndPrefetch(adUnit);

    assertNull(bid);
    assertListenerIsNotifyForBidConsumed(cacheAdUnit, internalBid);
  }

  @Test
  public void getBidForAdUnitAndPrefetch_GivenExpiredSilentModeBidCached_ShouldCallCdbAndPopulateCache()
      throws Exception {
    CacheAdUnit cacheAdUnit = sampleAdUnit();
    AdUnit adUnit = givenMockedAdUnitMappingTo(cacheAdUnit);
    givenExpiredSilentModeBidCached(cacheAdUnit);
    CdbResponseSlot slot = givenMockedCdbRespondingSlot();

    BidManager bidManager = createBidManager();
    bidManager.getBidForAdUnitAndPrefetch(adUnit);
    waitForIdleState();

    assertShouldCallCdbAndPopulateCacheOnlyOnce(singletonList(cacheAdUnit), slot);
  }

  @Test
  public void getBidForAdUnitAndPrefetch_GivenNotExpiredUserLevelSilentMode_ShouldNotCallCdbAndNotPopulateCache()
      throws Exception {
    CacheAdUnit cacheAdUnit = sampleAdUnit();
    AdUnit adUnit = givenMockedAdUnitMappingTo(cacheAdUnit);

    BidManager bidManager = createBidManager();
    givenMockedClockSetTo(0);
    bidManager.setTimeToNextCall(60); // Silent until 60_000 excluded
    givenMockedClockSetTo(60_000 - 1);
    bidManager.getBidForAdUnitAndPrefetch(adUnit);
    waitForIdleState();

    assertShouldNotCallCdbAndNotPopulateCache();
  }

  @Test
  public void getBidForAdUnitAndPrefetch_GivenExpiredUserLevelSilentMode_ShouldCallCdbAndPopulateCache()
      throws Exception {
    CacheAdUnit cacheAdUnit = sampleAdUnit();
    AdUnit adUnit = givenMockedAdUnitMappingTo(cacheAdUnit);
    CdbResponseSlot slot = givenMockedCdbRespondingSlot();

    BidManager bidManager = createBidManager();
    givenMockedClockSetTo(0);
    bidManager.setTimeToNextCall(60); // Silent until 60_000 included
    givenMockedClockSetTo(60_001);
    bidManager.getBidForAdUnitAndPrefetch(adUnit);
    waitForIdleState();

    assertShouldCallCdbAndPopulateCacheOnlyOnce(singletonList(cacheAdUnit), slot);
  }

  @Test
  public void getBidForAdUnitAndPrefetch_GivenCdbCallAndCachedPopulatedWithUserLevelSilentMode_UserLevelSilentModeIsUpdated()
      throws Exception {
    CacheAdUnit cacheAdUnit = sampleAdUnit();
    AdUnit adUnit = givenMockedAdUnitMappingTo(cacheAdUnit);
    CdbResponse cdbResponse = givenMockedCdbResponse();

    when(cdbResponse.getTimeToNextCall()).thenReturn(1337);

    BidManager bidManager = spy(createBidManager());
    bidManager.getBidForAdUnitAndPrefetch(adUnit);
    waitForIdleState();

    verify(bidManager).setTimeToNextCall(1337);
  }

  @Test
  public void getBidForAdUnitAndPrefetch_GivenFirstCdbCallWithoutUserLevelSilenceAndASecondFetchJustAfter_SecondFetchIsNotSilenced()
      throws Exception {
    CacheAdUnit cacheAdUnit = sampleAdUnit();
    AdUnit adUnit = givenMockedAdUnitMappingTo(cacheAdUnit);

    BidManager bidManager = createBidManager();

    // Given first CDB call without user-level silence
    CdbResponse cdbResponse = givenMockedCdbResponse();
    when(cdbResponse.getTimeToNextCall()).thenReturn(0);
    bidManager.getBidForAdUnitAndPrefetch(adUnit);
    waitForIdleState();

    // Count calls from this point
    clearInvocations(cache);
    clearInvocations(api);
    clearInvocations(bidLifecycleListener);
    clearInvocations(metricSendingQueueConsumer);

    // Given a second fetch, without any clock change
    CdbResponseSlot slot = givenMockedCdbRespondingSlot();
    bidManager.getBidForAdUnitAndPrefetch(adUnit);
    waitForIdleState();

    assertShouldCallCdbAndPopulateCacheOnlyOnce(singletonList(cacheAdUnit), slot);
  }

  @Test
  public void getBidForAdUnitAndPrefetch_GivenCdbGivingAnImmediateBid_ShouldPopulateCacheWithTtlSetToDefaultOne()
      throws Exception {
    CacheAdUnit cacheAdUnit = sampleAdUnit();
    AdUnit adUnit = givenMockedAdUnitMappingTo(cacheAdUnit);

    // Immediate bid means CPM > 0, TTL = 0
    CdbResponseSlot slot = givenMockedCdbRespondingSlot();
    when(slot.getCpmAsNumber()).thenReturn(1.);
    when(slot.getTtlInSeconds()).thenReturn(0);

    BidManager bidManager = createBidManager();
    bidManager.getBidForAdUnitAndPrefetch(adUnit);
    waitForIdleState();

    InOrder inOrder = inOrder(cache, slot);
    inOrder.verify(slot).setTtlInSeconds(DEFAULT_TTL_IN_SECONDS);
    inOrder.verify(cache).add(slot);
  }

  @Test
  public void getBidForAdUnitAndPrefetch_GivenCdbGivingInvalidSlots_IgnoreThem() throws Exception {
    CacheAdUnit cacheAdUnit = sampleAdUnit();
    AdUnit adUnit = givenMockedAdUnitMappingTo(cacheAdUnit);
    CdbResponseSlot slot = givenMockedCdbRespondingSlot();

    when(slot.isValid()).thenReturn(false);

    BidManager bidManager = createBidManager();
    bidManager.getBidForAdUnitAndPrefetch(adUnit);
    waitForIdleState();

    verify(cache, never()).add(slot);
  }

  @Test
  public void getBidForAdUnitAndPrefetch_GivenKillSwitchIsEnabledAndNoSilentMode_ShouldNotCallCdbAndNotPopulateCache()
      throws Exception {
    CacheAdUnit cacheAdUnit = sampleAdUnit();
    AdUnit adUnit = givenMockedAdUnitMappingTo(cacheAdUnit);
    givenKillSwitchIs(true);

    BidManager bidManager = createBidManager();
    bidManager.getBidForAdUnitAndPrefetch(adUnit);
    waitForIdleState();

    assertShouldNotCallCdbAndNotPopulateCache();
  }

  @Test
  public void fetchForLiveBidRequest_GivenKillSwitchIsEnabledAndNoSilentMode_ShouldReturnNoBid()
      throws Exception {
    givenKillSwitchIs(true);
    CacheAdUnit cacheAdUnit = sampleAdUnit();
    AdUnit adUnit = givenMockedAdUnitMappingTo(cacheAdUnit);
    givenMockedCdbRespondingSlot();

    BidManager bidManager = createBidManager();
    BidListener bidListener = mock(BidListener.class);

    bidManager.getLiveBidForAdUnit(adUnit, bidListener);
    waitForIdleState();

    verify(bidListener).onNoBid();
    verify(metricSendingQueueConsumer, never()).sendMetricBatch();
  }

  @Test
  public void fetchForLiveBidRequest_GivenKillSwitchNotEnabledAndNoSilentModeAndInvalidAdUnit_ShouldReturnNoBid()
      throws Exception {
    givenKillSwitchIs(false);
    CacheAdUnit cacheAdUnit = sampleAdUnit();
    AdUnit adUnit = givenMockedAdUnitMappingTo(cacheAdUnit);
    givenMockedCdbRespondingSlot();

    BidManager bidManager = createBidManager();
    BidManager bidManagerSpy = Mockito.spy(bidManager);
    doReturn(null).when(bidManagerSpy).mapToCacheAdUnit(adUnit);

    BidListener bidListener = mock(BidListener.class);

    bidManagerSpy.getLiveBidForAdUnit(adUnit, bidListener);
    waitForIdleState();

    verify(bidListener).onNoBid();
    verify(metricSendingQueueConsumer, never()).sendMetricBatch();
  }

  @Test
  public void fetchForLiveBidRequest_GivenGlobalSilentModeOn_AndCacheEmpty_ShouldReturnNoBid()
      throws Exception {
    CacheAdUnit cacheAdUnit = sampleAdUnit();
    AdUnit adUnit = givenMockedAdUnitMappingTo(cacheAdUnit);
    BidListener bidListener = mock(BidListener.class);

    BidManager bidManagerSpy = givenGlobalSilenceMode(true);
    bidManagerSpy.getLiveBidForAdUnit(adUnit, bidListener);
    waitForIdleState();

    verify(bidListener).onNoBid();
    assertNoLiveBidIsConsumedFromCache();
    assertNoLiveBidIsCached();
    assertShouldNotCallCdbAndNotPopulateCache();
    verify(metricSendingQueueConsumer).sendMetricBatch();
  }

  @Test
  public void fetchForLiveBidRequest_GivenGlobalSilentModeOn_AndValidCacheEntry_ShouldReturnCachedBid()
      throws Exception {
    CacheAdUnit cacheAdUnit = sampleAdUnit();
    AdUnit adUnit = givenMockedAdUnitMappingTo(cacheAdUnit);
    CdbResponseSlot cdbResponseSlot = givenNotExpiredValidCachedBid(cacheAdUnit);
    BidListener bidListener = mock(BidListener.class);

    BidManager bidManagerSpy = givenGlobalSilenceMode(true);
    bidManagerSpy.getLiveBidForAdUnit(adUnit, bidListener);
    waitForIdleState();

    verify(bidListener).onBidResponse(cdbResponseSlot);
    assertLiveBidIsConsumedFromCache(cacheAdUnit, cdbResponseSlot);
    assertNoLiveBidIsCached();
    assertShouldNotCallCdbAndNotPopulateCache();
    verify(metricSendingQueueConsumer).sendMetricBatch();
  }

  @Test
  public void fetchForLiveBidRequest_GivenGlobalSilentModeOn_AndExpiredCacheEntry_ShouldReturnNoBid()
      throws Exception {
    CacheAdUnit cacheAdUnit = sampleAdUnit();
    AdUnit adUnit = givenMockedAdUnitMappingTo(cacheAdUnit);
    CdbResponseSlot cdbResponseSlot = givenExpiredValidCachedBid(cacheAdUnit);
    BidListener bidListener = mock(BidListener.class);

    BidManager bidManagerSpy = givenGlobalSilenceMode(true);
    bidManagerSpy.getLiveBidForAdUnit(adUnit, bidListener);
    waitForIdleState();

    verify(bidListener).onNoBid();
    assertLiveBidIsConsumedFromCache(cacheAdUnit, cdbResponseSlot);
    assertNoLiveBidIsCached();
    assertShouldNotCallCdbAndNotPopulateCache();
    verify(metricSendingQueueConsumer).sendMetricBatch();
  }

  @Test
  public void fetchForLiveBidRequest_GivenGlobalSilentModeOn_AndSilencedCacheEntry_ShouldReturnNoBid()
      throws Exception {
    CacheAdUnit cacheAdUnit = sampleAdUnit();
    AdUnit adUnit = givenMockedAdUnitMappingTo(cacheAdUnit);
    givenNotExpiredSilentModeBidCached(cacheAdUnit);
    BidListener bidListener = mock(BidListener.class);

    BidManager bidManagerSpy = givenGlobalSilenceMode(true);
    bidManagerSpy.getLiveBidForAdUnit(adUnit, bidListener);
    waitForIdleState();

    verify(bidListener).onNoBid();
    assertNoLiveBidIsConsumedFromCache();
    assertNoLiveBidIsCached();
    assertShouldNotCallCdbAndNotPopulateCache();
    verify(metricSendingQueueConsumer).sendMetricBatch();
  }

  @Test
  public void fetchForLiveBidRequest_GivenGlobalSilentModeOff_ShouldFetchLiveBid() throws Exception {
    CacheAdUnit cacheAdUnit = sampleAdUnit();
    AdUnit adUnit = givenMockedAdUnitMappingTo(cacheAdUnit);
    givenMockedCdbResponse();
    BidListener bidListener = mock(BidListener.class);

    BidManager bidManagerSpy = givenGlobalSilenceMode(false);
    bidManagerSpy.getLiveBidForAdUnit(adUnit, bidListener);
    waitForIdleState();

    assertShouldCallCdb(singletonList(cacheAdUnit));
    verify(metricSendingQueueConsumer).sendMetricBatch();
  }

  @Test
  public void fetchForLiveBidRequest_ReceivedTimeToNextCall_TimeBudgetRespected_ShouldUpdateGlobalSilentMode()
      throws Exception {
    CacheAdUnit cacheAdUnit = sampleAdUnit();
    AdUnit adUnit = givenMockedAdUnitMappingTo(cacheAdUnit);
    BidListener bidListener = mock(BidListener.class);

    CdbResponse cdbResponse = givenMockedCdbResponse();
    when(cdbResponse.getTimeToNextCall()).thenReturn(10);

    BidManager bidManager = createBidManager();
    bidManager.getLiveBidForAdUnit(adUnit, bidListener);
    waitForIdleState();

    assertThat(bidManager.isGlobalSilenceEnabled()).isTrue();
  }

  @Test
  public void fetchForLiveBidRequest_ReceivedTimeToNextCall_TimeBudgetExceeded_ShouldUpdateGlobalSilentMode()
      throws Exception {
    givenTimeBudgetExceededWhenFetchingLiveBids();

    CacheAdUnit cacheAdUnit = sampleAdUnit();
    AdUnit adUnit = givenMockedAdUnitMappingTo(cacheAdUnit);
    BidListener bidListener = mock(BidListener.class);

    CdbResponse cdbResponse = givenMockedCdbResponse();
    when(cdbResponse.getTimeToNextCall()).thenReturn(10);

    BidManager bidManager = createBidManager();
    bidManager.getLiveBidForAdUnit(adUnit, bidListener);
    waitForIdleState();

    assertThat(bidManager.isGlobalSilenceEnabled()).isTrue();
  }

  @Test
  public void fetchForLiveBidRequest_ReceivedNoTimeToNextCall_TimeBudgetRespected_ShouldNotUpdateGlobalSilentMode()
      throws Exception {
    CacheAdUnit cacheAdUnit = sampleAdUnit();
    AdUnit adUnit = givenMockedAdUnitMappingTo(cacheAdUnit);
    BidListener bidListener = mock(BidListener.class);

    CdbResponse cdbResponse = givenMockedCdbResponse();
    when(cdbResponse.getTimeToNextCall()).thenReturn(0);

    BidManager bidManager = createBidManager();

    givenDuringLiveBidCall(() -> bidManager.setTimeToNextCall(42));

    bidManager.getLiveBidForAdUnit(adUnit, bidListener);
    waitForIdleState();

    assertThat(bidManager.isGlobalSilenceEnabled()).isTrue();
  }

  @Test
  public void fetchForLiveBidRequest_ReceivedNoTimeToNextCall_TimeBudgetExpected_ShouldNotUpdateGlobalSilentMode()
      throws Exception {
    givenTimeBudgetExceededWhenFetchingLiveBids();

    CacheAdUnit cacheAdUnit = sampleAdUnit();
    AdUnit adUnit = givenMockedAdUnitMappingTo(cacheAdUnit);
    BidListener bidListener = mock(BidListener.class);

    CdbResponse cdbResponse = givenMockedCdbResponse();
    when(cdbResponse.getTimeToNextCall()).thenReturn(0);

    BidManager bidManager = createBidManager();

    givenDuringLiveBidCall(() -> bidManager.setTimeToNextCall(42));

    bidManager.getLiveBidForAdUnit(adUnit, bidListener);
    waitForIdleState();

    assertThat(bidManager.isGlobalSilenceEnabled()).isTrue();
  }

  @Test
  public void fetchForLiveBidRequest_ValidBidFetched_TimeBudgetRespected_ShouldNotifyForBidAndNotPopulateCache()
      throws Exception {
    givenMockedClockSetTo(42);
    givenTimeBudgetRespectedWhenFetchingLiveBids();

    CacheAdUnit cacheAdUnit = sampleAdUnit();
    AdUnit adUnit = givenMockedAdUnitMappingTo(cacheAdUnit);

    // Use immediate bid (ttl = 0, cpm > 0) to prove that live bidding support it
    CdbResponseSlot slot = givenMockedCdbRespondingSlot();
    when(slot.getCpmAsNumber()).thenReturn(1.);
    when(slot.getTtlInSeconds()).thenReturn(0);

    BidListener bidListener = mock(BidListener.class);

    BidManager bidManager = createBidManager();
    bidManager.getLiveBidForAdUnit(adUnit, bidListener);
    waitForIdleState();

    InOrder inOrder = inOrder(bidListener, slot);
    inOrder.verify(slot).setTimeOfDownload(42);
    inOrder.verify(bidListener).onBidResponse(slot);
    assertLiveBidIsConsumedDirectly(cacheAdUnit, slot);
    assertNoLiveBidIsCached();
  }

  @Test
  public void fetchForLiveBidRequest_ValidBidCached_TimeBudgetExceeded_ShouldNotifyForConsumedBid()
      throws Exception {
    givenTimeBudgetExceededWhenFetchingLiveBids();

    CacheAdUnit cacheAdUnit = sampleAdUnit();
    AdUnit adUnit = givenMockedAdUnitMappingTo(cacheAdUnit);
    CdbResponseSlot slot = givenNotExpiredValidCachedBid(cacheAdUnit);
    BidListener bidListener = mock(BidListener.class);

    BidManager bidManager = createBidManager();
    bidManager.getLiveBidForAdUnit(adUnit, bidListener);
    waitForIdleState();

    verify(bidListener).onBidResponse(slot);
    assertLiveBidIsConsumedFromCache(cacheAdUnit, slot);
  }

  @Test
  public void fetchForLiveBidRequest_ValidBidFetched_ValidBidCached_TimeBudgetExceeded_ShouldNotifyForConsumedBidAndPopulateCache()
      throws Exception {
    givenMockedClockSetTo(42);
    givenTimeBudgetExceededWhenFetchingLiveBids();

    CacheAdUnit cacheAdUnit = sampleAdUnit();
    AdUnit adUnit = givenMockedAdUnitMappingTo(cacheAdUnit);
    CdbResponseSlot cachedSlot = givenNotExpiredValidCachedBid(cacheAdUnit);
    CdbResponseSlot newSlot = givenMockedCdbRespondingSlot();
    BidListener bidListener = mock(BidListener.class);

    BidManager bidManager = createBidManager();
    bidManager.getLiveBidForAdUnit(adUnit, bidListener);
    waitForIdleState();

    verify(bidListener).onBidResponse(cachedSlot);
    assertLiveBidIsCached(newSlot);
    assertLiveBidIsConsumedFromCache(cacheAdUnit, cachedSlot);

    InOrder inOrder = inOrder(cache, newSlot);
    inOrder.verify(cache).remove(cacheAdUnit);
    inOrder.verify(newSlot).setTimeOfDownload(42);
    inOrder.verify(cache).add(newSlot);
  }

  @Test
  public void fetchForLiveBidRequest_NothingCached_TimeBudgetExceeded_ShouldNotifyForNoBid()
      throws Exception {
    givenTimeBudgetExceededWhenFetchingLiveBids();

    CacheAdUnit cacheAdUnit = sampleAdUnit();
    AdUnit adUnit = givenMockedAdUnitMappingTo(cacheAdUnit);
    BidListener bidListener = mock(BidListener.class);

    BidManager bidManager = createBidManager();
    bidManager.getLiveBidForAdUnit(adUnit, bidListener);
    waitForIdleState();

    verify(bidListener).onNoBid();
    assertNoLiveBidIsCached();
    assertNoLiveBidIsConsumedFromCache();
  }

  @Test
  public void fetchForLiveBidRequest_ValidBidFetched_NothingCached_TimeBudgetExceeded_ShouldNotifyForNoBid()
      throws Exception {
    givenTimeBudgetExceededWhenFetchingLiveBids();

    CacheAdUnit cacheAdUnit = sampleAdUnit();
    AdUnit adUnit = givenMockedAdUnitMappingTo(cacheAdUnit);
    CdbResponseSlot newSlot = givenMockedCdbRespondingSlot();
    BidListener bidListener = mock(BidListener.class);

    BidManager bidManager = createBidManager();
    bidManager.getLiveBidForAdUnit(adUnit, bidListener);
    waitForIdleState();

    verify(bidListener).onNoBid();
    assertLiveBidIsCached(newSlot);
    assertNoLiveBidIsConsumedFromCache();
  }

  @Test
  public void fetchForLiveBidRequest_ExpiredBidCached_TimeBudgetExceeded_ShouldNotifyForNoBid()
      throws Exception {
    givenTimeBudgetExceededWhenFetchingLiveBids();

    CacheAdUnit cacheAdUnit = sampleAdUnit();
    AdUnit adUnit = givenMockedAdUnitMappingTo(cacheAdUnit);
    CdbResponseSlot cachedSlot = givenExpiredValidCachedBid(cacheAdUnit);
    BidListener bidListener = mock(BidListener.class);

    BidManager bidManager = createBidManager();
    bidManager.getLiveBidForAdUnit(adUnit, bidListener);
    waitForIdleState();

    verify(bidListener).onNoBid();
    assertNoLiveBidIsCached();
    assertLiveBidIsConsumedFromCache(cacheAdUnit, cachedSlot);
  }

  @Test
  public void fetchForLiveBidRequest_ValidBidFetched_ExpiredBidCached_TimeBudgetExceeded_ShouldNotifyForNoBidAndPopulateCache()
      throws Exception {
    givenTimeBudgetExceededWhenFetchingLiveBids();

    CacheAdUnit cacheAdUnit = sampleAdUnit();
    AdUnit adUnit = givenMockedAdUnitMappingTo(cacheAdUnit);
    CdbResponseSlot cachedSlot = givenExpiredValidCachedBid(cacheAdUnit);
    CdbResponseSlot newSlot = givenMockedCdbRespondingSlot();
    BidListener bidListener = mock(BidListener.class);

    BidManager bidManager = createBidManager();
    bidManager.getLiveBidForAdUnit(adUnit, bidListener);
    waitForIdleState();

    verify(bidListener).onNoBid();
    assertLiveBidIsCached(newSlot);
    assertLiveBidIsConsumedFromCache(cacheAdUnit, cachedSlot);
  }

  @Test
  public void fetchForLiveBidRequest_NoBidFetched_ValidBidCached_TimeBudgetRespected_ShouldNotifyForNoBidAndNotPopulateCache()
      throws Exception {
    CacheAdUnit cacheAdUnit = sampleAdUnit();
    AdUnit adUnit = givenMockedAdUnitMappingTo(cacheAdUnit);
    givenNotExpiredValidCachedBid(cacheAdUnit);
    CdbResponseSlot newSlot = givenMockedCdbRespondingSlot();
    when(newSlot.getCpmAsNumber()).thenReturn(0.);
    when(newSlot.getTtlInSeconds()).thenReturn(0);

    BidListener bidListener = mock(BidListener.class);

    BidManager bidManager = createBidManager();
    bidManager.getLiveBidForAdUnit(adUnit, bidListener);
    waitForIdleState();

    verify(bidListener).onNoBid();
    assertNoLiveBidIsCached();
    assertNoLiveBidIsConsumedFromCache();
  }

  @Test
  public void fetchForLiveBidRequest_NoBidFetched_ValidBidCached_TimeBudgetExceeded_ShouldNotifyForBidAndNotPopulateCache()
      throws Exception {
    givenTimeBudgetExceededWhenFetchingLiveBids();

    CacheAdUnit cacheAdUnit = sampleAdUnit();
    AdUnit adUnit = givenMockedAdUnitMappingTo(cacheAdUnit);
    CdbResponseSlot cachedSlot = givenNotExpiredValidCachedBid(cacheAdUnit);
    CdbResponseSlot newSlot = givenMockedCdbRespondingSlot();
    when(newSlot.getCpmAsNumber()).thenReturn(0.);
    when(newSlot.getTtlInSeconds()).thenReturn(0);

    BidListener bidListener = mock(BidListener.class);

    BidManager bidManager = createBidManager();
    bidManager.getLiveBidForAdUnit(adUnit, bidListener);
    waitForIdleState();

    verify(bidListener).onBidResponse(cachedSlot);
    assertNoLiveBidIsCached();
    assertLiveBidIsConsumedFromCache(cacheAdUnit, cachedSlot);
  }

  @Test
  public void fetchForLiveBidRequest_SilentBidCached_ShouldNotifyForNoBidAndNotFetch()
      throws Exception {
    CacheAdUnit cacheAdUnit = sampleAdUnit();
    AdUnit adUnit = givenMockedAdUnitMappingTo(cacheAdUnit);
    givenNotExpiredSilentModeBidCached(cacheAdUnit);

    BidListener bidListener = mock(BidListener.class);

    BidManager bidManager = createBidManager();
    bidManager.getLiveBidForAdUnit(adUnit, bidListener);
    waitForIdleState();

    verify(bidListener).onNoBid();
    assertNoLiveBidIsCached();
    assertNoLiveBidIsConsumedFromCache();
    assertShouldNotCallCdbAndNotPopulateCache();
  }

  @Test
  public void fetchForLiveBidRequest_ExpiredSilentBidCached_ShouldFetch() throws Exception {
    CacheAdUnit cacheAdUnit = sampleAdUnit();
    AdUnit adUnit = givenMockedAdUnitMappingTo(cacheAdUnit);
    givenExpiredSilentModeBidCached(cacheAdUnit);
    CdbResponseSlot newSlot = givenMockedCdbRespondingSlot();

    BidListener bidListener = mock(BidListener.class);

    BidManager bidManager = createBidManager();
    bidManager.getLiveBidForAdUnit(adUnit, bidListener);
    waitForIdleState();

    verify(bidListener).onBidResponse(newSlot);
    assertNoLiveBidIsCached();
    assertShouldCallCdb(singletonList(cacheAdUnit));
  }

  @Test
  public void fetchForLiveBidRequest_SilentBidFetched_TimeBudgetRespected_NotifyForNoBidAndPopulateCache()
      throws Exception {
    CacheAdUnit cacheAdUnit = sampleAdUnit();
    AdUnit adUnit = givenMockedAdUnitMappingTo(cacheAdUnit);
    CdbResponseSlot newSlot = givenMockedCdbRespondingSlot();
    when(newSlot.getCpmAsNumber()).thenReturn(0.);
    when(newSlot.getTtlInSeconds()).thenReturn(1);

    BidListener bidListener = mock(BidListener.class);

    BidManager bidManager = createBidManager();
    bidManager.getLiveBidForAdUnit(adUnit, bidListener);
    waitForIdleState();

    verify(bidListener).onNoBid();
    assertLiveBidIsCached(newSlot);
    assertNoLiveBidIsConsumedFromCache();
  }

  @Test
  public void fetchForLiveBidRequest_ValidBidFetched_SilentBidCachedDuringFetch_TimeBudgetRespected_NotifyForBid()
      throws Exception {
    CacheAdUnit cacheAdUnit = sampleAdUnit();
    AdUnit adUnit = givenMockedAdUnitMappingTo(cacheAdUnit);
    CdbResponseSlot newSlot = givenMockedCdbRespondingSlot();
    givenDuringLiveBidCall(() -> givenNotExpiredSilentModeBidCached(cacheAdUnit));

    BidListener bidListener = mock(BidListener.class);

    BidManager bidManager = createBidManager();
    bidManager.getLiveBidForAdUnit(adUnit, bidListener);
    waitForIdleState();

    verify(bidListener).onBidResponse(newSlot);
    assertNoLiveBidIsCached();
    assertLiveBidIsConsumedDirectly(cacheAdUnit, newSlot);
  }

  @Test
  public void fetchForLiveBidRequest_ValidBidFetched_SilentBidCachedDuringFetch_TimeBudgetExceeded_ShouldNotifyForNoBidAndNotPopulateCache()
      throws Exception {
    givenTimeBudgetExceededWhenFetchingLiveBids();

    CacheAdUnit cacheAdUnit = sampleAdUnit();
    AdUnit adUnit = givenMockedAdUnitMappingTo(cacheAdUnit);
    CdbResponseSlot newSlot = givenMockedCdbRespondingSlot();
    givenDuringLiveBidCall(() -> {
      givenNotExpiredSilentModeBidCached(cacheAdUnit);
      doReturn(cacheAdUnit).when(cache).detectCacheAdUnit(newSlot);
    });

    BidListener bidListener = mock(BidListener.class);

    BidManager bidManager = createBidManager();
    bidManager.getLiveBidForAdUnit(adUnit, bidListener);
    waitForIdleState();

    verify(bidListener).onNoBid();
    assertNoLiveBidIsCached();
    assertNoLiveBidIsConsumedFromCache();
  }

  @Test
  public void fetchForLiveBidRequest_ValidBidFetched_ExpiredSilentBidCachedDuringFetch_TimeBudgetExceeded_ShouldNotifyForNoBidAndPopulateCache()
      throws Exception {
    givenTimeBudgetExceededWhenFetchingLiveBids();

    CacheAdUnit cacheAdUnit = sampleAdUnit();
    AdUnit adUnit = givenMockedAdUnitMappingTo(cacheAdUnit);
    CdbResponseSlot newSlot = givenMockedCdbRespondingSlot();
    AtomicReference<CdbResponseSlot> cachedSlotRef = new AtomicReference<>();
    givenDuringLiveBidCall(() -> {
      cachedSlotRef.set(givenExpiredSilentModeBidCached(cacheAdUnit));
      doReturn(cacheAdUnit).when(cache).detectCacheAdUnit(newSlot);
    });

    BidListener bidListener = mock(BidListener.class);

    BidManager bidManager = createBidManager();
    bidManager.getLiveBidForAdUnit(adUnit, bidListener);
    waitForIdleState();

    verify(bidListener).onNoBid();
    assertLiveBidIsCached(newSlot);
    assertLiveBidIsConsumedFromCache(cacheAdUnit, cachedSlotRef.get());
  }

  @Test
  public void fetchForLiveBidRequest_GivenAnOngoingFetch_AndASecondFetchIsMade_BothFetchAreExecuteConcurrently()
      throws Exception {
    givenTimeBudgetRespectedWhenFetchingLiveBids();

    CacheAdUnit cacheAdUnit = sampleAdUnit();
    AdUnit adUnit = givenMockedAdUnitMappingTo(cacheAdUnit);
    CdbResponseSlot newSlot1 = givenMockedCdbRespondingSlot();
    CdbResponseSlot newSlot2 = givenMockedCdbRespondingSlot();

    CdbResponse response1 = givenMockedCdbResponse();
    CdbResponse response2 = givenMockedCdbResponse();
    when(response1.getSlots()).thenReturn(singletonList(newSlot1));
    when(response2.getSlots()).thenReturn(singletonList(newSlot2));

    CountDownLatch secondSlotIsReceived = new CountDownLatch(1);

    BidListener bidListener = mock(BidListener.class);
    doAnswer(answerVoid(ignored -> {
      secondSlotIsReceived.countDown();
    })).when(bidListener).onBidResponse(newSlot2);

    BidManager bidManager = createBidManager();

    doAnswer(invocation -> {
      // Fetch a second bid and wait until it is received by listener
      // Situation is unblocked only if multiple concurrent calls are possible
      bidManager.getLiveBidForAdUnit(adUnit, bidListener);
      secondSlotIsReceived.await();

      return response1;
    }).doReturn(response2).when(api).loadCdb(any(), any());

    bidManager.getLiveBidForAdUnit(adUnit, bidListener);
    waitForIdleState();

    InOrder inOrder = inOrder(bidListener);
    inOrder.verify(bidListener).onBidResponse(newSlot2);
    inOrder.verify(bidListener).onBidResponse(newSlot1);
    inOrder.verifyNoMoreInteractions();
    assertNoLiveBidIsCached();
    assertLiveBidIsConsumedDirectly(cacheAdUnit, newSlot1);
    assertLiveBidIsConsumedDirectly(cacheAdUnit, newSlot2);
  }

  @Test
  public void setCacheAdUnits_GivenValidCdbResponseSlot_ShouldTriggerBidCached() {
    CdbResponseSlot cdbResponseSlot = givenValidCdbResponseSlot();

    BidManager bidManager = createBidManager();
    bidManager.setCacheAdUnits(singletonList(cdbResponseSlot));

    verify(bidLifecycleListener).onBidCached(cdbResponseSlot);
  }

  @Test
  public void setCacheAdUnits_GivenOneValid_AndOneInvalidCdbResponseSlot_ShouldOnlyTriggerBidCachedForValid() {
    CdbResponseSlot validCdbResponseSlot = givenValidCdbResponseSlot();
    CdbResponseSlot invalidCdbResponseSlot = givenInvalidCdbResponseSlot();

    List<CdbResponseSlot> cdbResponseSlots = Arrays.asList(
        validCdbResponseSlot,
        invalidCdbResponseSlot
    );

    BidManager bidManager = createBidManager();
    bidManager.setCacheAdUnits(cdbResponseSlots);

    verify(bidLifecycleListener).onBidCached(validCdbResponseSlot);
  }

  @Test
  public void setCacheAdUnits_GivenInvalidCdbResponseSlot_ShouldNotTriggerBidCached()
      throws Exception {
    CdbResponseSlot invalidCdbResponseSlot = givenInvalidCdbResponseSlot();

    List<CdbResponseSlot> cdbResponseSlots = singletonList(
        invalidCdbResponseSlot
    );

    BidManager bidManager = createBidManager();
    bidManager.setCacheAdUnits(cdbResponseSlots);

    verify(bidLifecycleListener, never()).onBidCached(any());
  }

  private BidManager givenGlobalSilenceMode(boolean enabled) {
    BidManager bidManagerSpy = spy(createBidManager());
    doReturn(enabled).when(bidManagerSpy).isGlobalSilenceEnabled();
    return bidManagerSpy;
  }

  private void assertShouldCallCdbAndPopulateCacheOnlyOnce(
      List<CacheAdUnit> requestedAdUnits,
      CdbResponseSlot slot
  ) throws Exception {
    verify(cache).add(slot);
    assertShouldCallCdb(requestedAdUnits);
  }

  private void assertShouldCallCdb(List<CacheAdUnit> requestedAdUnits) throws Exception {
    verify(api).loadCdb(argThat(cdb -> {
      assertEquals(requestedAdUnits, getRequestedAdUnits(cdb));
      return true;
    }), any());
    verify(bidLifecycleListener).onCdbCallStarted(any());
    verify(bidLifecycleListener).onCdbCallFinished(any(), any());
    verify(metricSendingQueueConsumer).sendMetricBatch();
  }

  private void assertShouldNotCallCdbAndNotPopulateCache() throws Exception {
    verify(cache, never()).add(any());
    verify(api, never()).loadCdb(any(), any());
    verify(bidLifecycleListener, never()).onCdbCallStarted(any());
    verify(bidLifecycleListener, never()).onCdbCallFinished(any(), any());
    verify(bidLifecycleListener, never()).onCdbCallFailed(any(), any());
  }

  private void assertListenerIsNotifyForBidConsumed(CacheAdUnit cacheAdUnit, CdbResponseSlot bid) {
    verify(bidLifecycleListener).onBidConsumed(cacheAdUnit, bid);
  }

  private void assertListenerIsNotNotifyForBidConsumed() {
    verify(bidLifecycleListener, never()).onBidConsumed(any(), any());
  }

  private void assertLiveBidIsCached(@NonNull CdbResponseSlot cachedSlot) {
    verify(cachedSlot).setTimeOfDownload(anyLong());
    verify(cache).add(cachedSlot);
    verify(bidLifecycleListener).onBidCached(cachedSlot);
  }

  private void assertNoLiveBidIsCached() {
    verify(cache, never()).add(any());
    verify(bidLifecycleListener, never()).onBidCached(any());
  }

  private void assertLiveBidIsConsumedFromCache(@NonNull CacheAdUnit cacheAdUnit, @NonNull CdbResponseSlot cachedSlot) {
    verify(cache).remove(cacheAdUnit);
    verify(bidLifecycleListener).onBidConsumed(cacheAdUnit, cachedSlot);
  }

  private void assertNoLiveBidIsConsumedFromCache() {
    verify(cache, never()).remove(any());
    verify(bidLifecycleListener, never()).onBidConsumed(any(), any());
  }

  private void assertLiveBidIsConsumedDirectly(@NonNull CacheAdUnit cacheAdUnit, @NonNull CdbResponseSlot directSlot) {
    verify(directSlot).setTimeOfDownload(anyLong());
    verify(cache, never()).remove(any());
    verify(bidLifecycleListener).onBidConsumed(cacheAdUnit, directSlot);
  }

  private void waitForIdleState() {
    mockedDependenciesRule.waitForIdleState();
  }

  @NonNull
  private CdbResponseSlot givenNotExpiredValidCachedBid(CacheAdUnit cacheAdUnit) {
    CdbResponseSlot slot = mock(CdbResponseSlot.class);
    when(slot.getCpmAsNumber()).thenReturn(1.);
    when(slot.isExpired(clock)).thenReturn(false);
    when(slot.getTtlInSeconds()).thenReturn(60);

    cache.put(cacheAdUnit, slot);
    return slot;
  }

  @NonNull
  private CdbResponseSlot givenExpiredValidCachedBid(CacheAdUnit cacheAdUnit) {
    CdbResponseSlot slot = mock(CdbResponseSlot.class);
    when(slot.getCpmAsNumber()).thenReturn(1.);
    when(slot.getTtlInSeconds()).thenReturn(60);
    when(slot.isExpired(clock)).thenReturn(true);

    cache.put(cacheAdUnit, slot);
    return slot;
  }

  private void givenNoLastBid(CacheAdUnit cacheAdUnit) {
    cache.put(cacheAdUnit, null);
  }

  private void givenNotExpiredSilentModeBidCached(CacheAdUnit cacheAdUnit) {
    CdbResponseSlot slot = mock(CdbResponseSlot.class);
    when(slot.getCpmAsNumber()).thenReturn(0.);
    when(slot.getTtlInSeconds()).thenReturn(60);
    when(slot.isExpired(clock)).thenReturn(false);

    cache.put(cacheAdUnit, slot);
  }

  @NonNull
  private CdbResponseSlot givenExpiredSilentModeBidCached(CacheAdUnit cacheAdUnit) {
    CdbResponseSlot slot = mock(CdbResponseSlot.class);
    when(slot.getCpmAsNumber()).thenReturn(0.);
    when(slot.getTtlInSeconds()).thenReturn(60);
    when(slot.isExpired(clock)).thenReturn(true);

    cache.put(cacheAdUnit, slot);
    return slot;
  }

  @NonNull
  private CacheAdUnit sampleAdUnit() {
    return new CacheAdUnit(new AdSize(1, 1), "adUnit" + adUnitId++, CRITEO_BANNER);
  }

  private void givenKillSwitchIs(boolean isEnabled) {
    when(config.isKillSwitchEnabled()).thenReturn(isEnabled);
  }

  private void givenRemoteConfigWithKillSwitchEnabled() throws IOException {
    RemoteConfigResponse response = mock(RemoteConfigResponse.class);
    when(response.getKillSwitch()).thenReturn(true);
    when(api.loadConfig(any())).thenReturn(response);
  }

  @NonNull
  private CountDownLatch givenExecutorWaitingOn() {
    CountDownLatch waitingLatch = new CountDownLatch(1);

    Executor executor = dependencyProvider.provideThreadPoolExecutor();
    when(dependencyProvider.provideThreadPoolExecutor())
        .thenAnswer(invocation -> (Executor) command -> {
          Runnable waitingCommand = () -> {
            try {
              waitingLatch.await();
            } catch (InterruptedException e) {
              throw new RuntimeException(e);
            }
            command.run();
          };

          executor.execute(waitingCommand);
        });

    return waitingLatch;
  }

  private void givenDuringLiveBidCall(@NonNull Runnable action) {
    doAnswer(invocation -> {
      action.run();
      return invocation.callRealMethod();
    }).when(liveBidRequestSender).sendLiveBidRequest(any(), any());
  }

  private CdbResponseSlot givenMockedCdbRespondingSlot() throws Exception {
    CdbResponseSlot slot = spy(CdbResponseSlot.class);
    when(slot.getCpmAsNumber()).thenReturn(1337.);
    when(slot.getTtlInSeconds()).thenReturn(42);
    when(slot.getDisplayUrl()).thenReturn("http://foo.bar");
    CdbResponse response = givenMockedCdbResponse();
    when(response.getSlots()).thenReturn(singletonList(slot));
    return slot;
  }

  private CdbResponse givenMockedCdbResponse() throws Exception {
    CdbResponse response = mock(CdbResponse.class);
    when(api.loadCdb(any(), any())).thenReturn(response);
    return response;
  }

  private AdUnitMapper givenMockedAdUnitMapper() {
    AdUnitMapper mapper = mock(AdUnitMapper.class);
    when(dependencyProvider.provideAdUnitMapper()).thenReturn(mapper);
    return mapper;
  }

  private AdUnit givenMockedAdUnitMappingTo(CacheAdUnit toAdUnit) {
    AdUnit fromAdUnit = mock(AdUnit.class);

    AdUnitMapper adUnitMapper = givenMockedAdUnitMapper();
    when(adUnitMapper.map(fromAdUnit)).thenReturn(toAdUnit);
    when(adUnitMapper.mapToChunks(singletonList(fromAdUnit)))
        .thenReturn(singletonList(singletonList(toAdUnit)));

    return fromAdUnit;
  }

  private void givenMockedClockSetTo(long instant) {
    when(clock.getCurrentTimeInMillis()).thenReturn(instant);
  }

  private CdbResponseSlot givenValidCdbResponseSlot() {
    CdbResponseSlot cdbResponseSlot = mock(CdbResponseSlot.class);
    when(cdbResponseSlot.isValid()).thenReturn(true);
    return cdbResponseSlot;
  }

  private CdbResponseSlot givenInvalidCdbResponseSlot() {
    CdbResponseSlot cdbResponseSlot = mock(CdbResponseSlot.class);
    when(cdbResponseSlot.isValid()).thenReturn(false);
    return cdbResponseSlot;
  }

  @NonNull
  private List<CacheAdUnit> getRequestedAdUnits(CdbRequest cdbRequest) {
    List<CacheAdUnit> cacheAdUnits = new ArrayList<>();
    cdbRequest.getSlots().forEach(slot -> cacheAdUnits.add(toAdUnit(slot)));
    return cacheAdUnits;
  }

  @NonNull
  private CacheAdUnit toAdUnit(CdbRequestSlot slot) {
    String formattedSize = slot.getSizes().iterator().next();
    String[] parts = formattedSize.split("x");
    AdSize size = new AdSize(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));

    AdUnitType adUnitType = AdUnitType.CRITEO_BANNER;
    if (slot.isInterstitial() == Boolean.TRUE) {
      adUnitType = AdUnitType.CRITEO_INTERSTITIAL;
    } else if (slot.isNativeAd() == Boolean.TRUE) {
      adUnitType = AdUnitType.CRITEO_CUSTOM_NATIVE;
    }

    return new CacheAdUnit(size, slot.getPlacementId(), adUnitType);
  }

  private BidManager createBidManager() {
    return new BidManager(
        cache,
        dependencyProvider.provideConfig(),
        dependencyProvider.provideClock(),
        dependencyProvider.provideAdUnitMapper(),
        dependencyProvider.provideBidRequestSender(),
        dependencyProvider.provideLiveBidRequestSender(),
        dependencyProvider.provideBidLifecycleListener(),
        dependencyProvider.provideMetricSendingQueueConsumer()
    );
  }

  private void givenTimeBudgetRespectedWhenFetchingLiveBids() {
    when(config.getLiveBiddingTimeBudgetInMillis()).thenReturn(Integer.MAX_VALUE);
  }

  private void givenTimeBudgetExceededWhenFetchingLiveBids() {
    when(config.getLiveBiddingTimeBudgetInMillis()).thenReturn(1);
    givenDelayWhenFetchingBids(1000);
  }

  private void givenDelayWhenFetchingBids(long delay) {
    DependencyProvider dependencyProvider = mockedDependenciesRule.getDependencyProvider();
    Executor oldExecutor = dependencyProvider.provideThreadPoolExecutor();
    TrackingCommandsExecutorWithDelay trackingCommandsExecutorWithDelay = new TrackingCommandsExecutorWithDelay(
        oldExecutor,
        delay
    );
    doReturn(trackingCommandsExecutorWithDelay).when(dependencyProvider).provideThreadPoolExecutor();
  }
}
