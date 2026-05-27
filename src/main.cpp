#include "WString.h"
#include <AccelStepper.h>
#include <Arduino.h>
#include <ArduinoJson.h>
#include <ESP8266WebServer.h>
#include <ESP8266WiFi.h>
#include <ESP8266mDNS.h>
#include <WiFiClient.h>
#include <cstdio>
#include <time.h>

#ifndef STASSID
#define STASSID "Pixel_7030"
#define STAPSK "parola-puternica"
#endif

const char *ssid = STASSID;
const char *password = STAPSK;

const int STEP_PIN = 5;    // D1 on NodeMCU
const int DIR_PIN = 4;     // D2 on NodeMCU
const int ENABLE_PIN = 14; // D5 on NodeMCU - LOW = enabled
// Creality 42-40: 1.8 deg/step = 200 full steps/rev
// Multiply by driver microstepping: x1=200, x2=400, x8=1600, x16=3200
const int MICROSTEPS = 16; // set to match MS1/MS2/MS3 jumpers on your driver
const int stepsPerRevolution = 200 * MICROSTEPS;

ESP8266WebServer server(80);
AccelStepper myMotor(1, STEP_PIN, DIR_PIN);
const int led = 13;

// timekeeping — POSIX TZ string handles DST automatically
const char *ntpServer = "pool.ntp.org";
const char *tz = "EET-2EEST,M3.5.0/3,M10.5.0/4";

class CurtainStatus {
public:
  // position_start/end are measured in steps, calibrated by user input
  // they capture the motor position at start and end, mapped to 0% and 100%
  int position_start;
  int position_end;
  String state;
  String sunrise;
  String sunset;

  CurtainStatus(int pos, String st, String sr, String ss)
      : position_start(0), position_end(0), state(st), sunrise(sr), sunset(ss) {
  }

  String toJSON() {
    JsonDocument jd;
    jd["position_start"] = this->position_start;
    jd["position_end"] = this->position_end;
    jd["state"] = this->state;
    jd["sunrise"] = this->sunrise;
    jd["sunset"] = this->sunset;
    return jd.as<String>();
  }
};

enum ActionTimeSource { API, USER_SET };

class State {
public:
  CurtainStatus curtain_status;
  ActionTimeSource action_time_source;
  // sunrise and sunset times expressed in minutes (1440 minutes in a day)
  int sunrise_min, sunset_min;
  double lat = 42.439663, lon = 26.096306;

  State(int pos, String st, String sr, String ss, ActionTimeSource src)
      : curtain_status(pos, st, sr, ss), action_time_source(src) {
    this->sunrise_min = 0;
    this->sunset_min = 0;
  }

  String toJson() {
    JsonDocument jd;
    jd["lat"] = this->lat;
    jd["lon"] = this->lon;
    jd["sunrise_min"] = this->sunrise_min;
    jd["sunset_min"] = this->sunset_min;
    return jd.as<String>();
  }

  void fromJson(String json) {
    JsonDocument jd;
    deserializeJson(jd, json);
    this->lat = jd["lat"];
    this->lon = jd["lon"];
    this->sunrise_min = jd["sunrise_min"];
    this->sunset_min = jd["sunset_min"];
  }

  // parses time in "HH:MM" 24-hour format and returns it in minutes (0-1439)
  int parseTime(String time) {
    int colon = time.indexOf(':');
    int hour = time.substring(0, colon).toInt();
    int minute = time.substring(colon + 1, colon + 3).toInt();
    return hour * 60 + minute;
  }
};

void handleNotFound() {
  digitalWrite(led, 1);
  String message = "File Not Found\n\n";
  message += "URI: ";
  message += server.uri();
  message += "\nMethod: ";
  message += (server.method() == HTTP_GET) ? "GET" : "POST";
  message += "\nArguments: ";
  message += server.args();
  message += "\n";
  for (uint8_t i = 0; i < server.args(); i++) {
    message += " " + server.argName(i) + ": " + server.arg(i) + "\n";
  }
  server.send(404, "text/plain", message);
  digitalWrite(led, 0);
}

State *state;

// --- Auto-open/close state ---
bool opened_today = false;
bool closed_today = false;
int last_day = -1;
unsigned long lastAutoCheck = 0;

void goToPosition(int target) {
  digitalWrite(ENABLE_PIN, LOW);
  delay(100);
  myMotor.moveTo(target);
  while (myMotor.distanceToGo() != 0) {
    myMotor.run();
    server.handleClient();
    MDNS.update();
    yield();
  }
  digitalWrite(ENABLE_PIN, HIGH);
}

void checkAutoAction() {
  if (state->sunrise_min == 0 && state->sunset_min == 0)
    return;

  time_t t = time(nullptr);
  if (t < 1577836800UL)
    return; // NTP not yet synced

  struct tm *ti = localtime(&t);
  int current_min = ti->tm_hour * 60 + ti->tm_min;
  int today = ti->tm_yday;
  Serial.printf("Auto-check: current time %02d:%02d (min %d), sunrise at %d, "
                "sunset at %d\n",
                ti->tm_hour, ti->tm_min, current_min, state->sunrise_min,
                state->sunset_min);
  if (today != last_day) {
    opened_today = false;
    closed_today = false;
    last_day = today;
  }

  // 0% = open (position_start), 100% = closed (position_end)
  if (!opened_today && current_min >= state->sunrise_min) {
    opened_today = true;
    if (myMotor.currentPosition() != state->curtain_status.position_start) {
      Serial.println("Auto: opening curtains (sunrise)");
      state->curtain_status.state = "auto-opening";
      goToPosition(state->curtain_status.position_start);
      state->curtain_status.state = "auto-opened";
    }
  }

  if (!closed_today && current_min >= state->sunset_min) {
    closed_today = true;
    if (myMotor.currentPosition() != state->curtain_status.position_end) {
      Serial.println("Auto: closing curtains (sunset)");
      state->curtain_status.state = "auto-closing";
      goToPosition(state->curtain_status.position_end);
      state->curtain_status.state = "auto-closed";
    }
  }
}

void setup(void) {
  Serial.begin(115200);

  // Stepper motor init
  pinMode(ENABLE_PIN, OUTPUT);
  // Creality 42-40 @ 12V: safe up to ~2000 full steps/s; scale by microstepping
  myMotor.setMaxSpeed(200);     // 2 rev/s
  myMotor.setAcceleration(100); // 1 rev/s^2
  // Startup test: rotate 360 forward then back
  digitalWrite(ENABLE_PIN, LOW); // enable driver
  myMotor.moveTo(200);
  while (myMotor.distanceToGo() != 0) {
    myMotor.run();
    yield();
  }
  myMotor.moveTo(0);
  while (myMotor.distanceToGo() != 0) {
    myMotor.run();
    yield();
  }
  Serial.println("Startup motion test complete");
  digitalWrite(ENABLE_PIN, HIGH); // disable driver

  // WebServer init
  pinMode(led, OUTPUT);
  digitalWrite(led, 0);
  WiFi.mode(WIFI_STA);
  WiFi.begin(ssid, password);
  Serial.println("");
  while (WiFi.status() != WL_CONNECTED) {
    delay(500);
    Serial.print(".");
  }
  Serial.println("");
  Serial.print("Connected to ");
  Serial.println(ssid);
  Serial.print("IP address: ");
  Serial.println(WiFi.localIP());

  if (MDNS.begin("curtain_controller")) {
    MDNS.addService("http", "tcp", 80);
    Serial.println("MDNS responder started");
  }

  configTime(tz, ntpServer);

  state = new State(0, "idle", "", "", API);
  Serial.println("State initialized:");
  Serial.println(state->toJson());
  Serial.println("Current curtain status:");
  Serial.println(state->curtain_status.toJSON());

  server.on("/status", []() {
    JsonDocument jd;
    jd["position_start"] = state->curtain_status.position_start;
    jd["position_end"] = state->curtain_status.position_end;
    jd["current_position"] = myMotor.currentPosition();
    jd["state"] = state->curtain_status.state;
    jd["sunrise"] = state->curtain_status.sunrise;
    jd["sunset"] = state->curtain_status.sunset;
    server.send(200, "application/json", jd.as<String>());
  });

  server.on("/state", []() {
    if (server.method() == HTTP_POST) {
      String body = server.arg("plain");
      Serial.println("Received state update:");
      Serial.println(body);
      state->fromJson(body);
      Serial.println("Updated state:");
      Serial.println(state->toJson());
      server.send(200, "application/json", state->toJson());
    } else {
      server.send(200, "application/json", state->toJson());
    }
  });

  server.on("/set_sunrise_sunset", []() {
    String sunrise = server.arg("sunrise");
    String sunset = server.arg("sunset");
    if (sunrise.length() > 0) {
      state->curtain_status.sunrise = sunrise;
      state->sunrise_min = state->parseTime(sunrise);
    }
    if (sunset.length() > 0) {
      state->curtain_status.sunset = sunset;
      state->sunset_min = state->parseTime(sunset);
    }
    // Set flags based on current time to avoid re-triggering moves for times already passed.
    // Strictly-greater means: if the new time equals right now, the flag stays false → fires once.
    time_t t = time(nullptr);
    if (t > 1577836800UL) {
      struct tm *ti = localtime(&t);
      int current_min = ti->tm_hour * 60 + ti->tm_min;
      opened_today = (current_min > state->sunrise_min);
      closed_today = (current_min > state->sunset_min);
    } else {
      opened_today = false;
      closed_today = false;
    }
    Serial.println("Sun times updated:");
    Serial.println("Sunrise: " + sunrise + " (" + String(state->sunrise_min) +
                   " min), Sunset: " + sunset + " (" +
                   String(state->sunset_min) + " min)");
    server.send(200, "application/json", state->curtain_status.toJSON());
  });

  server.on("/set_start", []() {
    state->curtain_status.position_start = myMotor.currentPosition();
    server.send(200, "application/json", state->toJson());
  });

  server.on("/set_end", []() {
    state->curtain_status.position_end = myMotor.currentPosition();
    server.send(200, "application/json", state->toJson());
  });

  server.on("/motor_manual_step", []() {
    String stepValue = server.arg("steps");
    int steps = stepValue.toInt();
    Serial.printf("Manually stepping motor by %d steps\n", steps);
    digitalWrite(ENABLE_PIN, LOW); // enable driver
    delay(100);
    myMotor.move(steps);
    while (myMotor.distanceToGo() != 0) {
      myMotor.run();
      yield();
    }
    digitalWrite(ENABLE_PIN, HIGH); // disable driver
    server.send(200, "text/plain", "stepped " + String(steps) + " steps");
  });

  server.on("/motor_goto", []() {
    String targetValue = server.arg("target");
    int target = targetValue.toInt();
    if (target < 0 || target > 100) {
      server.send(400, "text/plain", "Invalid target value");
      return;
    }
    int targetPosition =
        map(target, 0, 100, state->curtain_status.position_start,
            state->curtain_status.position_end);
    Serial.printf("Moving motor to target position %d steps\n", targetPosition);
    digitalWrite(ENABLE_PIN, LOW); // enable driver
    delay(100);
    myMotor.moveTo(targetPosition);
    while (myMotor.distanceToGo() != 0) {
      myMotor.run();
      yield();
    }
    digitalWrite(ENABLE_PIN, HIGH); // disable driver
    server.send(200, "text/plain", "moved to " + String(target) + "%");
  });

  server.onNotFound(handleNotFound);

  server.begin();
  Serial.println("HTTP server started");
}

void loop(void) {
  server.handleClient();
  MDNS.update();

  if (millis() - lastAutoCheck >= 5000) {
    lastAutoCheck = millis();
    checkAutoAction();
  }
}
