# Cascade Driver Drowsiness Detection (Android Prototype)

An Android research prototype for driver drowsiness detection using on-device facial landmark tracking and an ONNX sleepy/awake classifier. Includes live camera monitoring and ZIP-based video dataset evaluation.

> **Safety notice:** Research/evaluation prototype only—not a validated medical or automotive safety device. Never interact with the app while driving.

## Features

- Live front-camera preview with facial landmarks and head-pose overlays
- Eye Aspect Ratio (EAR) and yaw/pitch/roll readings
- Live calibration and adjustable EAR/confidence thresholds
- ZIP video evaluation with selectable calibration frames
- Per-video results, accuracy, precision, recall, F1, confusion matrix
- Text report saved to app files directory

## Requirements

- Android Studio with SDK Platform 36.1, JDK 17+
- Android 7.0 (API 24)+ device/emulator with front camera

## Build & Run

Open `mobile-prototype` in Android Studio, sync Gradle, run the `app` configuration.