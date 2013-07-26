
package net.maizegenetics.gbs.maps;

/**
 * Container class for information on mapping position.  
 * <p>
 * TODO: Needs to be generalized for describing all mapping positions.
 * <p>
 * @author edbuckler
 */
public class TagMappingInfoV3 {
    /** Chromosome as an integer */
    public int chromosome=Integer.MIN_VALUE;  // 4 bytes
    /** Strand relative to reference genome. 1 = same sense as reference FASTA file.  -1 = opposite sense.  unknown = Byte.MIN_VALUE */
    public byte strand=Byte.MIN_VALUE;   // 1 byte
    /**Chromosomal position of the barcoded end of the tag */
    public int startPosition=Integer.MIN_VALUE;  //   // 4 bytes
    /**Chromosomal position of the common adapter end of the tag (smaller than startPosition if tag matches minus strand) */
    public int endPosition=Integer.MIN_VALUE;  //   // 4 bytes
    /**Number of diverging bp (edit distance) from reference, unknown = Byte.MIN_VALUE*/
    public byte divergence=Byte.MIN_VALUE;  
    /**Code of mappingSource. 0: Bowtie2; 1: BWA; 2: BLAST; 3: PE; 4: Genetic Mapping*/
    public byte mappingSource = Byte.MIN_VALUE;
    /**The rank of this mapping based on the scores from one aligner*/
    public byte mappingRank = Byte.MIN_VALUE;
    /**The mapping score of this mapping*/
    public short mappingScore = Short.MIN_VALUE;
    /**Double cross-over probability Round(Log2(P)), unknown Byte.MIN_VALUE */
    public byte dcoP=Byte.MIN_VALUE;
    /**Genetic mapping probability Round(Log2(P)), unknown Byte.MIN_VALUE */
    public byte mapP=Byte.MIN_VALUE;  //Round(Log2(P)), unknown Byte.MIN_VALUE
    
    public TagMappingInfoV3() {   
    }
    
    public TagMappingInfoV3(int chromosome, byte strand, int startPosition, 
                int endPosition, byte divergence, byte mappingSource, short mappingScore) {
        this.chromosome=chromosome;
        this.strand=strand;
        this.startPosition=startPosition;
        this.endPosition=endPosition;
        this.divergence=divergence;
        this.mappingSource = mappingSource;
        this.mappingScore = mappingScore;
    } 
    
    public TagMappingInfoV3(int chromosome, byte strand, int startPosition, 
                int endPosition, byte divergence, byte mappingSource, short mappingScore, byte[] variantPosOff, byte[] variantDef,
                byte dcoP, byte mapP) {
        this(chromosome, strand, startPosition, endPosition, divergence, mappingSource, mappingScore);
        this.dcoP=dcoP;
        this.mapP=mapP;
    }
    
    public void setMappingRank (byte mappingRank) {
        this.mappingRank = mappingRank;
    }
    
    public void setMappingSource (byte mappingSource) {
        this.mappingSource = mappingSource;
    }
}
