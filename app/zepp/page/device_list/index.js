import { createWidget, widget, align, deleteWidget } from '@zos/ui';
import { push } from '@zos/router';
import { deviceStore } from '../../src/store/DeviceStore';
import { bleManager, BluetoothState } from '../../src/bluetooth/BleManager';
import { px, screenWidth, screenHeight } from '../../src/utils/px';

// The text the list shows when the watch cannot scan. The app has no translation system yet, so
// the copy is English, like every other string of the Zepp app.
const COPY = {
  bluetoothOff: 'Bluetooth is off.\nTurn on Bluetooth on the watch.',
  noPermission: 'Snapshot PB has no\nBluetooth permission.',
  permissionInSettings: 'Give Snapshot PB Bluetooth access\nin the watch settings.',
  allowBluetooth: 'Allow Bluetooth',
  tryAgain: 'Try Again'
};

function isBluetoothProblem(state) {
  return state === BluetoothState.Disabled || state === BluetoothState.NeedsPermission;
}

Page({
  onInit() {
    console.log("DeviceList page onInit");
    this.state = BluetoothState.Ready;
    this.discoveredDevices = [];
    this.dynamicWidgets = [];
    // A previously-rendered red error widget (from a failed drawDynamicUI), so a
    // subsequent redraw can clear it instead of stacking error widgets.
    this._errorWidget = null;
    this._destroyed = false;
    this._syncAvailability();
  },

  // Takes the Bluetooth state from BleManager, which checks the permission again. Disabled comes
  // only from a scan that did not start, so BleManager keeps it until the next scan or connection.
  // A Scanning or Connecting state of BleManager belongs to a flow that this page does not own,
  // so the page then shows Ready.
  _syncAvailability() {
    if (this.state === BluetoothState.Scanning) return;
    const state = bleManager.refreshAvailability();
    this.state = isBluetoothProblem(state) ? state : BluetoothState.Ready;
  },

  build() {
    console.log("DeviceList page build");
    
    // Draw background
    createWidget(widget.FILL_RECT, {
      x: 0,
      y: 0,
      w: screenWidth(),
      h: screenHeight(),
      color: 0x000000
    });

    // Page Title
    createWidget(widget.TEXT, {
      x: px(60),
      y: px(40),
      w: px(360),
      h: px(40),
      color: 0xFFFFFF,
      text_size: px(32),
      align_h: align.CENTER_H,
      align_v: align.CENTER_V,
      text: 'Snapshot PB'
    });

    this._safeDrawDynamicUI();
  },

  // drawDynamicUI can throw on a corrupted/legacy stored device (e.g. a missing
  // id or knownProfiles). build() guards it; onResume() and the scan callbacks
  // call it too, so route every call site through this guard for consistent
  // error handling — a bad store entry must not tear the page down.
  _safeDrawDynamicUI() {
    // Clear any prior error widget so redraws don't stack them.
    if (this._errorWidget) {
      try {
        deleteWidget(this._errorWidget);
      } catch (e) {
        console.log("Error deleting error widget:", e);
      }
      this._errorWidget = null;
    }
    try {
      this.drawDynamicUI();
    } catch (err) {
      console.log("drawDynamicUI failed:", err);
      this._errorWidget = createWidget(widget.TEXT, {
        x: px(20),
        y: px(100),
        w: px(440),
        h: px(340),
        color: 0xFF0000,
        text_size: px(20),
        align_h: align.LEFT,
        align_v: align.TOP,
        text: `Err: ${err.message}\nStack: ${err.stack || ''}`
      });
    }
  },

  onResume() {
    console.log("DeviceList page onResume");
    // Returning from Device Config (e.g. after changing the active profile) leaves
    // the known-devices list stale. Redraw from the store WITHOUT starting a new
    // scan or re-registering scan callbacks. drawDynamicUI re-reads the store and
    // clears prior widgets via its delete loop, so this is safe to call directly.
    if (this.dynamicWidgets) {
      // The user can allow Bluetooth in the watch settings and come back.
      this._syncAvailability();
      this._safeDrawDynamicUI();
    }
  },

  drawDynamicUI() {
    // Clean up old dynamic widgets
    this.dynamicWidgets.forEach(w => deleteWidget(w));
    this.dynamicWidgets = [];

    const knownDevices = deviceStore.getKnownDevices();
    let currentY = px(90);

    // 1. Synced Devices Section
    const sectionTitle = createWidget(widget.TEXT, {
      x: px(60),
      y: currentY,
      w: px(360),
      h: px(30),
      color: 0x888888,
      text_size: px(20),
      align_h: align.CENTER_H,
      align_v: align.CENTER_V,
      text: 'SYNCED DEVICES'
    });
    this.dynamicWidgets.push(sectionTitle);
    currentY += px(35);

    if (knownDevices.length > 0) {
      knownDevices.forEach(dev => {
        const activeProfile = dev.knownProfiles.find(p => p.id === dev.activeProfileId);
        const profileName = activeProfile ? activeProfile.name : 'No Active Profile';
        
        const card = createWidget(widget.BUTTON, {
          x: px(60),
          y: currentY,
          w: px(360),
          h: px(70),
          text: `${dev.name}\nProfile: ${profileName}`,
          normal_color: 0x1A1A1A,
          press_color: 0x333333,
          radius: px(15),
          text_size: px(22),
          color: 0xFFFFFF,
          click_func: () => {
            this.handleDeviceSelected(dev);
          }
        });
        this.dynamicWidgets.push(card);
        currentY += px(80);
      });
    } else {
      const noSyncText = createWidget(widget.TEXT, {
        x: px(60),
        y: currentY,
        w: px(360),
        h: px(40),
        color: 0x666666,
        text_size: px(20),
        align_h: align.CENTER_H,
        align_v: align.CENTER_V,
        text: 'No Synced Devices'
      });
      this.dynamicWidgets.push(noSyncText);
      currentY += px(45);
    }

    // 2. Scan Button, or the reason the watch cannot scan
    if (isBluetoothProblem(this.state)) {
      this._drawBluetoothProblem(currentY);
      return;
    }
    const isScanning = this.state === BluetoothState.Scanning;
    const scanBtn = createWidget(widget.BUTTON, {
      x: px(90),
      y: currentY,
      w: px(300),
      h: px(50),
      text: isScanning ? 'Stop Scanning' : 'Scan for Boards',
      normal_color: isScanning ? 0xD32F2F : 0xE65100, // Red for stop, orange for scan
      press_color: isScanning ? 0xB71C1C : 0xEF6C00,
      radius: px(25),
      text_size: px(22),
      color: 0xFFFFFF,
      click_func: () => {
        if (isScanning) {
          this.stopScan();
        } else {
          this.startScan();
        }
      }
    });
    this.dynamicWidgets.push(scanBtn);
    currentY += px(65);

    // 3. Discovered Section
    if (isScanning) {
      const discTitle = createWidget(widget.TEXT, {
        x: px(60),
        y: currentY,
        w: px(360),
        h: px(30),
        color: 0x888888,
        text_size: px(20),
        align_h: align.CENTER_H,
        align_v: align.CENTER_V,
        text: 'DISCOVERED BOARDS'
      });
      this.dynamicWidgets.push(discTitle);
      currentY += px(35);

      if (this.discoveredDevices.length === 0) {
        const searchingText = createWidget(widget.TEXT, {
          x: px(60),
          y: currentY,
          w: px(360),
          h: px(40),
          color: 0x666666,
          text_size: px(20),
          align_h: align.CENTER_H,
          align_v: align.CENTER_V,
          text: 'Searching...'
        });
        this.dynamicWidgets.push(searchingText);
      } else {
        this.discoveredDevices.forEach(dev => {
          const shortId = dev.id.slice(-4).toUpperCase();
          const devBtn = createWidget(widget.BUTTON, {
            x: px(60),
            y: currentY,
            w: px(360),
            h: px(60),
            text: `${dev.name} • ${dev.boardType} [#${shortId}]`,
            normal_color: 0x2A2A2A,
            press_color: 0x444444,
            radius: px(12),
            text_size: px(20),
            color: 0xFFFFFF,
            click_func: () => {
              this.handleDirectConnect(dev);
            }
          });
          this.dynamicWidgets.push(devBtn);
          currentY += px(70);
        });
      }
    }
  },

  // Draws the message and the action for Disabled or NeedsPermission, from [startY] down.
  _drawBluetoothProblem(startY) {
    let currentY = startY;
    const disabled = this.state === BluetoothState.Disabled;
    const refused = !disabled && bleManager.permissionRequestRefused;
    let message = COPY.noPermission;
    if (disabled) message = COPY.bluetoothOff;
    else if (refused) message = COPY.permissionInSettings;

    const messageText = createWidget(widget.TEXT, {
      x: px(40),
      y: currentY,
      w: px(400),
      h: px(60),
      color: 0xFFB74D,
      text_size: px(20),
      align_h: align.CENTER_H,
      align_v: align.CENTER_V,
      text: message
    });
    this.dynamicWidgets.push(messageText);
    currentY += px(70);

    // A permission the watch can still ask for gets the request. A radio that is off, or a
    // permission the user must set in the watch settings, gets a new scan.
    const asksPermission = !disabled && !refused;
    const actionBtn = createWidget(widget.BUTTON, {
      x: px(90),
      y: currentY,
      w: px(300),
      h: px(50),
      text: asksPermission ? COPY.allowBluetooth : COPY.tryAgain,
      normal_color: 0xE65100,
      press_color: 0xEF6C00,
      radius: px(25),
      text_size: px(22),
      color: 0xFFFFFF,
      click_func: () => {
        if (asksPermission) {
          this.requestPermission();
        } else {
          this.startScan();
        }
      }
    });
    this.dynamicWidgets.push(actionBtn);
  },

  // Asks for the Bluetooth permission. When the user allows it, the scan starts, because the user
  // came to this page to find a board.
  requestPermission() {
    bleManager.requestBluetoothPermission((state) => {
      if (this._destroyed) return;
      this.state = state;
      if (state === BluetoothState.Ready) {
        this.startScan();
      } else {
        this._safeDrawDynamicUI();
      }
    });
  },

  startScan() {
    this.discoveredDevices = [];
    bleManager.startScanning((devices) => {
      this.discoveredDevices = devices;
      this._safeDrawDynamicUI();
    }, (state) => {
      this.state = state;
      this._safeDrawDynamicUI();
    });
  },

  stopScan() {
    bleManager.stopScanning((state) => {
      this.state = state;
      this._safeDrawDynamicUI();
    });
  },

  handleDeviceSelected(device) {
    this.stopScan();
    const app = getApp();
    app.globalData.selectedDevice = device;
    push({
      url: 'page/device_config/index'
    });
  },

  handleDirectConnect(discoveredDevice) {
    this.stopScan();
    const app = getApp();
    app.globalData.connectingDevice = discoveredDevice;
    push({
      url: 'page/connecting/index'
    });
  },

  onDestroy() {
    console.log("DeviceList page onDestroy");
    this._destroyed = true;
    // No state callback here: it would redraw widgets on a page that is being torn down.
    bleManager.stopScanning();
  }
});
