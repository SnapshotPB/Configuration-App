import { createWidget, widget, align, deleteWidget } from '@zos/ui';
import { back, push } from '@zos/router';
import { deviceStore } from '../../src/store/DeviceStore';
import { bleManager } from '../../src/bluetooth/BleManager';
import { writeDeviceToBoard } from '../../src/store/syncCoordinator';
import {
  formatProfileFieldValue,
  maxBpsForTiming,
  profileChoiceLabel,
  visibleProfileFields
} from '../../src/utils/profileFields';
import { SAVE_COLOR_FAIL, saveOutcome } from '../../src/utils/saveOutcome';
import { drawLockoutLine } from '../../src/utils/lockoutLine';
import { Toast } from '../../src/utils/toast';
import { px, screenWidth, screenHeight } from '../../src/utils/px';

// The mark a row carries when its field differs from the board baseline.
const UNSAVED_MARK = ' *';

// Editor for the active profile of the selected device. The watch edits the active profile only:
// to tune another profile, the user makes it active first. Each row opens the page for one field:
// the fire mode and the eye mode open board_setting_selection, and a number opens profile_field.
//
// A local edit goes to deviceStore directly. Only the board write goes through syncCoordinator.
Page({
  onInit() {
    console.log("ProfileEdit page onInit");
    this.destroyed = false;
    this.dynamicWidgets = [];
    this.toast = new Toast();
    // True while a board write runs. The Save row hides, so a second tap cannot start a second
    // OTS transfer on the same link.
    this.saving = false;
    this.loadDevice();
  },

  // Reloads the device and its active profile from the store, so a change made on a page above
  // shows after back().
  loadDevice() {
    const app = getApp();
    const selected = app.globalData.selectedDevice;
    this.device = null;
    this.profile = null;
    if (selected) {
      this.device = deviceStore.getKnownDevices().find(d => d.id === selected.id) || selected;
      this.profile = (this.device.knownProfiles || [])
        .find(p => p.id === this.device.activeProfileId) || null;
    }
    this.isConnected = !!(this.device && bleManager.isConnected(this.device.id));
  },

  onResume() {
    console.log("ProfileEdit page onResume");
    this.loadDevice();
    if (this.profile) this.buildDynamicUI();
  },

  build() {
    console.log("ProfileEdit page build");

    // Draw background
    createWidget(widget.FILL_RECT, {
      x: 0,
      y: 0,
      w: screenWidth(),
      h: screenHeight(),
      color: 0x000000
    });

    if (!this.profile) {
      createWidget(widget.TEXT, {
        x: px(60),
        y: px(180),
        w: px(360),
        h: px(80),
        color: 0xFF0000,
        text_size: px(24),
        align_h: align.CENTER_H,
        align_v: align.CENTER_V,
        text: this.device ? 'Error: No active profile' : 'Error: No device loaded'
      });
      return;
    }

    // Header Title: the profile name. The store holds the name, so the header is static.
    createWidget(widget.TEXT, {
      x: px(60),
      y: px(40),
      w: px(360),
      h: px(40),
      color: 0xFFFFFF,
      text_size: px(28),
      align_h: align.CENTER_H,
      align_v: align.CENTER_V,
      text: this.profile.name
    });

    // Draw the body here too. onResume draws it again, but a runtime that does
    // not call onResume on the first display would otherwise show only the title.
    this.buildDynamicUI();
  },

  buildDynamicUI() {
    this.toast.clear();
    this.dynamicWidgets.forEach(w => deleteWidget(w));
    this.dynamicWidgets = [];

    const deviceId = this.device.id;
    const profile = this.profile;
    const boltMode = this.device.boltMode || 'CLOSED';
    const profileDiverged = deviceStore.isProfileDiverged(deviceId, profile.id);

    let currentY = px(85);

    // 1. The max BPS the timing allows, so the user sees the effect of a change.
    const bpsLine = createWidget(widget.TEXT, {
      x: px(60),
      y: currentY,
      w: px(360),
      h: px(26),
      color: 0x888888,
      text_size: px(18),
      align_h: align.CENTER_H,
      align_v: align.CENTER_V,
      text: `Max BPS: ${maxBpsForTiming(profile, boltMode)}`
    });
    this.dynamicWidgets.push(bpsLine);
    currentY += px(28);

    // 2. Unsaved line
    if (profileDiverged) {
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
      currentY += px(28);
    }
    currentY += px(6);

    // 3. The two list-valued fields open the selection page.
    currentY = this.drawChoiceRow(currentY, 'fireMode', 'Fire Mode');
    currentY = this.drawChoiceRow(currentY, 'eyeMode', 'Eye Mode');

    // 4. One row per numeric field the fire mode, bolt mode and eye mode show.
    visibleProfileFields(profile, boltMode).forEach(def => {
      currentY = this.drawFieldRow(currentY, def);
    });
    currentY += px(10);

    // 5. Save to Board, when the link is live, a save would change the board, and no write runs.
    // A board in lockout does not accept a change, so a line that tells the user why takes the
    // place of the button.
    const canSave = this.isConnected && !this.saving && deviceStore.isDiverged(deviceId);
    if (canSave && this.device.lockoutEnabled) {
      currentY = drawLockoutLine(this.dynamicWidgets, currentY);
    } else if (canSave) {
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

    // 6. Discard, for this profile only. It cannot undo an unsaved board setting choice.
    if (profileDiverged) {
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

  // One row for a list-valued field. Returns the next y.
  drawChoiceRow(y, key, label) {
    const value = profileChoiceLabel(key, this.profile[key]);
    const mark = deviceStore.isProfileFieldDiverged(this.device.id, this.profile.id, key)
      ? UNSAVED_MARK
      : '';
    const row = createWidget(widget.BUTTON, {
      x: px(60),
      y: y,
      w: px(360),
      h: px(60),
      text: `${label}${mark}\n${value}`,
      normal_color: 0x1A1A1A,
      press_color: 0x333333,
      radius: px(12),
      text_size: px(18),
      color: 0xFFFFFF,
      click_func: () => {
        const app = getApp();
        app.globalData.selection = { scope: 'profile', key, profileId: this.profile.id };
        push({
          url: 'page/board_setting_selection/index'
        });
      }
    });
    this.dynamicWidgets.push(row);
    return y + px(68);
  },

  // One row for a numeric field. Returns the next y.
  drawFieldRow(y, def) {
    const value = formatProfileFieldValue(def, this.profile[def.field]);
    const mark = deviceStore.isProfileFieldDiverged(this.device.id, this.profile.id, def.field)
      ? UNSAVED_MARK
      : '';
    const row = createWidget(widget.BUTTON, {
      x: px(60),
      y: y,
      w: px(360),
      h: px(60),
      text: `${def.label}${mark}\n${value}`,
      normal_color: 0x1A1A1A,
      press_color: 0x333333,
      radius: px(12),
      text_size: px(18),
      color: 0xFFFFFF,
      click_func: () => {
        const app = getApp();
        app.globalData.profileField = { profileId: this.profile.id, key: def.key };
        push({
          url: 'page/profile_field/index'
        });
      }
    });
    this.dynamicWidgets.push(row);
    return y + px(68);
  },

  discardChanges() {
    console.log("Discarding profile changes:", this.profile.id);
    deviceStore.discardProfileChanges(this.device.id, this.profile.id);
    this.refreshFromStore();
    this.buildDynamicUI();
  },

  // Re-reads the device from the store and updates the global reference, so the page below sees
  // the change too.
  refreshFromStore() {
    const app = getApp();
    const stored = deviceStore.getKnownDevices().find(d => d.id === this.device.id);
    if (stored) {
      app.globalData.selectedDevice = stored;
      this.device = stored;
      this.profile = (stored.knownProfiles || []).find(p => p.id === this.profile.id) || this.profile;
    }
  },

  saveToBoard() {
    console.log("Saving profile to board...");
    if (this.saving || this.device.lockoutEnabled) return;
    const requested = this.device;
    this.saving = true;
    this.buildDynamicUI();
    writeDeviceToBoard(requested.id, requested, {
      onSuccess: (written) => {
        if (this.destroyed) return;
        console.log("Save successful!");
        this.saving = false;
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
        // link state again before the redraw.
        this.isConnected = !!bleManager.isConnected(this.device.id);
        this.buildDynamicUI();
        this.toast.show(err && err.linkLost ? 'Link Lost!' : 'Save Failed!', SAVE_COLOR_FAIL);
      }
    });
  },

  onDestroy() {
    console.log("ProfileEdit page onDestroy");
    this.destroyed = true;
    this.toast.clear();
    // device_config owns the BLE link, so this page does not release it.
  }
});
