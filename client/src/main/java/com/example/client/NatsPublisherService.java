package com.example.client;

import io.nats.client.Connection;
import io.nats.client.Nats;
import io.nats.client.Options;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;

import java.nio.charset.StandardCharsets;

/**
 * Connects to a NATS server and publishes OPC UA counter values.
 *
 * Reads NATS_URL from the environment (default: nats://localhost:4222).
 * Called by SubscriptionService whenever Floor1/PartCounter changes.
 *
 * Subject published: opcua.floor1.PartCounter
 * Payload format:    {"counter":42}
 */
@ApplicationScoped
public class NatsPublisherService {

    private Connection nats;

    void onStart(@Observes StartupEvent ev) {
        String natsUrl = System.getenv().getOrDefault("NATS_URL", "nats://localhost:4222");
        try {
            Options options = new Options.Builder()
                    .server(natsUrl)
                    .maxReconnects(-1)
                    .reconnectWait(java.time.Duration.ofSeconds(2))
                    .build();
            nats = Nats.connect(options);
            System.out.printf("[CLIENT][NATS] Connected to %s%n", natsUrl);
        } catch (Exception e) {
            System.err.printf("[CLIENT][NATS] Failed to connect to %s: %s%n", natsUrl, e.getMessage());
        }
    }

    void onStop(@Observes ShutdownEvent ev) {
        if (nats != null) {
            try {
                nats.close();
                System.out.println("[CLIENT][NATS] Disconnected.");
            } catch (Exception ignored) {}
        }
    }

    public void publish(String subject, String payload) {
        if (nats == null || nats.getStatus() != Connection.Status.CONNECTED) {
            System.err.printf("[CLIENT][NATS] Not connected — skipping publish to %s%n", subject);
            return;
        }
        nats.publish(subject, payload.getBytes(StandardCharsets.UTF_8));
    }
}
