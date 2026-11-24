# 🚗 OpenLink — CarPlay & Android Auto Head-Unit Engine (CarLink Mod Edition)

[![Release](https://img.shields.io/badge/Release-v1.0.0--experimental-blue.svg)](https://github.com/)
[![Platform](https://img.shields.io/badge/Platform-Android-green.svg)](https://android.com)
[![License](https://img.shields.io/badge/License-MIT-orange.svg)](LICENSE)

An open-source, highly customizable receiver engine for **Apple CarPlay** and **Android Auto** designed for Android head-units and standalone screens. This modded edition unlocks full control over your vehicle identity, starting with a clean `OpenLink` branding by default, while allowing live, on-the-fly vehicle name adjustments directly from a hidden, interactive gesture menu.

---

## 🔥 Key Features & Mod Highlights

* **Clean `OpenLink` Starter Profile:** Starts seamlessly with no awkward spaces or broken identifiers out of the box.
* **Magic 3-Finger Gesture Menu:** Swipe anywhere on the screen with **three fingers** to reveal the hidden developer/settings overlay effortlessly without cluttering the main driving UI.
* **Dynamic Vehicle Identity (Live Rename):** Edit `Manufacturer`, `Model`, or `OEM label` on the fly. Want it to show as `Nissan`, `NissanConnect`, `Tesla`, or a custom name? Just type and save!
* **Zero-Restart Live Sync (`Save and Reconnect`):** Modified vehicle settings sync instantly with `AirPlayPersistence` and `CarPlayHostIntegration.accessoryName`. Tapping **Save and reconnect** updates the broadcast payload without requiring a full app or hardware restart.
* **Built-in Safety Filter:** Includes word validation to ensure identity parameters remain safe and stable during system broadcasts.
* **Optimized Protocol Handshake:** Works seamlessly with both Apple CarPlay's Bluetooth SDP / Bonjour mDNS discovery and Android Auto's OEM vehicle manifest checks.

---

## 🖐️ How to Open the Mod Menu

1. Connect your Android head-unit or launch the app.
2. Swipe **down or across the screen using 3 fingers simultaneously**.
3. The hidden **CarPlay menu** overlay will slide out.
4. Navigate to the **Vehicle / Device Identity** tab to customize your system parameters.

---

## 📱 How to Apply & Update Display Name on iPhone (Apple CarPlay)

Because iOS caches Bluetooth SDP profile identifiers and accessory names, follow these quick steps when changing your vehicle name for the first time:

1. Open the 3-finger swipe menu inside the app.
2. Type your preferred vehicle name (e.g., `Nissan` or `OpenLink`) in the **Manufacturer / Model** fields.
3. Tap **Save and reconnect**.
4. On your iPhone, go to **Settings** > **General** > **CarPlay**.
5. Select your previous vehicle entry and tap **Forget This Car**.
6. Toggle **Bluetooth OFF and ON** on your iPhone.
7. Reconnect to the head-unit. Your iPhone will now recognize and save your customized vehicle identity!

---

## 🤖 Android Auto Brand Behavior Note

* **Android Auto:** Resolves identities heavily through OEM Vehicle Manifest checks to deliver a factory-like vehicle interface.
* **Apple CarPlay:** Dynamically reflects Bluetooth SDP and Bonjour mDNS `Accessory Name` payloads synced directly from this app's persistent storage.

---

## 📦 Installation & Release Package

This project is currently released as a **Community Preview / Experimental Build**.

* Download the compiled `.zip` package from the [GitHub Releases](https://github.com/) section.
* Extract the `.zip` file on your computer or Android head-unit.
* Install the `.apk` file and grant the required Bluetooth and Location permissions upon first launch.

---

## 🤝 Community & Contributions

Feel free to fork this repository, submit Pull Requests, or share your feedback and feature requests in the Issues tab. 

*Created for the community by Yuuma — Enjoy your truly custom CarPlay & Android Auto experience!*
