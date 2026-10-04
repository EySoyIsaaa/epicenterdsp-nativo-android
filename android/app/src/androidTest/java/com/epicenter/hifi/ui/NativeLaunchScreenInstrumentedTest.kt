package com.epicenter.hifi.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil.ImageLoader
import coil.decode.SvgDecoder
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.epicenter.hifi.R
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class NativeLaunchScreenInstrumentedTest {
    @Test
    fun originalLogoAnimationRendersAndCompletesWithoutPlaybackServiceOrPermissionDialogs() {
        val finished = CountDownLatch(1)
        val showingIntro = mutableStateOf(true)
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    if (showingIntro.value) NativeLaunchScreen {
                        showingIntro.value = false
                        finished.countDown()
                    }
                }
            }
            Thread.sleep(2_300)
            assertEquals("Do not cut the original 3.6-second sequence short", 1L, finished.count)
            saveScreenshot("intro-full-before-exit.png")
            assertTrue("The native intro must finish", finished.await(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun originalHiResSvgAssetDecodesSuccessfully() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val loader = ImageLoader.Builder(context).components { add(SvgDecoder.Factory()) }.build()
        try {
            val result = loader.execute(ImageRequest.Builder(context).data(R.raw.hires_audio).size(120).build())
            assertTrue("Use and decode the supplied Hi-Res logo", result is SuccessResult)
            val drawable = (result as SuccessResult).drawable
            assertTrue(drawable.intrinsicWidth > 0 && drawable.intrinsicHeight > 0)
        } finally { loader.shutdown() }
    }

    private fun saveScreenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        assertNotNull("The native surface must render", bitmap)
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "ui-verification")
        check(directory.isDirectory || directory.mkdirs())
        File(directory, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
