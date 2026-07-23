package tech.ula.utils

import android.Manifest
import android.annotation.TargetApi
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import tech.ula.R

class PermissionHandler {
    companion object {
        private const val permissionRequestCode = 1234

        fun permissionsAreGranted(context: Context): Boolean {
            val storageGranted = (ContextCompat.checkSelfPermission(context,
                    Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(context,
                            Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                return storageGranted &&
                        ContextCompat.checkSelfPermission(context,
                                Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            }
            return storageGranted
        }

        fun permissionsWereGranted(requestCode: Int, grantResults: IntArray): Boolean {
            return when (requestCode) {
                permissionRequestCode -> {
                    grantResults.isNotEmpty() &&
                            grantResults.all { it == PackageManager.PERMISSION_GRANTED }
                }
                else -> false
            }
        }

        @TargetApi(Build.VERSION_CODES.M)
        fun showPermissionsNecessaryDialog(activity: Activity) {
            val permissions = mutableListOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            val builder = AlertDialog.Builder(activity)
            builder.setMessage(R.string.alert_permissions_necessary_message)
                    .setTitle(R.string.alert_permissions_necessary_title)
                    .setPositiveButton(R.string.button_ok) { dialog, _ ->
                        activity.requestPermissions(permissions.toTypedArray(),
                                permissionRequestCode)
                        dialog.dismiss()
                    }
                    .setNegativeButton(R.string.alert_permissions_necessary_cancel_button) { dialog, _ ->
                        dialog.dismiss()
                    }
            builder.create().show()
        }
    }
}