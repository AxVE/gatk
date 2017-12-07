package org.broadinstitute.hellbender.tools.copynumber;

import htsjdk.samtools.SAMSequenceDictionary;
import org.broadinstitute.barclay.argparser.Advanced;
import org.broadinstitute.barclay.argparser.Argument;
import org.broadinstitute.barclay.argparser.ArgumentCollection;
import org.broadinstitute.barclay.argparser.CommandLineProgramProperties;
import org.broadinstitute.barclay.help.DocumentedFeature;
import org.broadinstitute.hellbender.cmdline.CommandLineProgram;
import org.broadinstitute.hellbender.cmdline.StandardArgumentDefinitions;
import org.broadinstitute.hellbender.cmdline.programgroups.CopyNumberProgramGroup;
import org.broadinstitute.hellbender.exceptions.UserException;
import org.broadinstitute.hellbender.tools.copynumber.arguments.GermlineContigPloidyModelArgumentCollection;
import org.broadinstitute.hellbender.tools.copynumber.arguments.GermlineContigPloidyHybridADVIArgumentCollection;
import org.broadinstitute.hellbender.tools.copynumber.formats.CopyNumberStandardArgument;
import org.broadinstitute.hellbender.tools.copynumber.formats.collections.CoveragePerContigCollection;
import org.broadinstitute.hellbender.tools.copynumber.formats.collections.SimpleCountCollection;
import org.broadinstitute.hellbender.tools.copynumber.formats.collections.SimpleIntervalCollection;
import org.broadinstitute.hellbender.tools.copynumber.formats.metadata.LocatableMetadata;
import org.broadinstitute.hellbender.tools.copynumber.formats.metadata.SimpleLocatableMetadata;
import org.broadinstitute.hellbender.tools.copynumber.formats.records.CoveragePerContig;
import org.broadinstitute.hellbender.tools.copynumber.formats.records.SimpleCount;
import org.broadinstitute.hellbender.utils.SimpleInterval;
import org.broadinstitute.hellbender.utils.Utils;
import org.broadinstitute.hellbender.utils.io.IOUtils;
import org.broadinstitute.hellbender.utils.io.Resource;
import org.broadinstitute.hellbender.utils.python.PythonScriptExecutor;

import java.io.File;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Model the baseline ploidy per contig for germline samples given their fragment counts.
 * These should be either HDF5 or TSV count files generated by {@link CollectFragmentCounts}.
 *
 * <p>This tool has two run-runMode as described below:</p>
 * <dl>
 *     <dt>COHORT run-runMode</dt>
 *     <dd>If a model parameter directory is not provided, the tool is run in the COHORT runMode. In this run-runMode,
 *      ploidy model parameters (e.g. coverage bias and variance in each contig) are inferred
 *      along with baseline contig ploidy of each sample. A table specifying priors for the ploidy per contig
 *      is required in this run-runMode.
 *
 *      The output will contain two subdirectories, one ending with "-model" and the other with "-calls".
 *      The model subdirectory contains the inferred parameters of the ploidy model, which may be used later
 *      for ploidy determination in one or more similarly-sequenced samples (see below).
 *
 *      The calls subdirectory contains one subdirectory for each sample, listing various sample-specific
 *      quantities such as the global read-depth, average ploidy, per-contig baseline ploidies, and per-contig
 *      coverage variance estimates.</dd>
 *
 *     <dt>CASE run-runMode</dt>
 *     <dd>If a previously obtained model parameter bundle is provided (e.g. from a previous run on a similar set
 *      of samples), then the tool is run in the CASE runMode. In this run-runMode, the ploidy model parameters are
 *      set to the given parameter bundle and only sample-specific quantities are estimated. Subsequently, the
 *      output directory will contain only the "-calls" subdirectory and is structured as described above.
 *
 *      In this run runMode, the contig ploidy prior table is taken directly from the provided model parameters
 *      path and must be not provided again.</dd>
 * </dl>
 *
 * <h3>Examples</h3>
 *
 * <h2> COHORT run-runMode: </h2>
 * <p><pre>
 * gatk-launch --javaOptions "-Xmx4g" DetermineGermlineContigPloidy \
 *   --input normal_1.counts.hdf5 \
 *   --input normal_2.counts.hdf5 \
 *   ... \
 *   --output output_dir \
 *   --output-prefix normal_cohort
 * </pre></p>
 *
 * <h2> CASE run-runMode: </h2>
 * <p><pre>
 * gatk-launch --javaOptions "-Xmx4g" DetermineGermlineContigPloidy \
 *   --model a_valid_ploidy_model_dir
 *   --input normal_1.counts.hdf5 \
 *   --input normal_2.counts.hdf5 \
 *   ... \
 *   --output output_dir \
 *   --output-prefix normal_case
 * </pre></p>
 *
 * @author Mehrtash Babadi &lt;mehrtash@broadinstitute.org&gt;
 * @author Samuel Lee &lt;slee@broadinstitute.org&gt;
 */
@CommandLineProgramProperties(
        summary = "Model the baseline ploidy per contig for germline samples given their fragment counts.",
        oneLineSummary = "Model the baseline ploidy per contig for germline samples given their fragment counts.",
        programGroup = CopyNumberProgramGroup.class
)
@DocumentedFeature
public final class DetermineGermlineContigPloidy extends CommandLineProgram {
    public enum RunMode {
        COHORT, CASE
    }

    private static final String COHORT_DETERMINE_PLOIDY_AND_DEPTH_PYTHON_SCRIPT = "cohort_determine_ploidy_and_depth.py";
    private static final String CASE_DETERMINE_PLOIDY_AND_DEPTH_PYTHON_SCRIPT = "case_determine_ploidy_and_depth.py";

    public static final String MODEL_PATH_SUFFIX = "-model";
    public static final String CALLS_PATH_SUFFIX = "-calls";

    public static final String CONTIG_PLOIDY_PRIORS_FILE_LONG_NAME = "contig-ploidy-priors";

    @Argument(
            doc = "Input read-count files containing integer read counts in genomic intervals for all samples.  " +
                    "Intervals must be identical and in the same order for all samples.  " +
                    "If only a single sample is specified, an input ploidy-model directory must also be specified.  ",
            fullName = StandardArgumentDefinitions.INPUT_LONG_NAME,
            minElements = 1
    )
    private List<File> inputReadCountFiles = new ArrayList<>();

    @Argument(
            doc = "Input file specifying contig-ploidy priors.  If only a single sample is specified, this input should not be provided.  " +
                    "If multiple samples are specified, this input is required.",
            fullName = CONTIG_PLOIDY_PRIORS_FILE_LONG_NAME,
            optional = true
    )
    private File inputContigPloidyPriorsFile;

    @Argument(
            doc = "Input ploidy-model directory.  If only a single sample is specified, this input is required.  " +
                    "If multiple samples are specified, this input should not be provided.",
            fullName = CopyNumberStandardArgument.MODEL_LONG_NAME,
            optional = true
    )
    private String inputModelDir;

    @Argument(
            doc = "Prefix for output filenames.",
            fullName =  CopyNumberStandardArgument.OUTPUT_PREFIX_LONG_NAME
    )
    private String outputPrefix;

    @Argument(
            doc = "Output directory for sample contig-ploidy calls and the contig-ploidy model parameters for " +
                    "future use.",
            fullName = StandardArgumentDefinitions.OUTPUT_LONG_NAME
    )
    private String outputDir;

    @Advanced
    @ArgumentCollection
    private GermlineContigPloidyModelArgumentCollection germlineContigPloidyModelArgumentCollection =
            new GermlineContigPloidyModelArgumentCollection();

    @Advanced
    @ArgumentCollection
    private GermlineContigPloidyHybridADVIArgumentCollection germlineContigPloidyHybridADVIArgumentCollection =
            new GermlineContigPloidyHybridADVIArgumentCollection();

    private RunMode runMode;

    @Override
    protected Object doWork() {
        setModeAndValidateArguments();

        //get sequence dictionary and intervals from the first read-count file to use to validate remaining files
        //(this first file is read again below, which is slightly inefficient but is probably not worth the extra code)
        final File firstReadCountFile = inputReadCountFiles.get(0);
        logger.info(String.format("Retrieving intervals from first read-count file (%s)...", firstReadCountFile));
        final SimpleCountCollection firstReadCounts = SimpleCountCollection.read(firstReadCountFile);
        final SAMSequenceDictionary sequenceDictionary = firstReadCounts.getMetadata().getSequenceDictionary();
        final List<SimpleInterval> intervals = firstReadCounts.getIntervals();

        //read in count files and output intervals and samples x coverage-per-contig table to temporary files
        final File intervalsFile = IOUtils.createTempFile("intervals", ".tsv");
        final LocatableMetadata metadata = new SimpleLocatableMetadata(sequenceDictionary);
        new SimpleIntervalCollection(metadata, intervals).write(intervalsFile);
        final File samplesByCoveragePerContigFile = IOUtils.createTempFile("samples-by-coverage-per-contig", ".tsv");
        writeSamplesByCoveragePerContig(samplesByCoveragePerContigFile, metadata, intervals);

        //call python inference code
        final boolean pythonReturnCode = executeDeterminePloidyAndDepthPythonScript(
                samplesByCoveragePerContigFile, intervalsFile);

        if (!pythonReturnCode) {
            throw new UserException("Python return code was non-zero.");
        }

        logger.info("Germline contig ploidy determination complete.");

        return "SUCCESS";
    }

    private void setModeAndValidateArguments() {
        germlineContigPloidyModelArgumentCollection.validate();
        germlineContigPloidyHybridADVIArgumentCollection.validate();
        Utils.nonNull(outputPrefix);
        inputReadCountFiles.forEach(IOUtils::canReadFile);
        Utils.validateArg(inputReadCountFiles.size() == new HashSet<>(inputReadCountFiles).size(),
                "List of input read-count files cannot contain duplicates.");

        if (inputModelDir != null) {
            runMode = RunMode.CASE;
            logger.info("A contig-ploidy model was provided, running in case runMode...");
            Utils.validateArg(new File(inputModelDir).exists(),
                    String.format("Input ploidy-model directory %s does not exist.", inputModelDir));
            if (inputContigPloidyPriorsFile != null) {
                throw new UserException.BadInput("Invalid combination of inputs: Running in case runMode, " +
                        "but contig-ploidy priors were provided.");
            }
        } else {
            runMode = RunMode.COHORT;
            logger.info("No contig-ploidy model was provided, running in cohort runMode...");
            if (inputReadCountFiles.size() == 1) {
                throw new UserException.BadInput("Invalid combination of inputs: Running in cohort runMode, " +
                        "but only a single sample was provided.");
            }
            if (inputContigPloidyPriorsFile == null){
                throw new UserException.BadInput("Contig-ploidy priors must be provided in cohort runMode.");
            }
            IOUtils.canReadFile(inputContigPloidyPriorsFile);
        }
    }

    private void writeSamplesByCoveragePerContig(final File samplesByCoveragePerContigFile,
                                                 final LocatableMetadata metadata,
                                                 final List<SimpleInterval> intervals) {
        logger.info("Validating and aggregating coverage per contig from input read-count files...");
        final int numSamples = inputReadCountFiles.size();
        final List<CoveragePerContig> coveragePerContigs = new ArrayList<>(numSamples);
        final List<String> contigs = intervals.stream().map(SimpleInterval::getContig).distinct()
                .collect(Collectors.toList());
        final ListIterator<File> inputReadCountFilesIterator = inputReadCountFiles.listIterator();
        while (inputReadCountFilesIterator.hasNext()) {
            final int sampleIndex = inputReadCountFilesIterator.nextIndex();
            final File inputReadCountFile = inputReadCountFilesIterator.next();
            logger.info(String.format("Aggregating read-count file %s (%d / %d)",
                    inputReadCountFile, sampleIndex + 1, numSamples));
            final SimpleCountCollection readCounts = SimpleCountCollection.read(inputReadCountFile);
            Utils.validateArg(readCounts.getMetadata().getSequenceDictionary()
                            .isSameDictionary(metadata.getSequenceDictionary()),
                    String.format("Sequence dictionary for read-count file %s does not match those " +
                            "in other read-count files.", inputReadCountFile));
            Utils.validateArg(readCounts.getIntervals().equals(intervals),
                    String.format("Intervals for read-count file %s do not match those in other " +
                            "read-count files.", inputReadCountFile));
            //calculate coverage per contig and construct record for each sample
            coveragePerContigs.add(new CoveragePerContig(
                    readCounts.getMetadata().getSampleName(),
                    readCounts.getRecords().stream()
                            .collect(Collectors.groupingBy(
                                    SimpleCount::getContig,
                                    LinkedHashMap::new,
                                    Collectors.summingInt(SimpleCount::getCount)))));
        }
        new CoveragePerContigCollection(metadata, coveragePerContigs, contigs)
                .write(samplesByCoveragePerContigFile);
    }

    private boolean executeDeterminePloidyAndDepthPythonScript(final File samplesByCoveragePerContigFile,
                                                               final File intervalsFile) {
        final PythonScriptExecutor executor = new PythonScriptExecutor(true);
        final String outputDirArg = Utils.nonEmpty(outputDir).endsWith(File.separator)
                ? outputDir
                : outputDir + File.separator;    //add trailing slash if necessary
        //note that the samples x coverage-by-contig table is referred to as "metadata" by gcnvkernel
        final List<String> arguments = new ArrayList<>(Arrays.asList(
                "--sample_coverage_metadata=" + samplesByCoveragePerContigFile.getAbsolutePath(),
                "--output_calls_path=" + outputDirArg + outputPrefix + CALLS_PATH_SUFFIX));
        arguments.addAll(germlineContigPloidyModelArgumentCollection.generatePythonArguments(runMode));
        arguments.addAll(germlineContigPloidyHybridADVIArgumentCollection.generatePythonArguments());

        final String script;
        if (runMode == RunMode.COHORT) {
            script = COHORT_DETERMINE_PLOIDY_AND_DEPTH_PYTHON_SCRIPT;
            arguments.add("--interval_list=" + intervalsFile.getAbsolutePath());
            arguments.add("--contig_ploidy_prior_table=" + inputContigPloidyPriorsFile.getAbsolutePath());
            arguments.add("--output_model_path=" + outputDirArg + outputPrefix + MODEL_PATH_SUFFIX);
        } else {
            script = CASE_DETERMINE_PLOIDY_AND_DEPTH_PYTHON_SCRIPT;
            arguments.add("--input_model_path=" + inputModelDir);
        }
        System.out.println(arguments.stream().collect(Collectors.joining(" ")));
        return executor.executeScript(
                new Resource(script, GermlineCNVCaller.class),
                null,
                arguments);
    }
}
