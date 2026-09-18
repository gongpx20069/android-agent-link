package com.gongpx.androidacpclient.integration

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.Signature
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], manifest = Config.NONE)
class CallerIdentityTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Suppress("DEPRECATION")
    private fun install(name: String, uid: Int, signer: String = "01020304") {
        shadowOf(context.packageManager).installPackage(PackageInfo().apply {
            packageName = name
            applicationInfo = ApplicationInfo().apply { packageName = name; this.uid = uid; name.let { nonLocalizedLabel = it } }
            signatures = arrayOf(Signature(signer))
        })
    }

    @Test fun realPackageManagerUidAndSignerDetermineIdentity() {
        install("test.mochi", 12345)
        val identity = identityForUid(context, 12345)
        assertEquals("test.mochi", identity.packageName)
        assertEquals(12345, identity.uid)
        assertEquals(64, identity.signers.single().length)
        assertThrows(SecurityException::class.java) { callerIdentity(context, "test.mochi", 77777) }
        assertThrows(SecurityException::class.java) { identityForUid(context, -1) }
    }

    @Test fun sharedUidCannotInheritOnePackagesGrant() {
        install("test.mochi", 12345)
        install("test.other", 12345)
        assertThrows(SecurityException::class.java) { identityForUid(context, 12345) }
        assertThrows(SecurityException::class.java) { callerIdentity(context, "test.mochi") }
    }

    @Test fun replacingSignerInvalidatesExistingGrant() {
        install("test.mochi", 12345)
        val original = identityForUid(context, 12345)
        val grant = IntegrationGrant(original.packageName, original.signers, setOf("m"), setOf("read"), 1)
        install("test.mochi", 12345, "01020305")
        assertFalse(grant.matches(identityForUid(context, 12345)))
    }

    @Test fun authorizationRejectsCallerSuppliedPackageWithoutRealCallingActivity() {
        val intent = Intent(context, AgentLinkAuthorizationActivity::class.java).setAction(AUTHORIZE)
            .putExtra("requestId", "random_nonce_123456").putExtra("packageName", "test.mochi")
        val controller = Robolectric.buildActivity(AgentLinkAuthorizationActivity::class.java, intent).create()
        assertTrue(controller.get().isFinishing)
        controller.destroy()
    }
}
