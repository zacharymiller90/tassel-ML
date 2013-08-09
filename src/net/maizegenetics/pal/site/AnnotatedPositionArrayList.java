package net.maizegenetics.pal.site;

import com.google.common.base.Preconditions;
import com.google.common.collect.ArrayListMultimap;

import java.nio.IntBuffer;
import java.util.*;

/**
 * An in memory immutable instance of {@link AnnotatedPositionList}.  Use the {@link AnnotatedPositionArrayList.Builder}
 * to create the list.
 *
 * @author Ed Buckler
 */
public final class AnnotatedPositionArrayList implements AnnotatedPositionList {
    private final List<AnnotatedPosition> mySiteList;
    private final int numPositions;
    private final byte[] refAlleles;
    private final byte[] majorAlleles;
    private final byte[] ancAlleles;
    private final byte[] hiDepAlleles;
    private final TreeMap<Chromosome,ChrOffPos> myChrOffPosTree;
    private final HashMap<String,Chromosome> myChrNameHash;

    private class ChrOffPos {
        final int startSiteOff;
        final int endSiteOff;
        final int[] position;
        private ChrOffPos(int startSiteOff, int endSiteOff, int[] position) {
            this.startSiteOff=startSiteOff;
            this.endSiteOff=endSiteOff;
            this.position=position;
        }
    }

    private AnnotatedPositionArrayList(ArrayList<AnnotatedPosition> builderList) {
        this.numPositions=builderList.size();
        refAlleles=new byte[numPositions];
        majorAlleles=new byte[numPositions];
        ancAlleles=new byte[numPositions];
        hiDepAlleles=new byte[numPositions];
        ArrayListMultimap<Chromosome,Integer> pTS=ArrayListMultimap.create();
        mySiteList=new ArrayList<AnnotatedPosition>(builderList.size());
        myChrOffPosTree=new TreeMap<Chromosome,ChrOffPos>();
        myChrNameHash=new HashMap();
        int currStart=0;
        Chromosome currChr=builderList.get(0).getChromosome();
        for (int i=0; i<builderList.size(); i++) {
            AnnotatedPosition ap=builderList.get(i);
            refAlleles[i]=ap.getReferenceAllele();
            majorAlleles[i]=ap.getGlobalMajorAllele();
            ancAlleles[i]=ap.getAncestralAllele();
            hiDepAlleles[i]=ap.getHighDepthAllele();
            mySiteList.add(ap);
            if((i==(builderList.size()-1))||!ap.getChromosome().equals(currChr)) {
                int end=(i==builderList.size()-1)?i:i-1;
                myChrOffPosTree.put(currChr, new ChrOffPos(currStart, i-1, null));
                currChr=ap.getChromosome();
                currStart=i;
            }
            pTS.put(ap.getChromosome(),ap.getPosition());
        }
        for (Chromosome chr: pTS.keySet()) {
            List<Integer> p=pTS.get(chr);
            int[] intP=new int[p.size()];
            for (int i=0; i<intP.length; i++) {intP[i]=p.get(i);}
            ChrOffPos currOff=myChrOffPosTree.get(chr);
            myChrOffPosTree.put(chr, new ChrOffPos(currOff.startSiteOff, currOff.endSiteOff, intP));
            myChrNameHash.put(chr.getName(),chr);
            }
        pTS=null;
    }

    @Override
    public byte getReferenceAllele(int site) {
        return mySiteList.get(site).getReferenceAllele();
    }
    
    @Override
    public byte[] getReference(int startSite, int endSite) {
        byte[] result = new byte[endSite - startSite];
        System.arraycopy(refAlleles,startSite,result,0, result.length);
        return result;
    }

    @Override
    public byte[] getReference() {
        return Arrays.copyOf(refAlleles,refAlleles.length);
    }

    @Override
    public boolean hasReference() {
        return true;
    }

    @Override
    public String[] getSNPIDs() {
        String[] theIDs=new String[mySiteList.size()];
        for (int i = 0; i < theIDs.length; i++) {
            theIDs[i]=mySiteList.get(i).getSNPID();
        }
        return theIDs;
    }

    @Override
    public String getSNPID(int site) {
        return mySiteList.get(site).getSNPID();
    }

    @Override
    public int getSiteCount() {
        return numPositions;
    }

    @Override
    public int getChromosomeSiteCount(Chromosome chromosome) {
        return myChrOffPosTree.get(chromosome).position.length;
    }

    @Override
    public int[] getStartAndEndOfChromosome(Chromosome chromosome) {
        ChrOffPos cop=myChrOffPosTree.get(chromosome);
        if(cop==null) return null;
        return new int[]{cop.startSiteOff,cop.endSiteOff};
    }

    @Override
    public int getPositionInChromosome(int site) {
        return mySiteList.get(site).getPosition();
    }

    @Override
    public int getSiteOfPhysicalPosition(int physicalPosition, Chromosome chromosome) {
        ChrOffPos cop=myChrOffPosTree.get(chromosome);
        if(cop==null) return Integer.MIN_VALUE;
        int i=Arrays.binarySearch(cop.position, physicalPosition); //AvgPerObj:227.5715ns  for 2million positions
        i+=(i<0)?-cop.startSiteOff:cop.startSiteOff;
        while((i>0)&&(physicalPosition==get(i-1).getPosition())) {i--;} //backup to the first position if there are duplicates
        return i;
    }

    @Override
    public int getSiteOfPhysicalPosition(int physicalPosition, Chromosome chromosome, String snpID) {
        int result=getSiteOfPhysicalPosition(physicalPosition, chromosome);
        if (result < 0) {return result;}
        else {
            if (snpID.equals(getSNPID(result))) {return result;
            } else {
                int index=result;
                while ((index < numPositions) && (getPositionInChromosome(index) == physicalPosition)) {
                    if (snpID.equals(getSNPID(index))) {return index;}
                    result++;
                }
                return -result - 1;
            }
        }
    }

    @Override
    public int[] getPhysicalPositions() {
        int[] result=new int[numPositions];
        IntBuffer ib=IntBuffer.wrap(result);
        for (ChrOffPos cop: myChrOffPosTree.values()) {
            ib.put(cop.position);
        }
        return result;
    }

    @Override
    public String getChromosomeName(int site) {
        return mySiteList.get(site).getChromosome().getName();
    }

    @Override
    public Chromosome getChromosome(int site) {
        return mySiteList.get(site).getChromosome();
    }

    @Override
    public Chromosome getChromosome(String name) {
        return myChrNameHash.get(name);
    }

    @Override
    public Chromosome[] getChromosomes() {
        return myChrOffPosTree.keySet().toArray(new Chromosome[0]);
    }

    @Override
    public int getNumChromosomes() {
        return myChrOffPosTree.size();
    }

    @Override
    public int[] getChromosomesOffsets() {
        int[] result=new int[myChrOffPosTree.size()];
        int index=0;
        for (ChrOffPos cop: myChrOffPosTree.values()) {
            result[index++]=cop.startSiteOff;
        }
        return result;
    }

    @Override
    public int getIndelSize(int site) {
        return mySiteList.get(site).getKnownVariants()[1].length();
    }

    @Override
    public boolean isIndel(int site) {
        return mySiteList.get(site).isIndel();
    }

    @Override
    public String getGenomeAssembly() {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public boolean isPositiveStrand(int site) {
        return (1==mySiteList.get(site).getStrand());
    }
    
    // List methods

    @Override
    public int size() {
        return mySiteList.size();
    }

    @Override
    public boolean isEmpty() {
        return mySiteList.isEmpty();
    }

    @Override
    public boolean contains(Object o) {
        return mySiteList.contains(o);
    }

    @Override
    public Iterator<AnnotatedPosition> iterator() {
        return mySiteList.iterator();
    }

    @Override
    public Object[] toArray() {
        return mySiteList.toArray();
    }

    @Override
    public <AnnotatedSite> AnnotatedSite[] toArray(AnnotatedSite[] a) {
        return mySiteList.toArray(a);
    }

    /**Not supported immutable class*/
    @Override@Deprecated
    public boolean add(AnnotatedPosition e) {
        throw new UnsupportedOperationException("This Class is Immutable.");
    }

    /**Not supported immutable class*/
    @Override@Deprecated
    public boolean remove(Object o) {
        throw new UnsupportedOperationException("This Class is Immutable.");
    }

    @Override
    public boolean containsAll(Collection<?> c) {
        return mySiteList.containsAll(c);
    }

    /**Not supported immutable class*/
    @Override@Deprecated
    public boolean addAll(Collection<? extends AnnotatedPosition> c) {
        throw new UnsupportedOperationException("This Class is Immutable.");
    }

    /**Not supported immutable class*/
    @Override@Deprecated
    public boolean addAll(int index, Collection<? extends AnnotatedPosition> c) {
        throw new UnsupportedOperationException("This Class is Immutable.");
    }

    /**Not supported immutable class*/
    @Override@Deprecated
    public boolean removeAll(Collection<?> c) {
        throw new UnsupportedOperationException("This Class is Immutable.");
    }

    /**Not supported immutable class*/
    @Override@Deprecated
    public boolean retainAll(Collection<?> c) {
        throw new UnsupportedOperationException("This Class is Immutable.");
    }

    /**Not supported immutable class*/
    @Override@Deprecated
    public void clear() {
        throw new UnsupportedOperationException("This Class is Immutable.");
    }

    @Override
    public AnnotatedPosition get(int index) {
        return mySiteList.get(index);
    }

    /**Not supported immutable class*/
    @Override@Deprecated
    public AnnotatedPosition set(int index, AnnotatedPosition element) {
        throw new UnsupportedOperationException("This Class is Immutable.");
    }

    /**Not supported immutable class*/
    @Override@Deprecated
    public void add(int index, AnnotatedPosition element) {
        throw new UnsupportedOperationException("This Class is Immutable.");
    }

    /**Not supported immutable class*/
    @Override@Deprecated
    public AnnotatedPosition remove(int index) {
        throw new UnsupportedOperationException("This Class is Immutable.");
    }

    @Override
    public int indexOf(Object o) {
        return mySiteList.indexOf(o);
    }

    @Override
    public int lastIndexOf(Object o) {
        return mySiteList.lastIndexOf(o);
    }

    @Override
    public ListIterator<AnnotatedPosition> listIterator() {
        return mySiteList.listIterator();
    }

    @Override
    public ListIterator<AnnotatedPosition> listIterator(int index) {
        return mySiteList.listIterator(index);
    }

    @Override
    public List<AnnotatedPosition> subList(int fromIndex, int toIndex) {
        return mySiteList.subList(fromIndex, toIndex);
    }

    /**
     * A builder for creating immutable AnnotatedPositionArrayList
     *
     * <p>Example:
     * <pre>   {@code
     *   AnnotatedPositionArrayList.Builder b=new AnnotatedPositionArrayList.Builder();
     *   for (int i = 0; i <size; i++) {
     *       AnnotatedPosition ap=new CoreAnnotatedPosition.Builder(chr[chrIndex[i]],pos[i]).refAllele(refSeq[i]).build();
     *       b.add(ap);
     *       }
     *   instance=b.build();}
     *
     * <p>Builder instances can be reused - it is safe to call {@link #build}
     * multiple times to build multiple lists in series. Each new list
     * contains the one created before it.
     */
    public static class Builder {
        private final ArrayList<AnnotatedPosition> contents = new ArrayList<AnnotatedPosition>();

        /**
         * Creates a new builder. The returned builder is equivalent to the builder
         * generated by {@link }.
         */
        public Builder() {}

        /**
         * Adds {@code element} to the {@code AnnotatedPositionList}.
         *
         * @param element the element to add
         * @return this {@code Builder} object
         * @throws NullPointerException if {@code element} is null
         */
        public Builder add(AnnotatedPosition element) {
            Preconditions.checkNotNull(element, "element cannot be null");
            contents.add(element);
            return this;
        }

        /**
         * Adds each element of {@code elements} to the {@code AnnotatedPositionList}.
         *
         * @param elements the {@code Iterable} to add to the {@code AnnotatedPositionList}
         * @return this {@code Builder} object
         * @throws NullPointerException if {@code elements} is or contains null
         */
        public Builder addAll(Iterable<? extends AnnotatedPosition> elements) {
            if (elements instanceof Collection) {
                @SuppressWarnings("unchecked")
                Collection<? extends AnnotatedPosition> collection = (Collection<? extends AnnotatedPosition>) elements;
                contents.ensureCapacity(contents.size() + collection.size());
            }
            for (AnnotatedPosition elem : elements) {
                Preconditions.checkNotNull(elem, "elements contains a null");
                contents.add(elem);
            }
            return this;
        }

        /**
         * Returns a newly-created {@code ImmutableList} based on the contents of
         * the {@code Builder}.
         */
        public AnnotatedPositionList build() {
            System.out.println("Beginning Sort of Position List");
            Collections.sort(contents);
            System.out.println("Finished Sort of Position List");
            return new AnnotatedPositionArrayList(contents);
        }
    }

}
