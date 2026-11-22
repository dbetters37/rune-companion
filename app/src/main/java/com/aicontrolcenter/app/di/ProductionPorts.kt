package com.aicontrolcenter.app.di

import android.content.Context

/**
 * Builds the real, on-device implementations of every port.
 *
 * This is the only place in the app that names the `:data` and `:device` modules, so swapping
 * a storage or platform implementation never touches a screen or a view model.
 */
fun createProductionPorts(context: Context): Ports = TODO("wired to :data and :device at integration")
