# Eclipse Milo OPC UA — Config-Driven Server + Client

Java 24 · Eclipse Milo 1.1.1 · Quarkus 3.27 LTS · Docker Compose

---

## Environment setup

Before first run, create a `.env` file from the example:

```bash
cp .env.example .env
# Edit .env if your OPC UA server or NATS broker is at a different address
```

`.env` is git-ignored. Never commit it.

| Variable | Description | Default |
|----------|-------------|---------|
| `OPCUA_SERVER_URL` | OPC UA endpoint the client connects to. In Docker Compose the server is reachable by service name. | `opc.tcp://opc-server:4840/` |
| `NATS_URL` | NATS broker URL for publishing PartCounter values. `host.docker.internal` resolves to the host machine from inside Docker. | `nats://host.docker.internal:4222` |

---

## Quick start

```bash
cd ~/projects/eclipse-milo
docker compose up --build
```

Both containers build and start. The client waits for the server health-check before
connecting. Console output from both services is interleaved in the terminal.

The client publishes `Floor1/PartCounter` updates to the DWH NATS broker at
`nats://host.docker.internal:4222` (subject: `opcua.floor1.PartCounter`).
Start the DWH NATS stack before starting eclipse-milo:

```bash
cd ~/projects/dwh/nats && docker compose up -d
```

---

## What is Quarkus?

Quarkus is a Java framework built for container-first, cloud-native applications.
It wraps standard Jakarta EE / CDI APIs and dramatically reduces the boilerplate
you would write in plain Java.

### Plain Java approach (without Quarkus)

```java
public static void main(String[] args) throws Exception {
    OpcUaServer server = buildServer();
    server.startup().get();

    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
        try { server.shutdown().get(); } catch (Exception ignored) {}
    }));
    new CountDownLatch(1).await();   // keep the JVM alive
}
```

You manage everything manually: object wiring, config parsing, lifecycle order,
graceful shutdown, scheduling, logging, health-checks.

### Quarkus approach (this project)

| Concern | Without Quarkus | With Quarkus |
|---------|----------------|--------------|
| Object wiring | `new Foo(new Bar())` everywhere | `@Inject` (CDI) |
| Startup order | explicit, error-prone | `@Observes StartupEvent` |
| Graceful shutdown | `Runtime.addShutdownHook(…)` | `@Observes ShutdownEvent` |
| Scheduling | `ScheduledExecutorService` boilerplate | `@Scheduled(every = "2s")` |
| Config from env | `System.getenv(…)` + nullchecks | `@ConfigProperty` with defaults |
| Logging | pick & configure a framework | unified facade, one properties key |
| Keep-alive | `CountDownLatch` | `Quarkus.waitForExit()` |
| Health endpoint | write it yourself | `quarkus-smallrye-health` (one dependency) |
| Metrics | write it yourself | `quarkus-micrometer` (one dependency) |

**Key point:** Quarkus does **not** change the Eclipse Milo API at all. It only
replaces infrastructure plumbing with annotations, leaving the OPC UA code identical
to what you would write in plain Java.

### Native image note

Quarkus can compile to a native binary (GraalVM) for ~10 ms startup time.
Eclipse Milo uses reflection internally, so native compilation requires a
`reflection-config.json` listing Milo's generated classes. This project uses
**JVM mode** (`java -jar`) which works out of the box with zero extra config.

---

## What is Eclipse Milo?

[Eclipse Milo](https://github.com/eclipse/milo) is a pure-Java implementation of
the OPC UA specification. It provides both a client SDK and a server SDK.

### OPC UA in one paragraph

OPC UA (Unified Architecture) is a machine-to-machine communication protocol used
in industrial automation. It models data as a graph of typed **nodes** (variables,
objects, methods) linked by **references**. Clients read/write variables, call
methods, and subscribe to value changes. Nodes are addressed by **NodeId**
(namespace index + identifier) and can be discovered by **browsing** the
reference graph.

### Does Eclipse Milo support OPC UA Browse?

**Yes.** `OpcUaClient.browse(BrowseDescription)` sends a Browse request to the
server and returns a `BrowseResult` with a `ReferenceDescription[]` — one entry
per discovered node. This is the same mechanism that GUI tools like UaExpert use
to build their node-tree view.

```
Client ──Browse(Floor1, forward, Variable)──► Server
Client ◄──[Temperature, Pressure, PartCounter, …]────
```

This project uses Browse in `NodeBrowser.java` to auto-discover tags when the
client config leaves the `tags` list empty.

---

## Architecture

```
┌─────────────────────────────────────────────────────────────────────────────┐
│  Docker network: opcua-net                                                   │
│                                                                              │
│  ┌───────────────────────┐         ┌───────────────────────────┐            │
│  │      opc-server       │         │        opc-client          │            │
│  │      port 4840        │         │                            │            │
│  │                       │         │  SUBSCRIPTION (all tags)   │            │
│  │  Namespace Floor1     │◄───────►│    Browse → all Floor1 &  │            │
│  │    Temperature (1 s)  │         │    Floor2 tags discovered  │            │
│  │    Pressure    (2 s)  │         │    automatically           │            │
│  │    PartCounter (500ms)│         │                            │            │
│  │    MachineRunning(5s) │         │  POLLING (timer, optional) │            │
│  │                       │         │    configurable per-ns     │            │
│  │  Namespace Floor2     │         │                            │            │
│  │    Humidity    (3 s)  │         └──────────────┬────────────┘            │
│  │    MotorSpeed  (1 s)  │                        │ publish opcua.<f>.<tag>  │
│  │    AlarmActive (7 s)  │                        ▼                          │
│  │    PartCounter (500ms)│         ┌────────────────────────┐               │
│  │    Throughput  (2 s)  │         │         NATS            │               │
│  └───────────────────────┘         │  opcua.floor1.*        │               │
│                                    │  opcua.floor2.*        │               │
└────────────────────────────────────┴────────────┬───────────┴───────────────┘
                                                   │
                    ┌──────────────────────────────┼──────────────────────┐
                    │                              │                       │
                    ▼                              ▼                       │
          MES backend                    nats-historian (mes-dwh)          │
          NatsOpcCounterService          historian.opcua_tags              │
          (PartCounter → order           (all tags, ClickHouse,            │
           produced count)               Grafana-ready)                    │
```

---

## Server configuration — `opcua-server-config.json`

Located at `server/src/main/resources/opcua-server-config.json`.
The server reads it at startup and creates one OPC UA namespace per entry.

### Schema

```jsonc
{
  "namespaces": [
    {
      "uri":        "<string>  Unique namespace URI, e.g. urn:example:factory:floor1",
      "folderName": "<string>  Folder node name visible in OPC UA browsers, e.g. Floor1",
      "tags": [
        {
          "id":            "<string>  Node identifier, e.g. Temperature",
          "displayName":   "<string>  Human-readable label shown in browsers",
          "dataType":      "<string>  Float | Double | Int64 | Int32 | Boolean | String",
          "minValue":      "<number>  Simulation lower bound",
          "maxValue":      "<number>  Simulation upper bound",
          "updatePeriodMs":"<int>     How often the simulated value changes (default 1000)"
        }
      ]
    }
  ]
}
```

### Simulation behaviour by data type

| dataType | Behaviour |
|----------|-----------|
| `Float` / `Double` | Random value in `[minValue, maxValue]` each tick |
| `Int32` / `Int64` | Counter: increments by 1 each tick, resets to 0 when > maxValue |
| `Boolean` | Toggles true/false each tick |
| `String` | Static placeholder (not simulated) |

### Example — current `opcua-server-config.json`

```json
{
  "namespaces": [
    {
      "uri": "urn:example:factory:floor1",
      "folderName": "Floor1",
      "tags": [
        { "id": "Temperature",    "displayName": "Temperature (°C)",  "dataType": "Float",   "minValue": 20.0, "maxValue": 80.0,    "updatePeriodMs": 1000 },
        { "id": "Pressure",       "displayName": "Pressure (hPa)",    "dataType": "Float",   "minValue": 98.0, "maxValue": 110.0,   "updatePeriodMs": 2000 },
        { "id": "PartCounter",    "displayName": "Part Counter",       "dataType": "Int64",   "minValue": 0,    "maxValue": 1000000, "updatePeriodMs": 500  },
        { "id": "MachineRunning", "displayName": "Machine Running",    "dataType": "Boolean",                                       "updatePeriodMs": 5000 }
      ]
    },
    {
      "uri": "urn:example:factory:floor2",
      "folderName": "Floor2",
      "tags": [
        { "id": "Humidity",    "displayName": "Humidity (%RH)",    "dataType": "Float",   "minValue": 30.0, "maxValue": 90.0,   "updatePeriodMs": 3000 },
        { "id": "MotorSpeed",  "displayName": "Motor Speed (RPM)", "dataType": "Float",   "minValue": 0.0,  "maxValue": 3000.0, "updatePeriodMs": 1000 },
        { "id": "AlarmActive", "displayName": "Alarm Active",      "dataType": "Boolean",                                      "updatePeriodMs": 7000 }
      ]
    }
  ]
}
```

### Resulting OPC UA node IDs

| NodeId | Type | Update period |
|--------|------|--------------|
| `ns=2;s=Floor1/Temperature` | Float | 1 s |
| `ns=2;s=Floor1/Pressure` | Float | 2 s |
| `ns=2;s=Floor1/PartCounter` | Int64 (counter) | 500 ms |
| `ns=2;s=Floor1/MachineRunning` | Boolean (toggle) | 5 s |
| `ns=3;s=Floor2/Humidity` | Float | 3 s |
| `ns=3;s=Floor2/MotorSpeed` | Float | 1 s |
| `ns=3;s=Floor2/AlarmActive` | Boolean (toggle) | 7 s |

> Namespace indices (ns=2, ns=3) are assigned dynamically by the server.
> The client discovers them at runtime by reading `Server_NamespaceArray`.

---

## Client configuration — `opcua-client-config.json`

Located at `client/src/main/resources/opcua-client-config.json`.
The client reads it at startup and connects each namespace to the appropriate strategy.

### Schema

```jsonc
{
  "pollNamespaces": [
    {
      "uri":          "<string>  Must match the server namespace URI",
      "folderName":   "<string>  Folder node to read from, e.g. Floor1",
      "pollPeriodMs": "<int>     How often to read (ms, default 2000)",
      "tags":         ["<tagId>", "..."]  // explicit list; omit or leave [] for Browse auto-discovery
    }
  ],
  "subscribeNamespaces": [
    {
      "uri":        "<string>  Must match the server namespace URI",
      "folderName": "<string>  Folder node to subscribe",
      "tags":       ["<tagId>", "..."]  // explicit list; omit or leave [] for Browse auto-discovery
    }
  ]
}
```

### Example — current `opcua-client-config.json`

```json
{
  "pollNamespaces": [
    {
      "uri": "urn:example:factory:floor1",
      "folderName": "Floor1",
      "pollPeriodMs": 2000,
      "tags": ["Temperature", "PartCounter"]
    }
  ],
  "subscribeNamespaces": [
    {
      "uri": "urn:example:factory:floor2",
      "folderName": "Floor2",
      "tags": ["Humidity", "MotorSpeed", "AlarmActive"]
    }
  ]
}
```

### Auto-discovery with OPC UA Browse

Leave `tags` empty (or omit the field) to let the client discover all variable
nodes under the folder automatically:

```json
{
  "pollNamespaces": [
    { "uri": "urn:example:factory:floor1", "folderName": "Floor1", "pollPeriodMs": 3000 }
  ]
}
```

`NodeBrowser.browseNodeIds()` sends a Browse request asking for all Variable-class
nodes under the folder and returns their exact `NodeId` objects (preserving numeric
vs string type). This lets you add tags to the server config without changing the
client config.

---

## Polling vs Subscriptions

### Polling (`PollingService.java`)

```
Client ──read(Floor1/Temperature)──► Server  (every pollPeriodMs)
Client ◄──DataValue──────────────
Client ──read(Floor1/PartCounter)──► Server
Client ◄──DataValue──────────────
```

- Client initiates every call; load is proportional to poll rate.
- Each poll-namespace runs at its own `pollPeriodMs` on a `ScheduledExecutorService`.
- Simple and works on any server; ideal for quick diagnostics.
- You always pay the round-trip cost, even when values haven't changed.

### Subscriptions (`SubscriptionService.java`)

```
Client ──CreateSubscription────────► Server  (once)
Client ──CreateMonitoredItems(all)──► Server  (once)

Server ──Publish(changed values)───► Client  (only on change)
```

- Server pushes only changed values; traffic scales with the change rate.
- All subscribe-namespaces share one `OpcUaSubscription`; each tag is a `OpcUaMonitoredItem`.
- Much more efficient at scale (thousands of nodes, low-change signals).
- Slightly more state: the subscription must be created and deleted cleanly.

**Rule of thumb:** use subscriptions in production; use polling for quick
diagnostics or when the server doesn't support subscriptions.

---

## Project layout

```
eclipse-milo/
├── docker-compose.yml
├── README.md
│
├── server/
│   ├── Dockerfile
│   ├── pom.xml
│   └── src/main/
│       ├── java/com/example/server/
│       │   ├── Main.java                  @QuarkusMain entry point
│       │   ├── OpcUaServerBean.java       Server lifecycle (CDI bean)
│       │   │                               Reads opcua-server-config.json,
│       │   │                               creates one DynamicNamespace per entry
│       │   ├── ServerConfig.java          JSON → Java model (namespaces + tags)
│       │   ├── DynamicNamespace.java      Config-driven namespace + simulation
│       │   │                               Creates folder + variable nodes,
│       │   │                               runs per-tag ScheduledExecutorService
│       │   └── SimulatedNamespace.java    Legacy reference (hard-coded 4 nodes)
│       └── resources/
│           ├── application.properties     Quarkus logging config
│           └── opcua-server-config.json   Namespace + tag definitions
│
└── client/
    ├── Dockerfile
    ├── pom.xml
    └── src/main/
        ├── java/com/example/client/
        │   ├── Main.java                  @QuarkusMain entry point
        │   ├── OpcUaClientBean.java       Connection + namespace index resolution
        │   │                               Reads opcua-client-config.json,
        │   │                               resolves ns indices for all URIs,
        │   │                               starts PollingService + SubscriptionService
        │   ├── ClientConfig.java          JSON → Java model (poll/subscribe namespaces)
        │   ├── NodeBrowser.java           OPC UA Browse — auto-discovers variable nodes
        │   │                               under a folder when tags list is empty
        │   ├── PollingService.java        Timer-based reads (ScheduledExecutorService)
        │   │                               One thread, per-namespace schedule
        │   └── SubscriptionService.java   Server-push subscriptions (Milo 1.x API)
        │                                   One OpcUaSubscription for all namespaces
        └── resources/
            ├── application.properties     Quarkus logging config
            └── opcua-client-config.json   Which namespaces to poll / subscribe
```

---

## How the pieces connect at startup

```
OpcUaServerBean.onStart()
  ├── reads opcua-server-config.json
  ├── builds OpcUaServer (endpoint, security, transport)
  ├── for each namespace config entry:
  │     DynamicNamespace(server, nsConfig).startup()
  │       creates UaFolderNode under Objects
  │       creates one UaVariableNode per tag
  │       starts ScheduledExecutorService (per-tag timer → updateNode())
  └── server.startup()  ← begins accepting connections on port 4840

OpcUaClientBean.onStart()
  ├── reads opcua-client-config.json
  ├── OpcUaClient.create() + connectWithRetry()
  ├── reads Server_NamespaceArray → maps URI → namespace index
  ├── SubscriptionService.setupSubscriptions()
  │     for each subscribeNamespace:
  │       resolve tags (explicit list or Browse)
  │       create OpcUaMonitoredItem per tag
  │     subscription.synchronizeMonitoredItems() → server starts pushing
  └── PollingService.startPolling()
        for each pollNamespace:
          resolve tags (explicit list or Browse)
          scheduleAtFixedRate(pollNamespace, pollPeriodMs)
```

---

## Expected console output

**Server:**

```
[SERVER] Starting — advertised host: opc-server, port: 4840, 2 namespace(s) configured
[SERVER] Namespace 'urn:example:factory:floor1' registered (folder: Floor1, 4 tag(s))
[SERVER] Namespace 'urn:example:factory:floor2' registered (folder: Floor2, 3 tag(s))
[SERVER] Ready — opc.tcp://opc-server:4840/
[SERVER][Floor1] Simulation started (4 tags)
[SERVER][Floor2] Simulation started (3 tags)
[SERVER][Floor1] Temperature          = 47.31
[SERVER][Floor2] MotorSpeed           = 1823.5
[SERVER][Floor1] PartCounter          = 1
```

**Client:**

```
[CLIENT] Config: 1 poll namespace(s), 1 subscribe namespace(s)
[CLIENT] Connecting to opc.tcp://opc-server:4840/
[CLIENT] Connected on attempt 1
[CLIENT] Namespace 'urn:example:factory:floor1' → ns=2
[CLIENT] Namespace 'urn:example:factory:floor2' → ns=3
[CLIENT][SUB] Monitoring: [Floor2/Humidity, Floor2/MotorSpeed, Floor2/AlarmActive]
[CLIENT][POLL] Floor1    |  Temperature=47.31        PartCounter=3
[CLIENT][SUB]  Floor2/Humidity              = 68.4         (quality: Good, serverTime: …)
[CLIENT][SUB]  Floor2/MotorSpeed            = 1823.5       (quality: Good, serverTime: …)
[CLIENT][POLL] Floor1    |  Temperature=52.84        PartCounter=7
```

---

---

## NATS publishing

Every subscribed tag is published to NATS automatically. The subject is derived from the folder name and tag ID:

```
opcua.<folderName.toLowerCase()>.<tagId>
```

Tag IDs containing special characters (dots, commas, spaces) are sanitized — any character outside `[a-zA-Z0-9_-]` is replaced with `_`. For example, `line_1.DB138.130,R` under `Floor1` → `opcua.floor1.line_1_DB138_130_R`.

Use `tagMappings` in `opcua-client-config.json` to override the subject for a specific tag.

### Current subjects

| Subject | Payload | Rate | Notes |
|---------|---------|------|-------|
| `opcua.floor1.Temperature` | `{"value":39.2}` | 1 s | Float |
| `opcua.floor1.Pressure` | `{"value":101.4}` | 2 s | Float |
| `opcua.floor1.PartCounter` | `{"value":402}` | 500 ms | Int64 counter |
| `opcua.floor1.MachineRunning` | `{"value":true}` | 5 s | Boolean |
| `opcua.floor2.Humidity` | `{"value":74.4}` | 3 s | Float |
| `opcua.floor2.MotorSpeed` | `{"value":2211.6}` | 1 s | Float |
| `opcua.floor2.AlarmActive` | `{"value":false}` | 7 s | Boolean |
| `opcua.floor2.PartCounter` | `{"value":402}` | 500 ms | Int64 counter |
| `opcua.floor2.Throughput` | `{"value":321.8}` | 2 s | Float |

### Downstream consumers

| Consumer | Subject(s) | What it does |
|---|---|---|
| MES backend `NatsOpcCounterService` | `opcua.floor1.PartCounter` | Adds delta to Wired Matts order produced count |
| `nats-historian` (mes-dwh) | `opcua.floor1.*`, `opcua.floor2.*` | Stores all tag values to ClickHouse `historian.opcua_tags` |

### Test commands

```bash
# Watch all floor1 tags in real time (Ctrl-C to stop)
docker exec -it nats-box nats sub 'opcua.floor1.*'

# Watch a specific tag
docker exec -it nats-box nats sub opcua.floor1.Temperature

# Check NATS monitoring UI
open http://localhost:8222
```

Expected output in nats-box:
```
[#1] Received on "opcua.floor1.Temperature"
{"value":47.31}

[#2] Received on "opcua.floor1.PartCounter"
{"value":3}

[#3] Received on "opcua.floor2.AlarmActive"
{"value":false}
```

### NATS_URL configuration

| Context | Value |
|---------|-------|
| Docker Compose (default) | `nats://host.docker.internal:4222` (via `extra_hosts: host-gateway`) |
| Local dev (`mvn quarkus:dev`) | `nats://localhost:4222` (default, no env var needed) |
| Custom | Set `NATS_URL=nats://yourhost:4222` |

---

## Connecting an external OPC UA browser

The server listens on `opc.tcp://localhost:4840/` (Docker port-forwarded).
Connect with any OPC UA browser (UaExpert, Prosys OPC UA Browser, etc.):

```
Endpoint:        opc.tcp://localhost:4840/
Security:        None
Authentication:  Anonymous
```

Browse: `Objects → Floor1 → Temperature` (and similar nodes for Floor2).

---

## Versions

| Component | Version |
|-----------|---------|
| Java | 24 |
| Eclipse Milo | 1.1.1 |
| Quarkus | 3.27.0 (LTS) |
| Maven | 3.9.x |
| Base image | eclipse-temurin:24-jre |
