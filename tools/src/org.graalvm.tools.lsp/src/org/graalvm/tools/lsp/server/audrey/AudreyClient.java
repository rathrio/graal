package org.graalvm.tools.lsp.server.audrey;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RootNode;
import redis.clients.jedis.Jedis;

import java.util.Set;
import java.util.stream.Collectors;

//            final HashSet<Class<? extends Tag>> tags = new HashSet<>();
//            tags.add(RootTag.class);
//            final Node nearestRootNode = nodeAtCaret.findNearestNodeAt(hoverSection.getCharIndex(), tags);
//            final String rootNodeId = extractRootName(nearestRootNode);
//            System.out.println("Root Node ID:" + rootNodeId);

public class AudreyClient {
    private Set<Sample> samples;

    private final String audreyProjectId = System.getenv("AUDREY_PROJECT_ID");
    private final Jedis jedis = new Jedis("localhost");

    private boolean enabled = false;

    public AudreyClient() {
        if (audreyProjectId == null || audreyProjectId.isEmpty()) {
            System.out.println("[Audrey LSP] Cannot load samples without $AUDREY_PROJECT_ID.");
            return;
        }

        GsonBuilder gsonBuilder = new GsonBuilder();
        gsonBuilder.registerTypeAdapter(Sample.class, new SampleAdapter());

        Gson gson = gsonBuilder.create();

        samples = jedis.smembers("audrey:" + audreyProjectId + ":samples")
            .stream()
            .map(json -> gson.fromJson(json, Sample.class))
            .collect(Collectors.toSet());

        enabled = true;
        System.out.println("[Audrey LSP] Loaded Audrey samples");
    }

    private String extractRootName(final Node node) {
        RootNode rootNode = node.getRootNode();

        if (rootNode != null) {
            if (rootNode.getName() == null) {
                return rootNode.toString();
            } else {
                return rootNode.getName();
            }
        } else {
            return "<Unknown>";
        }
    }
}
