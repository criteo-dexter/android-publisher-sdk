package com.criteo.publisher

import com.criteo.publisher.Util.BuildConfigWrapper
import com.criteo.publisher.Util.PreconditionsUtil
import com.nhaarman.mockitokotlin2.doReturn
import com.nhaarman.mockitokotlin2.mock
import org.junit.After
import org.junit.Test
import java.lang.RuntimeException

class PreconditionsUtilTests {

    @After
    fun tearDown() {
        DependencyProvider.setInstance(null)
    }

    @Test(expected = RuntimeException::class)
    fun givenDebug_RuntimeExceptionShouldBeThrown() {
        givenMockedDependencyProvider(true)
        PreconditionsUtil.throwOrLog(Exception(""))
    }

    @Test
    fun givenNotDebug_RuntimeExceptionShouldNotBeThrown() {
        givenMockedDependencyProvider(false)
        PreconditionsUtil.throwOrLog(Exception(""))
    }

    private fun givenMockedDependencyProvider(isDebugMode: Boolean) {
        val buildConfigWrapper = mock<BuildConfigWrapper> {
            on { isDebug } doReturn isDebugMode
        }

        val dependencyProvider = mock<DependencyProvider> {
            on { provideBuildConfigWrapper() } doReturn buildConfigWrapper
        }

        DependencyProvider.setInstance(dependencyProvider)
    }
}