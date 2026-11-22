package com.aicontrolcenter.app.di

import android.content.Context

/**
 * Builds the real, on-device implementations of every port.
 *
 * This is the only place in the app that names the `:data` and `:device` modules, so swapping
 * a storage or platform implementation never touches a screen or a view model. It is called
 * once per process, lazily, from [com.aicontrolcenter.app.AiControlCenterApp.graph], with the
 * application context; it must not block (open the database and the keystore lazily or on
 * first use) and must return a [Ports] in which:
 *
 *  - `agents`, `providers`, `models`, `aiApps`, `activityLog` and `settings` are the persistent
 *    implementations that pass the `:testkit` contract suites;
 *  - `secretVault` is the keystore-backed vault;
 *  - `aiAppScanner` and `deviceInspector` read the device through PackageManager and the
 *    public system APIs;
 *  - `aiAppLauncher` is the plain Android launcher. Do not wrap it in `LoggingAiAppLauncher`:
 *    [AppGraph] does that, and wrapping twice would log every hand-off twice;
 *  - `clock` and `idGenerator` can stay at their defaults;
 *  - `packageChanges` emits `Unit` whenever a package is added, removed, replaced or has its
 *    enabled state changed, for as long as it is collected.
 */
fun createProductionPorts(context: Context): Ports = TODO("wired to :data and :device at integration")
