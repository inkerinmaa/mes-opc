# eclipse-milo — Agent Reference

## Project overview
OPC UA server and client built with Eclipse Milo 1.1.1 and Quarkus 3.27. The server exposes simulated factory floor sensor data across two OPC UA namespaces. The client polls some tags and subscribes to others. Fully config-driven — tags and namespaces are defined in JSON, not code.

## How to run
```bash
cd ~/projects/eclipse-milo
docker compose up --build
```
- Server starts and logs `[SERVER] Ready — opc.tcp://opc-server:4840/`
- Client connects, logs polled reads every 2 s and pushed subscription updates

## How to run in dev mode (hot reload)
```bash
# Terminal 1
cd ~/projects/eclipse-milo/server && mvn quarkus:dev

# Terminal 2
cd ~/projects/eclipse-milo/client && mvn quarkus:dev
```

## Key files
| File | Purpose |
|------|---------|
| `docker-compose.yml` | Orchestrates server + client on `opcua-net` |
| `server/src/main/resources/opcua-server-config.json` | Namespace + tag definitions (add sensors here) |
| `client/src/main/resources/opcua-client-config.json` | Which tags to poll, which to subscribe |
| `server/…/DynamicNamespace.java` | Reads config, registers OPC UA nodes, runs simulation |
| `client/…/PollingService.java` | `@Scheduled` read loop |
| `client/…/SubscriptionService.java` | Server-push subscription manager |
| `client/…/NodeBrowser.java` | OPC UA Browse — auto-discovers nodes in a folder |

## OPC UA namespaces
| Namespace | Folder | Tags |
|-----------|--------|------|
| `urn:example:factory:floor1` | Floor1 | Temperature, Pressure, PartCounter, MachineRunning |
| `urn:example:factory:floor2` | Floor2 | Humidity, MotorSpeed, AlarmActive |

## Conventions
- Sensors are defined in JSON config, not in Java — edit `opcua-server-config.json` to add/remove tags
- Polling vs subscriptions: polling is time-based reads; subscriptions are server-push on value change
- `DynamicNamespace.java` is the active namespace implementation; `SimulatedNamespace.java` is legacy/reference only
- Quarkus `@Scheduled`, `@Inject`, `@Observes StartupEvent` are used instead of manual threading/wiring

## Do not
- Hardcode tag IDs or namespace URIs in Java — always use config JSON
- Connect to the server before it has logged "Ready" — the client retries, but Docker `depends_on` handles this
- Modify `SimulatedNamespace.java` — it is legacy; all changes go in `DynamicNamespace.java`
- Skip `--build` flag in `docker compose up` after Java source changes
