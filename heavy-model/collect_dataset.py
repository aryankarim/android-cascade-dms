#!/usr/bin/env python3

"""
Dataset Collection Script for Drowsiness Detection
Collects 50 awake and 50 sleepy/drowsy images using webcam.
Press 'a' for awake (eyes open), 's' for sleepy (eyes closed/drowsy)
Images are saved as awake_1.jpg, awake_2.jpg, sleepy_1.jpg, sleepy_2.jpg, etc.
"""

import os

import cv2

AWAKE_TARGET = 200
SLEEPY_TARGET = 200
SAVE_DIR = "./drowsy_dataset"

# Create directories
awake_dir = os.path.join(SAVE_DIR, "awake")
sleepy_dir = os.path.join(SAVE_DIR, "sleepy")
os.makedirs(awake_dir, exist_ok=True)
os.makedirs(sleepy_dir, exist_ok=True)

cap = cv2.VideoCapture(0)
if not cap.isOpened():
    print("Error: Could not open webcam.")
    exit(1)

# Get frame dimensions
width = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
height = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
print(f"Camera resolution: {width}x{height}")


# Get current counts from existing files
def get_next_number(directory, prefix):
    """Get the next sequential number for a new image"""
    existing = [
        f for f in os.listdir(directory) if f.startswith(prefix) and f.endswith(".jpg")
    ]
    if not existing:
        return 1
    numbers = []
    for f in existing:
        try:
            num = int(f.replace(prefix, "").replace(".jpg", ""))
            numbers.append(num)
        except:
            continue
    return max(numbers) + 1 if numbers else 1


awake_count = len([f for f in os.listdir(awake_dir) if f.endswith(".jpg")])
sleepy_count = len([f for f in os.listdir(sleepy_dir) if f.endswith(".jpg")])
next_awake_num = get_next_number(awake_dir, "awake_")
next_sleepy_num = get_next_number(sleepy_dir, "sleepy_")

print("\n" + "=" * 50)
print(" DATASET COLLECTION FOR DROWSINESS DETECTION")
print("=" * 50)
print(f"Target: {AWAKE_TARGET} awake, {SLEEPY_TARGET} sleepy images")
print(f"Current: {awake_count} awake, {sleepy_count} sleepy images")
print("\nControls:")
print("  'a' - Capture AWAKE (eyes open, alert)")
print("  's' - Capture SLEEPY (eyes closed, drowsy)")
print("  'q' - Quit an  d save progress")
print("=" * 50 + "\n")


def draw_progress_bar(frame, current, target, label, y_pos, color):
    bar_width = 200
    bar_height = 20
    filled = int((current / target) * bar_width) if target > 0 else 0
    cv2.rectangle(
        frame, (10, y_pos), (10 + bar_width, y_pos + bar_height), (50, 50, 50), -1
    )
    cv2.rectangle(frame, (10, y_pos), (10 + filled, y_pos + bar_height), color, -1)
    cv2.putText(
        frame,
        f"{label}: {current}/{target}",
        (10 + bar_width + 10, y_pos + 15),
        cv2.FONT_HERSHEY_SIMPLEX,
        0.6,
        color,
        2,
    )
    return frame


while True:
    ret, frame = cap.read()
    if not ret:
        print("Failed to grab frame")
        break

    display_frame = frame.copy()

    # Draw status
    cv2.putText(
        display_frame,
        "DROWSINESS DATASET COLLECTION",
        (10, 30),
        cv2.FONT_HERSHEY_SIMPLEX,
        0.7,
        (255, 255, 255),
        2,
    )
    cv2.putText(
        display_frame,
        "Press 'a' (awake) | 's' (sleepy) | 'q' (quit)",
        (10, 60),
        cv2.FONT_HERSHEY_SIMPLEX,
        0.6,
        (200, 200, 200),
        1,
    )

    # Draw progress bars
    display_frame = draw_progress_bar(
        display_frame, awake_count, AWAKE_TARGET, "AWAKE", 90, (0, 255, 0)
    )
    display_frame = draw_progress_bar(
        display_frame, sleepy_count, SLEEPY_TARGET, "SLEEPY", 120, (0, 0, 255)
    )

    # Show next numbers
    cv2.putText(
        display_frame,
        f"Next awake: awake_{next_awake_num}.jpg",
        (10, 160),
        cv2.FONT_HERSHEY_SIMPLEX,
        0.5,
        (0, 255, 0),
        1,
    )
    cv2.putText(
        display_frame,
        f"Next sleepy: sleepy_{next_sleepy_num}.jpg",
        (10, 180),
        cv2.FONT_HERSHEY_SIMPLEX,
        0.5,
        (0, 0, 255),
        1,
    )

    # Show capture hints
    if awake_count < AWAKE_TARGET:
        cv2.putText(
            display_frame,
            "Need more AWAKE photos - look alert!",
            (10, height - 30),
            cv2.FONT_HERSHEY_SIMPLEX,
            0.5,
            (0, 255, 0),
            1,
        )
    if sleepy_count < SLEEPY_TARGET:
        cv2.putText(
            display_frame,
            "Need more SLEEPY photos - close eyes!",
            (10, height - 10),
            cv2.FONT_HERSHEY_SIMPLEX,
            0.5,
            (0, 0, 255),
            1,
        )

    # Show completion message
    if awake_count >= AWAKE_TARGET and sleepy_count >= SLEEPY_TARGET:
        cv2.putText(
            display_frame,
            "COMPLETE! Press 'q' to finish",
            (width // 2 - 150, height // 2),
            cv2.FONT_HERSHEY_SIMPLEX,
            0.8,
            (0, 255, 0),
            2,
        )

    cv2.imshow("Drowsiness Dataset Collection", display_frame)

    # Handle key presses
    key = cv2.waitKey(1) & 0xFF

    # Capture awake (eyes open)
    if key == ord("a") and awake_count < AWAKE_TARGET:
        filename = f"awake_{next_awake_num}.jpg"
        filepath = os.path.join(awake_dir, filename)
        cv2.imwrite(filepath, frame)
        awake_count += 1
        next_awake_num += 1
        print(f"AWAKE #{awake_count}: {filename}")
        # Visual feedback
        temp_frame = display_frame.copy()
        cv2.putText(
            temp_frame,
            "AWAKE CAPTURED!",
            (width // 2 - 100, height // 2),
            cv2.FONT_HERSHEY_SIMPLEX,
            1.0,
            (0, 255, 0),
            3,
        )
        cv2.imshow("Drowsiness Dataset Collection", temp_frame)
        cv2.waitKey(500)

    # Capture sleepy (eyes closed)
    elif key == ord("s") and sleepy_count < SLEEPY_TARGET:
        filename = f"sleepy_{next_sleepy_num}.jpg"
        filepath = os.path.join(sleepy_dir, filename)
        cv2.imwrite(filepath, frame)
        sleepy_count += 1
        next_sleepy_num += 1
        print(f"SLEEPY #{sleepy_count}: {filename}")
        # Visual feedback
        temp_frame = display_frame.copy()
        cv2.putText(
            temp_frame,
            "SLEEPY CAPTURED!",
            (width // 2 - 100, height // 2),
            cv2.FONT_HERSHEY_SIMPLEX,
            1.0,
            (0, 0, 255),
            3,
        )
        cv2.imshow("Drowsiness Dataset Collection", temp_frame)
        cv2.waitKey(500)

    # Quit
    elif key == ord("q"):
        print(f"\nExiting. Saved {awake_count} awake, {sleepy_count} sleepy images.")
        break

    # Show completion message and auto-quit if both targets reached
    if awake_count >= AWAKE_TARGET and sleepy_count >= SLEEPY_TARGET:
        print("\nTARGETS REACHED! Dataset collection complete.")
        print(
            f"Saved {awake_count} awake and {sleepy_count} sleepy images to '{SAVE_DIR}'"
        )
        break

cap.release()
cv2.destroyAllWindows()
print("\n" + "=" * 50)
print(f"   Dataset saved in '{SAVE_DIR}/'")
print(f"   Awake images: {awake_count} in '{SAVE_DIR}/awake/'")
print(f"   Sleepy images: {sleepy_count} in '{SAVE_DIR}/sleepy/'")
print("=" * 50)
