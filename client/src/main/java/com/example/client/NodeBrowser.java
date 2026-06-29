package com.example.client;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.milo.opcua.sdk.client.OpcUaClient;
import org.eclipse.milo.opcua.stack.core.NodeIds;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.enumerated.BrowseDirection;
import org.eclipse.milo.opcua.stack.core.types.enumerated.NodeClass;
import org.eclipse.milo.opcua.stack.core.types.structured.BrowseDescription;
import org.eclipse.milo.opcua.stack.core.types.structured.ReferenceDescription;

import java.util.ArrayList;
import java.util.List;

import static org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned.uint;

/**
 * Wraps the OPC UA Browse service to auto-discover variable nodes under a folder.
 *
 * <h3>Does Eclipse Milo support OPC UA Browse?</h3>
 * Yes.  {@link OpcUaClient#browse(BrowseDescription)} sends a Browse request to the
 * server and returns a {@code BrowseResult} containing {@code ReferenceDescription[]} —
 * one entry per discovered node.  This is the same mechanism OPC UA client tools such as
 * UaExpert use to build their node tree view.
 *
 * <h3>How Browse works</h3>
 * Every OPC UA node can have typed References to other nodes. The most common reference
 * type for tree navigation is {@code HierarchicalReferences} (covers Organizes,
 * HasComponent, HasProperty, etc.).  A Browse request specifies:
 * <ul>
 *   <li>Start node — here the folder node, e.g. {@code ns=2;s=Floor1}</li>
 *   <li>Direction — {@code Forward} follows references away from the node</li>
 *   <li>Reference type filter — {@code HierarchicalReferences} with subtypes</li>
 *   <li>Node class filter — {@code Variable} (bit mask value 2) to skip folders/objects</li>
 *   <li>Result mask — bitmask 63 = all fields (NodeId, BrowseName, DisplayName, …)</li>
 * </ul>
 *
 * <h3>Usage</h3>
 * {@link PollingService} and {@link SubscriptionService} call this when the
 * {@code tags} list in a namespace config is empty, letting the client adapt
 * automatically to whatever variables the server currently exposes.
 */
@ApplicationScoped
public class NodeBrowser {

    @Inject
    OpcUaClientBean clientBean;

    /**
     * Returns the browse-names of all Variable nodes directly under a folder.
     *
     * @param namespaceUri namespace URI (must be in client config so the index is known)
     * @param folderName   folder node string identifier, e.g. {@code "Floor1"}
     * @return             list of tag IDs, e.g. {@code ["Temperature", "Pressure"]}
     */
    public List<String> browseTagIds(String namespaceUri, String folderName) {
        OpcUaClient client    = clientBean.getClient();
        NodeId      folderId  = clientBean.nodeId(namespaceUri, folderName);

        // OPC UA spec: NodeClass.Variable = 2; BrowseResultMask 63 = all fields
        BrowseDescription bd = new BrowseDescription(
                folderId,
                BrowseDirection.Forward,
                NodeIds.HierarchicalReferences,   // follow Organizes / HasComponent / …
                true,                             // includeSubtypes of HierarchicalReferences
                uint(NodeClass.Variable.getValue()),  // nodeClassMask: Variable nodes only
                uint(63)                          // resultMask: all result fields
        );

        List<String> tags = new ArrayList<>();
        try {
            var result = client.browse(bd);
            ReferenceDescription[] refs = result.getReferences();
            if (refs != null) {
                for (ReferenceDescription ref : refs) {
                    // BrowseName.name is the local part of the tag ID (no ns prefix)
                    String tagId = ref.getBrowseName().getName();
                    tags.add(tagId);
                    System.out.printf("[CLIENT][BROWSE] Discovered: %s/%s  (nodeId: %s)%n",
                            folderName, tagId,
                            ref.getNodeId().getIdentifier());
                }
            }
        } catch (Exception e) {
            System.err.printf("[CLIENT][BROWSE] Failed to browse '%s' in namespace '%s': %s%n",
                    folderName, namespaceUri, e.getMessage());
        }

        System.out.printf("[CLIENT][BROWSE] %s → %d tag(s) discovered%n", folderName, tags.size());
        return tags;
    }
}
