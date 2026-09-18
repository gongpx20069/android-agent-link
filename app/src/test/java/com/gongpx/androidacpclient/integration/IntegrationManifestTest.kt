package com.gongpx.androidacpclient.integration

import android.content.ComponentName
import android.content.pm.PackageManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class IntegrationManifestTest {
    @Test fun explicitComponentsAdvertiseProtocolWhileGrantManagementStaysPrivate() {
        val context = RuntimeEnvironment.getApplication()
        val pm = context.packageManager
        val key = "com.gongpx.androidacpclient.PROTOCOL_VERSION"
        val service = pm.getServiceInfo(ComponentName(context, AgentLinkControlService::class.java), PackageManager.GET_META_DATA)
        val authorization = pm.getActivityInfo(ComponentName(context, AgentLinkAuthorizationActivity::class.java), PackageManager.GET_META_DATA)
        val management = pm.getActivityInfo(ComponentName(context, IntegrationGrantsActivity::class.java), 0)
        assertTrue(service.exported)
        assertTrue(authorization.exported)
        assertFalse(management.exported)
        assertEquals(1, service.metaData.getInt(key))
        assertEquals(1, authorization.metaData.getInt(key))
    }
}
