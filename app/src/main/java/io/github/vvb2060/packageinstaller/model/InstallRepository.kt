package io.github.vvb2060.packageinstaller.model

import android.Manifest
import android.app.Application
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Intent
import android.content.IntentSender
import android.content.Intent_rename
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageInstaller.SessionInfo
import android.content.pm.PackageInstaller.SessionParams
import android.content.pm.PackageInstaller_rename
import android.content.pm.PackageManager
import android.content.pm.PackageManager_rename
import android.content.pm.VersionedPackage
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Process
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.system.Os
import android.util.Log
import androidx.core.graphics.drawable.toBitmap
import androidx.core.graphics.drawable.toDrawable
import androidx.core.net.toUri
import androidx.lifecycle.MutableLiveData
import io.github.vvb2060.packageinstaller.R
import io.github.vvb2060.packageinstaller.model.Hook.wrap
import io.github.vvb2060.packageinstaller.model.InstallAborted.Companion.ABORT_CLOSE
import io.github.vvb2060.packageinstaller.model.InstallAborted.Companion.ABORT_CREATE
import io.github.vvb2060.packageinstaller.model.InstallAborted.Companion.ABORT_INFO
import io.github.vvb2060.packageinstaller.model.InstallAborted.Companion.ABORT_NOTFOUND
import io.github.vvb2060.packageinstaller.model.InstallAborted.Companion.ABORT_PARSE
import io.github.vvb2060.packageinstaller.model.InstallAborted.Companion.ABORT_SHIZUKU
import io.github.vvb2060.packageinstaller.model.InstallAborted.Companion.ABORT_SPLIT
import io.github.vvb2060.packageinstaller.model.InstallAborted.Companion.ABORT_WRITE
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuProvider
import java.io.File
import java.io.IOException
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

class InstallRepository(private val context: Application) {
    private val TAG = InstallRepository::class.java.simpleName
    val installResult = MutableLiveData<InstallStage>()
    val stagingProgress = MutableLiveData<Int>()
    private lateinit var packageManager: PackageManager
    private lateinit var packageInstaller: PackageInstaller
    private var stagedSessionId = SessionInfo.INVALID_ID
    private var callingUid = Process.INVALID_UID
    private lateinit var intent: Intent
    private var apkLite: ApkLite? = null
    private var rootMode: Boolean = false
    // 兼容旧系统：动态获取 MATCH_KNOWN_PACKAGES，缺失则为 0
    private val matchKnownPackages: Int by lazy {
        try {
            // 运行期已被 remap 回真实 PackageManager 类
            val cls = Class.forName("android.content.pm.PackageManager")
            cls.getField("MATCH_KNOWN_PACKAGES").getInt(null)
        } catch (_: Throwable) { 0 }
    }

    private fun canUseRoot(): Boolean {
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val out = BufferedReader(InputStreamReader(proc.inputStream)).use { it.readText() }
            val err = BufferedReader(InputStreamReader(proc.errorStream)).use { it.readText() }
            proc.waitFor(2, TimeUnit.SECONDS)
            proc.exitValue() == 0 && (out.contains("uid=0") || err.contains("uid=0"))
        } catch (_: Exception) {
            false
        }
    }

    fun preCheck(intent: Intent): Boolean {
        if (!Shizuku.pingBinder()
            || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED
            || Shizuku.checkRemotePermission(Manifest.permission.INSTALL_PACKAGES) != PackageManager.PERMISSION_GRANTED
        ) {
            // 尝试 root 回退
            if (canUseRoot()) {
                rootMode = true
            } else {
                installResult.value = InstallAborted(
                    ABORT_SHIZUKU,
                    context.packageManager.getLaunchIntentForPackage(
                        ShizukuProvider.MANAGER_APPLICATION_ID
                    )
                )
                return false
            }
        }

        this.intent = intent
        Log.v(TAG, "Intent: $intent")

        if (!rootMode) {
            Hook.init(context)
            packageManager = Hook.pm
            packageInstaller = Hook.installer
            Hook.disableAdbVerify(context)
            PreferredActivity.set(context.packageManager)
        } else {
            // root 模式下直接使用自身 pm，不进行 Hook
            packageManager = context.packageManager
            packageInstaller = packageManager.packageInstaller
        }

        installResult.value = InstallParse()
        return true
    }

    fun parseUri() {
        var uri = intent.data
        if (uri == null) {
            uri = intent.getParcelableExtra(Intent.EXTRA_STREAM)
            intent.setData(uri)
        }
        if (uri != null && "package" == uri.scheme) {
            val packageName = uri.schemeSpecificPart
            installResult.postValue(processPackageUri(packageName))
            return
        }
        if (uri != null && "market" == uri.scheme && uri.authority == "details") {
            uri.getQueryParameter("id")?.let {
                installResult.postValue(processPackageUri(it))
                return
            }
        }
        if (uri != null && ContentResolver.SCHEME_CONTENT == uri.scheme) {
            installResult.postValue(processContentUri(uri))
            return
        }
        if (uri == null) {
            intent.getStringExtra(Intent.EXTRA_PACKAGE_NAME)?.let {
                installResult.postValue(processPackageUri(it))
                return
            }
        }
        installResult.postValue(InstallAborted(ABORT_INFO))
    }

    fun install(setInstaller: Boolean, commit: Boolean, full: Boolean, removeSplit: Boolean) {
        val uri = intent.data
        installResult.postValue(InstallInstalling(apkLite!!))
        if (rootMode) {
            rootInstall(uri, setInstaller, full, removeSplit)
            return
        }
        if (ContentResolver.SCHEME_CONTENT != uri?.scheme &&
            ContentResolver.SCHEME_FILE != uri?.scheme
        ) {
            installPackageUri()
            return
        }

        if (stagedSessionId == SessionInfo.INVALID_ID) {
            try {
                val params: SessionParams = createSessionParams(setInstaller, full)
                stagedSessionId = packageInstaller.createSession(params)
            } catch (e: IOException) {
                Log.e(TAG, "Failed to create a staging session", e)
                installResult.postValue(InstallAborted(ABORT_CREATE))
                return
            }
        }

        try {
            val session = packageInstaller.openSession(stagedSessionId)
            session.wrap()
            if (removeSplit) {
                session.removeSplit(apkLite!!.splitName!!)
            } else {
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                    if (apkLite!!.zip) {
                        PackageUtil.stageZip(session, afd) {
                            stagingProgress.postValue(it)
                        }
                    } else {
                        PackageUtil.stageApk(session, afd) {
                            stagingProgress.postValue(it)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            cleanupInstall()
            Log.e(TAG, "Could not stage APK.", e)
            installResult.postValue(InstallAborted(ABORT_WRITE))
            return
        }

        if (commit) {
            stagingProgress.postValue(101)
            commit()
        } else {
            installResult.postValue(InstallAborted(ABORT_CLOSE))
        }
    }

    private fun rootInstall(uri: Uri?, setInstaller: Boolean, full: Boolean, removeSplit: Boolean) {
        // root 模式：使用 pm shell 命令完成安装
        // 支持：单 APK、拆分补充、新增或移除 split（通过重新安装）以及 zip (简单解压后采用 install-multiple)
        try {
            val files = mutableListOf<File>()
            val apk = apkLite!!
            val cacheDir = File(context.cacheDir, "root_install").apply { mkdirs() }

            fun copyUriToFile(u: Uri): File? {
                return try {
                    val name = "apk_${System.currentTimeMillis()}" + if (apk.zip) ".zip" else ".apk"
                    val dst = File(cacheDir, name)
                    context.contentResolver.openInputStream(u)?.use { input ->
                        dst.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    } ?: return null
                    dst
                } catch (_: Exception) { null }
            }

            var source: File? = null
            if (uri != null) {
                source = when (uri.scheme) {
                    ContentResolver.SCHEME_FILE -> File(uri.path!!)
                    ContentResolver.SCHEME_CONTENT -> copyUriToFile(uri)
                    else -> null
                }
            }

            if (source == null || !source.exists()) {
                setStageBasedOnResult(
                    PackageInstaller.STATUS_FAILURE,
                    PackageManager_rename.INSTALL_FAILED_INTERNAL_ERROR,
                    "Source APK not found"
                )
                return
            }

            if (removeSplit) {
                // 通过重新安装不包含该 split 的其余 split 来实现移除
                try {
                    val oldInfo = packageManager.getPackageInfo(apk.packageName, 0)
                    val base = oldInfo.applicationInfo!!.sourceDir
                    val splits = oldInfo.applicationInfo!!.splitSourceDirs ?: emptyArray()
                    files.add(File(base))
                    splits.filterNot { it.contains("${apk.splitName}") }.forEach { files.add(File(it)) }
                } catch (e: Exception) {
                    setStageBasedOnResult(
                        PackageInstaller.STATUS_FAILURE,
                        PackageManager_rename.INSTALL_FAILED_INTERNAL_ERROR,
                        "Failed to prepare split removal: ${e.localizedMessage}"
                    )
                    return
                }
            } else if (apk.splitName != null && !apk.zip) {
                // 新增或替换单独 split: 需要包含 base + 其它 splits + 新 split
                try {
                    val oldInfo = packageManager.getPackageInfo(apk.packageName, 0)
                    val base = oldInfo.applicationInfo!!.sourceDir
                    val splits = oldInfo.applicationInfo!!.splitSourceDirs ?: emptyArray()
                    files.add(File(base))
                    // 其它 splits
                    splits.filterNot { path -> path.substringAfterLast('/') == source.name }.forEach { files.add(File(it)) }
                    files.add(source)
                } catch (_: Exception) {
                    // 如果旧信息获取失败，则退化为单 APK 安装
                    files.add(source)
                }
            } else if (apk.zip) {
                // 解压 zip 中的 apk 文件后用 install-multiple
                try {
                    context.contentResolver.openAssetFileDescriptor(uri!!, "r")?.use { afd ->
                        // 简化：直接逐条写出所有 .apk
                        org.apache.commons.compress.archivers.zip.ZipFile.builder()
                            .setIgnoreLocalFileHeader(true)
                            .setSeekableByteChannel(afd.createInputStream().channel)
                            .get().use { zf ->
                                val entries = zf.entries.asSequence().filter { it.name.endsWith(".apk") }.toList()
                                if (entries.isEmpty()) throw IOException("No apks in zip")
                                entries.forEach { entry ->
                                    val outFile = File(cacheDir, entry.name.substringAfterLast('/'))
                                    zf.getInputStream(entry).use { ins ->
                                        outFile.outputStream().use { outs -> ins.copyTo(outs) }
                                    }
                                    files.add(outFile)
                                }
                            }
                    }
                } catch (e: Exception) {
                    setStageBasedOnResult(
                        PackageInstaller.STATUS_FAILURE,
                        PackageManager_rename.INSTALL_FAILED_INTERNAL_ERROR,
                        "Zip extract failed: ${e.localizedMessage}"
                    )
                    return
                }
            } else {
                files.add(source)
            }

            val multiple = files.size > 1
            // 构建 pm 命令
            val cmd = buildString {
                append("pm ")
                append(if (multiple) "install-multiple" else "install")
                append(" -r -d -t") // 允许替换、降级，并允许 test-only
                if (setInstaller) {
                    // 尝试指定 installer (可能无效)
                    append(" --installer com.android.vending")
                }
                files.forEach { f -> append(' ') ; append(f.absolutePath) }
            }
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            val stdout = BufferedReader(InputStreamReader(proc.inputStream)).readText()
            val stderr = BufferedReader(InputStreamReader(proc.errorStream)).readText()
            proc.waitFor()
            // 清理缓存文件
            try { cacheDir.listFiles()?.forEach { it.delete() } } catch (_: Exception) {}
            val combined = (stdout + "\n" + stderr).lines().map { it.trim() }
            val success = combined.any { it.equals("Success", true) }
            if (success) {
                try { Thread.sleep(300) } catch (_: InterruptedException) {}
                setStageBasedOnResult(
                    PackageInstaller.STATUS_SUCCESS,
                    PackageManager_rename.INSTALL_SUCCEEDED,
                    null
                )
            } else {
                // 提取 Failure [REASON] 或首行错误信息
                val failureLine = combined.firstOrNull { it.startsWith("Failure") || it.startsWith("Error", true) }
                val msg = failureLine ?: combined.filter { it.isNotBlank() }.joinToString("\n").takeIf { it.isNotBlank() }
                setStageBasedOnResult(
                    PackageInstaller.STATUS_FAILURE,
                    PackageManager_rename.INSTALL_FAILED_INTERNAL_ERROR,
                    msg ?: "pm command failed"
                )
            }
        } catch (e: Exception) {
            setStageBasedOnResult(
                PackageInstaller.STATUS_FAILURE,
                PackageManager_rename.INSTALL_FAILED_INTERNAL_ERROR,
                e.localizedMessage
            )
        }
    }

    private fun processContentUri(uri: Uri): InstallStage {
        Log.v(TAG, "content URI: $uri")
        packageManager.resolveContentProvider(uri.authority!!, 0)?.also { info ->
            callingUid = info.applicationInfo.uid
        }
        val apk = try {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                PackageUtil.parseZipFromFd(afd)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse APK from content URI: $uri", e)
            null
        }
        if (apk == null) {
            return InstallAborted(ABORT_PARSE)
        }
        val old = try {
            packageManager.getPackageInfo(apk.packageName, matchKnownPackages)
        } catch (_: PackageManager.NameNotFoundException) { null }
        var full = true
        if (apk.isSplit()) {
            for (item in packageInstaller.allSessions) {
                if (item.isActive && item.appPackageName == apk.packageName) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        // noinspection NewApi
                        if (item.installerUid != Shizuku.getUid()) {
                            continue
                        }
                    } else {
                        if (item.installReason != PackageManager.INSTALL_REASON_USER) continue
                        item as PackageInstaller_rename.SessionInfo
                        if (item.installFlags and PackageManager_rename.INSTALL_FROM_ADB == 0) {
                            continue
                        }
                    }
                    val info = packageInstaller.getSessionInfo(item.sessionId)!!
                    stagedSessionId = info.sessionId
                    full = info.mode == SessionParams.MODE_FULL_INSTALL
                    apk.label = info.appLabel as String?
                    apk.icon = info.appIcon?.toDrawable(context.resources)
                    break
                }
            }
            if (stagedSessionId == SessionInfo.INVALID_ID) {
                if (old != null && old.longVersionCode == apk.versionCode) {
                    full = false
                } else {
                    return InstallAborted(ABORT_SPLIT)
                }
            }
        }
        if (old != null) {
            if (apk.label == null) {
                apk.label = old.applicationInfo!!.loadLabel(packageManager).toString()
            }
            if (apk.icon == null) {
                apk.icon = old.applicationInfo!!.loadIcon(packageManager)
            }
            // use this field to store the installer package name
            old.sharedUserId = packageManager.getInstallerPackageName(apk.packageName)
        }
        if (apk.label == null) {
            context.contentResolver.query(uri, null, null, null, null).use { cursor ->
                if (cursor != null && cursor.moveToFirst()) {
                    val displayNameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (displayNameIndex != -1) {
                        apk.label = cursor.getString(displayNameIndex)
                    }
                }
            }
        }
        apkLite = apk
        val skipCreate = stagedSessionId != SessionInfo.INVALID_ID
        return InstallUserAction(apk, old, full, skipCreate)
    }

    private fun processPackageUri(packageName: String): InstallStage {
        val info = try {
            packageManager.getPackageInfo(packageName, matchKnownPackages)
        } catch (e: PackageManager.NameNotFoundException) {
            Log.e(TAG, "Requested package not available.", e)
            return InstallAborted(ABORT_NOTFOUND)
        }

        val apk = PackageUtil.getApkLite(packageManager, info!!)
        apkLite = apk

        val path = info.applicationInfo!!.sourceDir
        if (path == null || !File(path).exists()) {
            queryZipUri(info)?.let {
                intent.setData(it)
                return processContentUri(it)
            }
        } else if (info.applicationInfo!!.flags and ApplicationInfo.FLAG_INSTALLED != 0) {
            return PackageUserAction(apk, info)
        }
        return InstallUserAction(apk, info)
    }

    private fun queryZipUri(info: PackageInfo): Uri? {
        val name = "${info.packageName}-${info.longVersionCode}.zip"
        val name2 = "${info.packageName}-${info.longVersionCode} (%).zip"
        val dir = Environment.DIRECTORY_DOCUMENTS + File.separator +
            context.getString(R.string.app_name)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val cr = context.contentResolver
            val tableUri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
            val selection = "${MediaStore.MediaColumns.DISPLAY_NAME}=? OR " +
                "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"
            val selectionArgs = arrayOf(name, name2)
            val projection = arrayOf(MediaStore.MediaColumns._ID)
            cr.query(tableUri, projection, selection, selectionArgs, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val id = cursor.getLong(index)
                    return MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL, id)
                }
            }
        } else {
            val file = File(context.getExternalFilesDir(dir), name)
            if (file.exists()) {
                return file.toUri()
            }
        }
        return null
    }

    private fun installPackageUri() {
        stagingProgress.postValue(101)
        try {
            val pm = packageManager as PackageManager_rename
            pm.installExistingPackage(apkLite!!.packageName, PackageManager.INSTALL_REASON_USER)
            setStageBasedOnResult(
                PackageInstaller.STATUS_SUCCESS,
                PackageManager_rename.INSTALL_SUCCEEDED,
                null
            )
        } catch (e: PackageManager.NameNotFoundException) {
            setStageBasedOnResult(
                PackageInstaller.STATUS_FAILURE,
                PackageManager_rename.INSTALL_FAILED_INTERNAL_ERROR,
                e.localizedMessage
            )
        }
    }

    private fun createSessionParams(setInstaller: Boolean, full: Boolean): SessionParams {
        val mode = if (full) {
            SessionParams.MODE_FULL_INSTALL
        } else {
            SessionParams.MODE_INHERIT_EXISTING
        }
        val params = SessionParams(mode)
        var installer = context.packageName
        if (setInstaller) {
            try {
                packageManager.getPackageInfo("com.android.vending", PackageManager.MATCH_SYSTEM_ONLY)
                installer = "com.android.vending"
            } catch (_: PackageManager.NameNotFoundException) {
                installer = "com.android.shell"
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                params.setPackageSource(PackageInstaller.PACKAGE_SOURCE_STORE)
            }
        } else {
            val referrerUri: Uri? = intent.getParcelableExtra(Intent.EXTRA_REFERRER)
            params.setReferrerUri(referrerUri)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val source = if (referrerUri != null) PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE
                else PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE
                params.setPackageSource(source)
            }
            params.setOriginatingUri(intent.getParcelableExtra(Intent.EXTRA_ORIGINATING_URI))
            params.setOriginatingUid(intent.getIntExtra(Intent_rename.EXTRA_ORIGINATING_UID, callingUid))
        }
        // noinspection NewApi
        params.setInstallerPackageName(installer)
        params.setInstallReason(PackageManager.INSTALL_REASON_USER)
        params.setAppPackageName(apkLite!!.packageName)
        if (apkLite!!.needSplit()) {
            params.setAppIcon(apkLite!!.icon?.toBitmap())
            params.setAppLabel(apkLite!!.label)
        }

        val p = params as PackageInstaller_rename.SessionParams
        p.installFlags = p.installFlags or PackageManager_rename.INSTALL_ALLOW_TEST
        p.installFlags = p.installFlags or PackageManager_rename.INSTALL_REPLACE_EXISTING
        p.installFlags = p.installFlags or PackageManager_rename.INSTALL_REQUEST_DOWNGRADE
        p.installFlags = p.installFlags or PackageManager_rename.INSTALL_FULL_APP
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            p.installFlags = p.installFlags or PackageManager_rename.INSTALL_BYPASS_LOW_TARGET_SDK_BLOCK
            p.installFlags = p.installFlags or PackageManager_rename.INSTALL_REQUEST_UPDATE_OWNERSHIP
        }
        return params
    }

    private fun commit() {
        val receiver = LocalIntentReceiver(::setStageBasedOnResult)
        try {
            val session = packageInstaller.openSession(stagedSessionId)
            session.wrap()
            // noinspection RequestInstallPackagesPolicy
            session.commit(receiver.intentSender as IntentSender)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to commit staged session", e)
            cleanupInstall()
            setStageBasedOnResult(
                PackageInstaller.STATUS_FAILURE,
                PackageManager_rename.INSTALL_FAILED_INTERNAL_ERROR,
                e.localizedMessage
            )
        }
    }

    private fun setStageBasedOnResult(statusCode: Int, legacyStatus: Int, message: String?) {
        if (statusCode == PackageInstaller.STATUS_SUCCESS) {
            val intent = packageManager.getLaunchIntentForPackage(apkLite!!.packageName)
            // 新安装后部分老系统/低性能设备上 PMS 尚未完成索引，增加轮询重试
            fetchPackageInfoWithRetry(apkLite!!.packageName)?.let { info ->
                try {
                    apkLite!!.icon = info.applicationInfo!!.loadIcon(packageManager)
                } catch (_: Exception) {}
                try {
                    apkLite!!.label = info.applicationInfo!!.loadLabel(packageManager).toString()
                } catch (_: Exception) {}
            }
            installResult.postValue(InstallSuccess(apkLite!!, intent))
        } else {
            installResult.postValue(InstallFailed(apkLite!!, legacyStatus, statusCode, message))
        }
    }

    private fun fetchPackageInfoWithRetry(pkg: String, attempts: Int = 20, intervalMs: Long = 150): PackageInfo? {
        repeat(attempts) { idx ->
            try {
                return packageManager.getPackageInfo(pkg, 0)
            } catch (_: PackageManager.NameNotFoundException) {
                try { Thread.sleep(intervalMs) } catch (_: InterruptedException) {}
            } catch (t: Throwable) { // 其它异常直接跳出
                Log.w(TAG, "getPackageInfo retry aborted: ${t.message}")
                return null
            }
        }
        return null
    }

    fun cleanupInstall() {
        if (stagedSessionId > 0) {
            try {
                packageInstaller.abandonSession(stagedSessionId)
            } catch (_: SecurityException) {
            }
            stagedSessionId = 0
        }
    }

    fun archivePackage(info: PackageInfo, uninstall: Boolean) {
        installResult.postValue(InstallInstalling(apkLite!!))

        val name = "${info.packageName}-${info.longVersionCode}.zip"
        val dir = Environment.DIRECTORY_DOCUMENTS + File.separator +
            context.getString(R.string.app_name)
        var path = ""
        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val cr = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.RELATIVE_PATH, dir)
                put(MediaStore.MediaColumns.IS_PENDING, true)
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            }
            val tableUri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
            cr.insert(tableUri, values)
        } else {
            val file = File(context.getExternalFilesDir(dir), name)
            path = file.absolutePath
            file.toUri()
        }
        if (uri == null) {
            installResult.postValue(InstallAborted(ABORT_WRITE))
            return
        }

        val list = ArrayList<File>()
        list.add(File(info.applicationInfo!!.sourceDir))
        info.applicationInfo!!.splitSourceDirs?.let {
            for (split in it) {
                list.add(File(split))
            }
        }

        try {
            context.contentResolver.openAssetFileDescriptor(uri, "wt")?.use { afd ->
                PackageUtil.archivePackage(list, afd) {
                    stagingProgress.postValue(it)
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to archive package", e)
            if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
                context.contentResolver.delete(uri, null, null)
            } else {
                File(uri.path!!).delete()
            }
            installResult.postValue(InstallAborted(ABORT_WRITE))
            return
        }

        if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
            val cr = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, false)
            }
            cr.update(uri, values, null, null)
            cr.query(uri, null, null, null, null).use { cursor ->
                if (cursor != null && cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                    if (index != -1) {
                        path = cursor.getString(index)
                    }
                }
            }
        }

        val intent = Intent(Intent.ACTION_VIEW)
        intent.setDataAndType(uri, "application/zip")
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        if (uninstall) {
            val receiver = LocalIntentReceiver { statusCode, legacyCode, msg ->
                if (statusCode == PackageInstaller.STATUS_SUCCESS) {
                    installResult.postValue(InstallSuccess(apkLite!!, intent, path))
                } else {
                    installResult.postValue(InstallSuccess(apkLite!!, intent, "$path\n\n$msg"))
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                try {
                    // noinspection MissingPermission
                    packageInstaller.requestArchive(
                        info.packageName,
                        receiver.intentSender as IntentSender
                    )
                    return
                } catch (e: PackageManager.NameNotFoundException) {
                    Log.w(TAG, "Failed to archive for package ${info.packageName}", e)
                }
            }
            // noinspection MissingPermission NewApi
            packageInstaller.uninstall(
                VersionedPackage(info.packageName, info.longVersionCode),
                PackageManager_rename.DELETE_KEEP_DATA,
                receiver.intentSender as IntentSender
            )
            val userId = Os.getuid() / 100000
            val pm = packageManager as PackageManager_rename
            pm.deleteApplicationCacheFilesAsUser(info.packageName, userId, null)
        } else {
            installResult.postValue(InstallSuccess(apkLite!!, intent, path))
        }
    }

    fun setPackageEnabled(packageName: String, enabled: Boolean) {
        val newState = if (enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
        }
        packageManager.setApplicationEnabledSetting(packageName, newState, 0)
    }

}
