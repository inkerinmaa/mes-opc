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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Config-driven OPC UA namespace.
 *
 * Reads a {@link ServerConfig.NamespaceConfig} and:
 *   - Creates a folder node under Objects
 *   - Creates one variable node per tag
 *   - Schedules a simulation update per tag at its own updatePeriodMs
 *
 * Supported dataTypes: Float, Double, Int64, Int32, Boolean
 *
 * Example node IDs created from folderName="Floor1", tag.id="Temperature":
 *   ns=2;s=Floor1/Temperature
 */
public class DynamicNamespace extends ManagedNamespaceWithLifecycle {

    private final ServerConfig.NamespaceConfig config;
    private final SubscriptionModel subscriptionModel;

    private final Map<String, UaVariableNode> nodes = new HashMap<>();
    private final Map<String, Long>    counters = new HashMap<>();
    private final Map<String, Boolean> toggles  = new HashMap<>();
    private final Random rng = new Random();

    private ScheduledExecutorService scheduler;

    public DynamicNamespace(OpcUaServer server, ServerConfig.NamespaceConfig config) {
        super(server, config.uri);
        this.config = config;

        subscriptionModel = new SubscriptionModel(server, this);
        getLifecycleManager().addLifecycle(subscriptionModel);
        getLifecycleManager().addStartupTask(this::createAndAddNodes);
        getLifecycleManager().addLifecycle(new Lifecycle() {
            @Override public void startup()  { startSimulation(); }
            @Override public void shutdown() {
                if (scheduler != null) scheduler.shutdownNow();
                System.out.printf("[SERVER][%s] Stopped%n", config.folderName);
            }
        });
    }

    // ── Node creation ──────────────────────────────────────────────────────

    private void createAndAddNodes() {
        UaFolderNode folder = new UaFolderNode(
                getNodeContext(),
                newNodeId(config.folderName),
                newQualifiedName(config.folderName),
                LocalizedText.english(config.folderName)
        );
        getNodeManager().addNode(folder);
        folder.addReference(new Reference(
                folder.getNodeId(), NodeIds.Organizes,
                NodeIds.ObjectsFolder.expanded(), false
        ));

        for (ServerConfig.TagConfig tag : config.tags) {
            UaVariableNode node = new UaVariableNode.UaVariableNodeBuilder(getNodeContext())
                    .setNodeId(newNodeId(config.folderName + "/" + tag.id))
                    .setAccessLevel(AccessLevel.toValue(AccessLevel.READ_ONLY))
                    .setUserAccessLevel(AccessLevel.toValue(AccessLevel.READ_ONLY))
                    .setBrowseName(newQualifiedName(tag.id))
                    .setDisplayName(LocalizedText.english(tag.displayName))
                    .setDataType(dataTypeNodeId(tag.dataType))
                    .setTypeDefinition(NodeIds.BaseDataVariableType)
                    .build();

            node.setValue(new DataValue(initialVariant(tag), StatusCode.GOOD, DateTime.now()));
            getNodeManager().addNode(node);
            folder.addOrganizes(node);
            nodes.put(tag.id, node);
        }

        System.out.printf("[SERVER][%s] Created %d nodes (namespace: %s)%n",
                config.folderName, config.tags.size(), config.uri);
    }

    // ── Simulation ─────────────────────────────────────────────────────────

    private void startSimulation() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "milo-sim-" + config.folderName);
            t.setDaemon(true);
            return t;
        });

        for (ServerConfig.TagConfig tag : config.tags) {
            scheduler.scheduleAtFixedRate(
                    () -> updateNode(tag),
                    tag.updatePeriodMs,   // initial delay = one period
                    tag.updatePeriodMs,
                    TimeUnit.MILLISECONDS
            );
        }
        System.out.printf("[SERVER][%s] Simulation started (%d tags)%n",
                config.folderName, config.tags.size());
    }

    private void updateNode(ServerConfig.TagConfig tag) {
        UaVariableNode node = nodes.get(tag.id);
        if (node == null) return;
        Variant v = nextVariant(tag);
        node.setValue(new DataValue(v, StatusCode.GOOD, DateTime.now()));
        System.out.printf("[SERVER][%s] %-20s = %s%n", config.folderName, tag.id, v.value());
    }

    // ── Value generation ───────────────────────────────────────────────────

    private Variant initialVariant(ServerConfig.TagConfig tag) {
        return switch (tag.dataType) {
            case "Double"  -> new Variant(tag.minValue != null ? tag.minValue : 0.0);
            case "Int64"   -> new Variant(tag.minValue != null ? tag.minValue.longValue() : 0L);
            case "Int32"   -> new Variant(tag.minValue != null ? tag.minValue.intValue()  : 0);
            case "Boolean" -> new Variant(false);
            default        -> new Variant(tag.minValue != null ? tag.minValue.floatValue() : 0.0f);
        };
    }

    private Variant nextVariant(ServerConfig.TagConfig tag) {
        return switch (tag.dataType) {
            case "Float" -> {
                float lo = tag.minValue != null ? tag.minValue.floatValue() : 0f;
                float hi = tag.maxValue != null ? tag.maxValue.floatValue() : 100f;
                yield new Variant(lo + rng.nextFloat() * (hi - lo));
            }
            case "Double" -> {
                double lo = tag.minValue != null ? tag.minValue : 0.0;
                double hi = tag.maxValue != null ? tag.maxValue : 100.0;
                yield new Variant(lo + rng.nextDouble() * (hi - lo));
            }
            case "Int64" -> {
                long max = tag.maxValue != null ? tag.maxValue.longValue() : Long.MAX_VALUE;
                long val = counters.merge(tag.id, 1L, Long::sum);
                if (val > max) { counters.put(tag.id, 0L); val = 0L; }
                yield new Variant(val);
            }
            case "Int32" -> {
                int max = tag.maxValue != null ? tag.maxValue.intValue() : Integer.MAX_VALUE;
                long val = counters.merge(tag.id, 1L, Long::sum);
                if (val > max) { counters.put(tag.id, 0L); val = 0L; }
                yield new Variant((int) val);
            }
            case "Boolean" -> {
                boolean next = !toggles.getOrDefault(tag.id, false);
                toggles.put(tag.id, next);
                yield new Variant(next);
            }
            default -> new Variant(0.0f);
        };
    }

    // ── OPC UA data type mapping ───────────────────────────────────────────

    private static NodeId dataTypeNodeId(String dataType) {
        return switch (dataType) {
            case "Double"  -> NodeIds.Double;
            case "Int64"   -> NodeIds.Int64;
            case "Int32"   -> NodeIds.Int32;
            case "Boolean" -> NodeIds.Boolean;
            case "String"  -> NodeIds.String;
            default        -> NodeIds.Float;
        };
    }

    // ── Subscription callbacks ─────────────────────────────────────────────

    @Override public void onDataItemsCreated(List<DataItem> items)        { subscriptionModel.onDataItemsCreated(items); }
    @Override public void onDataItemsModified(List<DataItem> items)       { subscriptionModel.onDataItemsModified(items); }
    @Override public void onDataItemsDeleted(List<DataItem> items)        { subscriptionModel.onDataItemsDeleted(items); }
    @Override public void onMonitoringModeChanged(List<MonitoredItem> items) { subscriptionModel.onMonitoringModeChanged(items); }
}
