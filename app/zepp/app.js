import { deviceStore } from './src/store/DeviceStore';

App({
  globalData: {
    selectedDevice: null,
    connectingDevice: null,
    mockDevicesEnabled: false,
    // Handoff to board_setting_selection: { scope: 'device' | 'profile', key, profileId }.
    // The page that pushes it sets it, and board_setting_selection clears it in onDestroy.
    selection: null,
    // Handoff to profile_field: { profileId, key }, where key is a MarkerProfileField name.
    // profile_edit sets it, and profile_field clears it in onDestroy.
    profileField: null
  },
  onCreate(options) {
    console.log("Snapshot PB app onCreate");
  },
  onDestroy(options) {
    console.log("Snapshot PB app onDestroy");
    // Write a deferred store change (a stepper edit) before the app closes.
    deviceStore.flush();
  }
});
