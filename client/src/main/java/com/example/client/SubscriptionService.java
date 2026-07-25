package com.example.client;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import io.quarkus.runtime.ShutdownEvent;

import org.eclipse.milo.opcua.sdk.client.OpcUaClient;
import org.eclipse.milo.opcua.sdk.client.subscriptions.MonitoredItemSynchronizationException;
import org.eclipse.milo.opcua.sdk.client.subscriptions.OpcUaMonitoredItem;
import org.eclipse.milo.opcua.sdk.client.subscriptions.OpcUaSubscription;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * SUBSCRIPTION strategy — the server pushes value changes to the client.
 *
 * Driven by {@code subscribeNamespaces} in {@code opcua-client-config.json}.
 * One {@link OpcUaSubscription} is created for all namespaces; each tag gets its
 * own {@link OpcUaMonitoredItem} with an individual data-change callback.
 *
 * Tags can be listed explicitly or left empty for auto-discovery via OPC UA Browse
 * ({@link NodeBrowser}).
 *
 * Milo 1.x subscription API (completely redesigned vs 0.6.x):
 * <pre>
 *   OpcUaSubscription sub = new OpcUaSubscription(client);
 *   sub.create();                                    // register on server
 *
 *   OpcUaMonitoredItem item = OpcUaMonitoredItem.newDataItem(nodeId);
 *   item.setDataValueListener((item, value) -> ...); // per-item callback
 *   sub.addMonitoredItem(item);
 *   sub.synchronizeMonitoredItems();                 // push item list to server
 * </pre>
 *
 * Compared to polling:
 *   + No polling overhead — server sends only changed values
 *   + Lower latency for rapid changes
 *   + Scales to many nodes with negligible extra client load
 *   - Slightly more state to manage (subscription lifecycle)
 */
@ApplicationScoped
public class SubscriptionService {

    @Inject OpcUaClientBean    clientBean;
    @Inject NodeBrowser        browser;
    @Inject NatsPublisherService natsPublisher;

    private OpcUaSubscription subscription;
    // label (folderName/tagId) → NATS subject, built from tagMappings in config
    private final Map<String, String> labelToSubject = new HashMap<>();

    /** Called by {@link OpcUaClientBean} once the connection and namespace indices are ready. */
    public void setupSubscriptions() {
        ClientConfig config = clientBean.getConfig();
        if (config.subscribeNamespaces == null || config.subscribeNamespaces.isEmpty()) {
            System.out.println("[CLIENT][SUB] No subscribe namespaces configured.");
            return;
        }

        try {
            OpcUaClient client = clientBean.getClient();

            subscription = new OpcUaSubscription(client);
            subscription.create();
            System.out.println("[CLIENT][SUB] Subscription created");

            List<String> allLabels = new ArrayList<>();
            labelToSubject.clear();

            for (ClientConfig.NamespaceConfig nsConfig : config.subscribeNamespaces) {
                // Build label → NATS subject map from tagMappings in config
                if (nsConfig.tagMappings != null) {
                    for (var entry : nsConfig.tagMappings.entrySet()) {
                        labelToSubject.put(tagLabel(nsConfig.folderName, entry.getKey()), entry.getValue());
                    }
                }

                // Use explicit tag list, or discover via OPC UA Browse when list is empty
                List<String> tags = (nsConfig.tags != null && !nsConfig.tags.isEmpty())
                        ? nsConfig.tags
                        : browser.browseTagIds(nsConfig.uri, nsConfig.folderName);

                System.out.printf("[CLIENT][SUB] Subscribing '%s': %d tag(s)%n",
                        nsConfig.folderName, tags.size());

                for (String tagId : tags) {
                    String identifier = tagLabel(nsConfig.folderName, tagId);
                    var nodeId = clientBean.nodeId(nsConfig.uri, identifier);
                    var item   = OpcUaMonitoredItem.newDataItem(nodeId);
                    item.setDataValueListener((mi, v) -> onValueChange(identifier, v));
                    subscription.addMonitoredItem(item);
                    allLabels.add(identifier);
                }
            }

            // Push the complete monitored-item list to the server
            try {
                subscription.synchronizeMonitoredItems();
                System.out.println("[CLIENT][SUB] Monitoring: " + allLabels);
            } catch (MonitoredItemSynchronizationException e) {
                e.getCreateResults().forEach(r ->
                        System.err.printf("[CLIENT][SUB] Failed to create item %s: service=%s op=%s%n",
                                r.monitoredItem().getReadValueId().getNodeId(),
                                r.serviceResult(), r.operationResult())
                );
            }

        } catch (Exception e) {
            System.err.println("[CLIENT][SUB] Setup failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    void onStop(@Observes ShutdownEvent ev) {
        if (subscription != null) {
            try { subscription.delete(); } catch (Exception ignored) {}
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /** Mirrors PollingService: omit folder prefix when folderName is blank. */
    private static String tagLabel(String folderName, String tagId) {
        return (folderName == null || folderName.isBlank())
                ? tagId
                : folderName + "/" + tagId;
    }

    // ── Callback ──────────────────────────────────────────────────────────

    private void onValueChange(String label, DataValue value) {
        try {
            Object raw = value.value().value();   // Milo 1.x: Variant.value()
            System.out.printf("[CLIENT][SUB] %-30s = %-12s  (quality: %s, serverTime: %s)%n",
                    label, raw, value.statusCode(), value.serverTime());

            String subject = labelToSubject.get(label);
            if (subject == null || raw == null) return;

            String payload = "{\"value\":" + toJsonValue(raw) + "}";
            natsPublisher.publish(subject, payload);
            System.out.printf("[CLIENT][NATS] → %s  payload=%s%n", subject, payload);
        } catch (Exception e) {
            System.err.printf("[CLIENT][SUB] Error in callback for %s: %s%n", label, e.getMessage());
        }
    }

    /** Converts an OPC UA value to a JSON token (number, true/false, or "quoted string"). */
    private static String toJsonValue(Object raw) {
        if (raw instanceof Number)  return raw.toString();
        if (raw instanceof Boolean) return raw.toString();
        // String, DateTime, etc. — quote and escape
        return "\"" + raw.toString().replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
