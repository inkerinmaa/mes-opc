# Project: eclipse-milo (OPC UA Server + Client)

## Stack
- Java 24 + Maven
- Quarkus 3.27.0 LTS (container-first runtime)
- Eclipse Milo 1.1.1 (OPC UA SDK)
- NATS Java client 2.20.4 (jnats) — publishes counter to DWH NATS
- Docker Compose (two services on `opcua-net` network)
- Two Maven modules: `server/` and `client/`

## What it does
OPC UA industrial automation demo. The server simulates factory floor sensors (temperature, pressure, part counter, humidity, motor speed, alarms). The client connects and reads data two ways:
- **Polling** — reads tags on a timer (every 2 s by default)
- **Subscriptions** — server pushes changed values automatically

The client also publishes `Floor1/PartCounter` (Int64, increments every 500 ms) to the
DWH NATS broker on subject `opcua.floor1.PartCounter` as `{"counter":42}`.

All tags, namespaces, and update intervals are config-driven — no code changes needed to add/modify nodes.

## Directory structure
```
eclipse-milo/
├── docker-compose.yml
├── server/
│   ├── pom.xml
│   ├── src/main/resources/
│   │   ├── application.properties      # Quarkus config
│   │   └── opcua-server-config.json    # Namespaces + tags + simulation params
│   └── src/main/java/com/example/server/
│       ├── Main.java                   # @QuarkusMain entry point
│       ├── OpcUaServerBean.java        # Lifecycle, reads config, builds server
│       ├── DynamicNamespace.java       # Config-driven namespace + value simulation
│       └── ServerConfig.java          # JSON model
└── client/
    ├── pom.xml
    ├── src/main/resources/
    │   ├── application.properties
    │   └── opcua-client-config.json    # Poll + subscribe configs
    └── src/main/java/com/example/client/
        ├── Main.java
        ├── OpcUaClientBean.java        # Connection, namespace index resolution
        ├── ClientConfig.java           # JSON model
        ├── NodeBrowser.java            # OPC UA Browse for auto-discovery
        ├── PollingService.java         # Timer-based read loop
        ├── SubscriptionService.java    # Server-push subscription + NATS publish for PartCounter
        └── NatsPublisherService.java   # NATS connection lifecycle + publish()
```

## Commands

### Prerequisites
Start the DWH NATS broker before eclipse-milo so the client can connect:
```bash
cd ~/projects/dwh/nats && docker compose up -d
```

### Run with Docker (recommended)
```bash
cd ~/projects/eclipse-milo
docker compose up --build
# Server: opc.tcp://opc-server:4840/  (internal)
# Client: connects to server + publishes PartCounter to NATS
```

### Run individually (development)
```bash
# Server (separate terminal)
cd server && mvn quarkus:dev    # hot reload, port 4840

# Client (separate terminal)
cd client && mvn quarkus:dev    # connects to localhost:4840, NATS_URL=nats://localhost:4222 by default
```

### Build JARs
```bash
cd server && mvn package -DskipTests
cd client && mvn package -DskipTests
```

### Test NATS output
```bash
# Watch counter values arriving in real time (Ctrl-C to stop)
docker exec -it nats-box nats sub opcua.floor1.PartCounter

# NATS monitoring dashboard
open http://localhost:8222

# Publish a test counter message manually
docker exec -it nats-box nats pub opcua.floor1.PartCounter '{"counter":42}'
```

## Configuration

### Server (`opcua-server-config.json`)
```json
{
  "namespaces": [
    {
      "uri": "urn:example:factory:floor1",
      "folderName": "Floor1",
      "tags": [
        { "id": "Temperature", "dataType": "Float", "minValue": 20.0, "maxValue": 80.0, "updatePeriodMs": 1000 }
      ]
    }
  ]
}
```

### Client (`opcua-client-config.json`)
```json
{
  "pollNamespaces":      [{ "uri": "...", "folderName": "Floor1", "pollPeriodMs": 2000, "tags": ["Temperature", "PartCounter"] }],
  "subscribeNamespaces": [
    { "uri": "...", "folderName": "Floor1", "tags": ["PartCounter"] },
    { "uri": "...", "folderName": "Floor2", "tags": ["Humidity", "MotorSpeed", "AlarmActive"] }
  ]
}
```

`Floor1/PartCounter` appears in both lists: polled every 2 s for visibility, and subscribed for server-push NATS publishing on every change.

### NATS_URL
| Context | Value |
|---------|-------|
| Docker Compose | `nats://host.docker.internal:4222` (set in docker-compose.yml via `extra_hosts: host-gateway`) |
| Local dev | `nats://localhost:4222` (default — no env var needed) |
| Custom | `NATS_URL=nats://yourhost:4222` |

## OPC UA namespaces
| Namespace URI | Folder | Tags |
|--------------|--------|------|
| `urn:example:factory:floor1` | Floor1 | Temperature, Pressure, PartCounter, MachineRunning |
| `urn:example:factory:floor2` | Floor2 | Humidity, MotorSpeed, AlarmActive |

## NATS publishing
| Subject | Source tag | Payload | Rate |
|---------|-----------|---------|------|
| `opcua.floor1.PartCounter` | Floor1/PartCounter | `{"counter":42}` | 500 ms |

## External client access
Connect any OPC UA client (UaExpert, Prosys OPC UA Browser) to:
`opc.tcp://localhost:4840/`
No authentication or security is configured in this demo.

## Rules
- Add new sensors by editing JSON config only — never hardcode tags in Java
- `DynamicNamespace.java` handles all simulation logic; `SimulatedNamespace.java` is the legacy hardcoded reference — do not use it
- The client retries connection on startup — server must be fully started first (Docker handles ordering via `depends_on`)
- Use `mvn quarkus:dev` for development (live reload); use `docker compose up --build` for integration testing
- NATS publishing is in `SubscriptionService.onValueChange` — detect label `"Floor1/PartCounter"` and call `natsPublisher.publish()`
- To publish additional tags to NATS, add the same pattern in `onValueChange`
