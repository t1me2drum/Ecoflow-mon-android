# Graph Report - PowerHub  (2026-09-29)

## Corpus Check
- Corpus is ~17,556 words - fits in a single context window. You may not need a graph.

## Summary
- 653 nodes · 1413 edges · 26 communities (22 shown, 4 thin omitted)
- Extraction: 95% EXTRACTED · 5% INFERRED · 0% AMBIGUOUS · INFERRED: 71 edges (avg confidence: 0.86)
- Token cost: 69,464 input · 0 output

## Community Hubs (Navigation)
- Repository & Settings Core
- MQTT Link & Diagnostics
- Delta 3 Protobuf Codec
- Background Monitor & Alerts
- Device State & History
- Device Controls & JSON Protocols
- EcoFlow Cloud Clients
- Station Screens UI
- README Architecture Concepts
- Main Activity & Login
- Screen Compose Imports
- Station List UI
- History Charts
- JSON Message Tests
- Device Model & Battery Flow
- Protocol Unit Tests
- Shared UI Helpers
- Widget Layout
- Login Screen UI
- Battery Widget
- CI Build Pipeline
- History Concept
- Diagnostic Log Concept

## God Nodes (most connected - your core abstractions)
1. `Repository` - 38 edges
2. `DeviceState` - 29 edges
3. `Device` - 26 edges
4. `SettingsStore` - 21 edges
5. `Control` - 20 edges
6. `Section` - 19 edges
7. `DeviceStateTest` - 17 edges
8. `MonitorService` - 15 edges
9. `Delta2Family` - 14 edges
10. `Delta3Protocol` - 14 edges

## Surprising Connections (you probably didn't know these)
- `CI build pipeline (tests gate APK publishing)` --references--> `Build APK workflow`  [EXTRACTED]
  README.md → .github/workflows/build.yml
- `CI build pipeline (tests gate APK publishing)` --references--> `Prepare signing key step`  [INFERRED]
  README.md → .github/workflows/build.yml
- `CI build pipeline (tests gate APK publishing)` --rationale_for--> `Unit tests step (testDebugUnitTest)`  [INFERRED]
  README.md → .github/workflows/build.yml
- `CI build pipeline (tests gate APK publishing)` --references--> `Publish dev build step`  [INFERRED]
  README.md → .github/workflows/build.yml
- `CI build pipeline (tests gate APK publishing)` --references--> `Publish release step`  [INFERRED]
  README.md → .github/workflows/build.yml

## Import Cycles
- None detected.

## Hyperedges (group relationships)
- **DeviceProtocol implementations** — readme_deviceprotocol, readme_delta2_protocol, readme_delta3_protocol, readme_deltapro3_protocol, readme_legacyjson_protocol [INFERRED 0.95]
- **CI test-build-publish flow** — _github_workflows_build_unit_tests, _github_workflows_build_prepare_signing_key, _github_workflows_build_build_release_apk, _github_workflows_build_rename_apk, _github_workflows_build_publish_dev_build, _github_workflows_build_publish_release [EXTRACTED 1.00]
- **EcoFlow cloud connectivity layer** — readme_ecoflowcloud, readme_ecoflowopenapi, readme_mqttlink, readme_ecoflow_auth_login, readme_mqtt_topics [INFERRED 0.85]

## Communities (26 total, 4 thin omitted)

### Community 0 - "Repository & Settings Core"
Cohesion: 0.05
Nodes (32): CompletableSignal, Connected, Connecting, ConnState, Failed, Idle, Synchronized, Reconnecting (+24 more)

### Community 1 - "MQTT Link & Diagnostics"
Cohesion: 0.05
Nodes (35): ByteArray, MqttLink, IMqttActionListener, MqttCallbackExtended, ByteArray, DiagLog, Context, Intent (+27 more)

### Community 2 - "Delta 3 Protobuf Codec"
Cohesion: 0.08
Nodes (21): Delta3Protocol, ByteArray, Params, ByteArray, ByteArray, Outgoing, TopicKind, DATA (+13 more)

### Community 3 - "Background Monitor & Alerts"
Cohesion: 0.07
Nodes (29): GridStatus, NONE, OK, WEAK, AlertEngine, Context, Memory, BootReceiver (+21 more)

### Community 4 - "Device State & History"
Cohesion: 0.09
Nodes (13): HistoryDb, ByteArray, Params, Params, Params, DeviceState, validMinutes(), DeviceStateTest (+5 more)

### Community 5 - "Device Controls & JSON Protocols"
Cohesion: 0.11
Nodes (19): Delta2Family, Delta2MaxProtocol, Delta2Protocol, DeltaPro3Protocol, DeltaMaxProtocol, River2MaxProtocol, Choice, Control (+11 more)

### Community 6 - "EcoFlow Cloud Clients"
Cohesion: 0.09
Nodes (28): EcoflowCloud, EcoflowException, JSONObject, OkHttpClient, MqttCredentials, Session, CloudDevice, EcoflowOpenApi (+20 more)

### Community 7 - "Station Screens UI"
Cohesion: 0.11
Nodes (33): app, ConnectionBanner(), DeleteStationDialog(), flowText(), Composable, Dp, minutes(), RenameDialog() (+25 more)

### Community 8 - "README Architecture Concepts"
Cohesion: 0.08
Nodes (28): AlertEngine, Battery state from energy flow, Delta2 protocol, Delta3 protocol, DeltaPro3 protocol, DeviceModel, DeviceModel.detect (SN prefix), DeviceProtocol (+20 more)

### Community 9 - "Main Activity & Login"
Cohesion: 0.09
Nodes (25): activity, activityresultcontracts, Composable, LoginScreen(), AppTheme(), Composable, MainActivity, Bundle (+17 more)

### Community 10 - "Screen Compose Imports"
Cohesion: 0.11
Nodes (25): arrangement, arrowback, batteryflow, carddefaults, collectasstatewithlifecycle, delete, edit, experimentalmaterial3api (+17 more)

### Community 11 - "Station List UI"
Cohesion: 0.08
Nodes (23): add, animatedpasstate, buildannotatedstring, combinedclickable, dragindicator, dropdownmenu, dropdownmenuitem, experimentalfoundationapi (+15 more)

### Community 12 - "History Charts"
Cohesion: 0.13
Nodes (21): Sample, ChartCard(), EnergyRow(), HistoryPane(), Composable, Series, background, box (+13 more)

### Community 13 - "JSON Message Tests"
Cohesion: 0.16
Nodes (8): JsonMessages, ByteArray, JSONObject, Params, JsonProtocolTest, Test, jsonarray, random

### Community 14 - "Device Model & Battery Flow"
Cohesion: 0.11
Nodes (20): absInt(), BatteryFlow, Charging, chargingFromGrid(), DeviceModel, DELTA_2, DELTA_2_MAX, DELTA_3 (+12 more)

### Community 15 - "Protocol Unit Tests"
Cohesion: 0.17
Nodes (9): ByteArray, Test, ProtobufProtocolTest, assertarrayequals, assertequals, assertfalse, assertnull, asserttrue (+1 more)

### Community 16 - "Shared UI Helpers"
Cohesion: 0.11
Nodes (17): alertdialog, alpha, animatefloat, bolt, canvas, fastoutslowineasing, fillmaxwidth, icons (+9 more)

### Community 17 - "Widget Layout"
Cohesion: 0.12
Nodes (16): actionstartactivity, alignment, clickable, column, cornerradius, dp, fillmaxsize, fontweight (+8 more)

### Community 18 - "Login Screen UI"
Cohesion: 0.13
Nodes (14): api_hosts, button, circularprogressindicator, height, icon, keyboardoptions, keyboardtype, outlinedtextfield (+6 more)

### Community 19 - "Battery Widget"
Cohesion: 0.24
Nodes (8): BatteryWidget, BatteryWidgetReceiver, Composable, Context, WidgetRow, GlanceAppWidget, GlanceAppWidgetReceiver, GlanceId

### Community 20 - "CI Build Pipeline"
Cohesion: 0.35
Nodes (11): build job, Build APK workflow, Build release APK step (assembleRelease), Prepare signing key step, Publish dev build step, Publish release step, Rename APK step, setup-gradle (Gradle 8.11.1) (+3 more)

### Community 21 - "History Concept"
Cohesion: 0.67
Nodes (3): Charge and power charts, HistoryDb (SQLite), Repository

## Knowledge Gaps
- **37 isolated node(s):** `Idle`, `Connecting`, `Connected`, `DARK`, `LIGHT` (+32 more)
  These have ≤1 connection - possible missing edges or undocumented components. (Counts symbols only; 161 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **4 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `DeviceState` connect `Device State & History` to `Repository & Settings Core`, `Background Monitor & Alerts`, `Device Controls & JSON Protocols`, `EcoFlow Cloud Clients`, `Station Screens UI`, `Screen Compose Imports`, `Device Model & Battery Flow`?**
  _High betweenness centrality (0.117) - this node is a cross-community bridge._
- **Why does `Repository` connect `Repository & Settings Core` to `MQTT Link & Diagnostics`, `Device State & History`, `EcoFlow Cloud Clients`?**
  _High betweenness centrality (0.095) - this node is a cross-community bridge._
- **Why does `Control` connect `Device Controls & JSON Protocols` to `Repository & Settings Core`, `Delta 3 Protobuf Codec`, `EcoFlow Cloud Clients`, `Station Screens UI`, `Screen Compose Imports`, `Device Model & Battery Flow`?**
  _High betweenness centrality (0.093) - this node is a cross-community bridge._
- **Are the 14 inferred relationships involving `DeviceState` (e.g. with `.`charging uses only the charge estimate`()` and `.`discharging uses only the discharge estimate`()`) actually correct?**
  _`DeviceState` has 14 INFERRED edges - model-reasoned connections that need verification._
- **What connects `Idle`, `Connecting`, `Connected` to the rest of the system?**
  _37 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `Repository & Settings Core` be split into smaller, more focused modules?**
  _Cohesion score 0.05081081081081081 - nodes in this community are weakly interconnected._
- **Should `MQTT Link & Diagnostics` be split into smaller, more focused modules?**
  _Cohesion score 0.0514216575922565 - nodes in this community are weakly interconnected._