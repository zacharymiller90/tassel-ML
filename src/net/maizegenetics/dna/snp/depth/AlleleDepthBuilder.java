/*
 *  AlleleDepthBuilder
 */
package net.maizegenetics.dna.snp.depth;

import ch.systemsx.cisd.hdf5.HDF5IntStorageFeatures;
import ch.systemsx.cisd.hdf5.IHDF5Reader;
import ch.systemsx.cisd.hdf5.IHDF5Writer;
import net.maizegenetics.dna.snp.HapMapHDF5Constants;
import net.maizegenetics.dna.snp.NucleotideAlignmentConstants;
import net.maizegenetics.taxa.Taxon;
import net.maizegenetics.util.SuperByteMatrix;
import net.maizegenetics.util.SuperByteMatrixBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * Builder to store information on DNA read depths.
 *
 * @author Terry Casstevens
 */
public class AlleleDepthBuilder {

    private static final HDF5IntStorageFeatures HDF5_FEATURES = HDF5IntStorageFeatures.createDeflation(2);

    private List<SuperByteMatrix> myDepths = null;
    private final boolean myIsHDF5;
    private IHDF5Writer myHDF5Writer = null;
    private final int myMaxNumAlleles;
    private final int myNumSites;
    private int myNumTaxa = 0;

    private AlleleDepthBuilder(IHDF5Writer writer, int numSites, int maxNumAlleles) {
        myIsHDF5 = true;
        myHDF5Writer = writer;
        myMaxNumAlleles = maxNumAlleles;
        myNumSites = numSites;
        if (!myHDF5Writer.exists(HapMapHDF5Constants.DEPTH)) {
            myHDF5Writer.createGroup(HapMapHDF5Constants.DEPTH);
        }
    }

    private AlleleDepthBuilder(int numSites, int maxNumAlleles) {
        myIsHDF5 = false;
        myHDF5Writer = null;
        myMaxNumAlleles = maxNumAlleles;
        myNumSites = numSites;
        myDepths = new ArrayList<>();
    }

    private AlleleDepthBuilder(int numTaxa, int numSites, int maxNumAlleles) {
        myIsHDF5 = false;
        myHDF5Writer = null;
        myMaxNumAlleles = maxNumAlleles;
        myNumSites = numSites;
        myNumTaxa = numTaxa;
        myDepths = new ArrayList<>();
        for (int i = 0; i < myNumTaxa; i++) {
            myDepths.add(SuperByteMatrixBuilder.getInstance(myNumSites, myMaxNumAlleles));
        }
    }

    public static AlleleDepthBuilder getInstance(int numTaxa, int numSites, int maxNumAlleles) {
        return new AlleleDepthBuilder(numTaxa, numSites, maxNumAlleles);
    }

    public static AlleleDepthBuilder getNucleotideInstance(int numTaxa, int numSites) {
        return new AlleleDepthBuilder(numTaxa, numSites, NucleotideAlignmentConstants.NUMBER_NUCLEOTIDE_ALLELES);
    }

    public static AlleleDepthBuilder getHDF5NucleotideInstance(IHDF5Writer writer, int numSites) {
        return new AlleleDepthBuilder(writer, numSites, NucleotideAlignmentConstants.NUMBER_NUCLEOTIDE_ALLELES);
    }

    /**
     * Set allele value for taxon, site, and allele. Value will be translated
     * using AlleleDepthUtil.
     *
     * @param taxon taxon
     * @param site site
     * @param allele allele
     * @param depth value
     *
     * @return builder
     */
    public AlleleDepthBuilder setDepth(int taxon, int site, byte allele, int depth) {
        if (myIsHDF5) {
            throw new IllegalStateException("AlleleDepthBuilder: setDepth: use addTaxon for HDF5 files.");
        }
        myDepths.get(taxon).set(site, allele, AlleleDepthUtil.depthIntToByte(depth));
        return this;
    }

    /**
     * Set depth for the all sites and alleles for a taxon simultaneously.
     * Value will be translated using AlleleDepthUtil. First dimension of depths
     * is number of alleles (6 for Nucleotide) and second dimension is sites.
     *
     * @param taxon Index of taxon
     * @param depths array[sites][allele] of all values
     *
     * @return builder
     */
    public AlleleDepthBuilder setDepth(int taxon, int[][] depths) {
        if (myIsHDF5) {
            throw new IllegalStateException("AlleleDepthBuilder: setDepth: use addTaxon for HDF5 files.");
        }
        int numAlleles = depths.length;
        if (numAlleles != myMaxNumAlleles) {
            throw new IllegalArgumentException("AlleleDepthBuilder: setDepth: value number of alleles: " + numAlleles + " should have: " + myMaxNumAlleles);
        }
        int numSites = depths[0].length;
        if (numSites != myNumSites) {
            throw new IllegalArgumentException("AlleleDepthBuilder: setDepth: value number of sites: " + numSites + " should have: " + myNumSites);
        }
        for (int a = 0; a < myMaxNumAlleles; a++) {
            for (int s = 0; s < myNumSites; s++) {
                setDepth(taxon, s, (byte) a, depths[s][a]);
            }
        }
        return this;
    }

    /**
     * Set depth for the all sites and alleles for a taxon simultaneously.
     * First dimension of depths
     * is number of alleles (6 for Nucleotide) and second dimension is sites.
     *
     * @param taxon Index of taxon
     * @param depths array[sites][allele] of all values
     *
     * @return builder
     */
    public AlleleDepthBuilder setDepthRangeForTaxon(int taxon, int siteOffset, byte[][] depths) {
        if (myIsHDF5) {
            throw new IllegalStateException("AlleleDepthBuilder: setDepth: use addTaxon for HDF5 files.");
        }
        int numAlleles = depths.length;
        if (numAlleles != myMaxNumAlleles) {
            throw new IllegalArgumentException("AlleleDepthBuilder: setDepth: value number of alleles: " + numAlleles + " should have: " + myMaxNumAlleles);
        }
        for (int a = 0; a < myMaxNumAlleles; a++) {
            for (int s = 0; s < depths[0].length; s++) {
                setDepth(taxon, s+siteOffset, (byte) a, depths[a][s]);
            }
        }
        return this;
    }

    /**
     * Set allele value for taxon, site, and allele. Value should have already
     * been translated using AlleleDepthUtil.
     *
     * @param taxon taxon
     * @param site site
     * @param allele allele
     * @param depth value
     *
     * @return builder
     */
    public AlleleDepthBuilder setDepth(int taxon, int site, byte allele, byte depth) {
        if (myIsHDF5) {
            throw new IllegalStateException("AlleleDepthBuilder: setDepth: use addTaxon for HDF5 files.");
        }
        myDepths.get(taxon).set(site, allele, depth);
        return this;
    }

    /**
     * Set allele for the all sites and alleles for a taxon simultaneously.
     * Values should have already been translated using AlleleDepthUtil. First
     * dimension of depths is number of alleles (6 for Nucleotide) and second
     * dimension is sites.
     *
     * @param taxon Index of taxon
     * @param depths array[sites][allele] of all values
     *
     * @return builder
     */
    public AlleleDepthBuilder setDepth(int taxon, byte[][] depths) {
        if (myIsHDF5) {
            throw new IllegalStateException("AlleleDepthBuilder: setDepth: use addTaxon for HDF5 files.");
        }
        int numAlleles = depths.length;
        if (numAlleles != myMaxNumAlleles) {
            throw new IllegalArgumentException("AlleleDepthBuilder: setDepth: value number of alleles: " + numAlleles + " should have: " + myMaxNumAlleles);
        }
        int numSites = depths[0].length;
        if (numSites != myNumSites) {
            throw new IllegalArgumentException("AlleleDepthBuilder: setDepth: value number of sites: " + numSites + " should have: " + myNumSites);
        }
        for (int a = 0; a < myMaxNumAlleles; a++) {
            for (int s = 0; s < myNumSites; s++) {
                setDepth(taxon, s, (byte) a, depths[s][a]);
            }
        }
        return this;
    }

    private String getTaxaDepthPath(Taxon taxon) {
        return HapMapHDF5Constants.DEPTH + "/" + taxon.getName();
    }

    /**
     * Add taxon and set values for all sites and alleles for that taxon. First
     * dimension of depths is number of alleles (6 for Nucleotide) and second
     * dimension is sites.
     *
     * @param taxon taxon
     * @param depths depth values
     *
     * @return builder
     */
    public AlleleDepthBuilder addTaxon(Taxon taxon, byte[][] depths) {
        if (myIsHDF5) {
            if ((depths == null) || (depths.length != 6)) {
                throw new IllegalStateException("AlleleDepthBuilder: addTaxon: Set A, C, G, T, -, + at once");
            }
            if (depths[0].length != myNumSites) {
                throw new IllegalStateException("AlleleDepthBuilder: addTaxon: Number of sites: " + depths[0].length + " should be: " + myNumSites);
            }
            synchronized (myHDF5Writer) {
                myHDF5Writer.writeByteMatrix(getTaxaDepthPath(taxon), depths, HDF5_FEATURES);
            }
            myNumTaxa++;
        } else {
            myDepths.add(SuperByteMatrixBuilder.getInstance(myNumSites, myMaxNumAlleles));
            setDepth(myNumTaxa, depths);
            myNumTaxa++;
        }
        return this;
    }

    /**
     * Add taxon and set values for all sites and alleles for that taxon. First
     * dimension of depths is number of alleles (6 for Nucleotide) and second
     * dimension is sites.
     *
     * @param taxon taxon
     * @param depths depth values
     *
     * @return builder
     */
    public AlleleDepthBuilder addTaxon(Taxon taxon, int[][] depths) {
        if (myIsHDF5) {
            int numAlleles = depths.length;
            if ((depths == null) || (numAlleles != 6)) {
                throw new IllegalStateException("AlleleDepthBuilder: addTaxon: Set A, C, G, T, -, + at once");
            }
            if (depths[0].length != myNumSites) {
                throw new IllegalStateException("AlleleDepthBuilder: addTaxon: Number of sites: " + depths[0].length + " should be: " + myNumSites);
            }
            byte[][] result = new byte[numAlleles][myNumSites];
            for (int a = 0; a < numAlleles; a++) {
                for (int s = 0; s < myNumSites; s++) {
                    result[a][s] = AlleleDepthUtil.depthIntToByte(depths[a][s]);
                }
            }
            return addTaxon(taxon, result);
        } else {
            myDepths.add(SuperByteMatrixBuilder.getInstance(myNumSites, myMaxNumAlleles));
            setDepth(myNumTaxa, depths);
            myNumTaxa++;
        }
        return this;
    }

    public AlleleDepth build() {
        if (myIsHDF5) {
            IHDF5Reader reader = myHDF5Writer;
            myHDF5Writer = null;
            return new HDF5AlleleDepth(reader);
        } else {
            SuperByteMatrix[] temp = new SuperByteMatrix[myDepths.size()];
            temp = myDepths.toArray(temp);
            myDepths = null;
            return new MemoryAlleleDepth(temp, myNumTaxa, myNumSites);
        }
    }
}
