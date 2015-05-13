package net.maizegenetics.analysis.gbs.v2;

import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Ordering;

import net.maizegenetics.analysis.gbs.Barcode;
import net.maizegenetics.dna.BaseEncoder;
import net.maizegenetics.dna.tag.*;
import net.maizegenetics.plugindef.AbstractPlugin;
import net.maizegenetics.plugindef.DataSet;
import net.maizegenetics.plugindef.Datum;
import net.maizegenetics.plugindef.PluginParameter;
import net.maizegenetics.taxa.TaxaList;
import net.maizegenetics.taxa.TaxaListIOUtils;
import net.maizegenetics.taxa.Taxon;
import net.maizegenetics.util.DirectoryCrawler;
import net.maizegenetics.util.Utils;

import org.apache.log4j.Logger;

import javax.swing.*;

import java.awt.*;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.IntStream;

import net.maizegenetics.util.GeneralAnnotation;

/**
 * Develops a discovery TBT file from a set of GBS sequence files.
 *
 * Keeps only good reads having a barcode and a cut site and no N's in the
 * useful part of the sequence. Trims off the barcodes and truncates sequences
 * that (1) have a second cut site, or (2) read into the common adapter.
 *
 * @author Ed Buckler
 */
public class GBSSeqToTagDBPlugin extends AbstractPlugin {

    private static final Logger myLogger = Logger.getLogger(GBSSeqToTagDBPlugin.class);

    private PluginParameter<String> myInputDir = new PluginParameter.Builder<>("i", null, String.class).guiName("Input Directory").required(true).inDir()
            .description("Input directory containing FASTQ files in text or gzipped text.\n"
                    + "     NOTE: Directory will be searched recursively and should\n"
                    + "     be written WITHOUT a slash after its name.").build();
    private PluginParameter<String> myKeyFile = new PluginParameter.Builder<>("k", null, String.class).guiName("Key File").required(true).inFile()
            .description("Key file listing barcodes distinguishing the samples").build();
    private PluginParameter<String> myEnzyme = new PluginParameter.Builder<>("e", null, String.class).guiName("Enzyme").required(true)
            .description("Enzyme used to create the GBS library, if it differs from the one listed in the key file").build();
    private PluginParameter<Integer> myMaxTagLength = new PluginParameter.Builder<>("mxTagL", 64, Integer.class).guiName("Maximum Tag Length")
            .description("Maximum Tag Length").build();
    private PluginParameter<Integer> myMinTagLength = new PluginParameter.Builder<>("mnTagL", 20, Integer.class).guiName("Minimum Tag Length")
            .description("Minimum Tag Length").build();
    private PluginParameter<Integer> myMinTagCount = new PluginParameter.Builder<>("c", 10, Integer.class).guiName("Min Tag Count")
            .description("Minimum tag count").build();
    private PluginParameter<String> myOutputDB = new PluginParameter.Builder<>("db", null, String.class).guiName("Output Database File").required(true).outFile()
            .description("Output Database File").build();
    private PluginParameter<Integer> myMinQualScore = new PluginParameter.Builder<>("mnQS", 0, Integer.class).guiName("Minimum quality score").required(false)
            .description("Minimum quality score within the barcode and read length to be accepted").build();
    private PluginParameter<Integer> myMaxTagNumber = new PluginParameter.Builder<>("mxTagNum", 50000000, Integer.class).guiName("Maximum Tag Number").required(false)
            .description("Maximum number of tags").build();
    private PluginParameter<Integer> myBatchSize = new PluginParameter.Builder<>("batchSize", 8, Integer.class).guiName("Batch size of fastq files").required(false)
            .description("Number of flow cells being processed simultaneously").build();
    LongAdder roughTagCnt = new LongAdder();
    
    private TagDistributionMap tagCntMap;
    private boolean taglenException;

    public GBSSeqToTagDBPlugin() {
        super(null, false);
    }

    public GBSSeqToTagDBPlugin(Frame parentFrame, boolean isInteractive) {
        super(parentFrame, isInteractive);
    }

    /**
     * Order files by size to improve concurrent performance
     * @param fs
     * @return 
     */
    private List<Path> sortFastqBySize (List<Path> fs) {
        ArrayList<Long> sizeList = new ArrayList();
        HashMap<Long,Path> sizePathMap = new HashMap();
        fs.parallelStream().forEach(f -> {
            try {
                long s = Files.size(f);
                sizeList.add(s);
                sizePathMap.putIfAbsent(s, f);
            }
            catch (Exception e) {
                e.printStackTrace();
            }
        });
        Collections.sort(sizeList, Collections.reverseOrder());
        //Collections.sort(sizeList);
        ArrayList<Path> np = new ArrayList();
        //files rarely have the same size, but it might happen
        try {
            for (int i = 0; i < sizeList.size(); i++) {
                np.add(sizePathMap.get(sizeList.get(i)));
            }
        }
        catch (Exception e) {
            e.printStackTrace();
        }
        return np;
    }
    
    private long[] calcTagMapStats(TagDistributionMap tagCntMap) {
        long totalDepth=0, memory=0;
        int cnt=0;
        for (Map.Entry<Tag, TaxaDistribution> entry : tagCntMap.entrySet()) {       	
            memory+=entry.getValue().memorySize();
            memory+=25;
            totalDepth+=entry.getValue().totalDepth();
            cnt++;
        }
        int currentSize = tagCntMap.size();
        memory+=tagCntMap.size()*2*16;  //estimate for the map size
        long[] stats={currentSize,memory, totalDepth,totalDepth/cnt};
        System.out.printf("Map Tags:%,d  Memory:%,d  TotalDepth:%,d  AvgDepthPerTag:%d%n",stats[0],stats[1],stats[2],stats[3]);
        return stats;
    }

    @Override
    public DataSet processData(DataSet input) {
        int batchSize = myBatchSize.value();
        float loadFactor = 0.95f;
        tagCntMap = new TagDistributionMap (myMaxTagNumber.value(),loadFactor, 128, this.minTagCount());
        double reducePoint = 0.5;
        try {
            //Get the list of fastq files
            Path keyPath= Paths.get(keyFile()).toAbsolutePath();
            List<Path> directoryFiles= DirectoryCrawler.listPaths(GBSUtils.inputFileGlob, Paths.get(myInputDir.value()).toAbsolutePath());
            if(directoryFiles.isEmpty()) { 
                myLogger.warn("No files matching:"+ GBSUtils.inputFileGlob);
                return null;
            } 
            // Cull files that are not represented in the given key file 
            List<Path> inputSeqFiles = GBSUtils.culledFiles(directoryFiles,keyPath);
            if (inputSeqFiles.size() == 0) return null; // no files in this directory to process
            //Files in a batch have roughly the same size
            //inputSeqFiles = this.sortFastqBySize(inputSeqFiles);
            int batchNum = inputSeqFiles.size()/batchSize;
            if (inputSeqFiles.size()%batchSize != 0) batchNum++;
            TaxaList masterTaxaList= TaxaListIOUtils.readTaxaAnnotationFile(keyFile(), GBSUtils.sampleNameField, new HashMap<>(), true);
            taglenException = false;
            for (int i = 0; i < inputSeqFiles.size(); i+=batchSize) {
                int end = i+batchSize;
                if (end > inputSeqFiles.size()) end = inputSeqFiles.size();
                ArrayList<Path> sub = new ArrayList();
                for (int j = i; j < end; j++) sub.add(inputSeqFiles.get(j));
                System.out.println("\nStart processing batch " + String.valueOf(i/batchSize+1));
                sub.parallelStream()
                .forEach(inputSeqFile -> {
                    try {
                        processFastQFile(masterTaxaList,keyPath, inputSeqFile, enzyme(),
                                minimumQualityScore(), tagCntMap, maximumTagLength());
                    } catch (StringIndexOutOfBoundsException oobe) {
                        oobe.printStackTrace();
                        myLogger.error(oobe.getMessage());
                        setTagLenException();
                        return;
                    }
                });
                if (taglenException == true) return null; // Tag length failure from processFastQ - halt processing

                System.out.println("\nTags are added from batch "+String.valueOf(i/batchSize+1) + ". Total batch number: " + batchNum);
                int currentSize = tagCntMap.size();
                System.out.println("Current tag number: " + String.valueOf(currentSize) + ". Max tag number: " + String.valueOf(myMaxTagNumber.value()));
                System.out.println(String.valueOf((float)currentSize/(float)myMaxTagNumber.value()) + " of max tag number");

                if (currentSize > 0) { // calcTagMapStats() gets "divide by 0" error when size == 0
                    this.calcTagMapStats(tagCntMap);
                    System.out.println();
                    //make sure don't lose rare ones, need to set maxTagNumber large enough
                    removeTagsWithoutReplication(tagCntMap);
                    if (tagCntMap.size() == 0) {
                        System.out.println("WARNING:  After removing tags without replication, there are NO  tags left in the database");
                    } else {
                        this.calcTagMapStats(tagCntMap);
                        System.out.println();
                        System.out.println("Tag number is reduced to " + tagCntMap.size()+"\n");  
                    }                   
                    this.roughTagCnt.reset();
                    this.roughTagCnt.add(tagCntMap.size());
                } else {
                    System.out.println("WARNING: Current tagcntmap size is 0 after processing batch " + String.valueOf(i/batchSize+1) );
                }

                System.out.println("Total memory: "+ String.valueOf((double)(Runtime.getRuntime().totalMemory()/1024/1024/1024))+" Gb");
                System.out.println("Free memory: "+ String.valueOf((double)(Runtime.getRuntime().freeMemory()/1024/1024/1024))+" Gb");
                System.out.println("Max memory: "+ String.valueOf((double)(Runtime.getRuntime().maxMemory()/1024/1024/1024))+" Gb");
                System.out.println("\n");
            }
            System.out.println("\nAll the batch are processed");
            tagCntMap.removeTagByCount(myMinTagCount.value());
            System.out.println("By removing tags with minCount of " + myMinTagCount.value() + "Tag number is reduced to " + tagCntMap.size()+"\n");
            
            removeSecondCutSitesFromMap(new GBSEnzyme(enzyme()));
            
            TagDataWriter tdw=new TagDataSQLite(myOutputDB.value());
            tdw.putTaxaList(masterTaxaList);
            tdw.putAllTag(tagCntMap.keySet());
            tdw.putTaxaDistribution(tagCntMap);
            ((TagDataSQLite)tdw).close();  //todo autocloseable should do this but it is not working.

        } catch(Exception e) {
            e.printStackTrace();
        }

        return new DataSet(new Datum("TagMap",tagCntMap,""),this);
    }
    
    private void processFastQFile(TaxaList masterTaxaList, Path keyPath, Path fastQPath, String enzymeName,
                     int minQuality, TagDistributionMap masterTagTaxaMap, int preferredTagLength) throws StringIndexOutOfBoundsException {
    	ArrayList<Taxon> tl=GBSUtils.getLaneAnnotatedTaxaList(keyPath, fastQPath);
    	if (tl.size() == 0) return; 
        BarcodeTrie barcodeTrie=GBSUtils.initializeBarcodeTrie(tl, masterTaxaList, new GBSEnzyme(enzymeName));
        try {
        	processFastQ(fastQPath,barcodeTrie,masterTaxaList,masterTagTaxaMap,preferredTagLength,minQuality);
        } catch (StringIndexOutOfBoundsException oobe) {
        	throw oobe; // Let processData() handle it - we want to stop processing on this error
        }        
    }

    private void processFastQ(Path fastqFile, BarcodeTrie barcodeTrie, TaxaList masterTaxaList,
                              TagDistributionMap masterTagTaxaMap, int preferredTagLength, int minQual) throws StringIndexOutOfBoundsException{
        int allReads=0, goodBarcodedReads = 0, lowQualityReads = 0;
        int maxTaxaNumber=masterTaxaList.size();
        int checkSize = 10000000;
        myLogger.info("processing file " + fastqFile.toString());
        try {
            int qualityScoreBase=GBSUtils.determineQualityScoreBase(fastqFile);
            BufferedReader br = Utils.getBufferedReader(fastqFile.toString(), 1 << 22);
            long time=System.nanoTime();
            String[] seqAndQual;
 
            while ((seqAndQual=GBSUtils.readFastQBlock(br,allReads)) != null) {
                allReads++;
                //After quality score is read, decode barcode using the current sequence & quality  score
                Barcode barcode=barcodeTrie.longestPrefix(seqAndQual[0]);
                if(barcode==null) continue;
                if(minQual>0) {
                    //todo move getFirstLowQualityPos into this class?
                    if(BaseEncoder.getFirstLowQualityPos(seqAndQual[1],minQual, qualityScoreBase)<(barcode.getBarLength()+preferredTagLength)){
                    	lowQualityReads++;
                    	continue;
                    }
                }

                int barcodeLen = barcode.getBarLength();               
                if (seqAndQual[0].length() - barcodeLen < preferredTagLength) {
                	String errMsg = "\n\nERROR processing " + fastqFile.toString() + "\n" +
                			"Reading entry number " + allReads + " fails the length test.\n" +
                			"Sequence length " + seqAndQual[0].length() + " minus barcode length "+ barcodeLen +
                			" is less then maxTagLength " + preferredTagLength + ".\n" +
                			"Re-run your files with either a shorter mxTagL value or a higher minimum quality score.\n";
                	throw new StringIndexOutOfBoundsException(errMsg);
                }
                
                Tag tag=TagBuilder.instance(seqAndQual[0].substring(barcodeLen, barcodeLen + preferredTagLength)).build();
                if(tag==null) continue;   //null occurs when any base was not A, C, G, T
                goodBarcodedReads++;
                TaxaDistribution taxaDistribution=masterTagTaxaMap.get(tag);
                if(taxaDistribution==null) {
                    masterTagTaxaMap.put(tag,TaxaDistBuilder.create(maxTaxaNumber,barcode.getTaxaIndex()));
                    this.roughTagCnt.increment();
                } else {
                    taxaDistribution.increment(barcode.getTaxaIndex());
                }
                if (allReads % checkSize == 0) {
                    myLogger.info("Total Reads:" + allReads + " Reads with barcode and cut site overhang:" + goodBarcodedReads
                            + " rate:" + (System.nanoTime()-time)/allReads +" ns/read. Current tag count:" + this.roughTagCnt);
                }
            }
            myLogger.info("Summary for "+fastqFile.toString()+"\n"+
                    "Total number of reads in lane=" + allReads +"\n"+
                    "Total number of good barcoded reads=" + goodBarcodedReads+"\n"+
                    "Total number of low quality reads=" + lowQualityReads+"\n"+
                    "Timing process (sorting, collapsing, and writing TagCount to file)."+"\n"+
                    "Process took " + (System.nanoTime() - time)/1e6 + " milliseconds.");
            System.out.println("tagCntMap size: "+masterTagTaxaMap.size());
            br.close();
        } catch (StringIndexOutOfBoundsException oobe) {
        	throw oobe; // pass it up to print error and stop processing
        } catch (Exception e) {
            myLogger.error("Good Barcodes Read: " + goodBarcodedReads);
            
            e.printStackTrace();
        }
    }

    private void removeSecondCutSitesFromMap(GBSEnzyme enzyme) {
        //this is a little tricky as you cannot add entries at the same time as removing entries to a map
        System.out.println("GBSSeqToTagDBPlugin.removeSecondCutSitesFromMap started Initial Size:"+tagCntMap.size());
        String[] likelyReadEnd=enzyme.likelyReadEnd();

        Map<Tag,TaxaDistribution> shortTags=new HashMap<>(tagCntMap.size()/5);
        int belowMinSize=0, shortExisting=0;
        for (Tag origTag : tagCntMap.keySet()) {
            int minCutSite=Integer.MAX_VALUE;
            String origTagSequence = origTag.sequence();
            for (String potentialCutSite : likelyReadEnd) {
            	int p = origTagSequence.indexOf(potentialCutSite, 1);
                if(p>0) minCutSite=Math.min(minCutSite,p);
            }
            if (minCutSite!=Integer.MAX_VALUE && minCutSite > 1) {
                if(minCutSite<minimumTagLength()) {
                    tagCntMap.remove(origTag);
                    belowMinSize++;
                    continue;
                }
                TaxaDistribution currentTaxaDist=tagCntMap.remove(origTag);
                Tag t = TagBuilder.instance(origTagSequence.substring(0, minCutSite + enzyme.readEndCutSiteRemnantLength())).build();
                TaxaDistribution existingTD=shortTags.get(t);
                if(existingTD!=null) {
                    if(currentTaxaDist==null || existingTD==null) {
                        System.out.println("We have a problem");
                    }
                    currentTaxaDist=TaxaDistBuilder.combine(currentTaxaDist,existingTD);
                    shortExisting++; //System.out.println("existingTD="+t);
                }
                shortTags.put(t, currentTaxaDist);
                //if(ttemp!=null) shortExisting++;
               // System.out.println(origTag+" -> "+t);
            }
        }
        System.out.println("After removal tagCntMap.size() = " + tagCntMap.size());
        System.out.println("After removal shortTags.size() = " + shortTags.size());
        System.out.println("belowMinSize = " + belowMinSize);
        System.out.println("shortExisting = " + shortExisting);
        tagCntMap.putAll(shortTags);
        System.out.println("After combining again tagCntMap.size() = " + tagCntMap.size());
    }
    
    /**
     * This method removes all tags are are never repeated in a single sample (taxa).  The concept is that
     * all biologically real tag should show up twice somewhere.  This could be called at the end of every
     * flowcell to test all the novel tags.
     */
    private static void removeTagsWithoutReplication (TagDistributionMap masterTagTaxaMap) {
        int currentSize = masterTagTaxaMap.size();
        int minTaxa=2;
        System.out.println("Starting removeTagsWithoutReplication. Current tag number: " + currentSize);
        LongAdder tagsRemoved=new LongAdder();
        masterTagTaxaMap.entrySet().parallelStream().forEach(t -> {
            TaxaDistribution td = t.getValue();
            if(td.totalDepth()<2*minTaxa) {
                masterTagTaxaMap.remove(t.getKey());
                tagsRemoved.increment();
            } 
            else if(IntStream.of(td.depths()).filter(depth -> depth>1).count()<minTaxa) {
                masterTagTaxaMap.remove(t.getKey());
                tagsRemoved.increment();
            }
        });
        System.out.println("Finished removeTagsWithoutReplication.  tagsRemoved = " + tagsRemoved + ". Current tag number: " + String.valueOf(currentSize-tagsRemoved.intValue()));
    }
    
    public void setTagLenException() {
    	taglenException = true;
    }

// The following getters and setters were auto-generated.
    // Please use this method to re-generate.
    //
    // public static void main(String[] args) {
    //     GeneratePluginCode.generate(GBSSeqToTagDBPlugin.class);
    // }

    /**
     * Convenience method to run plugin with one return object.
     */
    // TODO: Replace <Type> with specific type.
//    public <Type> runPlugin(DataSet input) {
//        return (<Type>) performFunction(input).getData(0).getData();
//    }

    /**
     * Input directory containing FASTQ files in text or gzipped
     * text.
     *      NOTE: Directory will be searched recursively and
     * should
     *      be written WITHOUT a slash after its name.
     *
     * @return Input Directory
     */
    public String inputDirectory() {
        return myInputDir.value();
    }

    /**
     * Set Input Directory. Input directory containing FASTQ
     * files in text or gzipped text.
     *      NOTE: Directory will be searched recursively and
     * should
     *      be written WITHOUT a slash after its name.
     *
     * @param value Input Directory
     *
     * @return this plugin
     */
    public GBSSeqToTagDBPlugin inputDirectory(String value) {
        myInputDir = new PluginParameter<>(myInputDir, value);
        return this;
    }

    /**
     * Key file listing barcodes distinguishing the samples
     *
     * @return Key File
     */
    public String keyFile() {
        return myKeyFile.value();
    }

    /**
     * Set Key File. Key file listing barcodes distinguishing
     * the samples
     *
     * @param value Key File
     *
     * @return this plugin
     */
    public GBSSeqToTagDBPlugin keyFile(String value) {
        myKeyFile = new PluginParameter<>(myKeyFile, value);
        return this;
    }

    /**
     * Enzyme used to create the GBS library, if it differs
     * from the one listed in the key file
     *
     * @return Enzyme
     */
    public String enzyme() {
        return myEnzyme.value();
    }

    /**
     * Set Enzyme. Enzyme used to create the GBS library,
     * if it differs from the one listed in the key file
     *
     * @param value Enzyme
     *
     * @return this plugin
     */
    public GBSSeqToTagDBPlugin enzyme(String value) {
        myEnzyme = new PluginParameter<>(myEnzyme, value);
        return this;
    }

    /**
     * Maximum Tag Length
     *
     * @return Maximum Tag Length
     */
    public Integer maximumTagLength() {
        return myMaxTagLength.value();
    }

    /**
     * Set Maximum Tag Length. Maximum Tag Length
     *
     * @param value Maximum Tag Length
     *
     * @return this plugin
     */
    public GBSSeqToTagDBPlugin maximumTagLength(Integer value) {
        myMaxTagLength = new PluginParameter<>(myMaxTagLength, value);
        return this;
    }

    /**
     * Minimum Tag Length
     *
     * @return Minimum Tag Length
     */
    public Integer minimumTagLength() {
        return myMinTagLength.value();
    }

    /**
     * Set Minimum Tag Length. Minimum Tag Length
     *
     * @param value Minimum Tag Length
     *
     * @return this plugin
     */
    public GBSSeqToTagDBPlugin minimumTagLength(Integer value) {
        myMinTagLength = new PluginParameter<>(myMinTagLength, value);
        return this;
    }

    /**
     * Minimum tag count
     *
     * @return Min Tag Count
     */
    public Integer minTagCount() {
        return myMinTagCount.value();
    }

    /**
     * Set Min Tag Count. Minimum tag count
     *
     * @param value Min Tag Count
     *
     * @return this plugin
     */
    public GBSSeqToTagDBPlugin minTagCount(Integer value) {
        myMinTagCount = new PluginParameter<>(myMinTagCount, value);
        return this;
    }

    /**
     * Output Database File
     *
     * @return Output Database File
     */
    public String outputDatabaseFile() {
        return myOutputDB.value();
    }

    /**
     * Set Output Database File. Output Database File
     *
     * @param value Output Database File
     *
     * @return this plugin
     */
    public GBSSeqToTagDBPlugin outputDatabaseFile(String value) {
        myOutputDB = new PluginParameter<>(myOutputDB, value);
        return this;
    }

    /**
     * Minimum quality score within the barcode and read length
     * to be accepted
     *
     * @return Minimum quality score
     */
    public Integer minimumQualityScore() {
        return myMinQualScore.value();
    }

    /**
     * Set Minimum quality score. Minimum quality score within
     * the barcode and read length to be accepted
     *
     * @param value Minimum quality score
     *
     * @return this plugin
     */
    public GBSSeqToTagDBPlugin minimumQualityScore(Integer value) {
        myMinQualScore = new PluginParameter<>(myMinQualScore, value);
        return this;
    }
    
    /**
     * Set maximum number of tag number
     * @param value
     * @return 
     */
    public GBSSeqToTagDBPlugin maximumTagNumber(Integer value) {
        myMaxTagNumber = new PluginParameter<>(myMaxTagNumber, value);
        return this;
    }
    
    /**
     * Set number of Fastq files processed simultaneously
     * @param value
     * @return 
     */
    public GBSSeqToTagDBPlugin batchSize(Integer value) {
        myBatchSize = new PluginParameter<>(myBatchSize, value);
        return this;
    }
     
    @Override
    public ImageIcon getIcon() {
        return null;
    }

    @Override
    public String getButtonName() {
        return "Discovery Tags By Taxa";
    }

    @Override
    public String getToolTipText() {
        return "Discovery Tags By Taxa";
    }

    /**
     * This ConcurrentHashMap constrain the size of the map, and purges low distribution count tags when the size needs
     * to be reduced.
     */
    static class TagDistributionMap extends ConcurrentHashMap<Tag,TaxaDistribution> {
        private final int maxTagNum;
        private int minDepthToRetainInMap=2;
        private final int minCount;
        
        TagDistributionMap (int maxTagNumber, float loadFactor, int concurrencyLevel, int minCount) {
            super((maxTagNumber*2), loadFactor, concurrencyLevel);
            maxTagNum = maxTagNumber;
            this.minCount = minCount;
        }
        
        @Override
        public TaxaDistribution put(Tag key, TaxaDistribution value) {
            return super.put(key, value);
        }

        public synchronized void removeTagByCount(int minCnt) {
            entrySet().parallelStream()
                    .filter(e -> e.getValue().totalDepth()<minCnt)
                    .forEach(e -> remove(e.getKey()));
        }
        
            
        public long estimateMapMemorySize() {
            long size=0;
            int cnt=0;
            for (Map.Entry<Tag, TaxaDistribution> entry : entrySet()) {
                size+=8+16+1; //Tag size
                size+=16; //Map references
                size+=entry.getValue().memorySize();
                cnt++;
                if(cnt>10000) break;
        }
            long estSize=(size()/cnt)*size;
            return estSize;
        }

        public long[] depthDistribution() {
            long[] base2bins=new long[34];
            int cnt=0;
            for (Map.Entry<Tag, TaxaDistribution> entry : entrySet()) {
                base2bins[31-Integer.numberOfLeadingZeros(entry.getValue().totalDepth())]++;
                cnt++;
               // if(cnt>100000) break;
            }
            return base2bins;
        }
    }
}


