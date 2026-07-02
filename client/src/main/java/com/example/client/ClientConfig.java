package com.example.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

/**
 * Maps {@code opcua-client-config.json} → Java objects.
 *
 * Two top-level lists drive the two read strategies:
 * <ul>
 *   <li>{@code pollNamespaces}      — client polls on a timer       ({@link PollingService})</li>
 *   <li>{@code subscribeNamespaces} — server pushes changes         ({@link SubscriptionService})</li>
 * </ul>
 *
 * Tags can be listed explicitly or left empty; when empty, {@link NodeBrowser}
 * performs an OPC UA Browse to discover all variable nodes under the folder.
 *
 * Example:
 * <pre>{@code
 * {
 *   "pollNamespaces": [
 *     { "uri": "urn:example:factory:floor1", "folderName": "Floor1",
 *       "pollPeriodMs": 2000, "tags": ["Temperature", "PartCounter"] }
 *   ],
 *   "subscribeNamespaces": [
 *     { "uri": "urn:example:factory:floor2", "folderName": "Floor2",
 *       "tags": ["Humidity", "MotorSpeed", "AlarmActive"] }
 *   ]
 * }
 * }</pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ClientConfig {

    /** Namespaces whose tags are read on a periodic timer. */
    public List<NamespaceConfig> pollNamespaces;

    /** Namespaces whose tags are monitored via server-push subscriptions. */
    public List<NamespaceConfig> subscribeNamespaces;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class NamespaceConfig {

        /** Must match the namespace URI registered on the server. */
        public String uri;

        /** OPC UA folder node name, e.g. {@code "Floor1"}. */
        public String folderName;

        /**
         * Poll period in milliseconds (poll namespaces only). Default 2000.
         * Ignored for subscribe namespaces.
         */
        public int pollPeriodMs = 2000;

        /**
         * Explicit tag IDs to read, e.g. {@code ["Temperature", "PartCounter"]}.
         * Leave empty or omit to auto-discover all variable nodes via OPC UA Browse.
         */
        public List<String> tags;

        /**
         * Optional NATS publishing map: tagId → NATS subject.
         * e.g. {@code {"PartCounter": "opcua.floor1.PartCounter"}}.
         * Tags not in this map are logged but not published.
         */
        public Map<String, String> tagMappings;
    }
}
