package net.maizegenetics.dna.map;

import net.maizegenetics.util.GeneralAnnotation;
import net.maizegenetics.util.GeneralAnnotationStorage;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Defines the chromosome structure and length. The name and length recorded for
 * each chromosome.
 *
 * @author Terry Casstevens and Ed Buckler
 */
public class Chromosome implements Comparable<Chromosome> {

    private static final long serialVersionUID = -5197800047652332969L;
    public static Chromosome UNKNOWN = new Chromosome("Unknown");
    private final String myName;
    private final int myChromosomeNumber;
    private final int myLength;
    private final GeneralAnnotation myGA;
    private final int hashCode;

    //since there are numerous redundant chromosome, this class use a hash, so that
    //only the pointers are stored.
    private static final ConcurrentMap<Chromosome, Chromosome> CHR_HASH = new ConcurrentHashMap<>(50);

    public static Chromosome getCanonicalChromosome(Chromosome chr) {
        if (CHR_HASH.size() > 1000) {
            CHR_HASH.clear();
        }
        Chromosome canon = CHR_HASH.putIfAbsent(chr, chr);
        return (canon == null) ? chr : canon;
    }

    /**
     *
     * @param name Name of the chromosome
     * @param length Length of chromosome in base pairs
     * @param features Map of features about the chromosome
     */
    public Chromosome(String name, int length, GeneralAnnotation features) {
        myName = parseName(name);
        myLength = length;
        int convChr = Integer.MAX_VALUE;
        try {
            convChr = Integer.parseInt(myName);
        } catch (NumberFormatException ne) {
        }
        myChromosomeNumber = convChr;
        myGA = features;
        hashCode = calcHashCode();
    }

    public Chromosome(String name) {
        this(name, -1, parseAnnotationFromName(name));
    }

    public String getName() {
        return myName;
    }

    /**
     * Returns the integer value of the chromosome (if name is not a number then
     * Integer.MAX_VALUE is returned)
     */
    public int getChromosomeNumber() {
        return myChromosomeNumber;
    }

    public int getLength() {
        return myLength;
    }

    public GeneralAnnotation getAnnotation() {
        return myGA;
    }

    @Override
    public String toString() {
        return getName();
    }

    @Override
    public int hashCode() {
        return hashCode;
    }

    private int calcHashCode() {
        int hash = 7;
        hash = 79 * hash + (this.myName != null ? this.myName.hashCode() : 0);
        return hash;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) {
            return true;
        }
        if (!(obj instanceof Chromosome)) {
            return false;
        }
        return (compareTo((Chromosome) obj) == 0);
    }

    @Override
    public int compareTo(Chromosome o) {
        int result = myChromosomeNumber - o.getChromosomeNumber();
        if ((result != 0) || (myChromosomeNumber != Integer.MAX_VALUE)) {
            return result;
        }
        return myName.compareTo(o.getName());
    }
    /**
     * Takes a string, makes all upper case, removes CHROMOSOME/CHR, 
     * returns the resulting string
     * @param chromosome
     * @return the input string minus a leading "chr" or "chromsome" 
     */
    private static String parseName(String name) {
        if (name == null || name == "") return name;
        String parsedName = name.trim();
        parsedName = parsedName.toUpperCase();
        parsedName = parsedName.replace("CHROMOSOME ","");
        parsedName = parsedName.replace("CHR","");
        int spaceIndex = parsedName.indexOf(" ");
        if (spaceIndex > 0){
            parsedName = parsedName.substring(0,parsedName.indexOf(" "));
        }
        return parsedName;
    }
    
    /**
     * Takes a chromosome name, looks for the first space, returns
     * the data beyond as an annotation.  This takes care of lines in
     * a fasta file that look like this:
     * >3 This is a description
     * @param name - the string chromosome passed in
     * @return Annotations built from the string beyond the name
     */
    private static GeneralAnnotation parseAnnotationFromName(String name) {
        if (name == null || name == "") return null;
        GeneralAnnotation myAnnotations = null;
        String currChrDesc = null;
        int spaceIndex = name.indexOf(" ");
        if (spaceIndex > 0) {                   
            currChrDesc = name.substring(name.indexOf(" ") + 1);
            myAnnotations = GeneralAnnotationStorage.getBuilder().addAnnotation("Description", currChrDesc).build();
        } 

        return myAnnotations;
    }
}
