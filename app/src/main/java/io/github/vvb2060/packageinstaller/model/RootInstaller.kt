package io.github.vvb2060.packageinstaller.model

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.system.Os
import android.util.Log
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.InputStreamReader

/**
 * Root-based package installer as fallback when Shizuku is not available
 */
object RootInstaller {
    private const val TAG = "RootInstaller"
    
    private var rootChecked = false
    private var hasRootAccess = false
    
    /**
     * Check if root access is available
     */
    fun isRootAvailable(): Boolean {
        if (rootChecked) {
            return hasRootAccess
        }
        
        return try {
            val process = Runtime.getRuntime().exec("su")
            val outputStream = DataOutputStream(process.outputStream)
            val inputStream = BufferedReader(InputStreamReader(process.inputStream))
            
            // Send a test command
            outputStream.writeBytes("id\n")
            outputStream.writeBytes("exit\n")
            outputStream.flush()
            
            val output = inputStream.readLine()
            val exitCode = process.waitFor()
            
            hasRootAccess = exitCode == 0 && output != null && output.contains("uid=0")
            rootChecked = true
            
            Log.d(TAG, "Root access check: available=$hasRootAccess, output=$output, exit=$exitCode")
            hasRootAccess
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check root access", e)
            rootChecked = true
            hasRootAccess = false
            false
        }
    }
    
    /**
     * Install APK using root privileges
     * @param context Application context
     * @param apkUri URI of the APK file to install
     * @return true if installation initiated successfully
     */
    fun installApk(context: Context, apkUri: Uri): Boolean {
        if (!isRootAvailable()) {
            Log.e(TAG, "Root access not available")
            return false
        }
        
        return try {
            // Copy APK to temp location accessible by root
            val tempPath = copyApkToTemp(context, apkUri)
            if (tempPath == null) {
                Log.e(TAG, "Failed to copy APK to temp location")
                return false
            }
            
            // Use pm install command with root privileges
            val process = Runtime.getRuntime().exec("su")
            val outputStream = DataOutputStream(process.outputStream)
            val inputStream = BufferedReader(InputStreamReader(process.inputStream))
            val errorStream = BufferedReader(InputStreamReader(process.errorStream))
            
            // Install the APK
            val installCommand = "pm install -r -d \"$tempPath\"\n"
            Log.d(TAG, "Executing: $installCommand")
            
            outputStream.writeBytes(installCommand)
            outputStream.writeBytes("exit\n")
            outputStream.flush()
            
            // Read output
            val output = StringBuilder()
            var line: String?
            while (inputStream.readLine().also { line = it } != null) {
                output.append(line).append("\n")
            }
            
            // Read errors
            val errors = StringBuilder()
            while (errorStream.readLine().also { line = it } != null) {
                errors.append(line).append("\n")
            }
            
            val exitCode = process.waitFor()
            Log.d(TAG, "Install output: $output")
            Log.d(TAG, "Install errors: $errors")
            Log.d(TAG, "Install exit code: $exitCode")
            
            // Clean up temp file
            try {
                val tempFile = File(tempPath)
                if (tempFile.exists()) {
                    tempFile.delete()
                    Log.d(TAG, "Cleaned up temp file: $tempPath")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to clean up temp file", e)
            }
            
            // Parse result - pm install typically returns "Success" on success
            val result = output.toString().trim()
            val success = result.contains("Success") && exitCode == 0
            
            Log.i(TAG, "Root installation result: success=$success")
            success
            
        } catch (e: Exception) {
            Log.e(TAG, "Root installation failed", e)
            false
        }
    }
    
    /**
     * Copy APK from content URI to a temp location accessible by root
     */
    private fun copyApkToTemp(context: Context, apkUri: Uri): String? {
        return try {
            // Create temp file in app's cache directory first
            val cacheDir = context.cacheDir
            val tempFile = File.createTempFile("install_", ".apk", cacheDir)
            
            // Copy content to cache file
            context.contentResolver.openInputStream(apkUri)?.use { inputStream ->
                tempFile.outputStream().use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            
            val tempPath = tempFile.absolutePath
            Log.d(TAG, "APK copied to temp path: $tempPath")
            
            // Make the file readable by root
            try {
                val process = Runtime.getRuntime().exec("su")
                val outputStream = DataOutputStream(process.outputStream)
                
                outputStream.writeBytes("chmod 644 \"$tempPath\"\n")
                outputStream.writeBytes("exit\n")
                outputStream.flush()
                
                process.waitFor()
                Log.d(TAG, "Made temp file readable by root")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to make temp file readable by root", e)
                // Continue anyway, might still work
            }
            
            tempPath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy APK to temp location", e)
            null
        }
    }
    
    /**
     * Check if we have permission to install packages using root
     */
    fun hasInstallPermission(): Boolean {
        if (!isRootAvailable()) {
            return false
        }
        
        return try {
            val process = Runtime.getRuntime().exec("su")
            val outputStream = DataOutputStream(process.outputStream)
            val inputStream = BufferedReader(InputStreamReader(process.inputStream))
            
            // Test pm command availability
            outputStream.writeBytes("which pm\n")
            outputStream.writeBytes("exit\n")
            outputStream.flush()
            
            val output = inputStream.readLine()
            val exitCode = process.waitFor()
            
            val hasPermission = exitCode == 0 && output != null && output.contains("/system/bin/pm")
            Log.d(TAG, "Install permission check: available=$hasPermission")
            hasPermission
            
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check install permission", e)
            false
        }
    }
}