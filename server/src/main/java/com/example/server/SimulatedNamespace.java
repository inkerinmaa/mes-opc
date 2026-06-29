package com.example.server;

import org.eclipse.milo.opcua.sdk.core.AccessLevel;
import org.eclipse.milo.opcua.sdk.core.Reference;
import org.eclipse.milo.opcua.sdk.server.Lifecycle;
import org.eclipse.milo.opcua.sdk.server.ManagedNamespaceWithLifecycle;
import org.eclipse.milo.opcua.sdk.server.OpcUaServer;
import org.eclipse.milo.opcua.sdk.server.items.DataItem;
import org.eclipse.milo.opcua.sdk.server.items.MonitoredItem;
import org.eclipse.milo.opcua.sdk.server.nodes.UaFolderNode;
import org.eclipse.milo.opcua.sdk.server.nodes.UaVariableNode;
import org.eclipse.milo.opcua.sdk.server.util.SubscriptionModel;
import org.eclipse.milo.opcua.stack.core.NodeIds;
import org.eclipse.milo.opcua.stack.core.types.builtin.*;

import java.util.List;
import java.util.Random;

/**
 * Custom OPC UA namespace that exposes four simulated variables:
 *
 *   ns=2;s=SimData/Temperature  Float   — random 20–30 °C,  updates every 1 s
 *   ns=2;s=SimData/Counter      Int64   — increments by 1 every 1 s
 *   ns=2;s=SimData/Pressure     Float   — ~101.3 ± 2.5 hPa, updates every 1 s
 *   ns=2;s=SimData/Status       Boolean — toggles every 5 s
 *
 * Milo 1.x key differences vs 0.6.x:
 *   - Extend ManagedNamespaceWithLifecycle (not ManagedNamespace)
 *   - Use getLifecycleManager() for startup tasks and child lifecycles
 *   - SubscriptionModel handles client subscription callbacks (onDataItemsCreated etc.)
 *   - AccessLevel.toValue(Set) instead of getMask(); NodeIds instead of Identifiers
 *   - Reference is in sdk.core not stack.core
 */
public class SimulatedNamespace extends ManagedNamespaceWithLifecycle {

    public static final String NAMESPACE_URI = "urn:eclipse:milo:simulation:namespace";

    private final SubscriptionModel subscriptionModel;

    private UaVariableNode temperatureNode;
    private UaVariableNode counterNode;
    private UaVariableNode pressureNode;
    private UaVariableNode statusNode;

    private volatile boolean running = false;
    private Thread updateThread;
    private long counter = 0L;
    private long tick = 0L;

    public SimulatedNamespace(OpcUaServer server) {
        super(server, NAMESPACE_URI);

        // SubscriptionModel handles sampling/publishing for subscribed data items
        subscriptionModel = new SubscriptionModel(server, this);
        getLifecycleManager().addLifecycle(subscriptionModel);

        // Node creation deferred to startup so namespace index is available
        getLifecycleManager().addStartupTask(this::createAndAddNodes);

        // Simulation thread lifecycle
        getLifecycleManager().addLifecycle(new Lifecycle() {
            @Override
            public void startup() {
                running = true;
                updateThread = new Thread(SimulatedNamespace.this::simulationLoop, "milo-sim-thread");
                updateThread.setDaemon(true);
                updateThread.start();
                System.out.println("[SERVER] SimulatedNamespace started — nodes registered, simulation running.");
            }

            @Override
            public void shutdown() {
                running = false;
                if (updateThread != null) updateThread.interrupt();
                System.out.println("[SERVER] SimulatedNamespace stopped.");
            }
        });
    }

    // ── Node creation ──────────────────────────────────────────────────────

    private void createAndAddNodes() {
        UaFolderNode folder = createFolder("SimData", "Simulated Data");

        temperatureNode = createVariable(folder, "Temperature", "Temperature (°C)",
                NodeIds.Float, new Variant(20.0f));

        counterNode = createVariable(folder, "Counter", "Event Counter",
                NodeIds.Int64, new Variant(0L));

        pressureNode = createVariable(folder, "Pressure", "Pressure (hPa)",
                NodeIds.Float, new Variant(101.3f));

        statusNode = createVariable(folder, "Status", "Device Status",
                NodeIds.Boolean, new Variant(false));
    }

    private UaFolderNode createFolder(String id, String displayName) {
        UaFolderNode folder = new UaFolderNode(
                getNodeContext(),
                newNodeId(id),
                newQualifiedName(id),
                LocalizedText.english(displayName)
        );
        getNodeManager().addNode(folder);
        // Link folder into the Objects hierarchy so OPC UA browsers can discover it
        folder.addReference(new Reference(
                folder.getNodeId(),
                NodeIds.Organizes,
                NodeIds.ObjectsFolder.expanded(),
                false
        ));
        return folder;
    }

    private UaVariableNode createVariable(UaFolderNode parent,
                                          String name,
                                          String displayName,
                                          NodeId dataType,
                                          Variant initialValue) {
        UaVariableNode node = new UaVariableNode.UaVariableNodeBuilder(getNodeContext())
                .setNodeId(newNodeId("SimData/" + name))
                .setAccessLevel(AccessLevel.toValue(AccessLevel.READ_ONLY))
                .setUserAccessLevel(AccessLevel.toValue(AccessLevel.READ_ONLY))
                .setBrowseName(newQualifiedName(name))
                .setDisplayName(LocalizedText.english(displayName))
                .setDataType(dataType)
                .setTypeDefinition(NodeIds.BaseDataVariableType)
                .build();

        node.setValue(new DataValue(new Variant(initialValue.value()), StatusCode.GOOD, DateTime.now()));
        getNodeManager().addNode(node);
        parent.addOrganizes(node);
        return node;
    }

    // ── Subscription callbacks (delegate to SubscriptionModel) ────────────

    @Override
    public void onDataItemsCreated(List<DataItem> dataItems) {
        subscriptionModel.onDataItemsCreated(dataItems);
    }

    @Override
    public void onDataItemsModified(List<DataItem> dataItems) {
        subscriptionModel.onDataItemsModified(dataItems);
    }

    @Override
    public void onDataItemsDeleted(List<DataItem> dataItems) {
        subscriptionModel.onDataItemsDeleted(dataItems);
    }

    @Override
    public void onMonitoringModeChanged(List<MonitoredItem> monitoredItems) {
        subscriptionModel.onMonitoringModeChanged(monitoredItems);
    }

    // ── Simulation loop ────────────────────────────────────────────────────

    private void simulationLoop() {
        Random rng = new Random();
        while (running) {
            try {
                Thread.sleep(1_000);

                tick++;
                counter++;
                DateTime now = DateTime.now();

                float temp = 20.0f + rng.nextFloat() * 10.0f;
                temperatureNode.setValue(new DataValue(new Variant(temp), StatusCode.GOOD, now));

                counterNode.setValue(new DataValue(new Variant(counter), StatusCode.GOOD, now));

                float pressure = 101.3f + (rng.nextFloat() - 0.5f) * 5.0f;
                pressureNode.setValue(new DataValue(new Variant(pressure), StatusCode.GOOD, now));

                boolean status = (tick / 5) % 2 == 0;
                statusNode.setValue(new DataValue(new Variant(status), StatusCode.GOOD, now));

                System.out.printf(
                        "[SERVER] tick=%-4d  Temp=%.2f°C  Counter=%d  Pressure=%.2f hPa  Status=%b%n",
                        tick, temp, counter, pressure, status
                );

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }
}
