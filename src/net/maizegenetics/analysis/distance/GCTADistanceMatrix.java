/*
 *  GCTADistanceMatrix
 * 
 *  Created on May 31, 2015
 */
package net.maizegenetics.analysis.distance;

import java.util.Arrays;
import java.util.Optional;
import java.util.Spliterator;
import static java.util.Spliterator.IMMUTABLE;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import net.maizegenetics.dna.snp.GenotypeTable;
import net.maizegenetics.taxa.distance.DistanceMatrix;
import net.maizegenetics.util.ProgressListener;
import net.maizegenetics.util.Tuple;
import org.apache.log4j.Logger;

/**
 *
 * @author Terry Casstevens
 */
public class GCTADistanceMatrix {

    private static final Logger myLogger = Logger.getLogger(GCTADistanceMatrix.class);

    private GCTADistanceMatrix() {
        // utility
    }

    /**
     * Compute GCTA kinship for all pairs of taxa. Missing sites are ignored.
     * http://www.ncbi.nlm.nih.gov/pmc/articles/PMC3014363/pdf/main.pdf
     * Equation-3
     *
     * @param genotype Genotype Table used to compute kinship
     *
     * @return GCTA Kinship Matrix
     */
    public static DistanceMatrix getInstance(GenotypeTable genotype) {
        return getInstance(genotype, null);
    }

    /**
     * Same as other getInstance() but reports progress.
     *
     * @param genotype Genotype Table used to compute kinship
     * @param listener Progress listener
     *
     * @return GCTA Kinship Matrix
     */
    public static DistanceMatrix getInstance(GenotypeTable genotype, ProgressListener listener) {
        return computeGCTADistances(genotype, listener);
    }

    private static DistanceMatrix computeGCTADistances(GenotypeTable genotype, ProgressListener listener) {

        int numSeqs = genotype.numberOfTaxa();
        long time = System.currentTimeMillis();

        //
        // Sets up parellel stream to divide up sites for processing.
        // Also reduces the distance sums and site counters into one instance.
        //
        Optional<CountersDistances> optional = stream(genotype, listener).reduce((CountersDistances t, CountersDistances u) -> {
            t.addAll(u);
            return t;
        });

        if (!optional.isPresent()) {
            return null;
        }
        CountersDistances counters = optional.get();
        int[] counts = counters.myCounters;
        float[] distances = counters.myDistances;

        //
        // This does the final division of the site counts into
        // the distance sums.
        //
        double[][] result = new double[numSeqs][numSeqs];
        int index = 0;
        for (int t = 0; t < numSeqs; t++) {
            for (int i = 0, n = numSeqs - t; i < n; i++) {
                result[t][t + i] = result[t + i][t] = distances[index] / (double) counts[index];
                index++;
            }
        }

        myLogger.info("GCTADistanceMatrix: computeGCTADistances time: " + (System.currentTimeMillis() - time) / 1000 + " seconds");
        return new DistanceMatrix(result, genotype.taxa());

    }

    protected static void fireProgress(int percent, ProgressListener listener) {
        if (listener != null) {
            listener.progress(percent, null);
        }

    }

    //
    // Each CPU thread (process) creates an instance of this class
    // to acculate terms of the GCTA equation and the number of
    // sites involved for each pair-wise calculation.  These are
    // combined with addAll() to result in one instance at the end.
    //
    private static class CountersDistances {

        private final int[] myCounters;
        private final float[] myDistances;
        private final int myNumTaxa;

        public CountersDistances(int numTaxa) {
            myNumTaxa = numTaxa;
            myCounters = new int[myNumTaxa * (myNumTaxa + 1) / 2];
            myDistances = new float[myNumTaxa * (myNumTaxa + 1) / 2];
        }

        public void addAll(CountersDistances counters) {
            float[] otherDistances = counters.myDistances;
            for (int t = 0, n = myCounters.length; t < n; t++) {
                myDistances[t] += otherDistances[t];
            }
            otherDistances = null;
            int[] otherCounters = counters.myCounters;
            for (int t = 0, n = myCounters.length; t < n; t++) {
                myCounters[t] += otherCounters[t];
            }
        }

    }

    //
    // This pre-calculates the number of occurances of the major allele
    // for all possible diploid allele values.  Numbers 0 through 7
    // represent A, C, G, T, -, +, N respectively.  First three bits
    // codes the major allele.  Remaining six bits codes the diploid
    // allele values. The stored counts are encodings.  Value 7 (bits 111) means
    // it's not a comparable combination because either major allele
    // is unknown or the diploid allele value is unknown.
    // Code 1 (bits 001) is zero count.
    // Code 2 (bits 010) is one count.
    // Code 4 (bits 100) is two count.
    //
    private static final byte[] PRECALCULATED_COUNTS = new byte[512];

    static {
        for (int major = 0; major < 8; major++) {
            for (int a = 0; a < 8; a++) {
                for (int b = 0; b < 8; b++) {
                    int temp = (major << 6) | (a << 3) | b;
                    if ((major == 7) | ((a == 7) && (b == 7))) {
                        PRECALCULATED_COUNTS[temp] = 7;
                    } else {
                        if (a == major) {
                            if (b == major) {
                                PRECALCULATED_COUNTS[temp] = 4;
                            } else {
                                PRECALCULATED_COUNTS[temp] = 2;
                            }
                        } else if (b == major) {
                            PRECALCULATED_COUNTS[temp] = 2;
                        } else {
                            PRECALCULATED_COUNTS[temp] = 1;
                        }
                    }
                }
            }
        }
    }

    //
    // This pre-calculates the number of sites involved in a GCTA pair-wise
    // comparison.  Counts are the number of sites involved in the
    // calculation (up to 5 sites).
    // Count value of 7 is coded when diploid allele value is
    // GenotypeTable.UNKNOWN_DIPLOID_ALLELE.  Any pair-wise comparison when
    // either taxa has GenotypeTable.UNKNOWN_DIPLOID_ALLELE at a given site,
    // is not involved in the calulation. The index of this array represents
    // every bitwise OR combination of major allele count (1, 2, 4) and UNKNOWN (7)
    // for five consecutive sites.  Each three bits encodes two counts.
    // Those three bits times five sites equals 32768 combinations.
    // Code 001 - both counts zero
    // Code 011 - one count zero, one count one
    // Code 010 - both counts one
    // Code 110 - one count one, one count two
    // Code 100 - both counts two
    // Code 101 - one count zero, one count two
    //
    private static final byte[] INCREMENT = new byte[32768];

    static {
        for (int a = 1; a < 8; a++) {
            int temp = a << 12;
            for (int b = 1; b < 8; b++) {
                int temp2 = b << 9;
                for (int c = 1; c < 8; c++) {
                    int temp3 = c << 6;
                    for (int d = 1; d < 8; d++) {
                        int temp4 = d << 3;
                        for (int e = 1; e < 8; e++) {
                            int incrementIndex = temp | temp2 | temp3 | temp4 | e;
                            if (a != 7) {
                                INCREMENT[incrementIndex]++;
                            }
                            if (b != 7) {
                                INCREMENT[incrementIndex]++;
                            }
                            if (c != 7) {
                                INCREMENT[incrementIndex]++;
                            }
                            if (d != 7) {
                                INCREMENT[incrementIndex]++;
                            }
                            if (e != 7) {
                                INCREMENT[incrementIndex]++;
                            }
                        }
                    }
                }
            }
        }
    }

    //
    // Used to report progress.  This is not thread-safe but
    // works well enough for this purpose.
    //
    private static int myNumSitesProcessed = 0;

    //
    // Creates stream from GCTASiteSpliterator and Genotype Table
    //
    private static Stream<CountersDistances> stream(GenotypeTable genotypes, ProgressListener listener) {
        myNumSitesProcessed = 0;
        return StreamSupport.stream(new GCTASiteSpliterator(genotypes, 0, genotypes.numberOfSites(), listener), true);
    }

    //
    // Spliterator that splits the sites into halves each time for
    // processing.
    //
    static class GCTASiteSpliterator implements Spliterator<CountersDistances> {

        private int myCurrentSite;
        private final int myFence;
        private final GenotypeTable myGenotypes;
        private final int myNumTaxa;
        private final int myNumSites;
        private final ProgressListener myProgressListener;

        GCTASiteSpliterator(GenotypeTable genotypes, int currentIndex, int fence, ProgressListener listener) {
            myGenotypes = genotypes;
            myNumTaxa = myGenotypes.numberOfTaxa();
            myNumSites = myGenotypes.numberOfSites();
            myCurrentSite = currentIndex;
            myFence = fence;
            myProgressListener = listener;
        }

        @Override
        public void forEachRemaining(Consumer<? super CountersDistances> action) {

            int numSitesProcessed = myFence - myCurrentSite;
            CountersDistances result = new CountersDistances(myNumTaxa);
            int[] counts = result.myCounters;
            float[] distances = result.myDistances;;

            float[] answer1 = new float[32768];
            float[] answer2 = new float[32768];
            float[] answer3 = new float[32768];

            for (; myCurrentSite < myFence; myCurrentSite += 15) {

                //
                // Pre-calculates possible terms and gets counts for
                // three blocks for five sites.
                //
                Tuple<short[], float[]> firstThree = getBlockOfSites(myCurrentSite);
                float[] possibleTerms = firstThree.y;
                short[] majorCount1 = firstThree.x;

                Tuple<short[], float[]> secondThree = getBlockOfSites(myCurrentSite + 5);
                float[] possibleTerms2 = secondThree.y;
                short[] majorCount2 = secondThree.x;

                Tuple<short[], float[]> thirdThree = getBlockOfSites(myCurrentSite + 10);
                float[] possibleTerms3 = thirdThree.y;
                short[] majorCount3 = thirdThree.x;

                //
                // Using possible terms, calculates all possible answers
                // for each site block.
                //
                for (int i = 0; i < 32768; i++) {
                    answer1[i] = possibleTerms[(i & 0x7000) >>> 12] + possibleTerms[((i & 0xE00) >>> 9) | 0x8] + possibleTerms[((i & 0x1C0) >>> 6) | 0x10] + possibleTerms[((i & 0x38) >>> 3) | 0x18] + possibleTerms[(i & 0x7) | 0x20];
                    answer2[i] = possibleTerms2[(i & 0x7000) >>> 12] + possibleTerms2[((i & 0xE00) >>> 9) | 0x8] + possibleTerms2[((i & 0x1C0) >>> 6) | 0x10] + possibleTerms2[((i & 0x38) >>> 3) | 0x18] + possibleTerms2[(i & 0x7) | 0x20];
                    answer3[i] = possibleTerms3[(i & 0x7000) >>> 12] + possibleTerms3[((i & 0xE00) >>> 9) | 0x8] + possibleTerms3[((i & 0x1C0) >>> 6) | 0x10] + possibleTerms3[((i & 0x38) >>> 3) | 0x18] + possibleTerms3[(i & 0x7) | 0x20];
                }

                //
                // Iterates through all pair-wise combinations of taxa adding
                // distance comparisons and site counts.
                //
                int index = 0;
                for (int firstTaxa = 0; firstTaxa < myNumTaxa; firstTaxa++) {
                    //
                    // Can skip inter-loop if all fifteen sites for first
                    // taxon is Unknown diploid allele values
                    //
                    if ((majorCount1[firstTaxa] != 0x7FFF) || (majorCount2[firstTaxa] != 0x7FFF) || (majorCount3[firstTaxa] != 0x7FFF)) {
                        for (int secondTaxa = firstTaxa; secondTaxa < myNumTaxa; secondTaxa++) {
                            //
                            // Combine first taxon's major allele counts with
                            // second taxon's major allele counts to
                            // create index into pre-calculated answers
                            // and site counts.
                            //
                            distances[index] += answer1[majorCount1[firstTaxa] | majorCount1[secondTaxa]] + answer2[majorCount2[firstTaxa] | majorCount2[secondTaxa]] + answer3[majorCount3[firstTaxa] | majorCount3[secondTaxa]];
                            counts[index] += INCREMENT[majorCount1[firstTaxa] | majorCount1[secondTaxa]] + INCREMENT[majorCount2[firstTaxa] | majorCount2[secondTaxa]] + INCREMENT[majorCount3[firstTaxa] | majorCount3[secondTaxa]];
                            index++;
                        }
                    } else {
                        index += myNumTaxa - firstTaxa;
                    }
                }
            }

            action.accept(result);
            myNumSitesProcessed += numSitesProcessed;
            fireProgress((int) ((double) myNumSitesProcessed / (double) myNumSites * 100.0), myProgressListener);
        }

        private static final int NUM_SITES_PER_BLOCK = 5;

        private Tuple<short[], float[]> getBlockOfSites(int currentSite) {

            int currentSiteNum = 0;

            //
            // This hold possible terms for the GCTA summation given
            // site's major allele frequency.  First two bits
            // identifies relative site (0, 1, 2, 3, 4).  Remaining three bits
            // the major allele counts encoding.
            //
            float[] possibleTerms = new float[40];

            //
            // This holds count of major allele for each taxa.
            // Each short holds count (0, 1, 2, 3) for all four sites
            // at given taxon.  The counts are stored in four bits each.
            // This leaves the two higher bits for each empty for shifting.
            //
            short[] majorCount = new short[myNumTaxa];

            //
            // This initializes the counts to 0x3333.  That means
            // diploid allele values for the four sites are Unknown.
            //
            Arrays.fill(majorCount, (short) 0x7FFF);

            while ((currentSiteNum < NUM_SITES_PER_BLOCK) && (currentSite < myFence)) {

                byte major = myGenotypes.majorAllele(currentSite);
                float majorFreq = (float) myGenotypes.majorAlleleFrequency(currentSite);
                float majorFreqTimes2 = majorFreq * 2.0f;
                float denominatorTerm = majorFreqTimes2 * (1.0f - majorFreq);

                //
                // Temporarily stores component terms of equation for
                // individual major allele counts (0, 1, 2)
                //
                float[] term = new float[3];

                //
                // If major allele is Unknown or major allele frequency
                // equals 1.0 (resulting in denominator 0.0), the entire
                // site is skipped.
                //
                if ((major != GenotypeTable.UNKNOWN_ALLELE) && (denominatorTerm != 0.0)) {

                    term[0] = 0.0f - majorFreqTimes2;
                    term[1] = 1.0f - majorFreqTimes2;
                    term[2] = 2.0f - majorFreqTimes2;

                    //
                    // Pre-calculates all possible terms of the summation
                    // for this current site.  Counts (0,0; 0,1; 0,2; 1,1; 1,2; 2,2)
                    //
                    int siteNumIncrement = currentSiteNum * 8;
                    possibleTerms[siteNumIncrement + 1] = term[0] * term[0] / denominatorTerm;
                    possibleTerms[siteNumIncrement + 3] = term[0] * term[1] / denominatorTerm;
                    possibleTerms[siteNumIncrement + 5] = term[0] * term[2] / denominatorTerm;
                    possibleTerms[siteNumIncrement + 2] = term[1] * term[1] / denominatorTerm;
                    possibleTerms[siteNumIncrement + 6] = term[1] * term[2] / denominatorTerm;
                    possibleTerms[siteNumIncrement + 4] = term[2] * term[2] / denominatorTerm;

                    //
                    // Records major allele counts (C) for current site in
                    // three bits.
                    //
                    int temp = (major & 0x7) << 6;
                    int shift = (NUM_SITES_PER_BLOCK - currentSiteNum - 1) * 3;
                    int mask = ~(0x7 << shift) & 0x7FFF;
                    for (int i = 0; i < myNumTaxa; i++) {
                        byte genotype = myGenotypes.genotype(i, currentSite);
                        majorCount[i] = (short) (majorCount[i] & (mask | PRECALCULATED_COUNTS[temp | ((genotype & 0x70) >>> 1) | (genotype & 0x7)] << shift));
                    }
                }

                currentSiteNum++;
                currentSite++;
            }

            return new Tuple<>(majorCount, possibleTerms);

        }

        @Override
        public boolean tryAdvance(Consumer<? super CountersDistances> action) {
            if (myCurrentSite < myFence) {

                CountersDistances result = new CountersDistances(myNumTaxa);
                int[] counts = result.myCounters;
                float[] distances = result.myDistances;
                byte[] majorCount = new byte[myNumTaxa];
                float[] answer = new float[12];
                byte[] increment = new byte[12];
                increment[0] = increment[1] = increment[2]
                        = increment[4] = increment[5] = increment[6]
                        = increment[8] = increment[9] = increment[10] = 1;

                byte major = myGenotypes.majorAllele(myCurrentSite);
                float majorFreq = (float) myGenotypes.majorAlleleFrequency(myCurrentSite);
                float majorFreqTimes2 = majorFreq * 2.0f;
                float denominatorTerm = majorFreqTimes2 * (1.0f - majorFreq);
                if ((major != GenotypeTable.UNKNOWN_ALLELE) && (denominatorTerm != 0.0)) {

                    float zeroTerm = 0.0f - majorFreqTimes2;
                    float oneTerm = 1.0f - majorFreqTimes2;
                    float twoTerm = 2.0f - majorFreqTimes2;

                    answer[0] = zeroTerm * zeroTerm / denominatorTerm;
                    answer[1] = answer[4] = zeroTerm * oneTerm / denominatorTerm;
                    answer[2] = answer[8] = zeroTerm * twoTerm / denominatorTerm;
                    answer[5] = oneTerm * oneTerm / denominatorTerm;
                    answer[6] = answer[9] = oneTerm * twoTerm / denominatorTerm;
                    answer[10] = twoTerm * twoTerm / denominatorTerm;

                    for (int i = 0; i < myNumTaxa; i++) {
                        byte genotype = myGenotypes.genotype(i, myCurrentSite);
                        if (genotype == GenotypeTable.UNKNOWN_DIPLOID_ALLELE) {
                            majorCount[i] = 3;
                        } else {
                            majorCount[i] = 0;
                            if ((genotype & 0xF) == major) {
                                majorCount[i]++;
                            }
                            if (((genotype >>> 4) & 0xF) == major) {
                                majorCount[i]++;
                            }
                        }
                    }

                    int index = 0;
                    for (int firstTaxa = 0; firstTaxa < myNumTaxa; firstTaxa++) {
                        if (majorCount[firstTaxa] != 3) {
                            int temp = majorCount[firstTaxa] << 2;
                            for (int secondTaxa = firstTaxa; secondTaxa < myNumTaxa; secondTaxa++) {
                                int aIndex = temp | majorCount[secondTaxa];
                                distances[index] += answer[aIndex];
                                counts[index] += increment[aIndex];
                                index++;
                            }
                        } else {
                            index += myNumTaxa - firstTaxa;
                        }
                    }
                }

                action.accept(result);

                return true;
            } else {
                return false;
            }
        }

        @Override
        /**
         * Splits sites into halves
         */
        public Spliterator<CountersDistances> trySplit() {
            int lo = myCurrentSite;
            int mid = (lo + myFence) >>> 1;
            if (lo < mid) {
                myCurrentSite = mid;
                return new GCTASiteSpliterator(myGenotypes, lo, mid, myProgressListener);
            } else {
                return null;
            }
        }

        @Override
        public long estimateSize() {
            return (long) (myFence - myCurrentSite);
        }

        @Override
        public int characteristics() {
            return IMMUTABLE;
        }
    }

}
