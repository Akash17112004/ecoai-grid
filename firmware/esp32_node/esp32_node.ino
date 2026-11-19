/*
 * EcoAI Grid - ESP32 rack node firmware  (one board per rack)
 *
 * STATUS: written to match the simulator's Esp32Node model and docs/MQTT_CONTRACT.md. NOT yet compiled or run on
 * hardware - expect to fix pins, shunt range and library versions when the rig is assembled.
 *
 * Libraries (Arduino Library Manager): PubSubClient, OneWire, DallasTemperature, Adafruit INA219.
 *
 * Behaviour (identical to sim/Esp32Node.java):
 *   - 1 Hz: read rack temp + intake temp (DS18B20), rack power (INA219), fan tach; EMA-filter the temperatures
 *   - publish telemetry JSON to  ecoai/rack<N>/telemetry
 *   - subscribe to               ecoai/rack<N>/cmd   -> {"fan":0..1,"pump":0..1,"seq":n}
 *   - LOCAL SAFETY, works with WiFi / broker down:
 *       over-temp (>=45 C) -> open the load relay and force fan+pump 100 % until < 41 C
 *       no command for 10 s -> watchdog fail-safe: fan 100 %, pump >= 50 %
 *       temperature sensor unplugged (-127) -> fan+pump 100 %
 *   - PWM slew limiting so actuators never jump
 */
#include <WiFi.h>
#include <PubSubClient.h>
#include <OneWire.h>
#include <DallasTemperature.h>
#include <Adafruit_INA219.h>
#include <Wire.h>

// ---------------------------------------------------------------- configuration
#define RACK_ID        0
static const char* WIFI_SSID = "YOUR_WIFI";
static const char* WIFI_PASS = "YOUR_PASSWORD";
static const char* MQTT_HOST = "192.168.1.10";   // laptop running Mosquitto + the Java backend
static const int   MQTT_PORT = 1883;

#define PIN_ONEWIRE    4    // DS18B20 bus: index 0 = rack temperature, index 1 = intake (supply) temperature
#define PIN_FAN_PWM    18   // 25 kHz PWM to the 4-pin fan
#define PIN_PUMP_PWM   19   // MOSFET driving the pump
#define PIN_TACH       34   // fan tach, 2 pulses / revolution (needs a pull-up)
#define PIN_RELAY      23   // IT-load relay; HIGH = load connected (invert if your module is active-low)

static const float TRIP_C = 45.0, TRIP_RESET_C = 41.0;
static const unsigned long WATCHDOG_MS = 10000;
static const float EMA_ALPHA = 0.3f;
static const float FAN_SLEW = 0.25f, PUMP_SLEW = 0.15f;       // max duty change per second

// ---------------------------------------------------------------- state
WiFiClient wifi;
PubSubClient mqtt(wifi);
OneWire oneWire(PIN_ONEWIRE);
DallasTemperature ds(&oneWire);
Adafruit_INA219 ina;

volatile uint32_t tachPulses = 0;
void IRAM_ATTR onTach() { tachPulses++; }

float emaT = NAN, emaS = NAN;
float fanTgt = 0.3f, pumpTgt = 0.0f, fanDuty = 0.3f, pumpDuty = 0.0f;
bool trip = false, failsafe = false;
unsigned long lastCmdMs = 0, lastTickMs = 0;
char topicTel[40], topicCmd[40];

// ---------------------------------------------------------------- helpers
static bool jnum(const char* json, const char* key, float* out) {
  char pat[16];
  snprintf(pat, sizeof pat, "\"%s\":", key);
  const char* p = strstr(json, pat);
  if (!p) return false;
  *out = atof(p + strlen(pat));
  return true;
}

static float clampf(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }

void onMessage(char* topic, byte* payload, unsigned int len) {
  char buf[128];
  len = len < sizeof(buf) - 1 ? len : sizeof(buf) - 1;
  memcpy(buf, payload, len); buf[len] = 0;
  float v;
  if (jnum(buf, "fan", &v))  fanTgt  = clampf(v, 0, 1);
  if (jnum(buf, "pump", &v)) pumpTgt = clampf(v, 0, 1);
  lastCmdMs = millis();
}

void connectNetwork() {
  if (WiFi.status() != WL_CONNECTED) { WiFi.begin(WIFI_SSID, WIFI_PASS); return; }   // non-blocking retry
  if (!mqtt.connected()) {
    char id[24];
    snprintf(id, sizeof id, "ecoai-esp32-%d", RACK_ID);
    if (mqtt.connect(id)) mqtt.subscribe(topicCmd);
  }
}

// ---------------------------------------------------------------- setup / loop
void setup() {
  Serial.begin(115200);
  snprintf(topicTel, sizeof topicTel, "ecoai/rack%d/telemetry", RACK_ID);
  snprintf(topicCmd, sizeof topicCmd, "ecoai/rack%d/cmd", RACK_ID);

  pinMode(PIN_RELAY, OUTPUT);  digitalWrite(PIN_RELAY, HIGH);
  pinMode(PIN_TACH, INPUT_PULLUP);
  attachInterrupt(digitalPinToInterrupt(PIN_TACH), onTach, FALLING);
  ledcAttach(PIN_FAN_PWM, 25000, 8);       // Arduino-ESP32 3.x API; on 2.x use ledcSetup + ledcAttachPin
  ledcAttach(PIN_PUMP_PWM, 1000, 8);

  ds.begin();
  ds.setWaitForConversion(false);          // never block the loop for the 750 ms conversion
  ds.requestTemperatures();
  ina.begin();                             // default 32 V / 2 A range - check the shunt against your max heater current

  mqtt.setServer(MQTT_HOST, MQTT_PORT);
  mqtt.setCallback(onMessage);
  lastCmdMs = millis();
  lastTickMs = millis();
}

void loop() {
  connectNetwork();
  mqtt.loop();

  unsigned long now = millis();
  if (now - lastTickMs < 1000) return;
  float dt = (now - lastTickMs) / 1000.0f;
  lastTickMs = now;

  // ---- sample
  float rawT = ds.getTempCByIndex(0), rawS = ds.getTempCByIndex(1);
  ds.requestTemperatures();                // result is read on the next tick
  bool unplugged = (rawT <= -100.0f);
  if (!unplugged) emaT = isnan(emaT) ? rawT : emaT + EMA_ALPHA * (rawT - emaT);
  if (rawS > -100.0f) emaS = isnan(emaS) ? rawS : emaS + EMA_ALPHA * (rawS - emaS);
  float powerW = ina.getPower_mW() / 1000.0f;
  noInterrupts(); uint32_t pulses = tachPulses; tachPulses = 0; interrupts();
  float rpm = (pulses / 2.0f) * 60.0f / dt;

  // ---- local safety (independent of the network)
  if (!trip && !isnan(emaT) && emaT >= TRIP_C) trip = true;
  else if (trip && emaT < TRIP_RESET_C) trip = false;
  digitalWrite(PIN_RELAY, trip ? LOW : HIGH);

  // ---- publish (before applying the new duty, same order as the simulator)
  char msg[200];
  snprintf(msg, sizeof msg,
           "{\"rack\":%d,\"t\":%lu,\"temp\":%.4f,\"supply\":%.4f,\"power\":%.3f,\"fan\":%.3f,\"pump\":%.3f,\"rpm\":%.0f,\"trip\":%d,\"fs\":%d}",
           RACK_ID, now / 1000UL, unplugged ? -127.0f : emaT, isnan(emaS) ? 24.0f : emaS, powerW,
           fanDuty, pumpDuty, rpm, trip ? 1 : 0, failsafe ? 1 : 0);
  if (mqtt.connected()) mqtt.publish(topicTel, msg);

  // ---- choose targets: backend command, overridden by the safety layers
  failsafe = (now - lastCmdMs) > WATCHDOG_MS;
  float ft = fanTgt, pt = pumpTgt;
  if (failsafe)  { ft = 1.0f; pt = pt > 0.5f ? pt : 0.5f; }
  if (unplugged) { ft = 1.0f; pt = 1.0f; }
  if (trip)      { ft = 1.0f; pt = 1.0f; }

  // ---- slew-limited PWM
  fanDuty  += clampf(ft - fanDuty,  -FAN_SLEW * dt,  FAN_SLEW * dt);
  pumpDuty += clampf(pt - pumpDuty, -PUMP_SLEW * dt, PUMP_SLEW * dt);
  ledcWrite(PIN_FAN_PWM,  (uint32_t)(fanDuty * 255));
  ledcWrite(PIN_PUMP_PWM, (uint32_t)(pumpDuty * 255));
}
