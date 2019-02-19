package org.graalvm.tools.lsp.server.audrey;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.oracle.truffle.api.instrumentation.InstrumentableNode;
import com.oracle.truffle.api.instrumentation.StandardTags;
import com.oracle.truffle.api.instrumentation.Tag;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.source.SourceSection;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.MarkedString;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.graalvm.tools.lsp.server.utils.SourceUtils;
import redis.clients.jedis.Jedis;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class AudreyClient {
    private Set<Sample> samples;
    private final String audreyProjectId = System.getenv("AUDREY_PROJECT_ID");
    private final Jedis jedis = new Jedis("localhost");
    private boolean enabled = false;
    private final HashSet<Class<? extends Tag>> tags = new HashSet<>();
    private final Class<? extends Tag> readVariableTag = StandardTags.ReadVariableTag.class;

    public AudreyClient() {
        if (audreyProjectId == null || audreyProjectId.isEmpty()) {
            System.out.println("[Audrey LSP] Cannot load samples without $AUDREY_PROJECT_ID.");
            return;
        }

        tags.add(StandardTags.RootTag.class);

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

    public Hover hoverResults(final SourceSection hoverSection, final InstrumentableNode node) {
        Node nearestRoot;
        try {
            nearestRoot = node.findNearestNodeAt(hoverSection.getCharIndex(), tags);
        } catch (Exception e) {
            e.printStackTrace();
            return new Hover(new ArrayList<>());
        }

        final String rootNodeId = extractRootName(nearestRoot);

        final Set<Sample> results = new SampleSearch(samples)
            .rootNodeId(rootNodeId)
//            .source(hoverSection.getSource().getName())
            .search()
            .collect(Collectors.toSet());

        final Stream<Sample> argumentSamples = results.stream().filter(Sample::isArgument);
        final Stream<Sample> returnSamples = results.stream().filter(Sample::isReturn);

        List<Either<String, MarkedString>> contents = new ArrayList<>();
        contents.add(Either.forLeft("Node under caret: " + node.toString() + "\n\n"));
        contents.add(Either.forLeft("Nearest root class: " + nearestRoot.toString()));

        return new Hover(contents, SourceUtils.sourceSectionToRange(hoverSection));
    }
}
