# Snapshot PB — Wear OS Application Module (`:app:wear`)

This module houses the Wear OS application for the Snapshot PB Configuration App. It targets smartwatches running Wear OS 3+ (API 30 or higher), such as the Pixel Watch.

## Architecture

* **UI Layer:** Implements standard **Compose for Wear OS** widgets (`ScalingLazyColumn`, `TitleCard`, etc.) to fit circular watch screens.
* **Shared Logic:** Directly reuses KMP logic modules:
  * `:core:bluetooth` (GATT Object Transfer Client)
  * `:core:model` & `:core:protocol` (Protobuf serialization schemas)
  * `:core:store` (Local Device configuration storage)
  * `:core:di` (Global Kodein DI bindings)
* **Shared Platform Implementations:** Imports `:platform:android:store` to handle platform-specific filesystem operations.
* **Direct BLE connection:** Imports `:platform:android:bluetooth` and `:platform:android:di` to reuse the Android-native Bluetooth stacks. This enables the watch to scan for and connect directly to Autococker ESP32 boards on the field, without needing a phone bridge active.

## Standalone Capabilities

The app declares `standalone="true"` in its `AndroidManifest.xml`. While it supports offline operations and direct BLE pairing with boards, it is also equipped to sync configurations with the main phone application via Wearable APIs once connectivity is restored.
