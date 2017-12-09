package org.broadinstitute.hellbender.utils.help;

import com.sun.javadoc.RootDoc;
import com.sun.tools.javadoc.RootDocImpl;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.broadinstitute.hellbender.utils.test.BaseTest;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Created by valentin on 12/8/17.
 */
public class GATKHelpDocletTest extends BaseTest {

    @Test(dataProvider = "testPicardTransformationData")
    public void testPicardTransformation(final String input, final String expected) {
        final StringBuilder original = new StringBuilder(input);
        final GATKHelpDocWorkUnitHandler handler = new GATKHelpDocWorkUnitHandler(new GATKHelpDoclet());
        final StringBuilder result = new StringBuilder(original.length() << 1);
        handler.translatePicardCodeBlock(original, 0, original.length(), result);
        Assert.assertEquals(result.toString(), expected);
    }

    public void testPicardXXX() {

    }

    @DataProvider(name = "testPicardTransformationData")
    public Object[][] testPicardTransformationData() {

        final String[] interleavedInputExceptedPairs = {
                // simple one liner with a flag.
               lines("<pre>java -jar picard.jar MergeVcfs I=file1 O=file2 CREATE_INDEX=true INPUT=file3</pre>"),
                lines("<pre>gatk MergeVcfs -I file1 -O file2 --CREATE_INDEX --INPUT file3</pre>"),
                // same in multiple lines:
                lines("<pre>   ",
                      "        java -jar picard.jar MergeVcfs \\  ",
                      "                             I=file1   \\",
                      "                             O=file2   \\",
                      "                             INPUT=fil3 \\",
                      "                             CREATE_INDEX=true  ",
                      "</pre>"),
                lines("<pre>   ",
                        "        gatk MergeVcfs \\  ",
                        "                             -I file1   \\",
                        "                             -O file2   \\",
                        "                             --INPUT fil3 \\",
                        "                             --CREATE_INDEX  ",
                        "</pre>"),
                // <pre> share line with other elements of the command line example
                lines("<pre>   java -jar picard.jar MergeVcfs \\  ",
                        "                             I=file1   \\",
                        "                             O=file2   \\",
                        "                             INPUT=fil3 \\",
                        "                             CREATE_INDEX=true </pre>"),
                lines("<pre>   gatk MergeVcfs \\  ",
                        "                             -I file1   \\",
                        "                             -O file2   \\",
                        "                             --INPUT fil3 \\",
                        "                             --CREATE_INDEX </pre>"),
                // mulitple commands:
                lines("<pre>   java -jar picard.jar MergeVcfs \\  ",
                        "                             I=file1  ",
                        "      java -jar picard.jar MergeVcfs O=file2   \\",
                        "                             INPUT=fil3 \\",
                        "                             CREATE_INDEX=true </pre>"),
                lines("<pre>   gatk MergeVcfs \\  ",
                        "                             -I file1  ",
                        "      gatk MergeVcfs -O file2   \\",
                        "                             --INPUT fil3 \\",
                        "                             --CREATE_INDEX </pre>"),
                lines("<pre>",
                        "java -jar picard.jar FastqToSam \\",
                        "    F1=input_reads.fastq \\",
                        "    O=unaligned_re ads.bam \\",
                        "    SM=sample001 \\",
                        "    RG=rg0013",
                        "</pre>"),
                lines("<pre>",
                        "gatk FastqToSam \\",
                        "    -F1 input_reads.fastq \\",
                        "    -O unaligned_re ads.bam \\",
                        "    -SM sample001 \\",
                        "    -RG rg0013",
                        "</pre>"),
                lines("<pre>",
                        "java -Xmx10g -Djava.io.tmp=mytemp   -jar picard.jar FastqToSam \\",
                        "    F1=input_reads.fastq \\",
                        "    O=unaligned_re ads.bam \\",
                        "    SM=sample001 \\",
                        "    RG=rg0013",
                        "</pre>"),
                lines("<pre>",
                        "gatk --javaOptions '-Xmx10g -Djava.io.tmp=mytemp' FastqToSam \\",
                        "    -F1 input_reads.fastq \\",
                        "    -O unaligned_re ads.bam \\",
                        "    -SM sample001 \\",
                        "    -RG rg0013",
                        "</pre>")
        };

        final List<Pair<String, String>> result = new ArrayList<>();
        for (int i = 0; i < interleavedInputExceptedPairs.length; i += 2) {
            result.add(new ImmutablePair<>(interleavedInputExceptedPairs[i], interleavedInputExceptedPairs[i + 1]));
        }
        return result.stream().map(pair -> new Object[] { pair.getLeft(), pair.getRight()}).toArray(Object[][]::new);
    }

    private static String lines(String ... lines) {
        return Arrays.stream(lines).collect(Collectors.joining("\n"));
    }


}
