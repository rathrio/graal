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
import redis.clients.jedis.Jedis;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

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
        String rootNodeId;
        try {
            nearestRoot = node.findNearestNodeAt(hoverSection.getCharIndex(), tags);
            rootNodeId = extractRootName(nearestRoot);
        } catch (Exception e) {
            e.printStackTrace();
            return new Hover(new ArrayList<>());
        }

        final Path fileName = Paths.get(hoverSection.getSource().getName()).getFileName();

        final Set<Sample> results = new SampleSearch(samples)
            .rootNodeId(rootNodeId)
            .source(fileName.toString())
            .search()
            .collect(Collectors.toSet());

        List<Either<String, MarkedString>> contents = new ArrayList<>();

        final Optional<Sample> argument = results.stream().filter(Sample::isArgument).findFirst();
        if (argument.isPresent()) {
            final Sample argumentSample = argument.get();
            contents.add(Either.forLeft("(parameter) " + argumentSample.getIdentifier() + ": " + argumentSample.getMetaObject() + ", e.g. " + argumentSample.getValue() + "\n"));
        }

        final Optional<Sample> returnSample = results.stream().filter(Sample::isReturn).findFirst();
        if (returnSample.isPresent()) {
            final Sample rSample = returnSample.get();
            contents.add(Either.forLeft("\n\nReturns a " +  rSample.getMetaObject() + ", e.g. " + rSample.getValue() + "\n"));
        }

//        return new Hover(contents, SourceUtils.sourceSectionToRange(hoverSection));
        return new Hover(contents);

    }
}
