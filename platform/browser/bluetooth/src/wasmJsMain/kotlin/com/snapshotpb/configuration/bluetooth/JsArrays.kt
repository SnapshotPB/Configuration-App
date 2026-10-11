@file:OptIn(ExperimentalWasmJsInterop::class)

package com.snapshotpb.configuration.bluetooth

import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.JsArray

/** The items of a JavaScript array as a Kotlin list. A `null` or `undefined` item is not in the list. */
internal fun <T : JsAny> JsArray<T>.items(): List<T> = (0 until length).mapNotNull { index -> get(index) }
