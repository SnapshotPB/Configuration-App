import { createWidget, widget, align } from '@zos/ui';
import { back } from '@zos/router';
import { deviceStore } from '../../src/store/DeviceStore';
import { BOARD_SETTINGS, boardSettingLabel } from '../../src/utils/boardSettings';
import { PROFILE_CHOICES, profileChoiceLabel } from '../../src/utils/profileFields';
import { px, screenWidth, screenHeight } from '../../src/utils/px';

// Choice of one value from a short list with one active value: a board setting (the board
// language, the screen brightness, or the screen-off delay), or a list-valued profile field (the
// fire mode or the eye mode). They share a page rather than repeat it five times. The page that
// pushes this one names the choice in globalData.selection:
//
//   { scope: 'device', key: 'language' | 'screenBrightness' | 'screenOffDelay' }
//   { scope: 'profile', key: 'fireMode' | 'eyeMode', profileId }
//
// The choice stays on the watch until the user saves to the board. The board can refuse a board
// setting it does not know, so the stored value tracks what the board reports after the write.
Page({
  onInit() {
    console.log("BoardSettingSelection page onInit");
    const app = getApp();
    const selected = app.globalData.selectedDevice;
    if (selected) {
      this.device = deviceStore.getKnownDevices().find(d => d.id === selected.id) || selected;
    }
    this.target = this.resolveTarget(app.globalData.selection);
  },

  // The list to offer, the value it holds now, and the store call that commits a choice, for the
  // handoff in [selection]. Null when the handoff names nothing the page can offer.
  resolveTarget(selection) {
    if (!this.device || !selection) return null;
    if (selection.scope === 'device') {
      const setting = BOARD_SETTINGS[selection.key];
      if (!setting) return null;
      return {
        setting,
        current: this.device[setting.field],
        label: (value) => boardSettingLabel(selection.key, value),
        commit: (value) => deviceStore.updateBoardSetting(this.device.id, setting.field, value)
      };
    }
    if (selection.scope === 'profile') {
      const setting = PROFILE_CHOICES[selection.key];
      const profile = (this.device.knownProfiles || []).find(p => p.id === selection.profileId);
      if (!setting || !profile) return null;
      return {
        setting,
        current: profile[setting.field],
        label: (value) => profileChoiceLabel(selection.key, value),
        commit: (value) => deviceStore.updateProfileField(
          this.device.id, profile.id, setting.field, value
        )
      };
    }
    return null;
  },

  build() {
    console.log("BoardSettingSelection page build");

    // Draw background
    createWidget(widget.FILL_RECT, {
      x: 0,
      y: 0,
      w: screenWidth(),
      h: screenHeight(),
      color: 0x000000
    });

    if (!this.device || !this.target) {
      createWidget(widget.TEXT, {
        x: px(60),
        y: px(180),
        w: px(360),
        h: px(80),
        color: 0xFF0000,
        text_size: px(24),
        align_h: align.CENTER_H,
        align_v: align.CENTER_V,
        text: this.device ? 'Error: No setting named' : 'Error: No device loaded'
      });
      return;
    }

    // Title
    createWidget(widget.TEXT, {
      x: px(60),
      y: px(40),
      w: px(360),
      h: px(40),
      color: 0xFFFFFF,
      text_size: px(28),
      align_h: align.CENTER_H,
      align_v: align.CENTER_V,
      text: this.target.setting.title
    });

    this.drawOptions();
  },

  drawOptions() {
    let currentY = px(95);
    const setting = this.target.setting;
    const current = this.target.current;
    const options = setting.optionsFor ? setting.optionsFor(current) : setting.options;

    options.forEach(option => {
      const isActive = option === current;

      createWidget(widget.BUTTON, {
        x: px(60),
        y: currentY,
        w: px(360),
        h: px(60),
        text: this.target.label(option),
        normal_color: isActive ? 0xE65100 : 0x1A1A1A, // Orange if active, dark grey otherwise
        press_color: isActive ? 0xEF6C00 : 0x333333,
        radius: px(12),
        text_size: px(22),
        color: 0xFFFFFF,
        click_func: () => {
          this.selectOption(option);
        }
      });
      currentY += px(68);
    });

    // Back button at the bottom
    createWidget(widget.BUTTON, {
      x: px(215),
      y: currentY + px(10),
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

    // A round screen is narrow at the bottom. When the page scrolls, add empty space below Back so
    // that it can scroll up to where the screen is wide enough to tap it.
    const bottom = currentY + px(60);
    if (bottom > screenHeight()) {
      createWidget(widget.FILL_RECT, {
        x: 0,
        y: bottom,
        w: screenWidth(),
        h: px(60),
        color: 0x000000
      });
    }
  },

  onDestroy() {
    // The selection is a handoff from the page below, so it must not outlive this page.
    const app = getApp();
    app.globalData.selection = null;
  },

  selectOption(option) {
    console.log("Selected", this.target.setting.field, option);
    if (option === this.target.current) {
      back();
      return;
    }

    // Save to the local store. The board gets the value on the next "Save to Board".
    this.target.commit(option);

    // Update global reference
    const app = getApp();
    const stored = deviceStore.getKnownDevices().find(d => d.id === this.device.id);
    app.globalData.selectedDevice = stored || app.globalData.selectedDevice;

    // Go back to the page below
    back();
  }
});
