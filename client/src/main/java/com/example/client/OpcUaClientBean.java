package com.example.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;

import org.eclipse.milo.opcua.sdk.client.OpcUaClient;
import org.eclipse.milo.opcua.sdk.client.identity.AnonymousProvider;
import org.eclipse.milo.opcua.stack.core.NodeIds;
import org.eclipse.milo.opcua.stack.core.security.SecurityPolicy;
import org.eclipse.milo.opcua.stack.core.types.builtin.LocalizedText;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UInteger;
import org.eclipse.milo.opcua.stack.core.types.enumerated.TimestampsToReturn;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Manages the OpcUaClient lifecycle and namespace index resolution.
 *
 * Reads {@code opcua-client-config.json} to determine which namespace URIs are
 * needed, connects to the server, resolves the namespace index for each URI, then
 * signals {@link PollingService} and {@link SubscriptionService} to begin.
 *
 * Supports multiple namespaces. Use {@link #nodeId(String, String)} to build
 * a node ID from a namespace URI and a string identifier.
 *
 * Key Milo 1.x changes vs 0.6.x:
 *   - OpcUaClient.create() takes 4 args: (url, endpointSelector, transportConfig, clientConfig)
 *   - client.connect() is synchronous and throws UaException (no .get() needed)
 *   - client.disconnect() is synchronous
 *   - client.readValue() is synchronous: readValue(maxAge, timestampsToReturn, nodeId)
 *   - DataValue accessors are record-style: value.value().value() not getValue().getValue()
 *   - NodeIds replaces Identifiers (same constants, different class name)
 */
@ApplicationScoped
public class OpcUaClientBean {

    private static final int  MAX_RETRIES    = 15;
    private static final long RETRY_DELAY_MS = 3_000;

    @Inject SubscriptionService subscriptionService;
    @Inject PollingService       pollingService;

    private OpcUaClient client;
    private ClientConfig config;
    private final Map<String, Integer> namespaceIndices = new HashMap<>();
    private volatile boolean ready = false;

    void onStart(@Observes StartupEvent ev) throws Exception {
        // ── Load client config ────────────────────────────────────────────
        ObjectMapper mapper = new ObjectMapper();
        try (var is = getClass().getResourceAsStream("/opcua-client-config.json")) {
            if (is == null) throw new IllegalStateException(
                    "opcua-client-config.json not found on classpath");
            config = mapper.readValue(is, ClientConfig.class);
        }
        int pollCount      = config.pollNamespaces      != null ? config.pollNamespaces.size()      : 0;
        int subscribeCount = config.subscribeNamespaces != null ? config.subscribeNamespaces.size() : 0;
        System.out.printf("[CLIENT] Config: %d poll namespace(s), %d subscribe namespace(s)%n",
                pollCount, subscribeCount);

        // ── Connect to server ─────────────────────────────────────────────
        String url = System.getenv().getOrDefault("OPCUA_SERVER_URL", "opc.tcp://localhost:4840/");
        System.out.println("[CLIENT] Connecting to " + url);

        // 4-arg create: url, endpoint selector, transport config, client config
        client = OpcUaClient.create(
                url,
                endpoints -> endpoints.stream()
                        .filter(e -> SecurityPolicy.None.getUri().equals(e.getSecurityPolicyUri()))
                        .findFirst(),
                b -> {},   // transport config: use defaults
                b -> b
                        .setApplicationName(LocalizedText.english("Eclipse Milo Dynamic Client"))
                        .setApplicationUri("urn:eclipse:milo:dynamic:client")
                        .setIdentityProvider(new AnonymousProvider())
        );
        connectWithRetry();

        // ── Resolve namespace indices ─────────────────────────────────────
        // Read the server's NamespaceArray (built-in node ns=0;i=2255)
        var dataValue = client.readValue(0.0, TimestampsToReturn.Neither,
                NodeIds.Server_NamespaceArray);
        String[] serverNamespaces = (String[]) dataValue.value().value();
        List<String> nsList = Arrays.asList(serverNamespaces);

        // Collect all unique URIs from both strategy lists (order-preserving)
        Set<String> uris = new LinkedHashSet<>();
        if (config.pollNamespaces      != null) config.pollNamespaces     .forEach(ns -> uris.add(ns.uri));
        if (config.subscribeNamespaces != null) config.subscribeNamespaces.forEach(ns -> uris.add(ns.uri));

        for (String uri : uris) {
            int idx = nsList.indexOf(uri);
            if (idx < 0) throw new IllegalStateException(
                    "Namespace '" + uri + "' not found on server. Available: "
                    + Arrays.toString(serverNamespaces));
            namespaceIndices.put(uri, idx);
            System.out.printf("[CLIENT] Namespace '%s' → ns=%d%n", uri, idx);
        }

        ready = true;

        // Kick off both strategies now that connection + namespace indices are ready
        subscriptionService.setupSubscriptions();
        pollingService.startPolling();
    }

    void onStop(@Observes ShutdownEvent ev) {
        System.out.println("[CLIENT] Disconnecting…");
        ready = false;
        pollingService.stopPolling();
        if (client != null) {
            try { client.disconnect(); } catch (Exception ignored) {}
        }
        System.out.println("[CLIENT] Disconnected.");
    }

    // ── Accessors ─────────────────────────────────────────────────────────

    public OpcUaClient getClient()  { return client; }
    public ClientConfig getConfig() { return config; }
    public boolean      isReady()   { return ready;  }

    /**
     * Build a NodeId in the given namespace.
     *
     * @param namespaceUri namespace URI (must be present in client config)
     * @param identifier   string identifier, e.g. {@code "Floor1/Temperature"};
     *                     or {@code "i=6"} to create a numeric NodeId
     */
    public NodeId nodeId(String namespaceUri, String identifier) {
        Integer idx = namespaceIndices.get(namespaceUri);
        if (idx == null) throw new IllegalArgumentException(
                "Namespace not configured: " + namespaceUri);
        if (identifier.startsWith("i=")) {
            return new NodeId(idx, UInteger.valueOf(Long.parseLong(identifier.substring(2))));
        }
        return new NodeId(idx, identifier);
    }

    // ── Private helpers ────────────────────────────────────────────────────

   private int getNameSpaceIndex(string namespaceUri) {
       Integer idx = namespaceIndices.get(namespaceUri);
       if (idx == null) {
           throw new IllegalArgumentException("Unknown namespace URI: " + namespaceUri);
       }
       return idx;
   }


    private void connectWithRetry() throws Exception {
        Exception last = null;
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                client.connect();   // synchronous in Milo 1.x
                System.out.println("[CLIENT] Connected on attempt " + attempt);
                return;
            } catch (Exception e) {
                last = e;
                System.out.printf("[CLIENT] Attempt %d/%d failed (%s). Retrying in %d s…%n",
                        attempt, MAX_RETRIES, e.getMessage(), RETRY_DELAY_MS / 1000);
                Thread.sleep(RETRY_DELAY_MS);
            }
        }
        throw new RuntimeException("Could not connect after " + MAX_RETRIES + " attempts", last);
    }
}
