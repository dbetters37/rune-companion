package com.aicontrolcenter.device.platform

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build

/*
 * minSdk-28-safe wrappers around the PackageManager queries this module uses. API 33 replaced
 * the int-flag overloads with typed flag objects and deprecated the old ones.
 *
 * Every result is filtered by package visibility: a package this app may not see looks exactly
 * like one that is not installed.
 */

/** The package's info, or null when it is not installed (or not visible to this app). */
internal fun PackageManager.packageInfoOrNull(packageName: String, flags: Long = 0L): PackageInfo? =
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(flags))
        } else {
            @Suppress("DEPRECATION")
            getPackageInfo(packageName, flags.toInt())
        }
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

/**
 * Every package visible to this app, in one binder call. The answer is deliberately partial:
 * it covers what the manifest `<queries>` block makes visible, which is all the callers need.
 */
@SuppressLint("QueryPermissionsNeeded")
internal fun PackageManager.installedPackages(flags: Long = 0L): List<PackageInfo> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getInstalledPackages(PackageManager.PackageInfoFlags.of(flags))
    } else {
        @Suppress("DEPRECATION")
        getInstalledPackages(flags.toInt())
    }

/** Activities that would handle [intent]. */
internal fun PackageManager.activitiesFor(intent: Intent, flags: Long = 0L): List<ResolveInfo> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(flags))
    } else {
        @Suppress("DEPRECATION")
        queryIntentActivities(intent, flags.toInt())
    }

/** Services that would handle [intent]. */
internal fun PackageManager.servicesFor(intent: Intent, flags: Long = 0L): List<ResolveInfo> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        queryIntentServices(intent, PackageManager.ResolveInfoFlags.of(flags))
    } else {
        @Suppress("DEPRECATION")
        queryIntentServices(intent, flags.toInt())
    }

/**
 * The activities of a query result that [callerPackage] is really allowed to start.
 *
 * PackageManager also returns activities that are not exported or that demand a permission the
 * caller lacks; starting those fails with a SecurityException, so they are not capabilities.
 */
internal fun List<ResolveInfo>.startableBy(pm: PackageManager, callerPackage: String): List<ActivityInfo> =
    mapNotNull { it.activityInfo }.filter { activity ->
        val permission = activity.permission
        activity.exported &&
            (permission.isNullOrEmpty() || pm.checkPermission(permission, callerPackage) == PackageManager.PERMISSION_GRANTED)
    }
