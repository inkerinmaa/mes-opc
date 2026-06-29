package com.example.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;

import org.eclipse.milo.opcua.sdk.server.OpcUaServer;
import org.eclipse.milo.opcua.sdk.server.OpcUaServerConfig;
import org.eclipse.milo.opcua.sdk.server.EndpointConfig;
import org.eclipse.milo.opcua.sdk.server.identity.AnonymousIdentityValidator;
import org.eclipse.milo.opcua.stack.core.security.SecurityPolicy;
import org.eclipse.milo.opcua.stack.core.transport.TransportProfile;
import org.eclipse.milo.opcua.stack.core.types.builtin.LocalizedText;
import org.eclipse.milo.opcua.stack.core.types.enumerated.MessageSecurityMode;
import org.eclipse.milo.opcua.stack.transport.server.tcp.OpcTcpServerTransport;
import org.eclipse.milo.opcua.stack.transport.server.tcp.OpcTcpServerTransportConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.eclipse.milo.opcua.sdk.server.OpcUaServerConfig.USER_TOKEN_POLICY_ANONYMOUS;

/**
 * Quarkus CDI bean that owns the OpcUaServer lifecycle.
 *
 * Reads {@code opcua-server-config.json} from the classpath and creates one
 * {@link DynamicNamespace} per entry.  Each namespace registers its own folder
 * and variable nodes, and runs its own simulation scheduler.
 *
 * Key Milo 1.x changes vs 0.6.x:
 *   - OpcUaServerConfig is an interface (package: sdk.server, not api.config)
 *   - setEndpoints() replaces setEndpointConfigurations()
 *   - setIdentityValidator() replaces setIdentityValidators()
 *   - OpcUaServer takes an explicit transport factory (second constructor arg)
 *   - namespace.startup()/shutdown() are synchronous void methods (Lifecycle interface)
 */
@ApplicationScoped
public class OpcUaServerBean {

    private OpcUaServer server;
    private final List<DynamicNamespace> namespaces = new ArrayList<>();

    void onStart(@Observes StartupEvent ev) throws Exception {
        String host = System.getenv().getOrDefault("OPCUA_SERVER_HOST", "localhost");
        int    port = Integer.parseInt(System.getenv().getOrDefault("OPCUA_SERVER_PORT", "4840"));

        // ── Load namespace config from classpath ──────────────────────────
        ObjectMapper mapper = new ObjectMapper();
        ServerConfig serverConfig;
        try (var is = getClass().getResourceAsStream("/opcua-server-config.json")) {
            if (is == null) throw new IllegalStateException(
                    "opcua-server-config.json not found on classpath");
            serverConfig = mapper.readValue(is, ServerConfig.class);
        }
        System.out.printf("[SERVER] Starting — advertised host: %s, port: %d, %d namespace(s) configured%n",
                host, port, serverConfig.namespaces.size());

        // ── Build OPC UA server ───────────────────────────────────────────
        EndpointConfig endpoint = EndpointConfig.newBuilder()
                .setBindAddress("0.0.0.0")
                .setHostname(host)
                .setPath("/")
                .setSecurityPolicy(SecurityPolicy.None)
                .setSecurityMode(MessageSecurityMode.None)
                .addTokenPolicies(USER_TOKEN_POLICY_ANONYMOUS)
                .setTransportProfile(TransportProfile.TCP_UASC_UABINARY)
                .setBindPort(port)
                .build();

        OpcUaServerConfig config = OpcUaServerConfig.builder()
                .setApplicationName(LocalizedText.english("Eclipse Milo Dynamic Server"))
                .setApplicationUri("urn:eclipse:milo:dynamic:server")
                .setEndpoints(Set.of(endpoint))
                .setIdentityValidator(AnonymousIdentityValidator.INSTANCE)
                .build();

        // In Milo 1.x the server takes an explicit transport factory as second arg
        server = new OpcUaServer(config, transportProfile -> {
            OpcTcpServerTransportConfig transportConfig =
                    OpcTcpServerTransportConfig.newBuilder().build();
            return new OpcTcpServerTransport(transportConfig);
        });

        // ── Register one DynamicNamespace per config entry ────────────────
        for (ServerConfig.NamespaceConfig nsConfig : serverConfig.namespaces) {
            DynamicNamespace ns = new DynamicNamespace(server, nsConfig);
            ns.startup();
            namespaces.add(ns);
            System.out.printf("[SERVER] Namespace '%s' registered (folder: %s, %d tag(s))%n",
                    nsConfig.uri, nsConfig.folderName, nsConfig.tags.size());
        }

        server.startup().get();
        System.out.printf("[SERVER] Ready — opc.tcp://%s:%d/%n", host, port);
    }

    void onStop(@Observes ShutdownEvent ev) {
        System.out.println("[SERVER] Shutting down…");
        namespaces.forEach(ns -> {
            try { ns.shutdown(); } catch (Exception ignored) {}
        });
        if (server != null) {
            try { server.shutdown().get(); } catch (Exception ignored) {}
        }
        System.out.println("[SERVER] Stopped.");
    }
}
