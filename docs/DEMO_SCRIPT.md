# 21 Nov demo: five minutes, plus likely questions

## Setup (before the room fills)
```
./build.sh
java -jar ecoai-grid.jar demo --speed 20     # open http://localhost:8080, full screen
```
Let it run at least 10 simulated minutes first (about 30 seconds at 20x) so the model has learned and the charts have history.
The live table moves with whatever you inject: right after a fan failure and load burst the AI controller can show *more* cooling
energy than the plain thermostat, because it is working harder to stay safe. The README figures are the 4-hour averages, so quote those. Have a second terminal ready for step 6.

## Flow
1. **The problem (30 s).** Cooling is a large share of data-centre energy and a growing water issue; fixed-speed fans and simple
   thermostats waste both. Point at the table: four controllers, *identical* workload and faults, running side by side.
2. **What you are looking at (30 s).** Top row = scoreboard. Cards = the AI-controlled racks (true temperature vs sensor,
   30 s forecast, fan and pump). Hardware is simulated now; the messages are exactly what the ESP32s will send.
3. **Load spike + fan failure (60 s).** Click *Burst all racks*, then *Fan fail* on rack 1. The AI card shows `FAN` within about
   5 simulated seconds and shifts cooling to the pump. Watch the table: STATIC and THERMOSTAT+ run past the 40 C limit
   (SLA breach column, max temperature) while the AI stays near its target. This exact sequence was tested.
4. **Sensor faults (45 s).** *Sensor stuck* on rack 2, then *Unplug sensor* on rack 0: `SENSOR` flag, full cooling, no wrong
   decisions made on bad data. *Clear faults*.
5. **Network drop (45 s).** *Network drop*: `STALE` appears on every rack after 5 simulated seconds (the backend has gone blind);
   after 10 s a red `NODE FAIL-SAFE` pill appears and the true fan/pump go to maximum, so temperatures fall. The node protects
   itself with no backend. *Network heal* and the AI takes back over. Stress that safety does not depend on the AI.
   (The `NODE FAIL-SAFE` pill and true duty come from the simulator; with real hardware the backend only sees `STALE`, then
   `FAILSAFE` once the link returns.)
6. **Hardware path (60 s).** Show three terminals: `mosquitto`, `simulator`, `backend`. Same dashboard, same AI, now over real
   MQTT. "When the ESP32s arrive we stop the simulator and power them on."
7. **Workloads and alerts (45 s).** Click *Batch jobs* under Workload and watch all four controllers face the same changing load.
   Point at *Safety and fault alerts*: every fault you injected is there with a time and severity. Click a rack card's
   explanation line ("Fan 15 %, pump 0 %: cheapest setting forecast to stay under ...") to show it is not a black box.
8. **Report (30 s).** Click *Report* in the header: a printable page with the table, charts, forecast accuracy and events
   (Save as PDF). `docs/evaluation-report.html` has the multi-seed tables.
9. **Replay (optional, 30 s).** Start with `--record runs`; later `java -jar ecoai-grid.jar replay runs` plays the run back
   with a scrub bar.
10. **Trade-off (30 s).** Drag *Target ceiling* to 39 and *Water priority* around: energy and coolant move in opposite directions.
   Say the numbers from README: -10 to -14 % at 38 C, -26 to -31 % at 39 C versus a thermostat, with zero breaches.

## Questions you should expect
- **"Why not just raise the thermostat?"** It saves the same energy but breached the limit in 5 of 6 seeds. The AI controller
  gets the saving without breaches because it predicts and handles faults. (README table.)
- **"Is this validated?"** Not on hardware yet. The simulator shares the model's structure, so accuracy is optimistic; the next step
  is to log the real rig (`--log`) and check the same metrics. Say so first.
- **"Why does the AI use more energy than the thermostat in Batch or Stress?"** Heavy load leaves less to save, and the
  thermostat breached the limit in those runs while the AI did not (README table). Say it before they do.
- **"Is the forecast any good?"** The scorecard under the temperature chart: while the temperature moves it is off by about
  0.4 K against 1.2 K for "stays the same". Steady state both are tiny. Simulator physics is optimistic.
- **"Is it Spring Boot?"** The Spring Boot layer is in the repo (`mvn spring-boot:run`, `docs/openapi.yaml`) on top of the same
  engine; it was type-checked but not run where this was built, and the demo uses the plain-JDK build. Say so.
- **"Where is the ML?"** An online-learning thermal model per rack (recursive least squares) feeding a model-predictive
  optimiser. Offline training / ONNX serving is a planned extension behind the `Policy` interface.
- **"What if the AI fails or the network dies?"** Four independent protection layers below it (table in `docs/MQTT_CONTRACT.md`).
- **"Why is water not better?"** The liquid loop is cheap in electricity, so the optimiser uses it; the slider shows the
  trade-off honestly. Raising water priority trades electricity for coolant.
- **"Real data centre savings?"** Not claimed. This is a scale model; the percentages describe this rig only.

## If something goes wrong on the day
- Dashboard blank: the process died, rerun the command. Port busy: `--port 8081`.
- Everything is local; no internet or login is needed. A pre-recorded fallback: `java -jar ecoai-grid.jar headless --hours 4 --faults`
  prints the whole comparison table in about 5 seconds.
