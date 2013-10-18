package net.maizegenetics.pal.position;

import cern.colt.GenericSorting;
import cern.colt.Swapper;
import cern.colt.function.IntComparator;
import ch.systemsx.cisd.hdf5.HDF5Factory;
import ch.systemsx.cisd.hdf5.IHDF5Reader;
import ch.systemsx.cisd.hdf5.IHDF5Writer;
import com.google.common.base.Preconditions;
import net.maizegenetics.pal.alignment.HapMapHDF5Constants;
import net.maizegenetics.pal.alignment.genotype.GenotypeBuilder;
import net.maizegenetics.util.HDF5Utils;

import java.util.*;

/**
 * A builder for creating immutable PositionList.  Can be used for either an in memory or HDF5 list.
 *
 * <p>Example:
 * <pre>   {@code
 *   PositionListBuilder b=new PositionArrayList.Builder();
 *   for (int i = 0; i <size; i++) {
 *       Position ap=new CoreAnnotatedPosition.Builder(chr[chrIndex[i]],pos[i]).refAllele(refSeq[i]).build();
 *       b.add(ap);
 *       }
 *   PositionList instance=b.build();}
 * <p></p>
 * If being built separately from the genotypes, then use validate ordering to make sure sites are added in the
 * indended order.  This list WILL be sorted.
 * <p>Builder instances can be reused - it is safe to call {@link #build}
 * multiple times to build multiple lists in series. Each new list
 * contains the one created before it.
 *
 * HDF5 Example
 * <p>Example:
 * <pre>   {@code
 *   PositionList instance=new PositionHDF5List.Builder("fileName").build();
 *   }
 *
 * <p>Builder instances can be reused - it is safe to call {@link #build}
 */
public class PositionListBuilder {

    private ArrayList<Position> myPositions = new ArrayList<Position>();
    private boolean isHDF5=false;
    private IHDF5Reader reader;

    /**
     * Creates a new builder. The returned builder is equivalent to the builder
     * generated by {@link }.
     */
    public PositionListBuilder() {}

    /**
     * Adds {@code element} to the {@code PositionList}.
     *
     * @param element the element to add
     * @return this {@code Builder} object
     * @throws NullPointerException if {@code element} is null
     */
    public PositionListBuilder add(Position element) {
        if(isHDF5) throw new UnsupportedOperationException("Positions cannot be added to existing HDF5 alignments");
        Preconditions.checkNotNull(element, "element cannot be null");
        myPositions.add(element);
        return this;
    }

    /**
     * Adds each element of {@code elements} to the {@code PositionList}.
     *
     * @param elements the {@code Iterable} to add to the {@code PositionList}
     * @return this {@code Builder} object
     * @throws NullPointerException if {@code elements} is or contains null
     */
    public PositionListBuilder addAll(Iterable<? extends Position> elements) {
        if(isHDF5) throw new UnsupportedOperationException("Positions cannot be added to existing HDF5 alignments");
        if (elements instanceof Collection) {
            Collection<? extends Position> collection = (Collection<? extends Position>) elements;
            myPositions.ensureCapacity(myPositions.size() + collection.size());
        }
        for (Position elem : elements) {
            Preconditions.checkNotNull(elem, "elements contains a null");
            myPositions.add(elem);
        }
        return this;
    }

    /**
     * Replaces the element at the specified position in this list with the
     * specified element.
     *
     * @param index index of the element to replace
     * @param element element to be stored at the specified position
     * @return this {@code Builder} object
     * @throws IndexOutOfBoundsException {@inheritDoc}
     */
    public PositionListBuilder set(int index, Position element) {
        if(isHDF5) throw new UnsupportedOperationException("Positions cannot be edited to existing HDF5 alignments");
        myPositions.set(index,element);
        return this;
    }

    /**
     * Returns whether List is already ordered. Important to check this if
     * genotype and sites are separately built, as the PositionArrayList must be
     * sorted, and will be with build.
     */
    public boolean validateOrdering() {
        boolean result=true;
        Position startAP=myPositions.get(0);
        for (Position ap:myPositions) {
            if(ap.compareTo(startAP)<0) return false;
            startAP=ap;
            }
        return result;
    }

    /**
     * Returns the size (number of positions) in the current list
     *
     * @return current size
     */
    public int size() {
        return myPositions.size();
    }

    /**
     * Creates a new position list based on an existing HDF5 file.
     */
    public static PositionList getInstance(String hdf5Filename) {
        return new PositionHDF5List(HDF5Factory.openForReading(hdf5Filename));
    }

    /**
     * Creates a new builder based on an existing HDF5 file reader.
     */
    public static PositionList getInstance(IHDF5Reader reader) {
        return new PositionHDF5List(reader);
    }


    /**
     * Creates a positionList in a new HDF5 file.
     */
    public PositionListBuilder(IHDF5Writer h5w, PositionList a) {
        h5w.setIntAttribute(HapMapHDF5Constants.DEFAULT_ATTRIBUTES_PATH, HapMapHDF5Constants.NUM_SITES, a.size());
        String[] lociNames = new String[a.getNumChromosomes()];
        Map<Chromosome, Integer> locusToIndex=new HashMap<>(10);
        Chromosome[] loci = a.getChromosomes();
        for (int i = 0; i < a.getNumChromosomes(); i++) {
            lociNames[i] = loci[i].getName();
            locusToIndex.put(loci[i],i);
        }
        h5w.createStringVariableLengthArray(HapMapHDF5Constants.LOCI, a.getNumChromosomes());
        h5w.writeStringVariableLengthArray(HapMapHDF5Constants.LOCI, lociNames);

        int blockSize=1<<16;
        h5w.createStringArray(HapMapHDF5Constants.SNP_IDS, 15,a.getSiteCount(),blockSize,HapMapHDF5Constants.genDeflation);
        h5w.createIntArray(HapMapHDF5Constants.LOCUS_INDICES, a.getSiteCount(),HapMapHDF5Constants.intDeflation);
        h5w.createIntArray(HapMapHDF5Constants.POSITIONS, a.getSiteCount(), HapMapHDF5Constants.intDeflation);

        int blocks=((a.getSiteCount()-1)/blockSize)+1;
        for (int block = 0; block < blocks; block++) {
            int startPos=block*blockSize;
            int length=((a.getSiteCount()-startPos)>blockSize)?blockSize:a.getSiteCount()-startPos;
            String[] snpIDs = new String[length];
            int[] locusIndicesArray = new int[length];
            int[] positions=new int[length];
            for (int i=0; i<length; i++) {
                Position gp=a.get(i+startPos);
                snpIDs[i]=gp.getSNPID();
                locusIndicesArray[i] = locusToIndex.get(gp.getChromosome());
                positions[i]=gp.getPosition();
            }
            HDF5Utils.writeHDF5Block(HapMapHDF5Constants.SNP_IDS,h5w,blockSize,block,snpIDs);
            HDF5Utils.writeHDF5Block(HapMapHDF5Constants.LOCUS_INDICES,h5w,blockSize,block,locusIndicesArray);
            HDF5Utils.writeHDF5Block(HapMapHDF5Constants.POSITIONS,h5w,blockSize,block,positions);
        }
        this.reader = h5w;
        isHDF5=true;
    }

    /**
     * Returns a newly-created {@code ImmutableList} based on the myPositions of
     * the {@code Builder}.
     */
    public PositionList build() {
        if (isHDF5) {
            return new PositionHDF5List(reader);
        } else {
            Collections.sort(myPositions);
            return new PositionArrayList(myPositions);
        }
    }

    public PositionList build(GenotypeBuilder genotypes) {
        sortPositions(genotypes);
        return new PositionArrayList(myPositions);
    }

    public PositionListBuilder sortPositions(GenotypeBuilder genotypes) {
        int numPositions = myPositions.size();
        if (numPositions != genotypes.getSiteCount()) {
            throw new IllegalArgumentException("PositionListBuilder: sortPositions: position list size: " + numPositions + " doesn't match genotypes num position: " + genotypes.getSiteCount());
        }
        genotypes.reorderPositions(sort());
        return this;
    }

    public PositionListBuilder sortPositions() {
        sort();
        return this;
    }

    private int[] sort() {

        int numPositions = myPositions.size();

        final int indicesOfSortByPosition[] = new int[numPositions];
        for (int i = 0; i < indicesOfSortByPosition.length; i++) {
            indicesOfSortByPosition[i] = i;
        }

        Swapper swapPosition = new Swapper() {
            @Override
            public void swap(int a, int b) {
                int temp = indicesOfSortByPosition[a];
                indicesOfSortByPosition[a] = indicesOfSortByPosition[b];
                indicesOfSortByPosition[b] = temp;
            }
        };

        IntComparator compPosition = new IntComparator() {
            @Override
            public int compare(int a, int b) {
                return myPositions.get(indicesOfSortByPosition[a]).compareTo(myPositions.get(indicesOfSortByPosition[b]));
            }
        };

        GenericSorting.quickSort(0, indicesOfSortByPosition.length, compPosition, swapPosition);

        ArrayList<Position> temp = new ArrayList<>(numPositions);
        for (int t = 0; t < numPositions; t++) {
            temp.add(myPositions.get(indicesOfSortByPosition[t]));
        }

        myPositions = temp;

        return indicesOfSortByPosition;

    }
}
