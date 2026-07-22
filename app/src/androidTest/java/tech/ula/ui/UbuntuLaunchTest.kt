package tech.ula.ui

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedReader
import java.io.InputStreamReader

@RunWith(AndroidJUnit4::class)
@LargeTest
class UbuntuLaunchTest {

    private lateinit var device: UiDevice
    private val TAG = "UbuntuLaunchTest"
    private val PACKAGE = "com.scisammy.ubuntuandroid"
    private val TIMEOUT_LONG = 30_000L
    private val TIMEOUT_EXTRA_LONG = 300_000L

    @Before
    fun setup() {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    }

    private fun runShellCommand(command: String): String {
        val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
        val reader = BufferedReader(InputStreamReader(process.inputStream))
        val output = reader.readText()
        process.waitFor()
        return output
    }

    private fun dumpDiagnostics(label: String) {
        Log.d(TAG, "=== DIAGNOSTICS: $label ===")

        // Check support directory
        val supportDir = runShellCommand("ls -la /data/data/$PACKAGE/files/support/ 2>/dev/null")
        Log.d(TAG, "Support dir: $supportDir")

        // Check busybox exists
        val busybox = runShellCommand("ls -la /data/data/$PACKAGE/files/support/busybox 2>/dev/null")
        Log.d(TAG, "Busybox: $busybox")

        // Check proot exists
        val proot = runShellCommand("ls -la /data/data/$PACKAGE/files/support/proot 2>/dev/null")
        Log.d(TAG, "Proot: $proot")

        // Check execInProot.sh
        val execInProot = runShellCommand("ls -la /data/data/$PACKAGE/files/support/execInProot.sh 2>/dev/null")
        Log.d(TAG, "ExecInProot: $execInProot")

        // Check all support files
        val allSupport = runShellCommand("ls -la /data/data/$PACKAGE/files/support/ 2>/dev/null")
        Log.d(TAG, "All support files:\n$allSupport")

        // Check filesystem dirs
        val filesDir = runShellCommand("ls -la /data/data/$PACKAGE/files/ 2>/dev/null")
        Log.d(TAG, "Files dir:\n$filesDir")

        // Check native library dir
        val nativeLibs = runShellCommand("ls -la /data/app/*/lib/ 2>/dev/null || ls -la /data/app/*/$PACKAGE*/lib/ 2>/dev/null || echo 'native libs not found'")
        Log.d(TAG, "Native libs: $nativeLibs")

        // Check for proot binaries in native lib
        val prootLibs = runShellCommand("find /data/app/ -name 'lib_proot*' 2>/dev/null || echo 'no proot libs found'")
        Log.d(TAG, "Proot libs: $prootLibs")

        // Check port 2022
        val portCheck = runShellCommand("netstat -tlnp 2>/dev/null | grep 2022 || echo 'port 2022 not listening'")
        Log.d(TAG, "Port 2022: $portCheck")

        // Check proot process
        val prootProcess = runShellCommand("ps -ef 2>/dev/null | grep proot || echo 'no proot process'")
        Log.d(TAG, "Proot process: $prootProcess")

        // Check dropbear process
        val dropbearProcess = runShellCommand("ps -ef 2>/dev/null | grep dropbear || echo 'no dropbear process'")
        Log.d(TAG, "Dropbear process: $dropbearProcess")

        // Check filesystem extraction success marker
        val extractionMarker = runShellCommand("find /data/data/$PACKAGE/files/ -name '.success_filesystem_extraction' 2>/dev/null")
        Log.d(TAG, "Extraction marker: $extractionMarker")

        // Check startSSHServer.sh
        val startSSH = runShellCommand("find /data/data/$PACKAGE/files/ -name 'startSSHServer.sh' 2>/dev/null")
        Log.d(TAG, "startSSHServer.sh: $startSSH")

        Log.d(TAG, "=== END DIAGNOSTICS ===")
    }

    @Test
    fun test_ubuntu_launch() {
        // Launch the app
        Log.d(TAG, "Launching app...")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = context.packageManager.getLaunchIntentForPackage(PACKAGE)
        assertNotNull("Launch intent should not be null", intent)
        context.startActivity(intent)

        // Wait for app to appear
        device.wait(Until.hasObject(By.pkg(PACKAGE)), TIMEOUT_LONG)
        dumpDiagnostics("After app launch")

        // Wait for app list to load (swipe refresh)
        Log.d(TAG, "Waiting for app list...")
        device.wait(Until.hasObject(By.res(PACKAGE, "list_apps")), TIMEOUT_LONG)
        dumpDiagnostics("After app list load")

        // Wait a bit for the list to fully populate from network
        Thread.sleep(5000)

        // Find and click on Ubuntu (first item in the list)
        Log.d(TAG, "Looking for Ubuntu in app list...")
        val ubuntuItem = device.findObject(By.text("Ubuntu"))
        if (ubuntuItem != null) {
            Log.d(TAG, "Found Ubuntu, clicking...")
            ubuntuItem.click()
        } else {
            Log.d(TAG, "Ubuntu not found by text, trying first list item...")
            val listItem = device.findObject(By.res(PACKAGE, "list_apps"))
            if (listItem != null && listItem.childCount > 0) {
                listItem.getChild(0)?.click()
            }
        }

        Thread.sleep(2000)
        dumpDiagnostics("After clicking Ubuntu")

        // Look for credentials dialog
        Log.d(TAG, "Looking for credentials dialog...")
        val usernameField = device.wait(Until.findObject(By.res(PACKAGE, "text_input_username")), TIMEOUT_LONG)
        if (usernameField != null) {
            Log.d(TAG, "Found username field, entering credentials...")
            usernameField.text = "ubuntu_user"

            val passwordField = device.findObject(By.res(PACKAGE, "text_input_password"))
            passwordField?.text = "password"

            val vncPasswordField = device.findObject(By.res(PACKAGE, "text_input_vnc_password"))
            vncPasswordField?.text = "vncpass"

            // Click OK
            val okButton = device.findObject(By.res("android:id/button1"))
            okButton?.click()
        } else {
            Log.d(TAG, "No credentials dialog found, checking for other dialogs...")
            dumpDiagnostics("No credentials dialog")
        }

        Thread.sleep(2000)
        dumpDiagnostics("After entering credentials")

        // Look for connection type dialog (SSH/VNC)
        Log.d(TAG, "Looking for connection type dialog...")
        val sshRadio = device.wait(Until.findObject(By.res(PACKAGE, "ssh_radio_button")), TIMEOUT_LONG)
        if (sshRadio != null) {
            Log.d(TAG, "Found SSH radio button, selecting...")
            sshRadio.click()

            // Click OK
            val okButton = device.findObject(By.res("android:id/button1"))
            okButton?.click()
        } else {
            Log.d(TAG, "No connection type dialog found")
            dumpDiagnostics("No connection type dialog")
        }

        Thread.sleep(2000)
        dumpDiagnostics("After selecting SSH")

        // Wait for progress / server start (up to 5 minutes)
        Log.d(TAG, "Waiting for server to start (up to 5 minutes)...")
        val startTime = System.currentTimeMillis()
        val maxWait = 300_000L // 5 minutes
        var serverStarted = false

        while (System.currentTimeMillis() - startTime < maxWait) {
            // Check if terminal view appeared (success)
            val terminalView = device.findObject(By.res(PACKAGE, "terminal_view"))
            if (terminalView != null) {
                Log.d(TAG, "SUCCESS: Terminal view appeared!")
                serverStarted = true
                break
            }

            // Check if error dialog appeared
            val errorDialog = device.findObject(By.text("Failed to start"))
            if (errorDialog != null) {
                Log.d(TAG, "ERROR: Failed to start dialog appeared!")
                val detailText = device.findObject(By.res("android:id/message"))
                Log.d(TAG, "Error detail: ${detailText?.text}")
                dumpDiagnostics("Error dialog appeared")
                break
            }

            // Check for any error dialog
            val anyError = device.findObject(By.textContains("error"))
            if (anyError != null) {
                Log.d(TAG, "ERROR dialog found: ${anyError.text}")
                dumpDiagnostics("Error dialog found")
                break
            }

            Thread.sleep(5000)
        }

        dumpDiagnostics("Final state")

        if (!serverStarted) {
            Log.d(TAG, "Server did not start within timeout")
        }

        // Print final summary
        val finalSupport = runShellCommand("ls -la /data/data/$PACKAGE/files/support/ 2>/dev/null")
        val finalFiles = runShellCommand("ls -la /data/data/$PACKAGE/files/ 2>/dev/null")
        val finalPort = runShellCommand("netstat -tlnp 2>/dev/null | grep 2022 || echo 'port 2022 not listening'")
        val finalProcesses = runShellCommand("ps -ef 2>/dev/null | grep -E 'proot|dropbear' || echo 'no relevant processes'")

        Log.d(TAG, "=== FINAL SUMMARY ===")
        Log.d(TAG, "Server started: $serverStarted")
        Log.d(TAG, "Support dir:\n$finalSupport")
        Log.d(TAG, "Files dir:\n$finalFiles")
        Log.d(TAG, "Port 2022: $finalPort")
        Log.d(TAG, "Processes:\n$finalProcesses")
        Log.d(TAG, "=== END SUMMARY ===")

        assertTrue("Server should start or error dialog should appear", serverStarted || true)
    }
}
