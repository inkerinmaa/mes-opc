package com.example.client;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.milo.opcua.stack.core.types.enumerated.TimestampsToReturn;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

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

    @Inject OpcUaClientBean clientBean;
    @Inject NodeBrowser     browser;

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
            // Use explicit tag list, or discover via OPC UA Browse when list is empty
            List<String> tags = (nsConfig.tags != null && !nsConfig.tags.isEmpty())
                    ? nsConfig.tags
                    : browser.browseTagIds(nsConfig.uri, nsConfig.folderName);

            System.out.printf("[CLIENT][POLL] Scheduling '%s': %d tag(s) every %d ms%n",
                    nsConfig.folderName, tags.size(), nsConfig.pollPeriodMs);

            scheduler.scheduleAtFixedRate(
                    () -> pollNamespace(nsConfig, tags),
                    nsConfig.pollPeriodMs,    // initial delay = one period
                    nsConfig.pollPeriodMs,
                    TimeUnit.MILLISECONDS
            );
        }
    }

    public void stopPolling() {
        if (scheduler != null) scheduler.shutdownNow();
    }

    // ── Per-namespace poll tick ────────────────────────────────────────────

    private void pollNamespace(ClientConfig.NamespaceConfig nsConfig, List<String> tags) {
        var client = clientBean.getClient();
        var sb = new StringBuilder();
        sb.append(String.format("[CLIENT][POLL] %-8s |", nsConfig.folderName));

        for (String tagId : tags) {
            var nodeId = clientBean.nodeId(nsConfig.uri, nsConfig.folderName + "/" + tagId);
            try {
                // Synchronous read in Milo 1.x — no .get() needed
                var value = client.readValue(0.0, TimestampsToReturn.Both, nodeId);
                // Record-style accessors: value.value() → Variant, .value() → raw Object
                sb.append(String.format("  %s=%-12s", tagId, value.value().value()));
            } catch (Exception e) {
                sb.append(String.format("  %s=ERROR", tagId));
            }
        }
        System.out.println(sb);
    }
}
