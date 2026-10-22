package fr.installapk.app.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.security.MessageDigest

object ApkInstaller {

    fun canRequestPackageInstalls(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                context.packageManager.canRequestPackageInstalls()
            } catch (e: Exception) {
                true
            }
        } else {
            true
        }
    }

    fun openInstallPermissionSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val intent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}")
                )
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            } catch (e: Exception) {
                try {
                    val fallbackIntent = Intent(Settings.ACTION_SECURITY_SETTINGS)
                    fallbackIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(fallbackIntent)
                } catch (e2: Exception) {
                    e2.printStackTrace()
                }
            }
        }
    }

    /**
     * Vérifie si le certificat de signature de l'APK téléchargé diffère de celui de l'application
     * déjà installée sur l'appareil (ce qui provoquerait INSTALL_FAILED_UPDATE_INCOMPATIBLE).
     */
    fun hasSignatureConflict(context: Context, packageName: String, apkFile: File): Boolean {
        if (packageName.isBlank() || !apkFile.exists()) return false
        return try {
            val pm = context.packageManager
            val installedSigs = getPackageSignaturesSha256(pm, packageName, isArchive = false)
            if (installedSigs.isEmpty()) return false // Pas installé ou signatures non lisibles

            val archiveSigs = getPackageSignaturesSha256(pm, apkFile.absolutePath, isArchive = true)
            if (archiveSigs.isEmpty()) return false

            installedSigs.intersect(archiveSigs).isEmpty()
        } catch (_: Exception) {
            false
        }
    }

    @Suppress("DEPRECATION")
    private fun getPackageSignaturesSha256(
        pm: PackageManager,
        target: String,
        isArchive: Boolean
    ): Set<String> {
        val info: PackageInfo? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val flags = PackageManager.GET_SIGNING_CERTIFICATES
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val pFlags = PackageManager.PackageInfoFlags.of(flags.toLong())
                if (isArchive) pm.getPackageArchiveInfo(target, pFlags) else pm.getPackageInfo(target, pFlags)
            } else {
                if (isArchive) pm.getPackageArchiveInfo(target, flags) else pm.getPackageInfo(target, flags)
            }
        } else {
            val flags = PackageManager.GET_SIGNATURES
            if (isArchive) pm.getPackageArchiveInfo(target, flags) else pm.getPackageInfo(target, flags)
        } ?: return emptySet()

        val rawSignatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = info?.signingInfo
            if (signingInfo != null) {
                if (signingInfo.hasMultipleSigners()) {
                    signingInfo.apkContentsSigners
                } else {
                    signingInfo.signingCertificateHistory
                }
            } else {
                info?.signatures
            }
        } else {
            info?.signatures
        } ?: return emptySet()

        return rawSignatures.mapNotNull { sig ->
            try {
                val digest = MessageDigest.getInstance("SHA-256").digest(sig.toByteArray())
                digest.joinToString("") { "%02x".format(it) }
            } catch (_: Exception) {
                null
            }
        }.toSet()
    }

    fun installApk(context: Context, apkFile: File): Boolean {
        if (!apkFile.exists() || apkFile.length() == 0L) return false
        return try {
            val apkUri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
                putExtra(Intent.EXTRA_RETURN_RESULT, true)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.queryIntentActivities(
                    intent,
                    PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            }
            for (ri in resolved) {
                try {
                    context.grantUriPermission(ri.activityInfo.packageName, apkUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: Exception) {}
            }

            context.startActivity(intent)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Ouvre l'application cible via son intent de lancement standard.
     */
    fun openApp(context: Context, packageName: String): Boolean {
        if (packageName.isBlank()) return false
        return try {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Déclenche la désinstallation native de l'application via l'intent système Android.
     */
    fun uninstallApp(context: Context, packageName: String): Boolean {
        if (packageName.isBlank()) return false
        return try {
            val intent = Intent(Intent.ACTION_DELETE).apply {
                data = Uri.parse("package:$packageName")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
