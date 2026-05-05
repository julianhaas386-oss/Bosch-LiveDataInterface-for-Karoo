package de.dxmedia.bosch.ldi

import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExtensionRegistrationTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun extensionServiceIsRegisteredWithKarooIntentFilter() {
        val pm = context.packageManager
        val intent = Intent("io.hammerhead.karooext.KAROO_EXTENSION")
            .setPackage(context.packageName)
        val services = pm.queryIntentServices(intent, 0)
        assertEquals("Exactly one KarooExtension service must be registered", 1, services.size)
        assertTrue(
            "Service must be BoschLiveDataService",
            services[0].serviceInfo.name.endsWith("BoschLiveDataService")
        )
    }

    @Test
    fun extensionServiceHasExtensionInfoMetaData() {
        val pm = context.packageManager
        val intent = Intent("io.hammerhead.karooext.KAROO_EXTENSION")
            .setPackage(context.packageName)
        val services = pm.queryIntentServices(intent, PackageManager.GET_META_DATA)
        val metaData = services[0].serviceInfo.metaData
        assertTrue(
            "EXTENSION_INFO meta-data must be declared",
            metaData.containsKey("io.hammerhead.karooext.EXTENSION_INFO")
        )
    }

    @Test
    fun appHasBlePermissionsDeclared() {
        val pm = context.packageManager
        val packageInfo = pm.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS
        )
        val permissions = packageInfo.requestedPermissions?.toList() ?: emptyList()
        assertTrue(
            "BLUETOOTH_ADVERTISE must be declared",
            permissions.contains("android.permission.BLUETOOTH_ADVERTISE")
        )
        assertTrue(
            "BLUETOOTH_CONNECT must be declared",
            permissions.contains("android.permission.BLUETOOTH_CONNECT")
        )
        assertTrue(
            "BLUETOOTH_SCAN must be declared",
            permissions.contains("android.permission.BLUETOOTH_SCAN")
        )
        assertTrue(
            "FOREGROUND_SERVICE_CONNECTED_DEVICE must be declared",
            permissions.contains("android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE")
        )
    }
}
