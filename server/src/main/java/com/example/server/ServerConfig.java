package com.example.server;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * Maps opcua-server-config.json → Java objects.
 *
 * Public fields + @JsonIgnoreProperties let Jackson deserialize without
 * requiring explicit getters/setters.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ServerConfig {

    public List<NamespaceConfig> namespaces;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class NamespaceConfig {
        /** OPC UA namespace URI (unique identifier for this namespace). */
        public String uri;
        /** Name of the OPC UA folder node under Objects. */
        public String folderName;
        public List<TagConfig> tags;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TagConfig {
        /** Node identifier within the namespace, e.g. "Temperature". */
        public String id;
        /** Human-readable display name shown in OPC UA browsers. */
        public String displayName;
        /**
         * OPC UA data type. Supported values:
         *   "Float", "Double", "Int64", "Int32", "Boolean", "String"
         */
        public String dataType;
        /**
         * Simulation: minimum value (inclusive).
         * Float/Double: random in [min, max].
         * Int64/Int32:  counter resets to min when it exceeds max.
         * Boolean/String: ignored.
         */
        public Double minValue;
        /** Simulation: maximum value (inclusive). */
        public Double maxValue;
        /** How often (ms) the simulated value is updated. Default 1000 ms. */
        public int updatePeriodMs = 1000;
    }
}
