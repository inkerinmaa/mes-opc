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
 * <h3>folderName format</h3>
 * <ul>
 *   <li>{@code "Floor1"}  → string NodeId {@code ns=X;s=Floor1}</li>
 *   <li>{@code "i=6"}     → numeric NodeId {@code ns=X;i=6}  (use when the OPC UA browser
 *       shows a numeric id for the folder node)</li>
 * </ul>
 *
 * <h3>Return value</h3>
 * Returns the actual {@link NodeId} objects discovered — not just browse names or identifier
 * strings.  This preserves the exact NodeId type (string vs numeric) so callers can subscribe
 * or read without rebuilding and potentially getting the type wrong.
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
     * Returns NodeIds of all Variable nodes directly under a folder.
     *
     * @param namespaceUri namespace URI (must be in client config so the index is known)
     * @param folderName   folder node identifier — {@code "Floor1"} for string NodeId,
     *                     {@code "i=6"} for numeric NodeId
     * @return             list of exact NodeIds ready for subscription or read
     */
    public List<NodeId> browseNodeIds(String namespaceUri, String folderName) {
        OpcUaClient client   = clientBean.getClient();
        NodeId      folderId = clientBean.nodeId(namespaceUri, folderName);

        System.out.printf("[CLIENT][BROWSE] Start node: %s  (folderName='%s', uri='%s')%n",
                folderId.toParseableString(), folderName, namespaceUri);

        BrowseDescription bd = new BrowseDescription(
                folderId,
                BrowseDirection.Forward,
                NodeIds.HierarchicalReferences,
                true,
                uint(NodeClass.Variable.getValue()),
                uint(63)
        );

        List<NodeId> result = new ArrayList<>();
        try {
            var browseResult = client.browse(bd);
            ReferenceDescription[] refs = browseResult.getReferences();
            if (refs != null) {
                for (ReferenceDescription ref : refs) {
                    NodeId nodeId = ref.getNodeId()
                            .toNodeId(client.getNamespaceTable())
                            .orElse(null);
                    if (nodeId == null) continue;
                    result.add(nodeId);
                    System.out.printf("[CLIENT][BROWSE]   %-40s  nodeId=%s%n",
                            ref.getBrowseName().getName(), nodeId.toParseableString());
                }
            }
        } catch (Exception e) {
            System.err.printf("[CLIENT][BROWSE] Failed to browse '%s' in namespace '%s': %s%n",
                    folderName, namespaceUri, e.getMessage());
        }

        System.out.printf("[CLIENT][BROWSE] %s → %d tag(s) discovered%n", folderName, result.size());
        return result;
    }
}
