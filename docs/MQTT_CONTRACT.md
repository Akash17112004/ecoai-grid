# MQTT contract (hardware <-> backend)

The simulator and the real ESP32 nodes speak exactly this. If the firmware matches it, the backend cannot tell them apart.

Broker: any MQTT 3.1.1 broker (Mosquitto recommended). QoS 0, no retained messages, anonymous on a private LAN.
All topics live under `ecoai/`.

## Telemetry: node -> backend, 1 Hz, `ecoai/rack<N>/telemetry`

```json
{"rack":0,"t":123,"temp":34.5,"supply":24.1,"power":28.4,"fan":0.55,"pump":0.0,"rpm":1650,"trip":0,"fs":0}
```

| field | unit | meaning |
|---|---|---|
| `rack` | - | rack index 0..2 (must match the topic) |
| `t` | s | node uptime (`millis()/1000`); used only for sample spacing, so a reboot is harmless |
| `temp` | C | rack temperature, EMA-filtered (alpha 0.3). **-127 = sensor unplugged** (DS18B20 error code) |
| `supply` | C | intake / ambient temperature |
| `power` | W | measured IT load (INA219) |
| `fan`, `pump` | 0..1 | duty the node has actually applied (after slew limiting and safety overrides) |
| `rpm` | rpm | fan tachometer; ~0 while `fan` > 0.4 means a dead or stalled fan |
| `trip` | 0/1 | node has opened the load relay (over-temperature) |
| `fs` | 0/1 | node is in watchdog fail-safe (backend silent > 10 s) |

## Command: backend -> node, in reply to every telemetry sample, `ecoai/rack<N>/cmd`

```json
{"fan":0.55,"pump":0.25,"seq":42}
```

`fan`, `pump` are target duties 0..1; `seq` is a counter for debugging. The node slews towards the target.

## Simulator control: dashboard -> simulator only, `ecoai/sim/control`

```json
{"cmd":"fanfail","rack":1}
```

Commands: `burst`, `burstall`, `fanfail`, `fanfix`, `stuck`, `unstuck`, `unplug`, `replug`, `partition`, `heal`, `clear`.
Real hardware ignores this topic (it does not subscribe to it).

## Who protects what

| layer | trigger | action | needs network |
|---|---|---|---|
| independent thermal fuse | 50 C | cuts the heater | no |
| ESP32 trip | >= 45 C (resets < 41 C) | opens load relay, fan + pump 100 % | no |
| ESP32 watchdog | no command for 10 s | fan 100 %, pump >= 50 % | no |
| ESP32 sensor check | temp = -127 | fan + pump 100 % | no |
| backend guard | measured >= 39 C | fan max, pump 100 % | yes |
| backend fault logic | stuck / invalid sensor, dead fan | full cooling / stop commanding the dead fan | yes |
| AI optimiser | normal operation | 30 s look-ahead, cheapest safe duty | yes |

Everything below the backend layers keeps working with the network down. That is deliberate: the AI is an optimiser on top of
safety that does not depend on it.
