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

package com.criteo.publisher.tasks;

import static com.criteo.publisher.CriteoListenerCode.INVALID;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.criteo.publisher.CriteoBannerAdListener;
import com.criteo.publisher.CriteoBannerView;
import com.criteo.publisher.CriteoErrorCode;
import com.criteo.publisher.CriteoListenerCode;
import java.lang.ref.WeakReference;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

public class CriteoBannerListenerCallTaskTest {

  @Mock
  private CriteoBannerAdListener criteoBannerAdListener;

  @Mock
  CriteoBannerView criteoBannerView;

  @Before
  public void setup() {
    MockitoAnnotations.initMocks(this);
  }

  @Test
  public void run_GivenNullListener_DoesNotCrash() throws Exception {
    criteoBannerAdListener = null;

    CriteoBannerListenerCallTask task = createTask(INVALID);

    assertThatCode(task::run).doesNotThrowAnyException();
  }

  @Test
  public void testWithInvalidCode() {
    CriteoBannerListenerCallTask task = createTask(INVALID);
    task.run();

    verify(criteoBannerAdListener, times(1))
        .onAdFailedToReceive(CriteoErrorCode.ERROR_CODE_NO_FILL);
    verify(criteoBannerAdListener, times(0)).onAdReceived(criteoBannerView);
    verify(criteoBannerAdListener, times(0)).onAdClicked();
    verify(criteoBannerAdListener, times(0)).onAdLeftApplication();
    verify(criteoBannerAdListener, times(0)).onAdOpened();

  }

  @Test
  public void testWithValidCode() {
    CriteoBannerListenerCallTask task = createTask(CriteoListenerCode.VALID);
    task.run();

    verify(criteoBannerAdListener, times(0))
        .onAdFailedToReceive(CriteoErrorCode.ERROR_CODE_NO_FILL);
    verify(criteoBannerAdListener, times(1)).onAdReceived(criteoBannerView);
    verify(criteoBannerAdListener, times(0)).onAdClicked();
    verify(criteoBannerAdListener, times(0)).onAdLeftApplication();
    verify(criteoBannerAdListener, times(0)).onAdOpened();
  }

  @Test
  public void testWithClickCode() {
    CriteoBannerListenerCallTask task = createTask(CriteoListenerCode.CLICK);
    task.run();

    verify(criteoBannerAdListener, times(0))
        .onAdFailedToReceive(CriteoErrorCode.ERROR_CODE_NO_FILL);
    verify(criteoBannerAdListener, times(0)).onAdReceived(criteoBannerView);
    verify(criteoBannerAdListener, times(1)).onAdClicked();
    verify(criteoBannerAdListener, times(1)).onAdLeftApplication();
    verify(criteoBannerAdListener, times(1)).onAdOpened();
  }

  @Test
  public void testWithCloseCode() {
    CriteoBannerListenerCallTask task = createTask(CriteoListenerCode.CLOSE);
    task.run();

    verify(criteoBannerAdListener).onAdClosed();
    verifyNoMoreInteractions(criteoBannerAdListener);
  }

  private CriteoBannerListenerCallTask createTask(CriteoListenerCode code) {
    return new CriteoBannerListenerCallTask(
        criteoBannerAdListener,
        new WeakReference<>(criteoBannerView),
        code);
  }


}