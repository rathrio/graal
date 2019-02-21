package org.graalvm.tools.lsp.server.audrey;

import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Can filter a Set of {@link Sample}s, e.g.
 *
 * <pre>
 *     new SampleSearch(samples)
 *        .forArguments()
 *        .value("\"foobar\"")
 *        .search() // => Stream of argument samples with value "foobar"
 * </pre>
 */
public class SampleSearch {
    private final Set<Sample> samples;
    private String category;
    private String rootNodeId;
    private String identifier;
    private Integer line;
    private String value;
    private String source;

    public SampleSearch(final Set<Sample> samples) {
        this.samples = samples;
    }

    public SampleSearch forArguments() {
        category = "ARGUMENT";
        return this;
    }

    public SampleSearch forReturns() {
        category = "RETURN";
        return this;
    }

    public SampleSearch rootNodeId(final String rootNodeId) {
        this.rootNodeId = rootNodeId;
        return this;
    }

    public SampleSearch identifier(final String identifier) {
        this.identifier = identifier;
        return this;
    }

    public SampleSearch line(final int line) {
        this.line = line;
        return this;
    }

    public SampleSearch value(final String value) {
        this.value = value;
        return this;
    }

    public SampleSearch source(final String source) {
        this.source = source;
        return this;
    }

    public Stream<Sample> search() {
        Stream<Sample> stream = samples.stream();
        if (category != null) {
            stream = stream.filter(sample -> category.equals(sample.getCategory().name()));
        }

        if (rootNodeId != null) {
            stream = stream.filter(sample -> rootNodeId.equals(sample.getRootNodeId()));
        }

        if (identifier != null) {
            stream = stream.filter(sample -> identifier.equals(sample.getIdentifier()));
        }

        if (line != null) {
            stream = stream.filter(sample -> line == sample.getSourceLine());
        }

        if (value != null) {
            stream = stream.filter(sample -> value.equals(sample.getValue()));
        }

        if (source != null) {
            stream = stream.filter(sample -> sample.getSource().endsWith(source));
        }

        return stream;
    }

    public Optional<Sample> findFirst() {
        return search().findFirst();
    }
}
