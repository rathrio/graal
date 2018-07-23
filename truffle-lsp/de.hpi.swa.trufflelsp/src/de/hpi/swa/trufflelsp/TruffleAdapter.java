package de.hpi.swa.trufflelsp;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.function.Function;

import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.DocumentHighlight;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.MarkedString;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SignatureHelp;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.SymbolKind;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.Scope;
import com.oracle.truffle.api.TruffleException;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.instrumentation.EventBinding;
import com.oracle.truffle.api.instrumentation.EventContext;
import com.oracle.truffle.api.instrumentation.ExecutionEventNode;
import com.oracle.truffle.api.instrumentation.ExecutionEventNodeFactory;
import com.oracle.truffle.api.instrumentation.InstrumentableNode;
import com.oracle.truffle.api.instrumentation.LoadSourceSectionEvent;
import com.oracle.truffle.api.instrumentation.LoadSourceSectionListener;
import com.oracle.truffle.api.instrumentation.SourceFilter;
import com.oracle.truffle.api.instrumentation.SourceSectionFilter;
import com.oracle.truffle.api.instrumentation.SourceSectionFilter.IndexRange;
import com.oracle.truffle.api.instrumentation.SourceSectionFilter.SourcePredicate;
import com.oracle.truffle.api.instrumentation.StandardTags;
import com.oracle.truffle.api.instrumentation.StandardTags.ExpressionTag;
import com.oracle.truffle.api.instrumentation.StandardTags.StatementTag;
import com.oracle.truffle.api.instrumentation.TruffleInstrument;
import com.oracle.truffle.api.instrumentation.TruffleInstrument.Env;
import com.oracle.truffle.api.interop.ForeignAccess;
import com.oracle.truffle.api.interop.Message;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.nodes.ExecutableNode;
import com.oracle.truffle.api.nodes.LanguageInfo;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.api.source.SourceSection;

import de.hpi.swa.trufflelsp.NearestSectionsFinder.NearestSections;
import de.hpi.swa.trufflelsp.NearestSectionsFinder.NodeLocationType;
import de.hpi.swa.trufflelsp.exceptions.EvaluationResultException;
import de.hpi.swa.trufflelsp.exceptions.InvalidCoverageScriptURI;
import de.hpi.swa.trufflelsp.exceptions.UnknownLanguageException;
import de.hpi.swa.trufflelsp.server.DiagnosticsPublisher;

public class TruffleAdapter implements VirtualLSPFileProvider, NestedEvaluatorRegistry {
    private static final int SORTING_PRIORITY_LOCALS = 1;
    private static final int SORTING_PRIORITY_GLOBALS = 2;
    private static final String COVERAGE_SCRIPT = "COVERAGE_SCRIPT:";
    private static final Node HAS_SIZE = Message.HAS_SIZE.createNode();
    private static final Node KEYS = Message.KEYS.createNode();
    private static final Node IS_INSTANTIABLE = Message.IS_INSTANTIABLE.createNode();
    private static final Node IS_EXECUTABLE = Message.IS_EXECUTABLE.createNode();

    protected final Map<URI, TextDocumentSurrogate> uri2TextDocumentSurrogate = new HashMap<>();
    protected final Map<SourceSection, SourceSection> section2definition = new HashMap<>();

    private final TruffleInstrument.Env env;
    private final PrintWriter err;
    private final SourceProvider sourceProvider;
    private DiagnosticsPublisher diagnosticsPublisher;
    private NestedEvaluator evaluator;

    private static final class SourceUriFilter implements SourcePredicate {

        public boolean test(Source source) {
            return source.getName().startsWith("file://"); // TODO(ds) how to filter? tag is
                                                           // STATEMENT && source.isAvailable()?
        }
    }

    protected static final class SourceFix {
        public final String text;
        public final int character;
        public final boolean isObjectPropertyCompletion;

        public SourceFix(String text, int character, boolean isObjectPropertyCompletion) {
            this.text = text;
            this.character = character;
            this.isObjectPropertyCompletion = isObjectPropertyCompletion;
        }
    }

    public TruffleAdapter(Env env) {
        this.env = env;
        this.sourceProvider = new SourceProvider();

        this.err = new PrintWriter(env.err(), true);

        env.getInstrumenter().attachLoadSourceListener(SourceFilter.newBuilder().sourceIs(new SourceUriFilter()).build(), this.sourceProvider, false);
        env.getInstrumenter().attachLoadSourceSectionListener(SourceSectionFilter.newBuilder().sourceIs(new SourceUriFilter()).build(), this.sourceProvider, false);
    }

    public void setDiagnosticsPublisher(DiagnosticsPublisher diagnosticsPublisher) {
        assert diagnosticsPublisher != null;
        this.diagnosticsPublisher = diagnosticsPublisher;
    }

    public void didOpen(URI uri, String text, String langId) {
        this.uri2TextDocumentSurrogate.put(uri, new TextDocumentSurrogate(uri, langId, text));
    }

    public void didClose(URI uri) {
        this.uri2TextDocumentSurrogate.remove(uri);
    }

    public Future<Void> parse(final String text, final String langId, final URI uri) {
        return evaluator.executeWithDefaultContext(() -> {
            try {
                parseWithEnteredContext(text, langId, uri);
            } finally {
                diagnosticsPublisher.reportCollectedDiagnostics(uri.toString());
            }
        });
    }

    protected void parseWithEnteredContext(final String text, final String langId, final URI uri) {
        TextDocumentSurrogate surrogate = this.uri2TextDocumentSurrogate.computeIfAbsent(uri, (_uri) -> new TextDocumentSurrogate(_uri, langId));
        surrogate.setEditorText(text);

// try {
        parse(surrogate);
// } catch (InterruptedException | ExecutionException e) {
// // TODO(ds) exception handling - where? here or in LSPServer? who sends what to client?
// if (e.getCause() instanceof UnknownLanguageException) {
// throw (UnknownLanguageException) e.getCause();
// } else {
// e.printStackTrace(err);
// }
// }
    }

    protected CallTarget parse(final TextDocumentSurrogate surrogate) {
        if (!env.getLanguages().containsKey(surrogate.getLangId())) {
            throw new UnknownLanguageException("Unknown language: " + surrogate.getLangId() + ". Known languages are: " + env.getLanguages().keySet());
        }

        CallTarget callTarget = null;
        try {
            callTarget = parseInternal(surrogate);
        } catch (IllegalStateException e) {
            this.err.println(e.getLocalizedMessage());
        } catch (RuntimeException e) {
            if (e instanceof TruffleException) {
                this.diagnosticsPublisher.addDiagnostics(surrogate.getUri(), new Diagnostic(getRangeFrom((TruffleException) e), e.getMessage(), DiagnosticSeverity.Error, "Truffle"));
            } else {
                throw e;
            }
        }

        return callTarget;
    }

    private static Range getRangeFrom(TruffleException te) {
        Range range = new Range(new Position(), new Position());
        SourceSection sourceLocation = te.getSourceLocation() != null ? te.getSourceLocation()
                        : (te.getLocation() != null ? te.getLocation().getEncapsulatingSourceSection() : null);
        if (sourceLocation != null && sourceLocation.isAvailable()) {
            range = sourceSectionToRange(sourceLocation);
        }
        return range;
    }

    private CallTarget parseInternal(final TextDocumentSurrogate surrogate) {
        String langId = surrogate.getLangId();
        URI uri = surrogate.getUri();
        String text = surrogate.getCurrentText();
        this.sourceProvider.remove(langId, uri);

        System.out.println("Parsing " + langId + " " + uri);
        try {
            Source source = Source.newBuilder(new File(uri)).name(uri.toString()).language(langId).content(text).build();
            CallTarget callTarget = env.parse(source);

            SourceWrapper sourceWrapper = this.sourceProvider.getLoadedSource(langId, uri);
            if (sourceWrapper == null) {
                System.out.println("!!! Cannot lookup Source. No parsed Source found for URI: " + uri);
                return null;
            }
            sourceWrapper.setParsingSuccessful(true);
            sourceWrapper.setText(text);
            sourceWrapper.setCallTarget(callTarget);
            surrogate.setParsedSourceWrapper(sourceWrapper);
            surrogate.getChangeEventsSinceLastSuccessfulParsing().clear();
            return callTarget;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public Future<Void> processChangesAndParse(List<? extends TextDocumentContentChangeEvent> list, URI uri) {
        return evaluator.executeWithDefaultContext(() -> {
            try {
                processChangesAndParseWithContextEntered(list, uri);
            } finally {
                diagnosticsPublisher.reportCollectedDiagnostics(uri.toString());
            }
        });
    }

    protected void processChangesAndParseWithContextEntered(List<? extends TextDocumentContentChangeEvent> list, URI uri) {
        if (list.isEmpty()) {
            return;
        }

        TextDocumentSurrogate surrogate = this.uri2TextDocumentSurrogate.get(uri);
        surrogate.getChangeEventsSinceLastSuccessfulParsing().addAll(list);
        surrogate.setEditorText(applyTextDocumentChanges(list, surrogate.getEditorText(), surrogate));

// try {
        parse(surrogate);
// } catch (InterruptedException | ExecutionException e) {
// if (e.getCause() instanceof UnknownLanguageException) {
// throw (UnknownLanguageException) e.getCause();
// } else {
// e.printStackTrace(err);
// }
// }
        if (surrogate.hasCoverageData()) {
            showCoverage(uri);
        }
    }

    protected static String applyTextDocumentChanges(List<? extends TextDocumentContentChangeEvent> list, String text, TextDocumentSurrogate surrogate) {
        StringBuilder sb = new StringBuilder(text);
        for (TextDocumentContentChangeEvent event : list) {
            Range range = event.getRange();
            if (range == null) {
                // The whole file has changed
                sb.setLength(0); // Clear StringBuilder
                sb.append(event.getText());
                continue;
            }

            TextMap textMap = TextMap.fromCharSequence(sb);
            Position start = range.getStart();
            Position end = range.getEnd();
            int startLine = start.getLine() + 1;
            int endLine = end.getLine() + 1;
            int replaceBegin;
            int replaceEnd;
            if (textMap.lineCount() < startLine) {
                assert start.getCharacter() == 0 : start.getCharacter();
                assert textMap.finalNL || textMap.lineCount() == 0;
                assert textMap.lineCount() < endLine;
                assert end.getCharacter() == 0 : end.getCharacter();

                replaceBegin = textMap.length();
                replaceEnd = replaceBegin;
            } else if (textMap.lineCount() < endLine) {
                replaceBegin = textMap.lineStartOffset(startLine) + start.getCharacter();
                replaceEnd = text.length();
            } else {
                replaceBegin = textMap.lineStartOffset(startLine) + start.getCharacter();
                replaceEnd = textMap.lineStartOffset(endLine) + end.getCharacter();
            }
            sb.replace(replaceBegin, replaceEnd, event.getText());

            if (surrogate != null && surrogate.hasCoverageData()) {
                updateCoverageData(surrogate, text, event.getText(), range, replaceBegin, replaceEnd);
            }
        }
        return sb.toString();
    }

    private static void updateCoverageData(TextDocumentSurrogate surrogate, String text, String newText, Range range, int replaceBegin, int replaceEnd) {
        TextMap textMapNewText = TextMap.fromCharSequence(newText);
        int linesNewText = textMapNewText.lineCount() + (textMapNewText.finalNL ? 1 : 0) + (newText.isEmpty() ? 1 : 0);
        String oldText = text.substring(replaceBegin, replaceEnd);
        TextMap textMapOldText = TextMap.fromCharSequence(oldText);
        int liensOldText = textMapOldText.lineCount() + (textMapOldText.finalNL ? 1 : 0) + (oldText.isEmpty() ? 1 : 0);
        int newLineModification = linesNewText - liensOldText;
        System.out.println("newLineModification: " + newLineModification);

        if (newLineModification != 0) {
            List<SourceLocation> locations = surrogate.getCoverageLocations();
            locations.stream().filter(location -> location.includes(range)).forEach(location -> {
                SourceLocation fixedLocation = new SourceLocation(location);
                fixedLocation.setEndLine(fixedLocation.getEndLine() + newLineModification);
                surrogate.replace(location, fixedLocation);
                System.out.println("Inlcuded - Old: " + location + " Fixed: " + fixedLocation);
            });
            locations.stream().filter(location -> location.behind(range)).forEach(location -> {
                SourceLocation fixedLocation = new SourceLocation(location);
                fixedLocation.setStartLine(fixedLocation.getStartLine() + newLineModification);
                fixedLocation.setEndLine(fixedLocation.getEndLine() + newLineModification);
                surrogate.replace(location, fixedLocation);
                System.out.println("Behind   - Old: " + location + " Fixed: " + fixedLocation);
            });
        }
    }

    public Future<List<? extends SymbolInformation>> getSymbolInfo(URI uri) {
        return evaluator.executeWithDefaultContext(() -> getSymbolInfoWithEnteredContext(uri));
    }

    protected List<? extends SymbolInformation> getSymbolInfoWithEnteredContext(URI uri) {
        Set<SymbolInformation> symbolInformation = new LinkedHashSet<>();

        TextDocumentSurrogate surrogate = this.uri2TextDocumentSurrogate.get(uri);
        env.getInstrumenter().attachLoadSourceSectionListener(
                        SourceSectionFilter.newBuilder().sourceIs(surrogate.getParsedSourceWrapper().getSource()).tagIs(StandardTags.RootTag.class).build(),
                        new LoadSourceSectionListener() {

                            public void onLoad(LoadSourceSectionEvent event) {
                                Node node = event.getNode();
                                SymbolKind kind = SymbolKind.Function;
                                Range range = TruffleAdapter.sourceSectionToRange(node.getSourceSection());
                                SymbolInformation si = new SymbolInformation(node.getRootNode().getName(),
                                                kind, new Location(node.getSourceSection().getSource().getURI().toString(), range));
                                symbolInformation.add(si);
                            }
                        }, true).dispose();

        return new ArrayList<>(symbolInformation);
    }

    public static LinkedHashMap<String, Map<Object, Object>> scopesToObjectMap(Iterable<Scope> scopes) {
        LinkedHashMap<String, Map<Object, Object>> map = new LinkedHashMap<>();
        for (Scope scope : scopes) {
            Object variables = scope.getVariables();
            if (variables instanceof TruffleObject) {
                TruffleObject truffleObj = (TruffleObject) variables;
                try {
                    // TODO(ds) check KEY_INFO has keys?
                    TruffleObject keys = ForeignAccess.sendKeys(KEYS, truffleObj, true);
                    boolean hasSize = ForeignAccess.sendHasSize(HAS_SIZE, keys);
                    if (!hasSize) {
                        continue;
                    }

                    map.put(scope.getName(), ObjectStructures.asMap(new ObjectStructures.MessageNodes(), truffleObj));
                } catch (UnsupportedMessageException e) {
                    throw new RuntimeException(e);
                }
            }
        }
        return map;
    }

    /**
     * Provides completions for a specific position in the document. If line or column are out of
     * range, items of global scope (top scope) are provided.
     *
     * @param uri
     * @param line 0-based line number
     * @param column 0-based column number (character offset)
     * @return a {@link Future} of {@link CompletionList} containing all completions for the cursor
     *         position
     */
    public Future<CompletionList> getCompletions(final URI uri, int line, int column) {
        return evaluator.executeWithDefaultContext(() -> {
            try {
                return getCompletionsWithEnteredContext(uri, line, column);
            } finally {
                diagnosticsPublisher.reportCollectedDiagnostics();
            }
        });
    }

    protected CompletionList getCompletionsWithEnteredContext(final URI uri, int line, int originalCharacter) {
        CompletionList completions = new CompletionList();
        completions.setIsIncomplete(false);

        TextDocumentSurrogate textDocumentSurrogate = this.uri2TextDocumentSurrogate.get(uri);
        String langId = textDocumentSurrogate.getLangId();
        boolean isObjectPropertyCompletion = false;

        int character = originalCharacter;
        SourceWrapper sourceWrapper = this.sourceProvider.getLoadedSource(langId, uri);
        if (sourceWrapper == null || !sourceWrapper.isParsingSuccessful()) {
            // Parsing failed, now try to fix simple syntax errors
            System.out.println("No source wrapper found, fixing source.");
            SourceFix sourceFix = fixSourceAtPosition(textDocumentSurrogate, line, character);
            if (sourceFix != null) {
                try {
                    textDocumentSurrogate.setFixedText(sourceFix.text);

                    parseInternal(textDocumentSurrogate);
                    sourceWrapper = this.sourceProvider.getLoadedSource(langId, uri);
                    character = sourceFix.character;
                    isObjectPropertyCompletion = sourceFix.isObjectPropertyCompletion;
                } catch (IllegalStateException e) {
                    // TODO(ds) exception handling
                    this.err.println(e.getLocalizedMessage());
                } catch (RuntimeException e) {
                    if (!(e instanceof TruffleException)) {
                        throw e;
                    }
                }
            }
        }

        try {
            if (sourceWrapper != null) {
                Source source = sourceWrapper.getSource();

                if (isLineValid(line, source)) {
                    int oneBasedLineNumber = zeroBasedLineToOneBasedLine(line, source);
                    NearestNodeHolder nearestNodeHolder = NearestSectionsFinder.findNearestNode(oneBasedLineNumber, character, source, env);
                    Node nearestNode = nearestNodeHolder.getNearestNode();
                    NodeLocationType locationType = nearestNodeHolder.getLocationType();

                    System.out.println("nearestNode: " +
                                    (nearestNode != null ? nearestNode.getClass().getSimpleName() : "--NULL--") + "\t-" + locationType + "-\t" +
                                    (nearestNode != null ? nearestNode.getSourceSection() : ""));

                    if (isInstrumentable(nearestNode)) {
                        if (isObjectPropertyCompletion) {
                            if (locationType == NodeLocationType.CONTAINS_END) {
                                EvaluationResult evalResult = tryDifferentEvalStrategies(textDocumentSurrogate, langId, sourceWrapper, nearestNode);
                                if (evalResult.isEvaluationDone() && !evalResult.isError()) {
                                    fillCompletionsFromTruffleObject(completions, langId, evalResult.getResult());
                                } else {
                                    this.diagnosticsPublisher.addDiagnostics(uri,
                                                    new Diagnostic(sourceSectionToRange(nearestNode.getSourceSection()), "No coverage information available for this source section.",
                                                                    DiagnosticSeverity.Information, "Truffle"));
                                }
                            }
                        } else {
                            fillCompletionsWithLocals(textDocumentSurrogate, nearestNode, completions, null);
                        }
                    }
                } // isLineValid
            } else {
                // TODO(ds) remove that when solved
                System.out.println("!!! Cannot lookup Source for local scoping. No parsed Node found for URI: " + uri);
            }

            if (!isObjectPropertyCompletion) {
                fillCompletionsWithGlobals(textDocumentSurrogate, completions);
            }
        } finally {
            // Clean up
            textDocumentSurrogate.setFixedText(null);
        }

        return completions;
    }

    private EvaluationResult tryDifferentEvalStrategies(TextDocumentSurrogate textDocumentSurrogate, String langId, SourceWrapper sourceWrapper, Node nearestNode) {
        EvaluationResult literalEvalResult = evalLiteral(langId, nearestNode);
        if (literalEvalResult.isEvaluationDone() && !literalEvalResult.isError()) {
            System.out.println("Literal shortcut!");
            return literalEvalResult;
        }

        EvaluationResult coverageEvalResult = evalWithCoverageData(textDocumentSurrogate, nearestNode);
        if (coverageEvalResult.isEvaluationDone() && !coverageEvalResult.isError()) {
            return coverageEvalResult;
        }

        System.out.println("Trying run-to-section eval...");
        EvaluationResult runToSectionEvalResult = runToSectionAndEval(nearestNode, sourceWrapper);
        if (runToSectionEvalResult.isEvaluationDone() && !runToSectionEvalResult.isError()) {
            return runToSectionEvalResult;
        }

        System.out.println("Trying global eval...");
        EvaluationResult globalScopeEvalResult = evalInGlobalScope(langId, nearestNode);
        return globalScopeEvalResult;
    }

    private static boolean isInstrumentable(Node node) {
        return node instanceof InstrumentableNode && ((InstrumentableNode) node).isInstrumentable();
    }

    private EvaluationResult evalWithCoverageData(TextDocumentSurrogate textDocumentSurrogate, Node nearestNode) {
        if (!textDocumentSurrogate.hasCoverageData()) {
            return EvaluationResult.createEvaluationSectionNotReached();
        }

        for (Node sibling : nearestNode.getParent().getChildren()) {
            SourceSection siblingSection = sibling.getSourceSection();
            if (siblingSection != null && siblingSection.isAvailable()) {
                SourceLocation siblingLocation = SourceLocation.from(siblingSection);
                if (textDocumentSurrogate.isLocationCovered(siblingLocation)) {
                    List<CoverageData> coverageDataObjects = textDocumentSurrogate.getCoverageData(siblingLocation);

                    final LanguageInfo info = nearestNode.getRootNode().getLanguageInfo();
                    final String code = nearestNode.getSourceSection().getCharacters().toString();
                    final Source inlineEvalSource = Source.newBuilder(code).name("inline eval").language(info.getId()).mimeType("content/unknown").build();
                    for (CoverageData coverageData : coverageDataObjects) {
                        final ExecutableNode executableNode = this.env.parseInline(inlineEvalSource, nearestNode, coverageData.getFrame());
                        final CoverageEventNode coverageEventNode = coverageData.getCoverageEventNode();
                        coverageEventNode.insertChild(executableNode);
                        // TODO(ds) remove child after execution? There is no API for that?! ->
                        // replace child!

                        try {
                            System.out.println("Trying coverage-based eval...");
                            Object result = executableNode.execute(coverageData.getFrame());
                            return EvaluationResult.createResult(result);
                        } catch (Exception e) {
                        }
                    }
                }
            }
        }
        return EvaluationResult.createEvaluationSectionNotReached();
    }

    private static EvaluationResult evalLiteral(String langId, Node nearestNode) {
        Object literalObj = LanguageSpecificHacks.getLiteralObject(nearestNode, langId);
        if (literalObj != null) {
            return EvaluationResult.createResult(literalObj);
        }
        return EvaluationResult.createEvaluationSectionNotReached();
    }

    private EvaluationResult evalInGlobalScope(String langId, Node nearestNode) {
        try {
            CallTarget callTarget = this.env.parse(
                            Source.newBuilder(nearestNode.getEncapsulatingSourceSection().getCharacters()).language(langId).name("eval in global scope").build());
            Object result = callTarget.call();
            return EvaluationResult.createResult(result);
        } catch (Exception e) {
            return EvaluationResult.createError();
        }
    }

    private EvaluationResult runToSectionAndEval(final Node nearestNode, final SourceWrapper sourceWrapper) {
// if (nearestNode instanceof InstrumentableNode && (((InstrumentableNode)
// nearestNode).hasTag(StandardTags.StatementTag.class)) ||
// ((InstrumentableNode) nearestNode).hasTag(StandardTags.ExpressionTag.class)) {
        if (nearestNode instanceof InstrumentableNode) {
            final URI uri = sourceWrapper.getSource().getURI();
            final TextDocumentSurrogate surrogateOfOpenedFile = this.uri2TextDocumentSurrogate.get(uri);
            final SourceSection sourceSection = nearestNode.getSourceSection();
            Set<URI> coverageUris = surrogateOfOpenedFile.getCoverageUris(sourceSection);
            // TODO(ds) run code of all URIs?
            URI coverageUri = coverageUris == null ? null : coverageUris.stream().findFirst().orElseGet(() -> null);

            if (coverageUri == null) {
                try {
                    coverageUri = extractCoverageScriptPath(surrogateOfOpenedFile);
                } catch (InvalidCoverageScriptURI e) {
                    this.diagnosticsPublisher.addDiagnostics(uri, new Diagnostic(new Range(new Position(0, e.getIndex()), new Position(0, e.getLength())), e.getReason(), DiagnosticSeverity.Error,
                                    "Coverage analysis"));
                }

                if (coverageUri == null) {
// return EvaluationResult.createUnknownExecutionTarget();
                    coverageUri = uri;
                }
            }

            // TODO(ds) can we always assume the same language for the source and its test?
            TextDocumentSurrogate surrogateOfTestFile = this.uri2TextDocumentSurrogate.computeIfAbsent(coverageUri,
                            (_uri) -> new TextDocumentSurrogate(_uri, surrogateOfOpenedFile.getLangId()));

            final String name = uri.getPath();
            final CallTarget callTarget = parse(surrogateOfTestFile);

            EventBinding<ExecutionEventNodeFactory> binding = this.env.getInstrumenter().attachExecutionEventFactory(
                            createSourceSectionFilter(uri, sourceSection, name),
                            new InlineEvaluationEventFactory(this.env));
            try {
                callTarget.call();
            } catch (EvaluationResultException e) {
                return e.isError() ? EvaluationResult.createError() : EvaluationResult.createResult(e.getResult());
            } catch (RuntimeException e) {
                if (e instanceof TruffleException) {
                    if (((TruffleException) e).isExit()) {
                        return EvaluationResult.createEvaluationSectionNotReached();
                    } else {
                        return EvaluationResult.createError();
                    }
                }
            } finally {
                binding.dispose();
            }
            return EvaluationResult.createEvaluationSectionNotReached();
        } else {
            return EvaluationResult.createEvaluationSectionNotReached();
        }
    }

    private static SourceSectionFilter createSourceSectionFilter(final URI uri, final SourceSection sourceSection, final String name) {
        // @formatter:off
        return SourceSectionFilter.newBuilder()
//                        .tagIs(StatementTag.class, ExpressionTag.class)
                        .lineStartsIn(IndexRange.between(sourceSection.getStartLine(), sourceSection.getStartLine() + 1))
                        .lineEndsIn(IndexRange.between(sourceSection.getEndLine(), sourceSection.getEndLine() + 1))
                        .columnStartsIn(IndexRange.between(sourceSection.getStartColumn(), sourceSection.getStartColumn() + 1))
                        .columnEndsIn(IndexRange.between(sourceSection.getEndColumn(), sourceSection.getEndColumn() + 1))
                        .sourceIs(source -> source.getURI().equals(uri) || source.getName().equals(name)).build();
        // @formatter:on
    }

    private void fillCompletionsWithLocals(final TextDocumentSurrogate surrogate, Node nearestNode, CompletionList completions, VirtualFrame frame) {
        fillCompletionsWithScopesValues(surrogate, completions, this.env.findLocalScopes(nearestNode, frame), CompletionItemKind.Variable, SORTING_PRIORITY_LOCALS);
    }

    private void fillCompletionsWithGlobals(final TextDocumentSurrogate surrogate, CompletionList completions) {
        fillCompletionsWithScopesValues(surrogate, completions, this.env.findTopScopes(surrogate.getLangId()), null, SORTING_PRIORITY_GLOBALS);
    }

    private void fillCompletionsWithScopesValues(TextDocumentSurrogate surrogate, CompletionList completions, Iterable<Scope> scopes,
                    CompletionItemKind completionItemKindDefault, int displayPriority) {
        String langId = surrogate.getLangId();
        LinkedHashMap<String, Map<Object, Object>> scopeMap = scopesToObjectMap(scopes);
        // Filter duplicates
        String[] existingCompletions = completions.getItems().stream().map((item) -> item.getLabel()).toArray(String[]::new);
        Set<String> completionKeys = new HashSet<>(Arrays.asList(existingCompletions));
        int scopeCounter = 0;
        for (Entry<String, Map<Object, Object>> scopeEntry : scopeMap.entrySet()) {
            ++scopeCounter;
            for (Entry<Object, Object> entry : scopeEntry.getValue().entrySet()) {
                String key = entry.getKey().toString();
                if (completionKeys.contains(key)) {
                    // Scopes are provided from inner to outer, so we need to detect duplicate
                    // keys and only take
                    // those from the most inner scope
                    continue;
                } else {
                    completionKeys.add(key);
                }

                Object object;
                try {
                    object = entry.getValue();
                } catch (Exception e) {
                    continue;
                }
                CompletionItem completion = new CompletionItem(key);
                // Inner scopes should be displayed first, so sort by priority and scopeCounter
                // (the innermost scope
                // has the lowest counter)
                completion.setSortText(String.format("%d.%04d.%s", displayPriority, scopeCounter, key));
                CompletionItemKind completionItemKind = findCompletionItemKind(object);
                completion.setKind(completionItemKind != null ? completionItemKind : completionItemKindDefault);
                completion.setDetail(createCompletionDetail(object, langId, completion.getKind() == null));
                completion.setDocumentation("in " + scopeEntry.getKey());
                completions.getItems().add(completion);
            }
        }
    }

    private static CompletionItemKind findCompletionItemKind(Object object) {
        if (object instanceof TruffleObject) {
            TruffleObject truffleObjVal = (TruffleObject) object;
            boolean isExecutable = ForeignAccess.sendIsExecutable(IS_EXECUTABLE, truffleObjVal);
            boolean isInstatiatable = ForeignAccess.sendIsInstantiable(IS_INSTANTIABLE, truffleObjVal);
            if (isInstatiatable) {
                return CompletionItemKind.Class;
            }
            if (isExecutable) {
                return CompletionItemKind.Function;
            }
        }

        return null;
    }

    protected boolean fillCompletionsFromTruffleObject(CompletionList completions, String langId, Object object) {
        return fillCompletionsFromTruffleObject(completions, langId, object, getMetaObject(langId, object));
    }

    protected boolean fillCompletionsFromTruffleObject(CompletionList completions, String langId, Object object, Object metaObject) {
        Map<Object, Object> map = null;
        if (object instanceof TruffleObject) {
            map = ObjectStructures.asMap(new ObjectStructures.MessageNodes(), (TruffleObject) object);
        } else {
            Object boxedObject = LanguageSpecificHacks.getBoxedObject(object, langId);
            if (boxedObject instanceof TruffleObject) {
                map = ObjectStructures.asMap(new ObjectStructures.MessageNodes(), (TruffleObject) boxedObject);
            } else {
                System.out.println("Result is no TruffleObject: " + object.getClass());
            }
        }

        if (map == null) {
            System.out.println("No completions found for object: " + object);
            return false;
        }

        for (Entry<Object, Object> entry : map.entrySet()) {
            Object value;
            try {
                value = entry.getValue();
            } catch (Exception e) {
                continue;
            }
            CompletionItem completion = new CompletionItem(entry.getKey().toString());
            CompletionItemKind kind = findCompletionItemKind(value);
            completion.setKind(kind != null ? kind : CompletionItemKind.Property);
            completion.setDetail(createCompletionDetail(value, langId, true));
            completions.getItems().add(completion);
            String documentation = "";

            documentation = LanguageSpecificHacks.getSignatureForCallable(langId, entry, completion, documentation);

            documentation += "of meta object: " + metaObject.toString();
            completion.setDocumentation(documentation);
        }

        return !map.isEmpty();
    }

    private String createCompletionDetail(Object obj, String langId, boolean includeClass) {
        Object metaObject = getMetaObject(langId, obj);
        String metaInfo = metaObject != null ? metaObject.toString() : null;

// if (obj instanceof TruffleObject) {
// TruffleObject truffleObj = (TruffleObject) obj;
// boolean isNull = ForeignAccess.sendIsExecutable(IS_NULL, truffleObj);
// }

        String detailText = "";
        if (metaInfo == null) {
            detailText = includeClass ? obj.getClass().getName() : "";
        } else {
            detailText = metaInfo;
        }
        if (!detailText.isEmpty()) {
            detailText += " -> ";
        }
        return detailText + obj;
    }

    protected Object getMetaObject(String langId, Object object) {
        LanguageInfo languageInfo = this.env.findLanguage(object);
        if (languageInfo == null) {
            languageInfo = this.env.getLanguages().get(langId);
        }

        Object metaObject = null;
        if (languageInfo != null) {
            metaObject = this.env.findMetaObject(languageInfo, object);
        }
        return metaObject;
    }

    // TODO(ds) implement a getTypeOfExpression(node) method (use GraalJS annotated stuff)

    private static boolean isLineValid(int line, Source source) {
        // line is zero-based, source line is one-based
        return line >= 0 && line < source.getLineCount();
    }

    private static int zeroBasedLineToOneBasedLine(int line, Source source) {
        if (line + 1 <= source.getLineCount()) {
            return line + 1;
        }

        String text = source.getCharacters().toString();
        boolean isNewlineEnd = text.charAt(text.length() - 1) == '\n';
        if (isNewlineEnd) {
            return line;
        }

        throw new IllegalStateException("Mismatch in line numbers. Source line count (one-based): " + source.getLineCount() + ", zero-based line count: " + line);
    }

    private static SourceFix fixSourceAtPosition(TextDocumentSurrogate surrogate, int line, int character) {
        Source originalSource = Source.newBuilder(surrogate.getCurrentText()).language(surrogate.getLangId()).name(surrogate.getUri().toString()).build();
        int oneBasedLineNumber = zeroBasedLineToOneBasedLine(line, originalSource);
        String textAtCaretLine = originalSource.getCharacters(oneBasedLineNumber).toString();

        SourceFix sourceFix = LanguageSpecificHacks.fixSourceAtPosition(surrogate.getCurrentText(), surrogate.getLangId(), character,
                        originalSource, oneBasedLineNumber, textAtCaretLine);
        if (sourceFix != null) {
            return sourceFix;
        }

        final List<TextDocumentContentChangeEvent> changeEventsSinceLastSuccessfulParsing = surrogate.getChangeEventsSinceLastSuccessfulParsing();
        if (!changeEventsSinceLastSuccessfulParsing.isEmpty() && surrogate.getParsedSourceWrapper() != null && surrogate.getParsedSourceWrapper().isParsingSuccessful()) {
            String lastSuccessfullyParsedText = surrogate.getParsedSourceWrapper().getText();
            List<TextDocumentContentChangeEvent> allButLastChanges = changeEventsSinceLastSuccessfulParsing.subList(0, changeEventsSinceLastSuccessfulParsing.size() - 1);
            String fixedText = applyTextDocumentChanges(allButLastChanges, lastSuccessfullyParsedText, null);

            TextDocumentContentChangeEvent lastEvent = changeEventsSinceLastSuccessfulParsing.get(changeEventsSinceLastSuccessfulParsing.size() - 1);
            boolean isObjectPropertyCompletionCharacter = LanguageSpecificHacks.isObjectPropertyCompletionCharacter(lastEvent.getText(), surrogate.getLangId());

            return new SourceFix(fixedText, lastEvent.getRange().getEnd().getCharacter(), isObjectPropertyCompletionCharacter);
        }

        return null;
    }

    @SuppressWarnings("unused")
    public List<? extends DocumentHighlight> getHighlights(URI uri, int line, int character) {
        List<DocumentHighlight> highlights = new ArrayList<>();

        return highlights;
    }

    public static Range sourceSectionToRange(SourceSection section) {
        if (section == null) {
            return new Range(new Position(), new Position());
        }
        return new Range(
                        new Position(section.getStartLine() - 1, section.getStartColumn() - 1),
                        new Position(section.getEndLine() - 1, section.getEndColumn() /* -1 */));
    }

    private static URI extractCoverageScriptPath(TextDocumentSurrogate surrogate) throws InvalidCoverageScriptURI {
        String currentText = surrogate.getCurrentText();
        String firstLine;
        try {
            firstLine = new BufferedReader(new StringReader(currentText)).readLine();
        } catch (IOException e1) {
            throw new IllegalStateException(e1);
        }
        int startIndex = firstLine.indexOf(COVERAGE_SCRIPT);
        if (startIndex >= 0) {
            Path coverageScriptPath;
            try {
                coverageScriptPath = Paths.get(firstLine.substring(startIndex + COVERAGE_SCRIPT.length()));
                if (!coverageScriptPath.isAbsolute()) {
                    Path currentFile = Paths.get(surrogate.getUri());
                    coverageScriptPath = currentFile.resolveSibling(coverageScriptPath).normalize();
                }
            } catch (InvalidPathException e) {
                throw new InvalidCoverageScriptURI(e, startIndex + COVERAGE_SCRIPT.length(), firstLine.length());
            }
            if (!Files.exists(coverageScriptPath)) {
                throw new InvalidCoverageScriptURI(startIndex + COVERAGE_SCRIPT.length(), "File not found: " + coverageScriptPath.toString(), firstLine.length());
            }
            return coverageScriptPath.toUri();
        }
        return null;
    }

    public Future<Boolean> runCoverageAnalysis(final URI uri) {
        return evaluator.executeWithNestedContext(() -> {
            try {
                return runCoverageAnalysisWithNestedEnteredContext(uri);
            } finally {
                diagnosticsPublisher.reportCollectedDiagnostics();
            }
        });
    }

    protected boolean runCoverageAnalysisWithNestedEnteredContext(final URI uri) {
        final TextDocumentSurrogate surrogateOfOpendFile = this.uri2TextDocumentSurrogate.get(uri);
        URI tempCoverageUri;
        try {
            tempCoverageUri = extractCoverageScriptPath(surrogateOfOpendFile);
        } catch (InvalidCoverageScriptURI e) {
            this.diagnosticsPublisher.addDiagnostics(uri,
                            new Diagnostic(new Range(new Position(0, e.getIndex()), new Position(0, e.getLength())), e.getReason(), DiagnosticSeverity.Error, "Coverage analysis"));
            return false;
        }

        if (tempCoverageUri == null) {
// this.diagnosticsPublisher.addDiagnostics(uri, new Diagnostic(new Range(new Position(), new
// Position()), "No COVERAGE_SCRIPT:<path> found anywhere in first line.", DiagnosticSeverity.Error,
// "Coverage analysis"));
// return;
            tempCoverageUri = uri;
        }

        final URI coverageUri = tempCoverageUri;

        // Clean-up TODO(ds) how to do this without dropping everything? If we have different tests,
        // the coverage run of one test will remove any coverage info provided from the other tests.
        this.uri2TextDocumentSurrogate.entrySet().stream().forEach(entry -> entry.getValue().clearCoverage());

        try {
            // TODO(ds) can we always assume the same language for the source and its test?
            TextDocumentSurrogate surrogateOfTestFile = this.uri2TextDocumentSurrogate.computeIfAbsent(coverageUri, (_uri) -> new TextDocumentSurrogate(_uri, surrogateOfOpendFile.getLangId()));

            final CallTarget callTarget = parse(surrogateOfTestFile);
            EventBinding<ExecutionEventNodeFactory> eventFactoryBinding = env.getInstrumenter().attachExecutionEventFactory(
                            SourceSectionFilter.newBuilder().tagIs(StatementTag.class, ExpressionTag.class).build(),
                            new ExecutionEventNodeFactory() {

                                public ExecutionEventNode create(final EventContext eventContext) {
                                    final SourceSection section = eventContext.getInstrumentedSourceSection();
                                    if (section != null && section.isAvailable()) {
                                        final Node instrumentedNode = eventContext.getInstrumentedNode();
                                        Function<URI, TextDocumentSurrogate> func = (sourceUri) -> TruffleAdapter.this.uri2TextDocumentSurrogate.computeIfAbsent(
                                                        sourceUri,
                                                        (_uri) -> new TextDocumentSurrogate(_uri, instrumentedNode.getRootNode().getLanguageInfo().getId()));

                                        return new CoverageEventNode(eventContext.getInstrumentedSourceSection(), coverageUri, func);
                                    } else {
                                        return new ExecutionEventNode() {
                                        };
                                    }
                                }
                            });
            try {
                callTarget.call();
            } finally {
                eventFactoryBinding.dispose();
            }

            surrogateOfOpendFile.setCoverageAnalysisDone(true);

            showCoverage(uri);

            return true;
        } catch (Exception e) {
            if (e instanceof TruffleException) {
                Node location = ((TruffleException) e).getLocation();
                URI uriOfErronousSource = null;
                if (location != null) {
                    SourceSection sourceSection = location.getEncapsulatingSourceSection();
                    if (sourceSection != null) {
                        uriOfErronousSource = sourceSection.getSource().getURI();
                    }
                }

                if (uriOfErronousSource == null) {
                    uriOfErronousSource = uri;
                }
                this.diagnosticsPublisher.addDiagnostics(uriOfErronousSource, new Diagnostic(getRangeFrom((TruffleException) e), e.getMessage(), DiagnosticSeverity.Error, "Coverage analysis"));
            } else {
                e.printStackTrace(err);
            }
            return false;
        }
    }

    public List<? extends Location> getDefinitions(URI uri, int line, int character) {
        List<Location> locations = new ArrayList<>();

        String langId = this.uri2TextDocumentSurrogate.get(uri).getLangId();
        if (this.sourceProvider.getLoadedSource(langId, uri) != null) {
            SourceWrapper wrapper = this.sourceProvider.getLoadedSource(langId, uri);
            Source source = wrapper.getSource();

            NearestSections nearestSections = NearestSectionsFinder.getNearestSections(source, env, zeroBasedLineToOneBasedLine(line, source), character);
            SourceSection containsSection = nearestSections.getContainsSourceSection();
            if (containsSection != null) {
                SourceSection definition = this.section2definition.get(containsSection);
                if (definition != null) {
                    locations.add(new Location(uri.toString(), sourceSectionToRange(definition)));
                }
            }
        }
        return locations;
    }

    protected SourceSection findSourceLocation(TruffleObject obj) {
        LanguageInfo lang = env.findLanguage(obj);

        if (lang != null) {
            return env.findSourceLocation(lang, obj);
        }

        return null;
    }

    public Future<Hover> getHover(URI uri, int line, int column) {
        return evaluator.executeWithDefaultContext(() -> {
            try {
                return getHoverWithEnteredContext(uri, line, column);
            } finally {
                diagnosticsPublisher.reportCollectedDiagnostics();
            }
        });
    }

    public Hover getHoverWithEnteredContext(URI uri, int line, int column) {
        List<Either<String, MarkedString>> contents = new ArrayList<>();

        String langId = this.uri2TextDocumentSurrogate.get(uri).getLangId();
        if (this.sourceProvider.getLoadedSource(langId, uri) != null) {
            SourceWrapper wrapper = this.sourceProvider.getLoadedSource(langId, uri);
            Source source = wrapper.getSource();
            NearestSections nearestSections = NearestSectionsFinder.getNearestSections(source, env, zeroBasedLineToOneBasedLine(line, source), column);
            SourceSection containsSection = nearestSections.getContainsSourceSection();
            if (containsSection != null) {
                SourceSection definition = this.section2definition.get(containsSection);
                if (definition != null) {
                    MarkedString markedString = new MarkedString(langId, definition.getCharacters().toString());
                    contents.add(Either.forRight(markedString));
                }
            }
        }
        return new Hover(contents);
    }

    @SuppressWarnings("unused")
    public SignatureHelp signatureHelp(URI uri, int line, int character) {
        return new SignatureHelp();
    }

    public String getSourceText(Path path) {
        TextDocumentSurrogate surrogate = this.uri2TextDocumentSurrogate.get(path.toUri());
        return surrogate != null ? surrogate.getCurrentText() : null;
    }

    public boolean isVirtualFile(Path path) {
        return this.uri2TextDocumentSurrogate.containsKey(path.toUri());
    }

    public void showCoverage(URI uri) {
        final TextDocumentSurrogate surrogate = this.uri2TextDocumentSurrogate.get(uri);
        if (surrogate.getParsedSourceWrapper() != null) {
            // @formatter:off
            SourceSectionFilter filter = SourceSectionFilter.newBuilder()
                            .sourceIs(surrogate.getParsedSourceWrapper().getSource())
                            .tagIs(StatementTag.class)
                            .build();
            Set<SourceSection> duplicateFilter = new HashSet<>();
            env.getInstrumenter().attachLoadSourceSectionListener(filter, new LoadSourceSectionListener() {

                public void onLoad(LoadSourceSectionEvent event) {
                    SourceSection section = event.getSourceSection();
                    if (!surrogate.isLocationCovered(SourceLocation.from(section)) && !duplicateFilter.contains(section)) {
                        duplicateFilter.add(section);
                        Diagnostic diag = new Diagnostic(sourceSectionToRange(section),
                                                         "Not covered",
                                                         DiagnosticSeverity.Warning,
                                                         "Coverage Analysis");
                        diagnosticsPublisher.addDiagnostics(uri, diag);
                    }
                }
            }, true).dispose();
//            Diagnostic[] coverageDiagnostics = surrogate.getParsedSourceWrapper().getNodes().stream()
//                            .filter(node -> node instanceof InstrumentableNode && ((InstrumentableNode) node).hasTag(StatementTag.class))
//                            .filter(node -> !surrogate.isLocationCovered(SourceLocation.from(node.getSourceSection())))
//                            .map(node -> new Diagnostic(sourceSectionToRange(node.getSourceSection()),
//                                                        "Not covered",
//                                                        DiagnosticSeverity.Warning,
//                                                        "Coverage Analysis"))
//                            .toArray(Diagnostic[]::new);
            // @formatter:on
// diagnosticsPublisher.addDiagnostics(uri, coverageDiagnostics);
        } else {
            diagnosticsPublisher.addDiagnostics(uri,
                            new Diagnostic(new Range(new Position(), new Position()), "No coverage information available", DiagnosticSeverity.Error, "Coverage Analysis"));
        }
    }

    public void register(NestedEvaluator nestedEvaluator) {
        this.evaluator = nestedEvaluator;
    }
}
