import { createWidget, widget, align, deleteWidget } from '@zos/ui';
import { back, push, replace } from '@zos/router';
import { deviceStore } from '../../src/store/DeviceStore';
import { bleManager } from '../../src/bluetooth/BleManager';
import { writeDeviceToBoard } from '../../src/store/syncCoordinator';
import { boardSettingLabel } from '../../src/utils/boardSettings';
import { SAVE_COLOR_FAIL, saveOutcome } from '../../src/utils/saveOutcome';
import { drawLockoutLine } from '../../src/utils/lockoutLine';
import { Toast } from '../../src/utils/toast';
import { px, screenWidth, screenHeight } from '../../src/utils/px';

Page({
  onInit() {
    console.log("DeviceConfig page onInit");
    this.destroyed = false;
    // Initialize once; buildDynamicUI owns clearing/recreating these.
    this.dynamicWidgets = [];
    // Transient save toast. It owns its widget, so it is not in dynamicWidgets.
    this.toast = new Toast();
    // True while a board write runs. The Save button hides, so a second tap cannot start a second
    // OTS transfer on the same link.
    this.saving = false;

    // Resolve the device BEFORE build() runs so the header can draw.
    const app = getApp();
    const selected = app.globalData.selectedDevice;
    if (selected) {
      // Reload from store to get the latest active profile and board settings.
      this.device = deviceStore.getKnownDevices().find(d => d.id === selected.id) || selected;
    }

    // Check if connected (real GATT link only).
    this.isConnected = !!(this.device && bleManager.isConnected(this.device.id));
  },

  onResume() {
    console.log("DeviceConfig page onResume");
    const app = getApp();
    const selected = app.globalData.selectedDevice;
    if (selected) {
      // Re-resolve from store in case the active profile or a board setting changed.
      this.device = deviceStore.getKnownDevices().find(d => d.id === selected.id) || selected;
    }

    // Recompute connection state (real GATT link only).
    this.isConnected = !!(this.device && bleManager.isConnected(this.device.id));

    this.buildDynamicUI();
  },

  build() {
    console.log("DeviceConfig page build");
    
    // Draw background
    createWidget(widget.FILL_RECT, {
      x: 0,
      y: 0,
      w: screenWidth(),
      h: screenHeight(),
      color: 0x000000
    });

    if (!this.device) {
      createWidget(widget.TEXT, {
        x: px(60),
        y: px(180),
        w: px(360),
        h: px(80),
        color: 0xFF0000,
        text_size: px(24),
        align_h: align.CENTER_H,
        align_v: align.CENTER_V,
        text: 'Error: No device loaded'
      });
      return;
    }

    // Header Title
    createWidget(widget.TEXT, {
      x: px(60),
      y: px(40),
      w: px(360),
      h: px(40),
      color: 0xFFFFFF,
      text_size: px(28),
      align_h: align.CENTER_H,
      align_v: align.CENTER_V,
      text: this.device.name
    });

    // Draw the body here too. onResume draws it again, but a runtime that does
    // not call onResume on the first display would otherwise show only the title.
    this.buildDynamicUI();
  },

  buildDynamicUI() {
    this.toast.clear();
    this.dynamicWidgets.forEach(w => deleteWidget(w));
    this.dynamicWidgets = [];

    const activeProfile = this.device.knownProfiles.find(p => p.id === this.device.activeProfileId);
    const activeProfileName = activeProfile ? activeProfile.name : 'None';
    const diverged = deviceStore.isDiverged(this.device.id);

    let currentY = px(90);

    // 1. Active Profile Card
    const profHeader = createWidget(widget.TEXT, {
      x: px(80),
      y: currentY,
      w: px(320),
      h: px(20),
      color: 0x888888,
      text_size: px(16),
      align_h: align.LEFT,
      text: 'ACTIVE PROFILE'
    });
    this.dynamicWidgets.push(profHeader);
    currentY += px(22);

    const profCard = createWidget(widget.BUTTON, {
      x: px(60),
      y: currentY,
      w: px(360),
      h: px(65),
      text: activeProfileName,
      normal_color: 0x1A1A1A,
      press_color: 0x333333,
      radius: px(15),
      text_size: px(22),
      color: 0xFFFFFF,
      click_func: () => {
        push({
          url: 'page/profile_selection/index'
        });
      }
    });
    this.dynamicWidgets.push(profCard);
    currentY += px(70);

    // The editor tunes the active profile, so the row needs one.
    if (activeProfile) {
      const tuneMark = deviceStore.isProfileDiverged(this.device.id, activeProfile.id) ? ' *' : '';
      const tuneCard = createWidget(widget.BUTTON, {
        x: px(60),
        y: currentY,
        w: px(360),
        h: px(50),
        text: `Tune Profile${tuneMark}`,
        normal_color: 0x1A1A1A,
        press_color: 0x333333,
        radius: px(15),
        text_size: px(20),
        color: 0xFFFFFF,
        click_func: () => {
          push({
            url: 'page/profile_edit/index'
          });
        }
      });
      this.dynamicWidgets.push(tuneCard);
      currentY += px(65);
    } else {
      currentY += px(15);
    }

    // 2. BLE Connection Card
    const bleHeader = createWidget(widget.TEXT, {
      x: px(80),
      y: currentY,
      w: px(320),
      h: px(20),
      color: 0x888888,
      text_size: px(16),
      align_h: align.LEFT,
      text: 'CONNECTION'
    });
    this.dynamicWidgets.push(bleHeader);
    currentY += px(22);

    const bleStatusText = this.isConnected ? 'Connected via BLE' : 'BLE Disconnected';
    const bleActionText = this.isConnected ? 'Connected via BLE\nTap to Disconnect' : 'BLE Disconnected\nTap to Connect';
    const bleCard = createWidget(widget.BUTTON, {
      x: px(60),
      y: currentY,
      w: px(360),
      h: px(65),
      text: bleActionText,
      normal_color: this.isConnected ? 0x0E6251 : 0x1A1A1A, // Dark green if connected
      press_color: this.isConnected ? 0x117A65 : 0x333333,
      radius: px(15),
      text_size: px(18),
      color: 0xFFFFFF,
      click_func: () => {
        this.toggleConnection();
      }
    });
    this.dynamicWidgets.push(bleCard);
    currentY += px(85);

    // 3. Unsaved line. It shows without a link too, so the user knows why to connect.
    if (diverged) {
      const unsaved = createWidget(widget.TEXT, {
        x: px(60),
        y: currentY,
        w: px(360),
        h: px(26),
        color: 0xFFAB00,
        text_size: px(18),
        align_h: align.CENTER_H,
        align_v: align.CENTER_V,
        text: 'Unsaved changes'
      });
      this.dynamicWidgets.push(unsaved);
      currentY += px(32);
    }

    // 4. Save to Board Button (visible if connected and no write runs). A board in lockout does
    // not accept a change, so a line that tells the user why takes the place of the button.
    if (this.isConnected && !this.saving && this.device.lockoutEnabled) {
      currentY = drawLockoutLine(this.dynamicWidgets, currentY);
    } else if (this.isConnected && !this.saving) {
      const saveBtn = createWidget(widget.BUTTON, {
        x: px(90),
        y: currentY,
        w: px(300),
        h: px(50),
        text: 'Save to Board',
        normal_color: 0x00C853,
        press_color: 0x00E676,
        radius: px(25),
        text_size: px(22),
        color: 0xFFFFFF,
        click_func: () => {
          this.saveToBoard();
        }
      });
      this.dynamicWidgets.push(saveBtn);
      currentY += px(65);
    }

    // 5. Discard Button. It restores every field from the board baseline: the boot text, the
    // active profile, every profile and the board settings.
    if (diverged) {
      const discardBtn = createWidget(widget.BUTTON, {
        x: px(90),
        y: currentY,
        w: px(300),
        h: px(50),
        text: 'Discard Changes',
        normal_color: 0x333333,
        press_color: 0x555555,
        radius: px(25),
        text_size: px(22),
        color: 0xFFFFFF,
        click_func: () => {
          this.discardChanges();
        }
      });
      this.dynamicWidgets.push(discardBtn);
      currentY += px(65);
    }

    // 6. Board setting cards. Each one shows only when the board reported a value for it:
    // firmware from before the field reports none and ignores a choice. They sit below Save
    // because they change rarely, so Save and its result message stay on the first screen.
    currentY = this.drawBoardSettingCard(currentY, 'boltMode', 'BOLT MODE');
    currentY = this.drawBoardSettingCard(currentY, 'language', 'BOARD LANGUAGE');
    currentY = this.drawBoardSettingCard(currentY, 'screenBrightness', 'SCREEN BRIGHTNESS');
    currentY = this.drawBoardSettingCard(currentY, 'screenOffDelay', 'SCREEN OFF DELAY');

    // 7. Back button
    const backBtn = createWidget(widget.BUTTON, {
      x: px(215),
      y: currentY,
      w: px(50),
      h: px(50),
      text: '<',
      normal_color: 0x333333,
      press_color: 0x555555,
      radius: px(25),
      text_size: px(22),
      color: 0xFFFFFF,
      click_func: () => {
        back();
      }
    });
    this.dynamicWidgets.push(backBtn);
    currentY += px(50);

    // A round screen is narrow at the bottom. When the page scrolls, add empty space below Back so
    // that it can scroll up to where the screen is wide enough to tap it. The spacer's own height
    // counts, because the case it exists for is a Back button just inside the bottom edge.
    if (currentY + px(60) > screenHeight()) {
      const bottomSpace = createWidget(widget.FILL_RECT, {
        x: 0,
        y: currentY,
        w: screenWidth(),
        h: px(60),
        color: 0x000000
      });
      this.dynamicWidgets.push(bottomSpace);
    }
  },

  // One card for a board setting, showing the value the board reported. Returns the next y.
  // A setting the board reported nothing for draws nothing: the board ignores a choice for it.
  drawBoardSettingCard(y, settingKey, header) {
    const value = this.device[settingKey];
    if (value == null) return y;

    const headerWidget = createWidget(widget.TEXT, {
      x: px(80),
      y: y,
      w: px(320),
      h: px(20),
      color: 0x888888,
      text_size: px(16),
      align_h: align.LEFT,
      text: header
    });
    this.dynamicWidgets.push(headerWidget);

    const card = createWidget(widget.BUTTON, {
      x: px(60),
      y: y + px(22),
      w: px(360),
      h: px(65),
      text: boardSettingLabel(settingKey, value),
      normal_color: 0x1A1A1A,
      press_color: 0x333333,
      radius: px(15),
      text_size: px(22),
      color: 0xFFFFFF,
      click_func: () => {
        const app = getApp();
        app.globalData.selection = { scope: 'device', key: settingKey };
        push({
          url: 'page/board_setting_selection/index'
        });
      }
    });
    this.dynamicWidgets.push(card);
    return y + px(107);
  },

  discardChanges() {
    console.log("Discarding pending changes:", this.device.id);
    deviceStore.discardPendingChanges(this.device.id);
    this.refreshFromStore();
    this.buildDynamicUI();
  },

  // Re-reads the device from the store and updates the global reference.
  refreshFromStore() {
    const stored = deviceStore.getKnownDevices().find(d => d.id === this.device.id);
    if (stored) {
      this.device = stored;
      getApp().globalData.selectedDevice = stored;
    }
  },

  toggleConnection() {
    if (this.isConnected) {
      // Disconnect: actually release the BLE link.
      console.log("Disconnecting device:", this.device.id);
      bleManager.disconnect(() => {
        if (this.destroyed) return;
        this.isConnected = false;
        this.buildDynamicUI();
      });
    } else {
      // Re-connect: redirect to connecting page. Carry BOTH identifiers: `id` is
      // the board's protobuf UUID (store key) and `address` is the BLE connect
      // address (the scan-provided MAC). BleManager keys the link on `address`,
      // never the UUID, so a saved-device reconnect actually reaches the board.
      const app = getApp();
      const mock = bleManager.isMock(this.device);
      app.globalData.connectingDevice = {
        id: this.device.id,
        address: mock ? this.device.id : this.device.mac,
        name: this.device.name,
        boardType: mock ? "Cocker" : "Snapshot PB",
        isMock: mock
      };
      // replace, not push: the connecting page replaces itself with a new
      // device_config on success, so a push would leave this one below it and
      // put two device_config pages on the stack.
      replace({
        url: 'page/connecting/index'
      });
    }
  },

  saveToBoard() {
    console.log("Saving config to board...");
    if (this.saving || this.device.lockoutEnabled) return;
    const requested = this.device;
    this.saving = true;
    this.buildDynamicUI();
    writeDeviceToBoard(requested.id, requested, {
      onSuccess: (written) => {
        if (this.destroyed) return;
        console.log("Save successful!");
        this.saving = false;
        // The store now holds the values the board reported, so redraw and tell the user about the
        // first value that differs from the choice (see saveOutcome).
        this.refreshFromStore();
        this.buildDynamicUI();
        const outcome = saveOutcome(requested, this.device, written);
        this.toast.show(outcome.text, outcome.color);
      },
      onFailure: (err) => {
        if (this.destroyed) return;
        console.log("Save failed:", err);
        this.saving = false;
        // A timeout during the save releases the link (see BleManager), so read the
        // link state again: the page must not keep showing "Connected".
        this.isConnected = !!bleManager.isConnected(this.device.id);
        this.buildDynamicUI();
        this.toast.show(err && err.linkLost ? 'Link Lost!' : 'Save Failed!', SAVE_COLOR_FAIL);
      }
    });
  },

  onDestroy() {
    console.log("DeviceConfig page onDestroy");
    this.destroyed = true;
    this.toast.clear();
    // This page owns the live BLE link handed off by the connecting page.
    // Release it when leaving config (e.g. back to the device list). Navigating
    // forward to profile_selection or board_setting_selection uses push(), which
    // keeps this page alive, so onDestroy only fires on a genuine exit.
    if (this.device && bleManager.isConnected(this.device.id)) {
      bleManager.disconnect();
    }
  }
});
