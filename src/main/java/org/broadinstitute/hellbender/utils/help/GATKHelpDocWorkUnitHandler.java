package org.broadinstitute.hellbender.utils.help;

import com.netflix.servo.util.VisibleForTesting;
import org.broadinstitute.barclay.argparser.CommandLineArgumentParser;
import org.broadinstitute.barclay.argparser.StrictBooleanConverter;
import org.broadinstitute.barclay.help.DefaultDocWorkUnitHandler;
import org.broadinstitute.barclay.help.DocWorkUnit;

import org.broadinstitute.barclay.help.HelpDoclet;
import picard.sam.FastqToSam;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The GATK Documentation work unit handler class that is the companion to GATKHelpDoclet.
 *
 * NOTE: Methods in this class are intended to be called by Gradle/Javadoc only, and should not be called
 * by methods that are used by the GATK runtime, as this class assumes a dependency on com.sun.javadoc classes
 * which may not be present.
 */
public class GATKHelpDocWorkUnitHandler extends DefaultDocWorkUnitHandler {

    private final static String GATK_JAVADOC_TAG_PREFIX = "GATK"; // prefix for custom javadoc tags used by GATK

    private final static String GATK_FREEMARKER_TEMPLATE_NAME = "generic.template.html";

    private final Pattern PICARD_CODE_EXAMPLE_BLOCK = Pattern.compile("<pre>\\s*java\\s*.*?\\s*-jar\\s*\\S*?picard\\S*?\\.jar(.|\\s)*?<\\/pre>",
            Pattern.CASE_INSENSITIVE);

    private final Pattern PICARD_CODE_EXAMPLE_COMMAND_START = Pattern.compile("java\\s*(.*?)\\s*-jar\\s*\\S*?picard\\S*?\\.jar",
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);

    private final Pattern PICARD_CODE_EXAMPLE_COMMAND_TOOLNAME = Pattern.compile("\\S+");

    private final Pattern PICARD_CODE_EXAMPLE_COMMAND_ARGUMENT_VALUE = Pattern.compile("(\\S+?)\\s*=\\s*(\\S+)");

    private final Pattern PICARD_FLAG_TRUE_VALUE = Pattern.compile("\\s*t(rue)?\\s*", Pattern.CASE_INSENSITIVE);


    /**
     * Detects the end of the picard command line: either {@code </pre>} or an empty line or a non-empty line where
     * the last non-space character is not the slash "\".
     */
    private final Pattern PICARD_CODE_EXAMPLE_COMMAND_END = Pattern.compile("(<\\/pre>)|(^\\s*$)|((?<=[^ \\t\\\\])\\s*$)",
            Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);

    public GATKHelpDocWorkUnitHandler(final GATKHelpDoclet doclet) {
        super(doclet);
    }
    /**
     * @return Prefix for custom GATK tags that should be lifted from the javadoc and stored in the
     * FreeMarker map. These will be available in the template returned by {@link #getTemplateName}.
     */
    @Override
    protected String getTagFilterPrefix() { return GATK_JAVADOC_TAG_PREFIX; }

    /**
     * @param workUnit the classdoc object being processed
     * @return the name of a the freemarker template to be used for the class being documented.
     * Must reside in the folder passed to the Barclay Doclet via the "-settings-dir" parameter to
     * Javadoc.
     */
    @Override
    public String getTemplateName(final DocWorkUnit workUnit) { return GATK_FREEMARKER_TEMPLATE_NAME; }

    /**
     * Additional GATK specific work-unit processing.
     * @param workUnit the target work-unit.
     * @param featureMaps feature-maps.
     * @param groupMaps group-maps.
     */
    @Override
    public void processWorkUnit(
            final DocWorkUnit workUnit,
            final List<Map<String, String>> featureMaps,
            final List<Map<String, String>> groupMaps) {
        super.processWorkUnit(workUnit, featureMaps, groupMaps);
        final CharSequence description = new StringBuilder((CharSequence) workUnit.getProperty("description"));
        final Matcher matcher = PICARD_CODE_EXAMPLE_BLOCK.matcher(description);
        if (!matcher.find()) { // we cannot find a picard code example.
            return;
        } else {
            final String newDescription = translatePicardCodeBlocks(description, matcher);
            workUnit.setProperty("description", newDescription);
        }
    }

    private String translatePicardCodeBlocks(final CharSequence description, final Matcher matcher) {
        final StringBuilder result = new StringBuilder(description.length() << 1);
        int lastMatchedOffset = 0;
        do {
            result.append(description.subSequence(lastMatchedOffset, matcher.start()));
            translatePicardCodeBlock(description, matcher.start(), matcher.end(), result);
            lastMatchedOffset = matcher.end();
        } while(matcher.find());
        result.append(description.subSequence(lastMatchedOffset, description.length()));
        return result.toString();
    }

    @VisibleForTesting
    public void translatePicardCodeBlock(final CharSequence block, final int start, final int end, final StringBuilder result) {
        final Matcher startMatcher = PICARD_CODE_EXAMPLE_COMMAND_START.matcher(block).region(start, end);
        final Matcher endMatcher = PICARD_CODE_EXAMPLE_COMMAND_END.matcher(block).region(start, end);
        int lastMatcherOffset = start;
        while (startMatcher.find()) {
            result.append(block.subSequence(lastMatcherOffset, startMatcher.start()));
            endMatcher.region(startMatcher.start(), end);
            if (!endMatcher.find()) { // we cannot find and end!? (in practice this hould never happen though.
                lastMatcherOffset = startMatcher.start();
            } else {
                final String javaOptions = startMatcher.group(1).trim();
                final CharSequence toolAndArguments = block.subSequence(startMatcher.end(), endMatcher.start());
                result.append("gatk");
                if (!javaOptions.isEmpty()) {
                    result.append(" --javaOptions '" + javaOptions + "'");
                }
                translatePicardToolAndArguments(toolAndArguments, result);
                result.append(endMatcher.group());
                lastMatcherOffset = endMatcher.end();
                startMatcher.region(endMatcher.end(), end);
            }
        }
        result.append(block.subSequence(lastMatcherOffset, end));
    }

    private void translatePicardToolAndArguments(final CharSequence toolAndArguments, final StringBuilder buffer) {
        final Matcher toolNameMatcher = PICARD_CODE_EXAMPLE_COMMAND_TOOLNAME.matcher(toolAndArguments);
        if (!toolNameMatcher.find()) {
            buffer.append(toolAndArguments);
        } else {
            buffer.append(toolAndArguments.subSequence(0, toolNameMatcher.start()));
            buffer.append(toolNameMatcher.group());
            final String toolName = toolNameMatcher.group();
            final CommandLineProgramIntrospector introspector = CommandLineProgramIntrospector.of(toolName);
            if (introspector == null) {
                printError("unknown picard tool-name: " + toolName);
            } else {
                printNotice("found picard code command example on tool: " + toolName);
            }
            final Matcher argumentMatcher = PICARD_CODE_EXAMPLE_COMMAND_ARGUMENT_VALUE.matcher(
                    toolAndArguments).region(toolNameMatcher.end(), toolAndArguments.length());
            int lastMatcherOffset = toolNameMatcher.end();
            while (argumentMatcher.find()) {
                buffer.append(toolAndArguments.subSequence(lastMatcherOffset, argumentMatcher.start()));
                lastMatcherOffset = argumentMatcher.end();
                final String name = argumentMatcher.group(1);
                final String value = argumentMatcher.group(2);
                final CommandLineArgumentParser.ArgumentDefinition definition = introspector != null ? introspector.getArgument(name) : null;
                final String prefix;
                if (definition != null) { // we can find out whether is long or short name using the descriptor.
                    prefix = definition.getLongName().equals(name) ? "--" : "-";
                } else if (name.contains("_")) { // heuristic, if it has a _ then is long.
                    prefix = "--";
                } else { // we apply a heuristic where 3 or less letter long names are short, 4 or more are long.
                    prefix = name.length() <= 3 ? "-" : "--";
                }
                if (definition.isFlag() && PICARD_FLAG_TRUE_VALUE.matcher(value).matches()) {
                    buffer.append(prefix).append(name); // we compress ARG=true to -ARG or --ARG for flags.
                } else {
                    buffer.append(prefix).append(name).append(' ').append(value);
                }
            }
            buffer.append(toolAndArguments.subSequence(lastMatcherOffset, toolAndArguments.length()));
        }
    }

    private void printError(final String message) {
        ((GATKHelpDoclet)getDoclet()).printError(message);
    }

    private void printWarnings(final String message) {
        ((GATKHelpDoclet)getDoclet()).printWarning(message);
    }

    private void printNotice(final String message) {
        ((GATKHelpDoclet)getDoclet()).printNotice(message);
    }
}
