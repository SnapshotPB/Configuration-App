import { createWidget, widget, align, deleteWidget } from '@zos/ui';
import { back } from '@zos/router';
import { deviceStore } from '../../src/store/DeviceStore';
import {
  formatProfileFieldValue,
  maxBpsForTiming,
  profileFieldByKey,
  stepLabel,
  stepProfileField,
  stepSize
} from '../../src/utils/profileFields';
import { px, screenWidth, screenHeight } from '../../src/utils/px';

// Stepper for one numeric profile field. A Zepp button has no hold-to-repeat, so the page offers
// minus and plus at the fine step and at the coarse step as four buttons. Each tap commits to the
// store at once: a tap is a deliberate step, unlike the 25 ms repeat of the Wear OS stepper. The
// flash write of the store waits until the taps stop.
//
// profile_edit names the field in globalData.profileField before it pushes this page.
Page({
  onInit() {
    console.log("ProfileField page onInit");
    this.valueWidgets = [];
    const app = getApp();
    const handoff = app.globalData.profileField;
    const selected = app.globalData.selectedDevice;
    this.device = null;
    this.profile = null;
    this.fieldDef = null;
    if (selected) {
      this.device = deviceStore.getKnownDevices().find(d => d.id === selected.id) || selected;
    }
    if (this.device && handoff) {
      this.profile = (this.device.knownProfiles || []).find(p => p.id === handoff.profileId) || null;
      this.fieldDef = profileFieldByKey(handoff.key);
    }
  },

  build() {
    console.log("ProfileField page build");

    // Draw background
    createWidget(widget.FILL_RECT, {
      x: 0,
      y: 0,
      w: screenWidth(),
      h: screenHeight(),
      color: 0x000000
    });

    if (!this.profile || !this.fieldDef) {
      createWidget(widget.TEXT, {
        x: px(60),
        y: px(180),
        w: px(360),
        h: px(80),
        color: 0xFF0000,
        text_size: px(24),
        align_h: align.CENTER_H,
        align_v: align.CENTER_V,
        text: this.device ? 'Error: No field named' : 'Error: No device loaded'
      });
      return;
    }

    // Title: the field label. The labels are long, so the title is smaller than on other pages.
    createWidget(widget.TEXT, {
      x: px(40),
      y: px(40),
      w: px(400),
      h: px(40),
      color: 0xFFFFFF,
      text_size: px(22),
      align_h: align.CENTER_H,
      align_v: align.CENTER_V,
      text: this.fieldDef.label
    });

    this.drawValue();
    this.drawButtons();
  },

  // The value, the max BPS line and the unsaved line. These are the widgets a step changes, so
  // they are tracked and redrawn on their own; the buttons stay.
  drawValue() {
    this.valueWidgets.forEach(w => deleteWidget(w));
    this.valueWidgets = [];

    const def = this.fieldDef;
    const value = this.profile[def.field];
    const diverged = deviceStore.isProfileFieldDiverged(this.device.id, this.profile.id, def.field);

    const valueText = createWidget(widget.TEXT, {
      x: px(40),
      y: px(95),
      w: px(400),
      h: px(60),
      color: diverged ? 0xFFAB00 : 0xFFFFFF,
      text_size: px(44),
      align_h: align.CENTER_H,
      align_v: align.CENTER_V,
      text: formatProfileFieldValue(def, value)
    });
    this.valueWidgets.push(valueText);

    if (def.affectsMaxBps) {
      const bpsLine = createWidget(widget.TEXT, {
        x: px(60),
        y: px(160),
        w: px(360),
        h: px(26),
        color: 0x888888,
        text_size: px(18),
        align_h: align.CENTER_H,
        align_v: align.CENTER_V,
        text: `Max BPS: ${maxBpsForTiming(this.profile)}`
      });
      this.valueWidgets.push(bpsLine);
    }

    if (diverged) {
      const unsaved = createWidget(widget.TEXT, {
        x: px(60),
        y: px(188),
        w: px(360),
        h: px(26),
        color: 0xFFAB00,
        text_size: px(18),
        align_h: align.CENTER_H,
        align_v: align.CENTER_V,
        text: 'Unsaved'
      });
      this.valueWidgets.push(unsaved);
    }
  },

  // Four step buttons in one row at mid-height, where a round screen is widest, then Back.
  drawButtons() {
    const def = this.fieldDef;
    const fine = stepSize(false);
    const coarse = stepSize(true);
    const deltas = [-coarse, -fine, fine, coarse];
    const buttonW = px(82);
    const gap = px(10);
    let x = px(60);
    deltas.forEach(delta => {
      createWidget(widget.BUTTON, {
        x: x,
        y: px(235),
        w: buttonW,
        h: px(60),
        text: stepLabel(def, delta),
        normal_color: Math.abs(delta) === coarse ? 0x1A1A1A : 0x333333,
        press_color: 0x555555,
        radius: px(12),
        text_size: px(20),
        color: 0xFFFFFF,
        click_func: () => {
          this.step(delta);
        }
      });
      x += buttonW + gap;
    });

    createWidget(widget.BUTTON, {
      x: px(215),
      y: px(325),
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
  },

  // Applies one step of [delta] stored units and commits it. The board gets the value on the next
  // "Save to Board".
  step(delta) {
    const def = this.fieldDef;
    const next = stepProfileField(def, this.profile[def.field], delta);
    if (next === this.profile[def.field]) return;

    // The store changes at once, but the flash write waits for the taps to stop (see
    // DeviceStore._save). onDestroy writes what is still waiting.
    deviceStore.updateProfileField(this.device.id, this.profile.id, def.field, next, { defer: true });

    // Update global reference
    const app = getApp();
    const stored = deviceStore.getKnownDevices().find(d => d.id === this.device.id);
    if (stored) {
      app.globalData.selectedDevice = stored;
      this.device = stored;
      this.profile = (stored.knownProfiles || []).find(p => p.id === this.profile.id) || this.profile;
    }
    this.drawValue();
  },

  onDestroy() {
    console.log("ProfileField page onDestroy");
    deviceStore.flush();
    // The field is a handoff from profile_edit, so it must not outlive this page.
    const app = getApp();
    app.globalData.profileField = null;
  }
});
