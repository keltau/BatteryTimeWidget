package com.keltau.batterytimewidget

import android.app.Application
import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@SdkSuppress(minSdkVersion = 29)
class DocumentQueueTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun slowDocumentProvidersDoNotBlockStorageOrDeveloperRefreshes() {
        for (export in listOf(false, true)) {
            val file = File(context.cacheDir, "slow-document-test.json")
            file.outputStream().use { DataTransfer.write(it, BatteryStore.Snapshot(emptyList(), emptyList()), 50, System.currentTimeMillis()) }
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val provider = object : ContentProvider() {
                override fun onCreate() = true
                override fun getType(uri: Uri) = "application/json"
                override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
                override fun insert(uri: Uri, values: ContentValues?): Uri? = null
                override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
                override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
                override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
                    entered.countDown()
                    check(release.await(15, TimeUnit.SECONDS))
                    return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
                }
            }
            provider.attachInfo(context, ProviderInfo().apply { authority = "slow-document-test" })
            val resolver = ContentResolver.wrap(provider)
            val application = object : Application() {
                init { attachBaseContext(this@DocumentQueueTest.context) }
                override fun getContentResolver() = resolver
            }
            lateinit var model: MainActivity.Model
            try {
                instrumentation.runOnMainSync {
                    model = MainActivity.Model(application)
                    val uri = Uri.parse("content://slow-document-test/history")
                    if (export) model.export(uri) else model.prepareImport(uri)
                }
                assertTrue("Document operation did not start", entered.await(10, TimeUnit.SECONDS))
                assertEquals(42, BatteryStore.executor.submit<Int> { 42 }.get(3, TimeUnit.SECONDS))
                instrumentation.runOnMainSync { model.setDiagnosticsVisible(true) }
                await { model.rawLoaded && model.snapshot.value != null }
                instrumentation.runOnMainSync { assertTrue(model.busy.value == true) }
                release.countDown()
                await { model.busy.value == false }
                instrumentation.runOnMainSync {
                    if (export) assertEquals(context.getString(R.string.export_complete), model.message.value)
                    else assertNotNull(model.pendingImport.value)
                }
            } finally {
                release.countDown()
                file.delete()
            }
        }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < deadline) {
            var ready = false
            instrumentation.runOnMainSync { ready = condition() }
            if (ready) return
            SystemClock.sleep(50)
        }
        fail("Operation did not complete")
    }
}
