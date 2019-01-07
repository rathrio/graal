package org.graalvm.tools.lsp.server.utils;

import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.graalvm.options.OptionValues;

import com.oracle.truffle.api.TruffleException;
import com.oracle.truffle.api.instrumentation.SourceSectionFilter.SourcePredicate;
import com.oracle.truffle.api.instrumentation.TruffleInstrument;
import com.oracle.truffle.api.nodes.LanguageInfo;
import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.api.source.SourceSection;

public class SourceUtils {

    public static final class SourceFix {
        public final String text;
        public final String removedCharacters;
        public final int characterIdx;

        public SourceFix(String text, String removedChracters, int characterIdx) {
            this.text = text;
            this.removedCharacters = removedChracters;
            this.characterIdx = characterIdx;
        }
    }

    public static boolean isLineValid(int zeroBasedLine, Source source) {
        // Source line is one-based
        return zeroBasedLine >= 0 &&
                        (zeroBasedLine < source.getLineCount() ||
                                        (zeroBasedLine == source.getLineCount() && endsWithNewline(source)) ||
                                        (zeroBasedLine == 0 && source.getLineCount() == 0));
    }

    public static boolean isColumnValid(int line, int column, Source source) {
        return column <= source.getLineLength(zeroBasedLineToOneBasedLine(line, source));
    }

    public static int zeroBasedLineToOneBasedLine(int line, Source source) {
        if (source.getLineCount() < line) {
            System.err.println("Warning: Line is out of range: " + line);
        }

        return line + 1;
    }

    private static boolean endsWithNewline(Source source) {
        String text = source.getCharacters().toString();
        boolean isNewlineEnd = !text.isEmpty() && text.charAt(text.length() - 1) == '\n';
        return isNewlineEnd;
    }

    public static Range sourceSectionToRange(SourceSection section) {
        if (section == null) {
            return new Range(new Position(), new Position());
        }
        int endColumn = section.getEndColumn();
        if (section.getCharacters().toString().endsWith("\n")) {
            // TODO(ds) Python problem - without correction, goto definition highlighting is not
            // working
            endColumn -= 1;
        }
        return new Range(
                        new Position(section.getStartLine() - 1, section.getStartColumn() - 1),
                        new Position(section.getEndLine() - 1, endColumn));
    }

    public static SourceSection findSourceLocation(TruffleInstrument.Env env, String langId, Object object) {
        LanguageInfo languageInfo = env.findLanguage(object);
        if (languageInfo == null) {
            languageInfo = env.getLanguages().get(langId);
        }

        SourceSection sourceSection = null;
        if (languageInfo != null) {
            sourceSection = env.findSourceLocation(languageInfo, object);
        }
        return sourceSection;
    }

    public static SourceFix removeLastTextInsertion(TextDocumentSurrogate surrogate, int originalCharacter) {
        TextDocumentContentChangeEvent lastChange = surrogate.getLastChange();
        Range range = lastChange.getRange();
        TextDocumentContentChangeEvent replacementEvent = new TextDocumentContentChangeEvent(
                        new Range(range.getStart(), new Position(range.getEnd().getLine(), range.getEnd().getCharacter() + lastChange.getText().length())), lastChange.getText().length(), "");
        String codeBeforeLastChange = applyTextDocumentChanges(Arrays.asList(replacementEvent), surrogate.getSource(), surrogate);
        int characterIdx = originalCharacter - (originalCharacter - range.getStart().getCharacter());

        return new SourceFix(codeBeforeLastChange, lastChange.getText(), characterIdx);
    }

    public static String applyTextDocumentChanges(List<? extends TextDocumentContentChangeEvent> list, Source source, TextDocumentSurrogate surrogate) {
        Source currentSource = null;
        String text = source.getCharacters().toString();
        StringBuilder sb = new StringBuilder(text);
        for (TextDocumentContentChangeEvent event : list) {
            if (currentSource == null) {
                currentSource = source;
            } else {
                currentSource = Source.newBuilder(currentSource.getLanguage(), sb, currentSource.getName()).cached(false).build();
            }

            Range range = event.getRange();
            if (range == null) {
                // The whole file has changed
                sb.setLength(0); // Clear StringBuilder
                sb.append(event.getText());
                continue;
            }

            Position start = range.getStart();
            Position end = range.getEnd();
            int startLine = start.getLine() + 1;
            int endLine = end.getLine() + 1;
            int replaceBegin = currentSource.getLineStartOffset(startLine) + start.getCharacter();
            int replaceEnd = currentSource.getLineStartOffset(endLine) + end.getCharacter();

            sb.replace(replaceBegin, replaceEnd, event.getText());

            if (surrogate != null && surrogate.hasCoverageData()) {
                updateCoverageData(surrogate, currentSource, event.getText(), range, replaceBegin, replaceEnd);
            }
        }
        return sb.toString();
    }

    private static void updateCoverageData(TextDocumentSurrogate surrogate, Source source, String newText, Range range, int replaceBegin, int replaceEnd) {
        Source newSourceSnippet = Source.newBuilder("dummyLanguage", newText, "dummyCoverage").cached(false).build();
        int linesNewText = newSourceSnippet.getLineCount() + (newText.endsWith("\n") ? 1 : 0) + (newText.isEmpty() ? 1 : 0);

        Source oldSourceSnippet = source.subSource(replaceBegin, replaceEnd - replaceBegin);
        int liensOldText = oldSourceSnippet.getLineCount() + (oldSourceSnippet.getCharacters().toString().endsWith("\n") ? 1 : 0) + (oldSourceSnippet.getLength() == 0 ? 1 : 0);

        int newLineModification = linesNewText - liensOldText;
        System.out.println("newLineModification: " + newLineModification);

        if (newLineModification != 0) {
            List<MutableSourceSection> sections = surrogate.getCoverageLocations();
            sections.stream().filter(section -> section.includes(range)).forEach(section -> {
                MutableSourceSection migratedSection = new MutableSourceSection(section);
                migratedSection.setEndLine(migratedSection.getEndLine() + newLineModification);
                surrogate.replace(section, migratedSection);
                System.out.println("Inlcuded - Old: " + section + " Fixed: " + migratedSection);
            });
            sections.stream().filter(section -> section.behind(range)).forEach(section -> {
                MutableSourceSection migratedSection = new MutableSourceSection(section);
                migratedSection.setStartLine(migratedSection.getStartLine() + newLineModification);
                migratedSection.setEndLine(migratedSection.getEndLine() + newLineModification);
                surrogate.replace(section, migratedSection);
                System.out.println("Behind   - Old: " + section + " Fixed: " + migratedSection);
            });
        }
    }

    public static Range getRangeFrom(TruffleException te) {
        Range range = new Range(new Position(), new Position());
        SourceSection sourceLocation = te.getSourceLocation() != null ? te.getSourceLocation()
                        : (te.getLocation() != null ? te.getLocation().getEncapsulatingSourceSection() : null);
        if (sourceLocation != null && sourceLocation.isAvailable()) {
            range = sourceSectionToRange(sourceLocation);
        }
        return range;
    }

    public static int convertLineAndColumnToOffset(Source source, int oneBasedLineNumber, int column) {
        int offset = source.getLineStartOffset(oneBasedLineNumber);
        if (column > 0) {
            offset += column - 1;
        }
        return offset;
    }

    public static URI getOrFixFileUri(Source source) {
        if (source.getURI().getScheme().equals("file")) {
            return source.getURI();
        } else if (source.getURI().getScheme().equals("truffle")) {
            // We assume, that the source name is a valid file path if
            // the URI has no file scheme
            Path path = Paths.get(source.getName());
            return path.toUri();
        } else {
            throw new IllegalStateException("Source has an URI with unknown schema: " + source.getURI());
        }
    }

    public static boolean isValidSourceSection(SourceSection sourceSection, OptionValues options) {
        SourcePredicate predicate = SourcePredicateBuilder.newBuilder().excludeInternal(options).build();
        return sourceSection != null && sourceSection.isAvailable() && predicate.test(sourceSection.getSource());
    }

}
