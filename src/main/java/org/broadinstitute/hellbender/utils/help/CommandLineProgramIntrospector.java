package org.broadinstitute.hellbender.utils.help;

import org.broadinstitute.barclay.argparser.ClassFinder;
import org.broadinstitute.barclay.argparser.CommandLineArgumentParser;
import org.broadinstitute.barclay.argparser.CommandLinePluginDescriptor;
import org.broadinstitute.barclay.argparser.CommandLinePluginProvider;
import org.broadinstitute.barclay.argparser.CommandLineProgramProperties;
import org.broadinstitute.hellbender.cmdline.CommandLineProgram;
import org.broadinstitute.hellbender.cmdline.PicardCommandLineProgramExecutor;
import org.broadinstitute.hellbender.utils.ClassUtils;
import org.broadinstitute.hellbender.utils.Utils;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Utility to query definitions of program arguments given their names.
 */
public class CommandLineProgramIntrospector {

    private final static Map<Class<?>, CommandLineProgramIntrospector> introspectors = new HashMap<>();
    private static final Map<String, Class<?>> simpleNameToClass = new LinkedHashMap<>();

    /*TODO this code was copied from Picard code base,
     *TODO we need to expose this functionality in picard and the pull it from the dependency
     *TODO instead.
     */
    static {

        final List<String> packageList = Arrays.asList("org.broadinstitute.hellbender", "picard");

        final ClassFinder classFinder = new ClassFinder();
        for (final String pkg : packageList) {
            classFinder.find(pkg, picard.cmdline.CommandLineProgram.class);
            classFinder.find(pkg, CommandLineProgram.class);
        }
        String missingAnnotationClasses = "";
        final Set<Class<?>> toCheck = classFinder.getClasses();
        for (final Class<?> clazz : toCheck) {
            if (clazz.equals(PicardCommandLineProgramExecutor.class)) {
                continue;
            }
            // No interfaces, synthetic, primitive, local, or abstract classes.
            if (ClassUtils.canMakeInstances(clazz)) {
                final CommandLineProgramProperties property = clazz.getAnnotation(CommandLineProgramProperties.class);
                // Check for missing annotations
                if (null == property) {
                    if (missingAnnotationClasses.isEmpty()) missingAnnotationClasses += clazz.getSimpleName();
                    else missingAnnotationClasses += ", " + clazz.getSimpleName();
                } else { /** We should check for missing annotations later **/
                    if (simpleNameToClass.containsKey(clazz.getSimpleName())) {
                        throw new RuntimeException("Simple class name collision: " + clazz.getName());
                    }
                    simpleNameToClass.put(clazz.getSimpleName(), clazz);
                }
            }
        }
        if (!missingAnnotationClasses.isEmpty()) {
            throw new RuntimeException("The following classes are missing the required CommandLineProgramProperties annotation: " + missingAnnotationClasses);
        }

    }

    private final Class<?> clazz;
    private final CommandLineArgumentParser parser;
    private final Map<String, CommandLineArgumentParser.ArgumentDefinition> definitionsByName;

    private CommandLineProgramIntrospector(final Class<?> clazz) {
        this.clazz = Utils.nonNull(clazz);
        this.definitionsByName = new HashMap<>();
        try {
            final Object program = clazz.newInstance();
            final CommandLineArgumentParser parser;
            if (program instanceof CommandLinePluginProvider) {
                final List<? extends CommandLinePluginDescriptor<?>> pluginDescriptors = ((CommandLinePluginProvider) program).getPluginDescriptors();
                parser = new CommandLineArgumentParser(program, pluginDescriptors, Collections.emptySet());
            } else {
                parser = new CommandLineArgumentParser(program);
            }
            this.parser = parser;
        } catch (final InstantiationException | IllegalAccessException e) {
            throw new IllegalArgumentException("could not create parser for " + clazz.getName(), e);
        }
        for (final CommandLineArgumentParser.ArgumentDefinition definition : parser.getArgumentDefinitions()) {
            for (final String name : definition.getNames()) {
                definitionsByName.put(name, definition);
            }
        }
    }

    public static CommandLineProgramIntrospector of(final String name) {
        final Class<?> clazz = simpleNameToClass.get(name);
        if (clazz == null) {
            return null;
        } else {
            return of(clazz);
        }
    }

    public static CommandLineProgramIntrospector of(final Class<?> clazz) {
        return introspectors.computeIfAbsent(clazz, CommandLineProgramIntrospector::new);

    }

    public CommandLineArgumentParser.ArgumentDefinition getArgument(final String name) {
        Utils.nonNull(name);
        return definitionsByName.get(name);
    }
}
