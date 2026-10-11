package com.snapshotpb.configuration.store

import kotlinx.serialization.json.Json

/**
 * The canonical app-wide [Json] used to persist and transport domain models (device store
 * on disk, Wear Data Layer transfers). Bound as the app's single `Json` singleton in
 * `coreDiModule` so every (de)serialization site shares this configuration.
 *
 * [Json.coerceInputValues] is load-bearing, not cosmetic: enums serialize by constant name,
 * so when an enum constant is removed (e.g. a retired `MarkerFireMode`), previously-persisted
 * JSON still references the old name. Without coercion, decoding an unknown enum value throws
 * and — because the store decodes the whole `List<Device>` at once — a single stale record
 * takes down the entire load, silently emptying the device list and then overwriting it. With
 * coercion, the unknown value degrades to the property default (e.g. `MarkerFireMode.SEMI`)
 * instead. `ignoreUnknownKeys` only covers unknown object *keys*, not unknown enum *values*,
 * so it does not substitute for this. Do not remove [Json.coerceInputValues] without a
 * migration path for stored data.
 */
val appJson: Json = Json {
    prettyPrint = false
    ignoreUnknownKeys = true
    encodeDefaults = false
    coerceInputValues = true
}
