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

package com.criteo.publisher.model

import com.criteo.publisher.bid.UniqueIdGenerator
import com.criteo.publisher.integration.IntegrationRegistry
import com.criteo.publisher.privacy.UserPrivacyUtil
import com.criteo.publisher.privacy.gdpr.GdprData
import com.criteo.publisher.util.AdUnitType.CRITEO_BANNER
import com.criteo.publisher.util.AdvertisingInfo
import com.criteo.publisher.util.BuildConfigWrapper
import com.nhaarman.mockitokotlin2.doReturn
import com.nhaarman.mockitokotlin2.mock
import com.nhaarman.mockitokotlin2.stub
import com.nhaarman.mockitokotlin2.whenever
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger

class CdbRequestFactoryTest {

  @Mock
  private lateinit var publisher: Publisher

  @Mock
  private lateinit var deviceInfo: DeviceInfo

  @Mock
  private lateinit var advertisingInfo: AdvertisingInfo

  @Mock
  private lateinit var userPrivacyUtil: UserPrivacyUtil

  @Mock
  private lateinit var uniqueIdGenerator: UniqueIdGenerator

  @Mock
  private lateinit var buildConfigWrapper: BuildConfigWrapper

  @Mock
  private lateinit var integrationRegistry: IntegrationRegistry

  private lateinit var factory: CdbRequestFactory

  private val adUnitId = AtomicInteger()

  @Before
  fun setUp() {
    MockitoAnnotations.initMocks(this)

    whenever(userPrivacyUtil.mopubConsent).thenReturn("mopubConsent")
    whenever(userPrivacyUtil.iabUsPrivacyString).thenReturn("iabUsPrivacyString")
    whenever(userPrivacyUtil.usPrivacyOptout).thenReturn("usPrivacyoptout")

    factory = CdbRequestFactory(
        publisher,
        deviceInfo,
        advertisingInfo,
        userPrivacyUtil,
        uniqueIdGenerator,
        buildConfigWrapper,
        integrationRegistry
    )
  }

  @Test
  fun userAgent_GivenDeviceInfo_DelegateToIt() {
    val expected: Future<String> = mock()
    whenever(deviceInfo.userAgent).thenReturn(expected)

    val userAgent = factory.userAgent

    assertThat(userAgent).isSameAs(expected)
  }

  @Test
  fun createRequest_GivenInput_BuildRequest() {
    val adUnit = createAdUnit()
    val adUnits: List<CacheAdUnit> = listOf(adUnit)
    val expectedGdpr: GdprData = mock()

    val expectedSlot = CdbRequestSlot.create(
        "impId",
        adUnit.placementId,
        adUnit.adUnitType,
        adUnit.size
    )

    buildConfigWrapper.stub {
      on { sdkVersion } doReturn "1.2.3"
    }

    whenever(integrationRegistry.profileId).thenReturn(42)
    whenever(userPrivacyUtil.gdprData).thenReturn(expectedGdpr)
    whenever(uniqueIdGenerator.generateId())
        .thenReturn("myRequestId")
        .thenReturn("impId")

    val request = factory.createRequest(adUnits)

    assertThat(request.id).isEqualTo("myRequestId")
    assertThat(request.publisher).isEqualTo(publisher)
    assertThat(request.sdkVersion).isEqualTo("1.2.3")
    assertThat(request.profileId).isEqualTo(42)
    assertThat(request.gdprData).isEqualTo(expectedGdpr)
    assertThat(request.slots).containsExactlyInAnyOrder(expectedSlot)
  }

  @Test
  fun givenOneRequestWithNonEmptyPrivacyValue_AndOneRequestWithEmptyPrivacyValues_VerifyRequestsAreDifferent() {
    // request 1
    val adUnit = createAdUnit()
    val adUnits: List<CacheAdUnit> = listOf(adUnit)
    val expectedGdpr: GdprData = mock()

    val expectedSlot = CdbRequestSlot.create(
        "impId",
        adUnit.placementId,
        adUnit.adUnitType,
        adUnit.size
    )

    buildConfigWrapper.stub {
      on { sdkVersion } doReturn "1.2.3"
    }

    userPrivacyUtil.stub {
      on { gdprData } doReturn expectedGdpr
      on { usPrivacyOptout } doReturn "usPrivacyOptout"
      on { iabUsPrivacyString } doReturn "iabUsPrivacyString"
      on { mopubConsent } doReturn "mopubConsent"
    }

    whenever(integrationRegistry.profileId).thenReturn(1337)
    whenever(uniqueIdGenerator.generateId())
        .thenReturn("myRequestId")
        .thenReturn("impId")

    var request = factory.createRequest(adUnits)

    assertThat(request.id).isEqualTo("myRequestId")
    assertThat(request.publisher).isEqualTo(publisher)
    assertThat(request.sdkVersion).isEqualTo("1.2.3")
    assertThat(request.profileId).isEqualTo(1337)
    assertThat(request.gdprData).isEqualTo(expectedGdpr)
    assertThat(request.user.uspIab()).isEqualTo("iabUsPrivacyString")
    assertThat(request.user.uspOptout()).isEqualTo("usPrivacyOptout")
    assertThat(request.user.mopubConsent()).isEqualTo("mopubConsent")
    assertThat(request.slots).containsExactlyInAnyOrder(expectedSlot)

    // request 2
    userPrivacyUtil.stub {
      on { usPrivacyOptout } doReturn ""
      on { iabUsPrivacyString } doReturn ""
      on { mopubConsent } doReturn ""
    }

    request = factory.createRequest(adUnits)

    assertThat(request.user.uspIab()).isNull()
    assertThat(request.user.uspOptout()).isNull()
    assertThat(request.user.mopubConsent()).isNull()
  }

  @Test
  fun createRequest_GivenAdUnits_MapThemToRequestSlotWithImpressionId() {
    val adUnit1 = createAdUnit()
    val adUnit2 = createAdUnit()
    val adUnits: List<CacheAdUnit> = listOf(adUnit1, adUnit2)

    val expectedSlot1 = CdbRequestSlot.create(
        "impId1",
        adUnit1.placementId,
        adUnit1.adUnitType,
        adUnit1.size
    )

    val expectedSlot2 = CdbRequestSlot.create(
        "impId2",
        adUnit2.placementId,
        adUnit2.adUnitType,
        adUnit2.size
    )

    uniqueIdGenerator.stub {
      on { generateId() }.doReturn("myRequestId", "impId1", "impId2")
    }

    buildConfigWrapper.stub {
      on { sdkVersion } doReturn "1.2.3"
    }

    whenever(integrationRegistry.profileId).thenReturn(1337)

    val request = factory.createRequest(adUnits)

    assertThat(request.slots).containsExactlyInAnyOrder(expectedSlot1, expectedSlot2)
  }

  private fun createAdUnit(): CacheAdUnit {
    val id = "adUnit #" + adUnitId.incrementAndGet()
    return CacheAdUnit(AdSize(1, 2), id, CRITEO_BANNER)
  }
}
