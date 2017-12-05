package org.broadinstitute.hellbender.transformers;

import htsjdk.samtools.*;
import htsjdk.samtools.util.SequenceUtil;
import org.broadinstitute.hellbender.engine.ReferenceDataSource;
import org.broadinstitute.hellbender.engine.ReferenceFileSource;
import org.broadinstitute.hellbender.utils.read.GATKRead;

import java.io.File;

/**
 * Annotates reads with the number of distinct mismatches relative to the reference.
 */
public class DistinctMismatchTagTransformer implements ReadTransformer {
    public static final long serialVersionUID = 1L;
    public static final String SAM_TAG = "DM";

    private final ReferenceDataSource referenceDataSource;
    private SAMFileHeader header;

    public DistinctMismatchTagTransformer(final File fastaFile, final SAMFileHeader header) {
        referenceDataSource = new ReferenceFileSource(fastaFile);
        this.header = header;
    }

    @Override
    public GATKRead apply(final GATKRead read) {
        //TODO: if read already has NM attribute, we can calculate from that and the read's CIGAR

        // Note: querying the ReferenceDataSource copies the reference bases.  This seems wasteful but really the cost is
        // negligible -- about one minute total for a billion length-100 reads.
        final byte[] refBases = referenceDataSource.queryAndPrefetch(read).getBases();

        final int numDistinctMismatches = countDistinctMismatches(read.convertToSAMRecord(header), refBases, read.getAssignedStart() - 1);
        // since DM == 0 implies NM == 0, and since the latter may be more generally useful, we record it if possible
        if (numDistinctMismatches == 0) {
            read.setAttribute(SAMTag.NM.name(), 0);
        }
        read.setAttribute(SAM_TAG, numDistinctMismatches);
        return read;
    }


    // Note: this is almost copied directly from {@link SequenceUtil}.  The difference is that we ignore consecutive mismatches,
    // so that MNPs are counted as a single mismatch.  Also, we count indels and soft clips as here.
    private static int countDistinctMismatches(final SAMRecord read, final byte[] referenceBases, final int referenceOffset) {
        int mismatches = 0;
        final byte[] readBases = read.getReadBases();

        for (final AlignmentBlock block : read.getAlignmentBlocks()) {
            final int readBlockStart = block.getReadStart() - 1;
            final int referenceBlockStart = block.getReferenceStart() - 1 - referenceOffset;
            final int length = block.getLength();

            boolean lastBaseWasMatch = true;
            for (int i = 0; i < length; ++i) {
                final byte readBase = readBases[readBlockStart + i];
                final byte refBase = referenceBases[referenceBlockStart + i];
                final boolean basesMatch = SequenceUtil.basesEqual(readBase, refBase);
                if (!basesMatch && lastBaseWasMatch) {
                    ++mismatches;
                }
                lastBaseWasMatch = basesMatch;
            }
        }

        // the above counts the number of mismatches within Cigar elements with operator M, X, or EQ
        // we add the number of I, D, S, H, and N operators

        return mismatches + (int) read.getCigar().getCigarElements().stream().filter(ce -> isNonRefElement(ce)).count();

    }

    private static boolean isNonRefElement(final CigarElement cigarElement) {
        final CigarOperator operator = cigarElement.getOperator();
        return operator == CigarOperator.I || operator == CigarOperator.D || operator == CigarOperator.S
                || operator == CigarOperator.H || operator == CigarOperator.N;
    }
}
