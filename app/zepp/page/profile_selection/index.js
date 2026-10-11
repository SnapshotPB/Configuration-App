import { createWidget, widget, align } from '@zos/ui';
import { back } from '@zos/router';
import { deviceStore } from '../../src/store/DeviceStore';
import {
  formatProfileFieldValue,
  profileChoiceLabel,
  profileFieldByKey
} from '../../src/utils/profileFields';
import { px, screenWidth, screenHeight } from '../../src/utils/px';

Page({
  onInit() {
    console.log("ProfileSelection page onInit");
    const app = getApp();
    const selected = app.globalData.selectedDevice;
    if (selected) {
      this.device = deviceStore.getKnownDevices().find(d => d.id === selected.id) || selected;
    }
  },

  build() {
    console.log("ProfileSelection page build");
    
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
      text: 'Select Profile'
    });

    this.drawProfiles();
  },

  drawProfiles() {
    const profiles = this.device.knownProfiles || [];
    let currentY = px(95);

    profiles.forEach(profile => {
      const isActive = profile.id === this.device.activeProfileId;
      const subText = `Mode: ${profileChoiceLabel('fireMode', profile.fireMode) || '--'} / Dwell: ${this.dwellText(profile)}`;

      // Draw a capsule button for the profile card
      createWidget(widget.BUTTON, {
        x: px(60),
        y: currentY,
        w: px(360),
        h: px(60),
        text: `${profile.name}\n${subText}`,
        normal_color: isActive ? 0xE65100 : 0x1A1A1A, // Orange if active, dark grey otherwise
        press_color: isActive ? 0xEF6C00 : 0x333333,
        radius: px(12),
        text_size: px(18),
        color: 0xFFFFFF,
        click_func: () => {
          this.selectProfile(profile);
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
  },

  // The sear dwell for a profile card. A profile with no value shows "--", not "NaN ms".
  dwellText(profile) {
    const value = profile.searSolenoidDwell;
    if (value == null || !Number.isFinite(Number(value))) return '--';
    return formatProfileFieldValue(profileFieldByKey('SEAR_SOLENOID_DWELL'), value);
  },

  selectProfile(profile) {
    console.log("Selected active profile:", profile.name);
    
    // Save to Local Store
    deviceStore.updateDeviceActiveProfile(this.device.id, profile.id);
    
    // Update global reference
    const app = getApp();
    app.globalData.selectedDevice = deviceStore.getKnownDevices().find(d => d.id === this.device.id);

    // Go back to config
    back();
  }
});
