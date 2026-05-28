# Blackout Curtain Opener

Motor controller for blackout curtains, built on an ESP8266 with an Android app as the remote.

## Hardware

- **MCU**: NodeMCU v2 (ESP8266)
- **Motor**: Creality 42-40 stepper (1.8°/step, 200 steps/rev)
- **Driver**: A4988
- **Wiring**:
  | Signal | NodeMCU pin |
  |--------|-------------|
  | STEP   | D1 (GPIO 5) |
  | DIR    | D2 (GPIO 4) |
  | ENABLE | D5 (GPIO 14) |

## ESP Firmware

PlatformIO project targeting `nodemcuv2`. Dependencies: AccelStepper, ArduinoJson, ESP8266WebServer, ESP8266WiFi, ESP8266mDNS.

On boot the motor runs one revolution forward and back as a self-test, then connects to WiFi and starts an HTTP server on port 80.

### Endpoints

| Method | Path | Description |
|--------|------|-------------|
| GET | `/status` | Current position, state, sunrise/sunset strings |
| GET | `/state` | Internal state (lat, lon, sunrise/sunset in minutes) |
| GET | `/set_start` | Mark current position as 0% (open) |
| GET | `/set_end` | Mark current position as 100% (closed) |
| GET | `/motor_goto?target=N` | Move to N% (0–100) |
| GET | `/motor_manual_step?steps=N` | Step N steps (negative = reverse) |
| GET | `/set_sunrise_sunset?sunrise=HH:MM&sunset=HH:MM` | Push open/close times |

### Automation

Every 5 seconds the firmware checks the current time against the configured sunrise/sunset. At sunrise the curtains open (0%), at sunset they close (100%). The check is skipped until NTP has synced. Updating the times resets the daily flags so the new schedule takes effect immediately.

Timezone is set via the POSIX string `EET-2EEST,M3.5.0/3,M10.5.0/4` (Romania, handles DST automatically).

## Android App

Requires Android with local WiFi access to the ESP. The ESP's IP address is configured in the Settings screen and saved across restarts.

**Main screen**
- Position slider (0% = open, 100% = closed) — updates on every status refresh
- Current motor position shown in both percentage and raw steps
- Manual step buttons with configurable step count for calibration
- Set Open / Set Closed buttons to record the physical endpoints
- Status refreshes every 3 seconds automatically

**Settings screen**
- ESP IP address field
- Schedule toggle: *Sunrise/Sunset* (fetched from api.sunrise-sunset.org using the phone's timezone) or *Manual* (user-picked open/close times)
- Save & Sync pushes whichever times are active to the ESP

## Calibration

1. Flash the firmware and open the Android app.
2. Use the step buttons to move the motor to the fully open position.
3. Tap **Set Open**.
4. Move to the fully closed position.
5. Tap **Set Closed**.

The slider and automation use these two positions as the 0% and 100% endpoints.
