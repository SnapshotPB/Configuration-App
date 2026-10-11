import { createWidget, widget, align, deleteWidget, text_style } from '@zos/ui';
import { back, replace } from '@zos/router';
import { bleManager, StepStatus } from '../../src/bluetooth/BleManager';
import { deviceStore } from '../../src/store/DeviceStore';
import {
  resolveConflict as resolveSyncConflict,
  confirmNewDevice as confirmSyncNewDevice,
  writeDeviceToBoard
} from '../../src/store/syncCoordinator';
import { SAVE_BLOCKED_LOCKOUT_TEXT } from '../../src/utils/lockoutLine';
import { px, screenWidth, screenHeight } from '../../src/utils/px';

Page({
  onInit() {
    console.log("Connecting page onInit");
    this.destroyed = false;
    this.handingOff = false;
    const app = getApp();
    this.connectingDevice = app.globalData.connectingDevice;
    this.progress = {
      step1: StepStatus.PENDING,
      step2: StepStatus.PENDING,
      step3: StepStatus.PENDING,
      step4: StepStatus.PENDING
    };
    // 'NewDevice' | 'Conflict' | 'Synced' | 'Failed', or during "Keep My Values":
    // 'Writing' while the write runs and 'WriteFailed' when it fails.
    this.syncState = null;
    this.writeError = "";
    this.errorMessage = "";
    this.dynamicWidgets = [];
    this.loadedDeviceData = null;
  },

  onDestroy() {
    console.log("Connecting page onDestroy");
    // Guard pending callbacks/timers against acting on a torn-down page.
    this.destroyed = true;
    // Release the in-flight BLE link ONLY when the page is being abandoned
    // (Cancel/Back). On a successful handoff to device_config we keep the link
    // alive — device_config owns it from here and releases it on its own
    // onDestroy. (navigateToConfig uses replace(), which destroys this page.)
    if (!this.handingOff) {
      bleManager.disconnect();
    }
  },

  build() {
    console.log("Connecting page build");
    
    // Draw background
    createWidget(widget.FILL_RECT, {
      x: 0,
      y: 0,
      w: screenWidth(),
      h: screenHeight(),
      color: 0x000000
    });

    if (!this.connectingDevice) {
      createWidget(widget.TEXT, {
        x: px(60),
        y: px(180),
        w: px(360),
        h: px(80),
        color: 0xFF0000,
        text_size: px(24),
        align_h: align.CENTER_H,
        align_v: align.CENTER_V,
        text: 'Error: No device selected'
      });
      return;
    }

    this.drawStaticUI();
    this.drawDynamicUI();
    
    // Start connect flow
    this.startConnection();
  },

  drawStaticUI() {
    const shortId = this.connectingDevice.id.slice(-4).toUpperCase();
    
    // Device Subtext
    createWidget(widget.TEXT, {
      x: px(60),
      y: px(85),
      w: px(360),
      h: px(30),
      color: 0x888888,
      text_size: px(20),
      align_h: align.CENTER_H,
      align_v: align.CENTER_V,
      text: `${this.connectingDevice.boardType || 'Snapshot PB'} [#${shortId}]`
    });
  },

  drawDynamicUI() {
    if (this.destroyed) return;
    this.dynamicWidgets.forEach(w => deleteWidget(w));
    this.dynamicWidgets = [];

    // Title status text
    let titleText = 'Connecting...';
    let titleColor = 0xFFFFFF;
    if (this.syncState === 'Synced' || this.syncState === 'NewDevice' ||
        this.syncState === 'Conflict' || this.syncState === 'Writing') {
      titleText = 'Connected';
      titleColor = 0x4CAF50;
    } else if (this.syncState === 'WriteFailed') {
      titleText = 'Write Failed';
      titleColor = 0xD32F2F;
    } else if (this.syncState === 'Failed') {
      titleText = 'Connection Failed';
      titleColor = 0xD32F2F;
    }

    const titleWidget = createWidget(widget.TEXT, {
      x: px(60),
      y: px(40),
      w: px(360),
      h: px(40),
      color: titleColor,
      text_size: px(28),
      align_h: align.CENTER_H,
      align_v: align.CENTER_V,
      text: titleText
    });
    this.dynamicWidgets.push(titleWidget);

    // If still in progress, show steps
    if (!this.syncState) {
      const step1Text = this._getStepText('Connection', this.progress.step1);
      const s1 = createWidget(widget.TEXT, {
        x: px(100),
        y: px(130),
        w: px(280),
        h: px(30),
        color: this._getStepColor(this.progress.step1),
        text_size: px(20),
        align_h: align.LEFT,
        text: step1Text
      });
      this.dynamicWidgets.push(s1);

      const step2Text = this._getStepText('Services', this.progress.step2);
      const s2 = createWidget(widget.TEXT, {
        x: px(100),
        y: px(165),
        w: px(280),
        h: px(30),
        color: this._getStepColor(this.progress.step2),
        text_size: px(20),
        align_h: align.LEFT,
        text: step2Text
      });
      this.dynamicWidgets.push(s2);

      const step3Text = this._getStepText('Device Info', this.progress.step3);
      const s3 = createWidget(widget.TEXT, {
        x: px(100),
        y: px(200),
        w: px(280),
        h: px(30),
        color: this._getStepColor(this.progress.step3),
        text_size: px(20),
        align_h: align.LEFT,
        text: step3Text
      });
      this.dynamicWidgets.push(s3);

      const step4Text = this._getStepText('Profiles', this.progress.step4);
      const s4 = createWidget(widget.TEXT, {
        x: px(100),
        y: px(235),
        w: px(280),
        h: px(30),
        color: this._getStepColor(this.progress.step4),
        text_size: px(20),
        align_h: align.LEFT,
        text: step4Text
      });
      this.dynamicWidgets.push(s4);

      // Cancel button
      const cancelBtn = createWidget(widget.BUTTON, {
        x: px(140),
        y: px(340),
        w: px(200),
        h: px(50),
        text: 'Cancel',
        normal_color: 0x333333,
        press_color: 0x555555,
        radius: px(25),
        text_size: px(22),
        color: 0xFFFFFF,
        click_func: () => {
          back();
        }
      });
      this.dynamicWidgets.push(cancelBtn);
    } 
    
    // Synced flow
    else if (this.syncState === 'Synced') {
      const successText = createWidget(widget.TEXT, {
        x: px(60),
        y: px(160),
        w: px(360),
        h: px(60),
        color: 0xCCCCCC,
        text_size: px(22),
        align_h: align.CENTER_H,
        align_v: align.CENTER_V,
        text: 'All data loaded successfully.'
      });
      this.dynamicWidgets.push(successText);

      const contBtn = createWidget(widget.BUTTON, {
        x: px(115),
        y: px(300),
        w: px(250),
        h: px(55),
        text: 'Continue',
        normal_color: 0x00C853,
        press_color: 0x00E676,
        radius: px(25),
        text_size: px(24),
        color: 0xFFFFFF,
        click_func: () => {
          this.navigateToConfig(this.loadedDeviceData);
        }
      });
      this.dynamicWidgets.push(contBtn);
    } 
    
    // New Device Flow
    else if (this.syncState === 'NewDevice') {
      const successText = createWidget(widget.TEXT, {
        x: px(60),
        y: px(140),
        w: px(360),
        h: px(100),
        color: 0xFFFFFF,
        text_size: px(20),
        align_h: align.CENTER_H,
        align_v: align.CENTER_V,
        text: 'New Device Detected!\nDo you want to sync this board?'
      });
      this.dynamicWidgets.push(successText);

      const registerBtn = createWidget(widget.BUTTON, {
        x: px(90),
        y: px(270),
        w: px(300),
        h: px(50),
        text: 'Sync Board',
        normal_color: 0xE65100,
        press_color: 0xEF6C00,
        radius: px(25),
        text_size: px(22),
        color: 0xFFFFFF,
        click_func: () => {
          // Register in store
          confirmSyncNewDevice(this.loadedDeviceData, this.defaultDeviceName());
          // Navigate to config
          const savedDevice = deviceStore.getKnownDevices().find(d => d.id === this.loadedDeviceData.id);
          this.navigateToConfig(savedDevice);
        }
      });
      this.dynamicWidgets.push(registerBtn);

      const cancelBtn = createWidget(widget.BUTTON, {
        x: px(140),
        y: px(340),
        w: px(200),
        h: px(50),
        text: 'Ignore',
        normal_color: 0x333333,
        press_color: 0x555555,
        radius: px(25),
        text_size: px(22),
        color: 0xFFFFFF,
        click_func: () => {
          back();
        }
      });
      this.dynamicWidgets.push(cancelBtn);
    } 
    
    // Conflict Flow
    else if (this.syncState === 'Conflict') {
      const conflictText = createWidget(widget.TEXT, {
        x: px(60),
        y: px(130),
        w: px(360),
        h: px(80),
        color: 0xFFAB00,
        text_size: px(18),
        align_h: align.CENTER_H,
        align_v: align.CENTER_V,
        text: 'Data Divergence Conflict!\nSettings on watch and board differ.'
      });
      this.dynamicWidgets.push(conflictText);

      // Overwrite local
      const useBoardBtn = createWidget(widget.BUTTON, {
        x: px(60),
        y: px(220),
        w: px(360),
        h: px(50),
        text: 'Use Board Values',
        normal_color: 0x2962FF,
        press_color: 0x448AFF,
        radius: px(12),
        text_size: px(20),
        color: 0xFFFFFF,
        click_func: () => {
          resolveSyncConflict(this.loadedDeviceData.id, 'TakeDevice', this.loadedDeviceData, {
            onSuccess: () => {
              if (this.destroyed) return;
              const savedDevice = deviceStore.getKnownDevices().find(d => d.id === this.loadedDeviceData.id);
              this.navigateToConfig(savedDevice);
            },
            onFailure: (e) => {
              if (this.destroyed) return;
              console.log('Sync failed:', e);
              const savedDevice = deviceStore.getKnownDevices().find(d => d.id === this.loadedDeviceData.id);
              this.navigateToConfig(savedDevice);
            }
          });
        }
      });
      this.dynamicWidgets.push(useBoardBtn);

      // Overwrite board
      const useWatchBtn = createWidget(widget.BUTTON, {
        x: px(60),
        y: px(280),
        w: px(360),
        h: px(50),
        text: 'Keep My Values (Sync Watch)',
        normal_color: 0x00C853,
        press_color: 0x00E676,
        radius: px(12),
        text_size: px(20),
        color: 0xFFFFFF,
        click_func: () => {
          this.keepLocalValues();
        }
      });
      this.dynamicWidgets.push(useWatchBtn);

      const cancelBtn = createWidget(widget.BUTTON, {
        x: px(140),
        y: px(345),
        w: px(200),
        h: px(45),
        text: 'Cancel',
        normal_color: 0x333333,
        press_color: 0x555555,
        radius: px(20),
        text_size: px(20),
        color: 0xFFFFFF,
        click_func: () => {
          back();
        }
      });
      this.dynamicWidgets.push(cancelBtn);
    } 
    
    // "Keep My Values" write in progress. No buttons: a second tap must not start
    // a second write, and the conflict choice is already made.
    else if (this.syncState === 'Writing') {
      const writingText = createWidget(widget.TEXT, {
        x: px(60),
        y: px(160),
        w: px(360),
        h: px(60),
        color: 0xCCCCCC,
        text_size: px(22),
        align_h: align.CENTER_H,
        align_v: align.CENTER_V,
        text: 'Writing your values\nto the board...'
      });
      this.dynamicWidgets.push(writingText);
    }

    // "Keep My Values" write failed. The store keeps the watch values as unsaved
    // edits, so the user can retry now or continue and save later.
    else if (this.syncState === 'WriteFailed') {
      const linkUp = !!(this.loadedDeviceData && bleManager.isConnected(this.loadedDeviceData.id));
      const errText = createWidget(widget.TEXT, {
        x: px(60),
        y: px(110),
        w: px(360),
        h: px(115),
        color: 0xD32F2F,
        text_size: px(18),
        align_h: align.CENTER_H,
        align_v: align.CENTER_V,
        // The lockout text has two lines of its own, so a long line must wrap to fit.
        text_style: text_style.WRAP,
        text: `${this.writeError || 'The board did not accept the write.'}\nYour values are kept as unsaved changes.`
      });
      this.dynamicWidgets.push(errText);

      // A retry needs the link, and a board in lockout refuses it. Else only Continue shows.
      const stored = this.storedDevice();
      const lockedOut = !!(stored && stored.lockoutEnabled);
      if (linkUp && !lockedOut) {
        const retryBtn = createWidget(widget.BUTTON, {
          x: px(90),
          y: px(230),
          w: px(300),
          h: px(50),
          text: 'Retry',
          normal_color: 0x00C853,
          press_color: 0x00E676,
          radius: px(25),
          text_size: px(22),
          color: 0xFFFFFF,
          click_func: () => {
            this.writeLocalValues();
          }
        });
        this.dynamicWidgets.push(retryBtn);
      }

      const contBtn = createWidget(widget.BUTTON, {
        x: px(90),
        y: px(295),
        w: px(300),
        h: px(50),
        text: 'Continue',
        normal_color: 0x333333,
        press_color: 0x555555,
        radius: px(25),
        text_size: px(22),
        color: 0xFFFFFF,
        click_func: () => {
          this.navigateToConfig(this.storedDevice());
        }
      });
      this.dynamicWidgets.push(contBtn);
    }

    // Failed Flow
    else if (this.syncState === 'Failed') {
      const errText = createWidget(widget.TEXT, {
        x: px(60),
        y: px(160),
        w: px(360),
        h: px(80),
        color: 0xD32F2F,
        text_size: px(20),
        align_h: align.CENTER_H,
        align_v: align.CENTER_V,
        text: this.errorMessage || 'Unable to communicate\nwith the board.'
      });
      this.dynamicWidgets.push(errText);

      const backBtn = createWidget(widget.BUTTON, {
        x: px(115),
        y: px(300),
        w: px(250),
        h: px(55),
        text: 'Back to List',
        normal_color: 0xD32F2F,
        press_color: 0xC62828,
        radius: px(25),
        text_size: px(24),
        color: 0xFFFFFF,
        click_func: () => {
          back();
        }
      });
      this.dynamicWidgets.push(backBtn);
    }
  },

  _getStepText(label, status) {
    switch (status) {
      case StepStatus.PENDING: return `o  ${label}`;
      case StepStatus.IN_PROGRESS: return `>  ${label}`;
      case StepStatus.COMPLETED: return `v  ${label}`;
      case StepStatus.FAILED: return `x  ${label}`;
      default: return `o  ${label}`;
    }
  },

  _getStepColor(status) {
    switch (status) {
      case StepStatus.PENDING: return 0x777777;
      case StepStatus.IN_PROGRESS: return 0xFFFFFF;
      case StepStatus.COMPLETED: return 0x4CAF50;
      case StepStatus.FAILED: return 0xD32F2F;
      default: return 0x777777;
    }
  },

  startConnection() {
    bleManager.connect(
      this.connectingDevice,
      (progress) => {
        if (this.destroyed) return;
        this.progress = progress;
        this.drawDynamicUI();
      },
      (deviceData) => {
        if (this.destroyed) return;
        this.loadedDeviceData = deviceData;

        // Reconcile via DeviceStore
        const syncResult = deviceStore.onDeviceRead(deviceData);
        console.log("Reconciliation sync result:", syncResult.type);

        this.syncState = syncResult.type;
        this.drawDynamicUI();
      },
      (err) => {
        if (this.destroyed) return;
        this.syncState = 'Failed';
        this.errorMessage = err.message || 'Connection failed';
        this.drawDynamicUI();
      }
    );
  },

  // The name a new board gets: the advertised name and the last four characters
  // of the board UUID, so two boards do not get the same name.
  defaultDeviceName() {
    const base = (this.connectingDevice && this.connectingDevice.name) || 'Snapshot PB';
    const id = (this.loadedDeviceData && this.loadedDeviceData.id) || '';
    const shortId = id.replace(/-/g, '').slice(-4).toUpperCase();
    return shortId ? `${base} ${shortId}` : base;
  },

  storedDevice() {
    return deviceStore.getKnownDevices().find(d => d.id === this.loadedDeviceData.id);
  },

  // "Keep My Values": record the choice in the store, then write the watch values
  // to the board.
  keepLocalValues() {
    if (this.syncState !== 'Conflict') return;
    resolveSyncConflict(this.loadedDeviceData.id, 'TakeLocal', this.loadedDeviceData, {
      onSuccess: () => this.handleLocalWriteSuccess(),
      onFailure: (e) => this.handleLocalWriteFailure(e)
    });
    // resolveConflict can fail at once (device not in the store). Show "Writing"
    // only while the write actually runs.
    if (this.syncState === 'Conflict') {
      this.syncState = 'Writing';
      this.drawDynamicUI();
    }
  },

  // Writes the stored watch values to the board again, after a failed write.
  writeLocalValues() {
    if (this.syncState !== 'WriteFailed') return;
    const local = this.storedDevice();
    if (!local) {
      this.handleLocalWriteFailure(new Error('Device not found'));
      return;
    }
    this.syncState = 'Writing';
    this.drawDynamicUI();
    writeDeviceToBoard(local.id, local, {
      onSuccess: () => this.handleLocalWriteSuccess(),
      onFailure: (e) => this.handleLocalWriteFailure(e)
    });
  },

  handleLocalWriteSuccess() {
    if (this.destroyed) return;
    this.navigateToConfig(this.storedDevice());
  },

  handleLocalWriteFailure(e) {
    if (this.destroyed) return;
    console.log('Board write failed:', e);
    this.syncState = 'WriteFailed';
    if (e && e.lockout) {
      this.writeError = SAVE_BLOCKED_LOCKOUT_TEXT;
    } else if (e && e.linkLost) {
      this.writeError = 'The link to the board is lost.';
    } else {
      this.writeError = 'The board did not accept the write.';
    }
    this.drawDynamicUI();
  },

  navigateToConfig(device) {
    const app = getApp();
    app.globalData.selectedDevice = device;
    // Hand the live BLE link off to device_config; replace() destroys this page
    // so onDestroy must NOT disconnect (see onDestroy).
    this.handingOff = true;
    replace({
      url: 'page/device_config/index'
    });
  }
});
