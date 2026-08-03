package com.example.client;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.enumerated.TimestampsToReturn;

/**
 * POLLING strategy — reads tags from each poll-namespace on a per-namespace timer.
 *
 * Driven by {@code pollNamespaces} in {@code opcua-client-config.json}:
 * <ul>
 *   <li>Each namespace has its own {@code pollPeriodMs}.</li>
 *   <li>Tags can be listed explicitly or left empty for auto-discovery via
 *       OPC UA Browse ({@link NodeBrowser}).</li>
 * </ul>
 *
 * Uses a {@link ScheduledExecutorService} rather than Quarkus {@code @Scheduled}
 * so that different namespaces can run at different intervals independently.
 *
 * Compared to subscriptions:
 * <ul>
 *   <li>+ Simple and stateless — no subscription lifecycle to manage</li>
 *   <li>+ Works even if the server does not support subscriptions</li>
 *   <li>- Load is proportional to poll rate, not to how often values actually change</li>
 *   <li>- Higher latency: you only see a change at the next poll tick</li>
 * </ul>
 */
@ApplicationScoped
public class PollingService {

    @Inject
    OpcUaClientBean clientBean;

    @Inject
    NodeBrowser browser;

    private ScheduledExecutorService scheduler;

    /** Called by {@link OpcUaClientBean} once the connection and namespace indices are ready. */
    public void startPolling() {
        ClientConfig config = clientBean.getConfig();
        if (config.pollNamespaces == null || config.pollNamespaces.isEmpty()) {
            System.out.println("[CLIENT][POLL] No poll namespaces configured.");
            return;
        }

        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "opcua-poll-thread");
            t.setDaemon(true);
            return t;
        });

        for (ClientConfig.NamespaceConfig nsConfig : config.pollNamespaces) {
            if (nsConfig.tags != null && !nsConfig.tags.isEmpty()) {
                // Explicit tag list — build NodeIds from strings at startup, poll with them
                System.out.printf("[CLIENT][POLL] Scheduling '%s': %d tag(s) (explicit) every %d ms%n",
                        nsConfig.folderName, nsConfig.tags.size(), nsConfig.pollPeriodMs);
                List<String> tags = nsConfig.tags;
                scheduler.scheduleAtFixedRate(
                        () -> pollExplicit(nsConfig, tags),
                        nsConfig.pollPeriodMs, nsConfig.pollPeriodMs, TimeUnit.MILLISECONDS);
            } else {
                // Auto-discover via OPC UA Browse — resolve NodeIds once, reuse each poll tick
                // folderName supports "i=6" for numeric folder NodeIds
                List<NodeId> discovered = browser.browseNodeIds(nsConfig.uri, nsConfig.folderName);
                System.out.printf("[CLIENT][POLL] Scheduling '%s': %d tag(s) (discovered) every %d ms%n",
                        nsConfig.folderName, discovered.size(), nsConfig.pollPeriodMs);
                scheduler.scheduleAtFixedRate(
                        () -> pollDiscovered(nsConfig, discovered),
                        nsConfig.pollPeriodMs, nsConfig.pollPeriodMs, TimeUnit.MILLISECONDS);
            }
        }
    }

    public void stopPolling() {
        if (scheduler != null) scheduler.shutdownNow();
    }

    // ── Per-namespace poll ticks ───────────────────────────────────────────

    private void pollExplicit(ClientConfig.NamespaceConfig nsConfig, List<String> tags) {
        var client = clientBean.getClient();
        var sb = new StringBuilder();
        sb.append(String.format("[CLIENT][POLL] %-8s |", nsConfig.folderName));
        for (String tagId : tags) {
            String identifier = nsConfig.folderName == null || nsConfig.folderName.isBlank()
                    ? tagId
                    : nsConfig.folderName + "/" + tagId;
            var nodeId = clientBean.nodeId(nsConfig.uri, identifier);
            try {
                var value = client.readValue(0.0, TimestampsToReturn.Both, nodeId);
                sb.append(String.format("  %s=%-12s", tagId, value.value().value()));
            } catch (Exception e) {
                sb.append(String.format("  %s=ERROR", tagId));
            }
        }
        System.out.println(sb);
    }

    private void pollDiscovered(ClientConfig.NamespaceConfig nsConfig, List<NodeId> nodeIds) {
        var client = clientBean.getClient();
        var sb = new StringBuilder();
        sb.append(String.format("[CLIENT][POLL] %-8s |", nsConfig.folderName));
        for (NodeId nodeId : nodeIds) {
            String label = nodeId.getIdentifier().toString();
            try {
                var value = client.readValue(0.0, TimestampsToReturn.Both, nodeId);
                sb.append(String.format("  %s=%-12s", label, value.value().value()));
            } catch (Exception e) {
                sb.append(String.format("  %s=ERROR", label));
            }
        }
        System.out.println(sb);
    }
}
