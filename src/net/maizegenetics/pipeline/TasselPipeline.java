/*
 * TasselPipeline.java
 *
 * Created on June 11, 2009
 *
 */
package net.maizegenetics.pipeline;

import net.maizegenetics.analysis.popgen.SequenceDiversityPlugin;
import net.maizegenetics.analysis.distance.KinshipPlugin;
import net.maizegenetics.analysis.distance.DistanceMatrixRangesPlugin;
import net.maizegenetics.analysis.distance.DistanceMatrixPlugin;
import net.maizegenetics.analysis.chart.AbstractDisplayPlugin;
import net.maizegenetics.analysis.chart.TableDisplayPlugin;
import net.maizegenetics.analysis.data.ConvertAlignmentCoordinatesPlugin;
import net.maizegenetics.analysis.association.FixedEffectLMPlugin;
import net.maizegenetics.analysis.association.WeightedMLMPlugin;
import net.maizegenetics.analysis.data.PlinkLoadPlugin;
import net.maizegenetics.analysis.popgen.LinkageDiseqDisplayPlugin;
import net.maizegenetics.analysis.popgen.LinkageDisequilibriumPlugin;
import net.maizegenetics.analysis.data.CombineDataSetsPlugin;
import net.maizegenetics.analysis.data.MergeGenotypeTablesPlugin;
import net.maizegenetics.analysis.data.MergeAlignmentsSameSitesPlugin;
import net.maizegenetics.analysis.data.UnionAlignmentPlugin;
import net.maizegenetics.analysis.data.FileLoadPlugin;
import net.maizegenetics.analysis.data.IntersectionAlignmentPlugin;
import net.maizegenetics.analysis.data.GenotypeSummaryPlugin;
import net.maizegenetics.analysis.data.SeparatePlugin;
import net.maizegenetics.analysis.data.SynonymizerPlugin;
import net.maizegenetics.analysis.data.ExportMultiplePlugin;
import net.maizegenetics.analysis.data.HetsToUnknownPlugin;
import net.maizegenetics.analysis.data.ProjectionLoadPlugin;
import net.maizegenetics.analysis.filter.FilterTaxaAlignmentPlugin;
import net.maizegenetics.analysis.filter.FilterSiteNamePlugin;
import net.maizegenetics.analysis.filter.FilterAlignmentPlugin;
import net.maizegenetics.analysis.filter.FilterTraitsPlugin;
import net.maizegenetics.analysis.filter.FilterSubsetPlugin;
import net.maizegenetics.analysis.tree.CreateTreePlugin;
import net.maizegenetics.analysis.tree.ArchaeopteryxPlugin;
import net.maizegenetics.analysis.association.RidgeRegressionEmmaPlugin;
import net.maizegenetics.dna.map.TagsOnPhysMapHDF5;
import net.maizegenetics.dna.map.TagsOnPhysicalMap;
import net.maizegenetics.dna.snp.score.SiteScore;
import net.maizegenetics.analysis.popgen.LinkageDisequilibriumComponent;
import net.maizegenetics.analysis.popgen.LinkageDisequilibrium.HetTreatment;
import net.maizegenetics.analysis.popgen.LinkageDisequilibrium.testDesign;
import net.maizegenetics.taxa.TaxaListBuilder;
import net.maizegenetics.taxa.Taxon;
import net.maizegenetics.plugindef.*;
import net.maizegenetics.prefs.TasselPrefs;
import net.maizegenetics.progress.ProgressPanel;
import net.maizegenetics.tassel.DataTreePanel;
import net.maizegenetics.tassel.TASSELMainFrame;
import net.maizegenetics.tassel.TasselLogging;
import net.maizegenetics.util.LoggingUtils;
import net.maizegenetics.util.ExceptionUtils;
import net.maizegenetics.util.Utils;

import org.apache.log4j.Level;
import org.apache.log4j.Logger;

import java.io.BufferedReader;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Pattern;

import net.maizegenetics.analysis.chart.ManhattanDisplayPlugin;
import net.maizegenetics.analysis.data.PrincipalComponentsPlugin;

/**
 *
 * @author Terry Casstevens
 */
public class TasselPipeline implements PluginListener {

    public static enum FLAGS {

        t, s, k, q, h, h5, hdf5Schema, r, plink, fasta, geneticMap,
        table, vcf, readSerialAlignment, importGuess, projection,
        convertTOPMtoHDF5, retainRareAlleles, union, intersect, separate,
        homozygous, synonymizer, mergeGenotypeTables, mergeAlignmentsSameSites,
        excludeLastTrait, mlm, glm, td_csv, td_tab, td_gui, diversity, ld, ldd,
        ck, tree, gs, distanceMatrix, distMatrixRanges, genotypeSummary,
        export, filterAlign, numericalGenoTransform, includeTaxa,
        includeTaxaInFile, excludeTaxa, excludeTaxaInFile, includeSiteNames,
        includeSiteNamesInFile, excludeSiteNames, excludeSiteNamesInFile,
        subsetSites, subsetTaxa, newCoordinates;

        @Override
        public String toString() {
            return "-" + super.toString();
        }
    };

    private static final Logger myLogger = Logger.getLogger(TasselPipeline.class);
    private final TASSELMainFrame myMainFrame;
    private final Map<String, List<Plugin>> myForks = new LinkedHashMap<>();
    private String myCurrentFork = null;
    private List<Plugin> myCurrentPipe = null;
    private Plugin myFirstPlugin = null;
    private final List<ThreadedPluginListener> myThreads = new ArrayList<>();
    private final Map<Plugin, Integer> myProgressValues = new HashMap<>();
    private final StringBuilder myDeprecatedWarning = new StringBuilder();
    private final boolean myIsInteractive;
    private final boolean myIsThreaded;
    private TasselPipelineStepsDialog myStepsDialog = null;
    private String[] myDescriptions = null;
    private int myCurrentDescriptionIndex = 0;

    /**
     * Creates a new instance of TasselPipeline
     */
    public TasselPipeline(String args[], TASSELMainFrame frame) {
        this(args, frame, false, null);
    }

    /**
     * Creates a new instance of TasselPipeline
     */
    public TasselPipeline(String args[], TASSELMainFrame frame, boolean interactive, String name) {

        myMainFrame = frame;
        myIsInteractive = interactive;
        myIsThreaded = !myIsInteractive;

        final ExecutorService pool;
        if (myIsInteractive) {
            pool = null;
        } else {
            int numThreads = Runtime.getRuntime().availableProcessors() / 2;
            pool = Executors.newFixedThreadPool(numThreads);
        }
        try {

            if ((args.length == 1) && (args[0].equalsIgnoreCase("-versionComment"))) {
                System.out.println("Version " + TASSELMainFrame.version + " on " + TASSELMainFrame.versionDate);
                return;
            }

            if ((args.length == 1) && (args[0].equalsIgnoreCase("-versionTag"))) {
                System.out.println("V" + TASSELMainFrame.version);
                return;
            }

            TasselLogging.basicLoggingInfo();

            if (myIsInteractive) {
                myStepsDialog = new TasselPipelineStepsDialog(myMainFrame, name);
            }

            parseArgs(args);

            if ((myMainFrame != null) && (!myIsInteractive)) {
                ProgressPanel progressPanel = myMainFrame.getProgressPanel();
                if (progressPanel != null) {
                    Iterator<String> itr = myForks.keySet().iterator();
                    while (itr.hasNext()) {
                        String key = itr.next();
                        List<Plugin> current = myForks.get(key);
                        progressPanel.addPipelineSegment(current);
                    }
                }
            }

            if (myIsInteractive) {
                myStepsDialog.showDialog();
                for (ThreadedPluginListener current : myThreads) {
                    try {
                        current.run();
                        if (((Plugin) current.getPluginListener()).wasCancelled()) {
                            break;
                        }
                    } catch (Exception ex) {
                        myLogger.error(ex.getMessage(), ex);
                        break;
                    }
                }
            } else {
                List<Future<?>> futures = new ArrayList<>();

                myThreads.stream().forEach((current) -> {
                    futures.add(pool.submit(current));
                });

                for (Future<?> future : futures) {
                    future.get();
                }
            }

            if (myDeprecatedWarning.length() != 0) {
                myLogger.warn(myDeprecatedWarning.toString());
            }

        } catch (Exception e) {
            myLogger.error(e.getMessage(), e);
            if (myIsInteractive) {
                throw new IllegalStateException("TasselPipeline: init: " + e.getMessage());
            } else {
                System.exit(1);
            }
        } finally {
            if (pool != null) {
                pool.shutdownNow();
            }
        }

    }

    public static void main(String args[]) {

        TasselPrefs.setPersistPreferences(false);
        LoggingUtils.setupLogging();

        if ((args.length >= 2) && (args[0].equalsIgnoreCase("-createXML"))) {
            String xmlFilename = args[1].trim();
            String[] temp = new String[args.length - 2];
            System.arraycopy(args, 2, temp, 0, temp.length);
            temp = addForkFlagsIfNeeded(temp);
            TasselPipelineXMLUtil.writeArgsAsXML(xmlFilename, temp);
        } else if ((args.length >= 2) && (args[0].equalsIgnoreCase("-translateXML"))) {
            String xmlFilename = args[1].trim();
            String[][] result = TasselPipelineXMLUtil.readXMLAsArgs(xmlFilename);
            for (int i = 0; i < result[0].length; i++) {
                System.out.print(result[0][i]);
                System.out.print(" ");
            }
            System.out.println("");
        } else if ((args.length >= 1) && (args[0].equalsIgnoreCase("-debug") || args[0].equalsIgnoreCase("-log"))) {
            String filename = null;
            if (args.length >= 2) {
                filename = args[1].trim();
            }

            String[] temp = null;
            if ((filename != null) && (!filename.startsWith("-"))) {
                try {
                    if (args[0].equalsIgnoreCase("-debug")) {
                        LoggingUtils.setupDebugLogfile(filename);
                    } else {
                        LoggingUtils.setupLogfile(filename);
                    }
                } catch (Exception e) {
                    myLogger.error("Problem with file: " + filename + "\n" + e.getMessage());
                }
                temp = new String[args.length - 2];
                System.arraycopy(args, 2, temp, 0, temp.length);
            } else {
                if (args[0].equalsIgnoreCase("-debug")) {
                    LoggingUtils.setupDebugLogging();
                } else {
                    LoggingUtils.setupLogging();
                }
                temp = new String[args.length - 1];
                System.arraycopy(args, 1, temp, 0, temp.length);
            }

            new TasselPipeline(temp, null);
        } else {
            new TasselPipeline(args, null);
        }

    }

    public void parseArgs(String[] input) {

        String[] args = input;

        if ((args.length >= 1) && (args[0].equalsIgnoreCase("-configFile"))) {
            if (args.length < 2) {
                throw new IllegalArgumentException("TasselPipeline: parseArgs: a filename must follow -configFile flag.");
            }
            String xmlFilename = args[1].trim();
            String[][] tempArgsDesc = TasselPipelineXMLUtil.readXMLAsArgs(xmlFilename);
            args = tempArgsDesc[0];
            myDescriptions = tempArgsDesc[1];
        } else if ((args.length >= 1) && (args[0].equalsIgnoreCase("-configResourceFile"))) {
            if (args.length < 2) {
                throw new IllegalArgumentException("TasselPipeline: parseArgs: a filename must follow -configResourceFile flag.");
            }
            String xmlFilename = args[1].trim();
            String[][] tempArgsDesc = TasselPipelineXMLUtil.readXMLAsArgsFromResource(xmlFilename);
            args = tempArgsDesc[0];
            myDescriptions = tempArgsDesc[1];
            if ((myStepsDialog != null) && (tempArgsDesc[2] != null)) {
                if (tempArgsDesc[2][0] != null) {
                    myStepsDialog.setOverallDescription(tempArgsDesc[2][0]);
                }
                if (tempArgsDesc[2][1] != null) {
                    myStepsDialog.setCitation(tempArgsDesc[2][1]);
                }
            }
        } else {
            args = addForkFlagsIfNeeded(args);
        }

        myLogger.info("Tassel Pipeline Arguments: " + Arrays.deepToString(args));
        int index = 0;
        while (index < args.length) {

            myCurrentDescriptionIndex = index;

            try {

                String current = args[index++];
                String emDash = "\u2014";
                current = current.replaceFirst(emDash, "-");

                if (!current.startsWith("-")) {
                    throw new IllegalArgumentException("TasselPipeline: parseArgs: expecting argument beginning with dash: " + current);
                }

                if (current.startsWith("-runfork")) {
                    String key = current.replaceFirst("-runfork", "-fork");
                    List<Plugin> specifiedPipe = myForks.get(key);
                    if (specifiedPipe == null) {
                        throw new IllegalArgumentException("TasselPipeline: parseArgs: unknown fork: " + current);
                    } else if (specifiedPipe.isEmpty()) {
                        throw new IllegalArgumentException("TasselPipeline: parseArgs: empty fork: " + current);
                    } else {
                        PluginEvent event = new PluginEvent(new DataSet((Datum) null, null));
                        ThreadedPluginListener thread = new ThreadedPluginListener(specifiedPipe.get(0), event);
                        myThreads.add(thread);
                    }
                } else if (current.startsWith("-fork")) {
                    if ((myCurrentPipe != null) && (!myCurrentPipe.isEmpty())) {
                        myCurrentPipe.get(myCurrentPipe.size() - 1).setThreaded(myIsThreaded);
                    }
                    myCurrentFork = current;
                    myCurrentPipe = new ArrayList<>();
                    myForks.put(myCurrentFork, myCurrentPipe);
                } else if (current.startsWith("-input")) {
                    String key = current.replaceFirst("-input", "-fork");
                    List<Plugin> specifiedPipe = myForks.get(key);
                    if (specifiedPipe == null) {
                        throw new IllegalArgumentException("TasselPipeline: parseArgs: unknown input: " + current);
                    } else {
                        Plugin lastCurrentPipe = null;
                        try {
                            lastCurrentPipe = myCurrentPipe.get(myCurrentPipe.size() - 1);
                        } catch (Exception e) {
                            throw new IllegalArgumentException("TasselPipeline: parseArgs: -input must come after plugin in current fork.");
                        }
                        Plugin endSpecifiedPipe = specifiedPipe.get(specifiedPipe.size() - 1);
                        lastCurrentPipe.receiveInput(endSpecifiedPipe);
                    }
                } else if (current.startsWith("-inputOnce")) {
                    String key = current.replaceFirst("-input", "-fork");
                    List<Plugin> specifiedPipe = myForks.get(key);
                    if (specifiedPipe == null) {
                        throw new IllegalArgumentException("TasselPipeline: parseArgs: unknown input: " + current);
                    } else {
                        CombineDataSetsPlugin combinePlugin = null;
                        try {
                            combinePlugin = (CombineDataSetsPlugin) myCurrentPipe.get(myCurrentPipe.size() - 1);
                        } catch (Exception e) {
                            throw new IllegalArgumentException("TasselPipeline: parseArgs: -inputOnce must follow -combine flag.");
                        }
                        Plugin endSpecifiedPipe = specifiedPipe.get(specifiedPipe.size() - 1);
                        combinePlugin.receiveDataSetOnceFrom(endSpecifiedPipe);
                    }
                } else if (current.startsWith("-combine")) {
                    current = current.replaceFirst("-combine", "-fork");
                    if ((myCurrentPipe != null) && (!myCurrentPipe.isEmpty())) {
                        myCurrentPipe.get(myCurrentPipe.size() - 1).setThreaded(myIsThreaded);
                    }
                    myCurrentFork = current;
                    myCurrentPipe = new ArrayList<>();
                    myForks.put(myCurrentFork, myCurrentPipe);
                    integratePlugin(new CombineDataSetsPlugin(), false);
                } else if (current.equalsIgnoreCase("-t")) {
                    String traitFile = args[index++].trim();
                    loadFile(traitFile, FileLoadPlugin.TasselFileType.Phenotype);
                } else if (current.equalsIgnoreCase("-s")) {
                    String inputFile = args[index++].trim();
                    loadFile(inputFile, FileLoadPlugin.TasselFileType.Sequence);
                } else if (current.equalsIgnoreCase("-k")) {
                    String kinshipFile = args[index++].trim();
                    loadFile(kinshipFile, FileLoadPlugin.TasselFileType.SqrMatrix);
                } else if (current.equalsIgnoreCase("-q")) {
                    String populationFile = args[index++].trim();
                    loadFile(populationFile, FileLoadPlugin.TasselFileType.Phenotype);
                } else if (current.equalsIgnoreCase("-h")) {
                    String hapFile = args[index++].trim();
                    loadFile(hapFile, FileLoadPlugin.TasselFileType.Hapmap);
                } else if (current.equalsIgnoreCase("-h5")) {
                    String hdf5File = args[index++].trim();
                    loadFile(hdf5File, FileLoadPlugin.TasselFileType.HDF5);
                } else if (current.equalsIgnoreCase("-hdf5Schema")) {
                    String hdf5File = args[index++].trim();
                    loadFile(hdf5File, FileLoadPlugin.TasselFileType.HDF5Schema);
                } else if (current.equalsIgnoreCase("-r")) {
                    String phenotypeFile = args[index++].trim();
                    loadFile(phenotypeFile, FileLoadPlugin.TasselFileType.Phenotype);
                } else if (current.equalsIgnoreCase("-plink")) {
                    String pedFile = null;
                    String mapFile = null;
                    for (int i = 0; i < 2; i++) {
                        String fileType = args[index++].trim();
                        String filename = args[index++].trim();
                        if (fileType.equalsIgnoreCase("-ped")) {
                            pedFile = filename;
                        } else if (fileType.equalsIgnoreCase("-map")) {
                            mapFile = filename;
                        } else {
                            throw new IllegalArgumentException("TasselPipeline: parseArgs: -plink: unknown file type: " + fileType);
                        }
                    }
                    if ((pedFile == null) || (mapFile == null)) {
                        throw new IllegalArgumentException("TasselPipeline: parseArgs: -plink must specify both ped and map files.");
                    }
                    PlinkLoadPlugin plugin = new PlinkLoadPlugin(myMainFrame, myIsInteractive);
                    plugin.pedFile(pedFile);
                    plugin.mapFile(mapFile);
                    integratePlugin(plugin, true);
                } else if (current.equalsIgnoreCase("-fasta")) {
                    String fastaFile = args[index++].trim();
                    loadFile(fastaFile, FileLoadPlugin.TasselFileType.Fasta);
                } else if (current.equalsIgnoreCase("-table")) {
                    String tableFile = args[index++].trim();
                    loadFile(tableFile, FileLoadPlugin.TasselFileType.Table);
                } else if (current.equalsIgnoreCase("-vcf")) {
                    String vcfFile = args[index++].trim();
                    loadFile(vcfFile, FileLoadPlugin.TasselFileType.VCF);
                } else if (current.equalsIgnoreCase("-readSerialAlignment")) {
                    String file = args[index++].trim();
                    loadFile(file, FileLoadPlugin.TasselFileType.Serial);
                } else if (current.equalsIgnoreCase("-importGuess")) {
                    String file = args[index++].trim();
                    loadFile(file, FileLoadPlugin.TasselFileType.Unknown);
                } else if (current.equalsIgnoreCase("-projection")) {
                    String file = args[index++].trim();
                    ProjectionLoadPlugin plugin = new ProjectionLoadPlugin(myMainFrame, myIsInteractive);
                    plugin.recombinationBreakpoints(file);
                    integratePlugin(plugin, true);
                } else if (current.equalsIgnoreCase("-printGenoSummary")) {
                    GenotypeSummaryPlugin plugin = new GenotypeSummaryPlugin(myMainFrame, myIsInteractive);
                    plugin.overview(false);
                    plugin.siteSummary(false);
                    plugin.taxaSummary(false);
                    integratePlugin(plugin, true);
                } else if (current.equalsIgnoreCase("-convertTOPMtoHDF5")) {
                    String filename = args[index++].trim();
                    TagsOnPhysicalMap topm = null;
                    String h5Filename = null;
                    if (filename.endsWith(".topm.txt")) {
                        topm = new TagsOnPhysicalMap(filename, false);
                        h5Filename = filename.replaceAll(".topm.txt", ".topm.h5");
                    } else if (filename.endsWith(".topm.bin")) {
                        topm = new TagsOnPhysicalMap(filename, true);
                        h5Filename = filename.replaceAll(".topm.bin", ".topm.h5");
                    } else {
                        throw new IllegalArgumentException("TasselPipeline: parseArgs: -convertTOPMtoHDF5: Unknown file extension: " + filename);
                    }
                    TagsOnPhysMapHDF5.createFile(topm, h5Filename, 4, 8);
                } else if (current.equalsIgnoreCase("-retainRareAlleles")) {
                    String temp = args[index++].trim();
                    boolean retain = true;
                    if (temp.equalsIgnoreCase("false")) {
                        retain = false;
                    } else if (temp.equalsIgnoreCase("true")) {
                        retain = true;
                    } else {
                        throw new IllegalArgumentException("TasselPipeline: parseArgs: -retainRareAlleles parameter must be true or false.");
                    }
                    TasselPrefs.putAlignmentRetainRareAlleles(retain);
                } else if (current.equalsIgnoreCase("-union")) {
                    UnionAlignmentPlugin plugin = new UnionAlignmentPlugin(myMainFrame, myIsInteractive);
                    integratePlugin(plugin, true);
                } else if (current.equalsIgnoreCase("-intersect")) {
                    IntersectionAlignmentPlugin plugin = new IntersectionAlignmentPlugin(myMainFrame, myIsInteractive);
                    integratePlugin(plugin, true);
                } else if (current.equalsIgnoreCase("-separate")) {
                    SeparatePlugin plugin = new SeparatePlugin(myMainFrame, myIsInteractive);
                    String temp = args[index].trim();
                    if (!temp.startsWith("-")) {
                        String[] chromosomes = temp.split(",");
                        plugin.setChromosomesToSeparate(chromosomes);
                        index++;
                    }
                    integratePlugin(plugin, true);
                } else if (current.equalsIgnoreCase("-homozygous")) {
                    HetsToUnknownPlugin plugin = new HetsToUnknownPlugin(myMainFrame, myIsInteractive);
                    integratePlugin(plugin, true);
                } else if (current.equalsIgnoreCase("-synonymizer")) {
                    SynonymizerPlugin plugin = new SynonymizerPlugin(myMainFrame, myIsInteractive);
                    integratePlugin(plugin, true);
                } else if (current.equalsIgnoreCase("-mergeGenotypeTables")) {
                    MergeGenotypeTablesPlugin plugin = new MergeGenotypeTablesPlugin(myMainFrame, myIsInteractive);
                    integratePlugin(plugin, true);
                } else if (current.equalsIgnoreCase("-mergeAlignmentsSameSites")) {
                    MergeAlignmentsSameSitesPlugin plugin = new MergeAlignmentsSameSitesPlugin(myMainFrame);
                    try {
                        for (int i = 0; i < 2; i++) {
                            String paraType = args[index++].trim();
                            String value = args[index++].trim();
                            if (paraType.equalsIgnoreCase("-input")) {
                                String[] files = value.split(",");
                                List<String> filenames = new ArrayList<String>();
                                for (int j = 0; j < files.length; j++) {
                                    filenames.add(files[j]);
                                }
                                plugin.setInputFiles(filenames);
                            } else if (paraType.equalsIgnoreCase("-output")) {
                                plugin.setOutputFile(value);
                            } else {
                                throw new IllegalArgumentException("TasselPipeline: parseArgs: -mergeAlignmentsSameSites: unknown descriptor: " + paraType);
                            }
                        }
                    } catch (IndexOutOfBoundsException e) {
                        throw new IllegalArgumentException("TasselPipeline: parseArgs: -mergeAlignmentsSameSites: not specified correctly.");
                    }
                    integratePlugin(plugin, true);
                } else if (current.equalsIgnoreCase("-excludeLastTrait")) {
                    FilterTraitsPlugin plugin = new FilterTraitsPlugin(myMainFrame, myIsInteractive);
                    plugin.excludeLast(true);
                    integratePlugin(plugin, true);
                } else if (current.equalsIgnoreCase("-pca")) {
                    PrincipalComponentsPlugin plugin = new PrincipalComponentsPlugin(myMainFrame, myIsInteractive);
                    integratePlugin(plugin, true);
                } else if (current.equalsIgnoreCase("-mhd")) {
                    ManhattanDisplayPlugin plugin = new ManhattanDisplayPlugin(myMainFrame, myIsInteractive);
                    integratePlugin(plugin, true);
                } else if (current.equalsIgnoreCase("-mlm")) {
                    WeightedMLMPlugin plugin = new WeightedMLMPlugin(myMainFrame, myIsInteractive);
                    integratePlugin(plugin, true);
                } else if (current.equalsIgnoreCase("-mlmVarCompEst")) {
                    WeightedMLMPlugin plugin = (WeightedMLMPlugin) findLastPluginFromCurrentPipe(new Class[]{WeightedMLMPlugin.class});
                    
                    if (plugin == null) {
                        throw new IllegalArgumentException("TasselPipeline: parseArgs: No MLM step defined: " + current);
                    }
                    String method = args[index++].trim();
                    plugin.setVarCompEst(method);
                } else if (current.equalsIgnoreCase("-mlmCompressionLevel")) {
                    WeightedMLMPlugin plugin = (WeightedMLMPlugin) findLastPluginFromCurrentPipe(new Class[]{WeightedMLMPlugin.class});
                    if (plugin == null) {
                        throw new IllegalArgumentException("TasselPipeline: parseArgs: No MLM step defined: " + current);
                    }
                    String type = args[index++].trim();
                    if (type.equalsIgnoreCase("Optimum")) {
                        plugin.setCompressionType(WeightedMLMPlugin.CompressionType.Optimum);
                    } else if (type.equalsIgnoreCase("Custom")) {
                        plugin.setCompressionType(WeightedMLMPlugin.CompressionType.Custom);
                    } else if (type.equalsIgnoreCase("None")) {
                        plugin.setCompressionType(WeightedMLMPlugin.CompressionType.None);
                    } else {
                        throw new IllegalArgumentException("TasselPipeline: parseArgs: Unknown compression type: " + type);
                    }
                } else if (current.equalsIgnoreCase("-mlmCustomCompression")) {
                    WeightedMLMPlugin plugin = (WeightedMLMPlugin) findLastPluginFromCurrentPipe(new Class[]{WeightedMLMPlugin.class});
                    if (plugin == null) {
                        throw new IllegalArgumentException("TasselPipeline: parseArgs: No MLM step defined: " + current);
                    }
                    String temp = args[index++].trim();
                    double value = 0;
                    try {
                        value = Double.parseDouble(temp);
                    } catch (Exception e) {
                        throw new IllegalArgumentException("TasselPipeline: parseArgs: Problem parsing custom compression: " + temp);
                    }
                    plugin.setCustomCompression(value);
                } else if (current.equalsIgnoreCase("-mlmOutputFile")) {
                    WeightedMLMPlugin plugin = (WeightedMLMPlugin) findLastPluginFromCurrentPipe(new Class[]{WeightedMLMPlugin.class});
                    if (plugin == null) {
                        throw new IllegalArgumentException("TasselPipeline: parseArgs: No MLM step defined: " + current);
                    }
                    String filename = args[index++].trim();
                    plugin.setOutputName(filename);
                } else if (current.equalsIgnoreCase("-mlmMaxP")) {
                    WeightedMLMPlugin plugin = (WeightedMLMPlugin) findLastPluginFromCurrentPipe(new Class[]{WeightedMLMPlugin.class});
                    if (plugin == null) {
                        throw new IllegalArgumentException("TasselPipeline: parseArgs: No MLM step defined: " + current);
                    }
                    String temp = args[index++].trim();
                    double maxP = 0;
                    try {
                        maxP = Double.parseDouble(temp);
                    } catch (Exception e) {
                        throw new IllegalArgumentException("TasselPipeline: parseArgs: Problem parsing max P: " + temp);
                    }
                    plugin.setMaxp(maxP);
                } else if (current.equalsIgnoreCase("-glm")) {
                    myDeprecatedWarning.append("parseArgs: NOTE: The -glm flags are deprecated.\n");
                    myDeprecatedWarning.append("parseArgs: PLEASE RUN THIS COMMAND TO GET USAGE: ./run_pipeline.pl -FixedEffectLMPlugin\n");
                    FixedEffectLMPlugin plugin = new FixedEffectLMPlugin(myMainFrame, myIsInteractive);
                    integratePlugin(plugin, true);
                } else if (current.equalsIgnoreCase("-glmOutputFile")) {
                    FixedEffectLMPlugin plugin = (FixedEffectLMPlugin) findLastPluginFromCurrentPipe(new Class[]{FixedEffectLMPlugin.class});
                    if (plugin == null) {
                        throw new IllegalArgumentException("TasselPipeline: parseArgs: No GLM step defined: " + current);
                    }
                    String filename = args[index++].trim();
                    plugin.setOutputFile(filename);
                } else if (current.equalsIgnoreCase("-glmMaxP")) {
                    FixedEffectLMPlugin plugin = (FixedEffectLMPlugin) findLastPluginFromCurrentPipe(new Class[]{FixedEffectLMPlugin.class});
                    if (plugin == null) {
                        throw new IllegalArgumentException("TasselPipeline: parseArgs: No GLM step defined: " + current);
                    }
                    String temp = args[index++].trim();
                    double maxP = 0;
                    try {
                        maxP = Double.parseDouble(temp);
                    } catch (Exception e) {
                        throw new IllegalArgumentException("TasselPipeline: parseArgs: Problem parsing max P: " + temp);
                    }
                    plugin.setMaxP(maxP);
                } else if (current.equalsIgnoreCase("-glmPermutations")) {
                    FixedEffectLMPlugin plugin = (FixedEffectLMPlugin) findLastPluginFromCurrentPipe(new Class[]{FixedEffectLMPlugin.class});
                    if (plugin == null) {
                        throw new IllegalArgumentException("TasselPipeline: parseArgs: No GLM step defined: " + current);
                    }
                    String temp = args[index++].trim();
                    int permutations = 0;
                    try {
                        permutations = Integer.parseInt(temp);
                    } catch (Exception e) {
                        throw new IllegalArgumentException("TasselPipeline: parseArgs: Problem parsing number of permutations: " + temp);
                    }
                    plugin.setPermute(true);
                    plugin.setNumberOfPermutations(permutations);
                } else if (current.equalsIgnoreCase("-td_csv")) {
                    String csvFile = args[index++].trim();
                    getTableDisplayPlugin(csvFile, current);
                } else if (current.equalsIgnoreCase("-td_tab")) {
                    String tabFile = args[index++].trim();
                    getTableDisplayPlugin(tabFile, current);
                } else if (current.equalsIgnoreCase("-td_gui")) {
                    getTableDisplayPlugin(null, current);
                } else if (current.equalsIgnoreCase("-diversity")) {
                    SequenceDiversityPlugin plugin = new SequenceDiversityPlugin(myMainFrame, myIsInteractive);
                    integratePlugin(plugin, true);
                } else if (current.equalsIgnoreCase("-diversityStartBase")) {

                    SequenceDiversityPlugin plugin = null;
                    try {
                        plugin = (SequenceDiversityPlugin) myCurrentPipe.get(myCurrentPipe.size() - 1);
                    } catch (Exception e) {
                        throw new IllegalArgumentException("TasselPipeline: parseArgs: No SequenceDiversityPlugin step defined: " + current);
                    }

                    String str = args[index++].trim();
                    int start = -1;
                    try {
                        start = Integer.parseInt(str);
                    } catch (Exception e) {
                        throw new IllegalArgumentException("TasselPipeline: parseArgs: Problem with Diversity Start Base number: " + str);
                    }
                    if (start < 0) {
                        throw new IllegalArgumentException("TasselPipeline: parseArgs: Diversity Start Base can't be less than 0.");
                    }

                    plugin.setStartSite(start);

                } else if (current.equalsIgnoreCase("-diversityEndBase")) {

                    SequenceDiversityPlugin plugin = null;
                    try {
                        plugin = (SequenceDiversityPlugin) myCurrentPipe.get(myCurrentPipe.size() - 1);
                    } catch (Exception e) {
                        throw new IllegalArgumentException("TasselPipeline: parseArgs: No SequenceDiversityPlugin step defined: " + current);
                    }

                    String str = args[index++].trim();
                    int end = -1;
                    try {
                        end = Integer.parseInt(str);
                    } catch (Exception e) {
                        throw new IllegalArgumentException("TasselPipeline: parseArgs: Problem with Diversity Start Base number: " + str);
                    }

                    plugin.setEndSite(end);

                } else if (current.equalsIgnoreCase("-diversitySlidingWin")) {
                    SequenceDiversityPlugin plugin = null;
                    try {
                        plugin = (SequenceDiversityPlugin) myCurrentPipe.get(myCurrentPipe.size() - 1);
                    } catch (Exception e) {
                        throw new IllegalArgumentException("TasselPipeline: parseArgs: No SequenceDiversityPlugin step defined: " + current);
                    }
                    plugin.setSlidingWindowAnalysis(true);
                } else if (current.equalsIgnoreCase("-diversitySlidingWinStep")) {

                    SequenceDiversityPlugin plugin = null;
                    try {
                        plugin = (SequenceDiversityPlugin) myCurrentPipe.get(myCurrentPipe.size() - 1);
                    } catch (Exception e) {
                        throw new IllegalArgumentException("TasselPipeline: parseArgs: No SequenceDiversityPlugin step defined: " + current);
                    }

                    String str = args[index++].trim();
                    int step = -1;
                    try {
                        step = Integer.parseInt(str);
                    } catch (Exception e) {
                        throw new IllegalArgumentException("TasselPipeline: parseArgs: Problem with Diversity Sliding Win Step number: " + str);
                    }

                    plugin.setStepSize(step);
                    plugin.setSlidingWindowAnalysis(true);

                } else if (current.equalsIgnoreCase("-diversitySlidingWinSize")) {

                    SequenceDiversityPlugin plugin = null;
                    try {
                        plugin = (SequenceDiversityPlugin) myCurrentPipe.get(myCurrentPipe.size() - 1);
                    } catch (Exception e) {
                        throw new IllegalArgumentException("TasselPipeline: parseArgs: No SequenceDiversityPlugin step defined: " + current);
                    }

                    String str = args[index++].trim();
                    int size = -1;
                    try {
                        size = Integer.parseInt(str);
                    } catch (Exception e) {
                        throw new IllegalArgumentException("TasselPipeline: parseArgs: Problem with Diversity Sliding Win Size number: " + str);
                    }

                    plugin.setWindowSize(size);
                    plugin.setSlidingWindowAnalysis(true);

                } else if (current.equalsIgnoreCase("-ld")) {
                    LinkageDisequilibriumPlugin plugin = new LinkageDisequilibriumPlugin(myMainFrame, myIsInteractive);
                    integratePlugin(plugin, true);
                } else if (current.equalsIgnoreCase("-ldPermNum")) {

                    LinkageDisequilibriumPlugin plugin = null;
                    try {
                        plugin = (LinkageDisequilibriumPlugin) myCurrentPipe.get(myCurrentPipe.size() - 1);
                    } catch (Exception e) {
                        throw new IllegalArgumentException("TasselPipeline: parseArgs: No LinkageDisequilibriumPlugin step defined: " + current);
                    }

                    String str = args[index++].trim();
                    int permNum = -1;
                    try {
                        permNum = Integer.parseInt(str);
                    } catch (Exception e) {
                        throw new IllegalArgumentException("TasselPipeline: parseArgs: Problem with LD Permutation number: " + str);
                    }
                    if (permNum < 1) {
                        throw new IllegalArgumentException("TasselPipeline: parseArgs: LD Permutation size can't be less than 1.");
                    }

                    plugin.setPermutationNumber(permNum);

                } else if (current.equalsIgnoreCase("-ldTestSite")) {

                    LinkageDisequilibriumPlugin plugin = null;
                    try {
                        plugin = (LinkageDisequilibriumPlugin) myCurrentPipe.get(myCurrentPipe.size() - 1);
                    } catch (Exception e) {
                        throw new IllegalArgumentException("TasselPipeline: parseArgs: No LinkageDisequilibriumPlugin step defined: " + current);
                    }

                    String str = args[index++].trim();
                    int testSite = -1;
                    try {
                        testSite = Integer.parseInt(str);
                    } catch (Exception e) {
                        throw new IllegalArgumentException("TasselPipeline: parseArgs: Problem with LD Test Site number: " + str);
                    }
                    if (testSite < 0) {
                        throw new IllegalArgumentException("TasselPipeline: parseArgs: LD Test Site can't be less than 0.");
                    }

                    plugin.setTestSite(testSite);

                } else if (current.equalsIgnoreCase("-ldTestSiteName")) {

                    LinkageDisequilibriumPlugin plugin = null;
                    try {
                        plugin = (LinkageDisequilibriumPlugin) myCurrentPipe.get(myCurrentPipe.size() - 1);
                    } catch (Exception e) {
                        throw new IllegalArgumentException("TasselPipeline: parseArgs: No LinkageDisequilibriumPlugin step defined: " + current);
                    }

                    String str = args[index++].trim();
                    plugin.setTestSiteName(str);

                } else if (current.equalsIgnoreCase("-ldWinSize")) {

                    LinkageDisequilibriumPlugin plugin = null;
                    try {
                        plugin = (LinkageDisequilibriumPlugin) myCurrentPipe.get(myCurrentPipe.size() - 1);
                    } catch (Exception e) {
                        throw new IllegalArgumentException("TasselPipeline: parseArgs: No LinkageDisequilibriumPlugin step defined: " + current);
                    }

                    String str = args[index++].trim();
                    int winSize = -1;
                    try {
                        winSize = Integer.parseInt(str);
                    } catch (Exception e) {
                        throw new IllegalArgumentException("TasselPipeline: parseArgs: Problem with LD Window Size: " + str);
                    }
                    if (winSize < 1) {
                        throw new IllegalArgumentException("TasselPipeline: parseArgs: LD Window Size can't be less than 1.");
                    }

                    plugin.setWinSize(winSize);

                } else if (current.equalsIgnoreCase("-ldRapidAnalysis")) {

                    LinkageDisequilibriumPlugin plugin = null;
                    try {
                        plugin = (LinkageDisequilibriumPlugin) myCurrentPipe.get(myCurrentPipe.size() - 1);
                    } catch (Exception e) {
                        throw new IllegalArgumentException("TasselPipeline: parseArgs: No LinkageDisequilibriumPlugin step defined: " + current);
                    }

                    String temp = args[index++].trim();
                    boolean rapid = true;
                    if (temp.equalsIgnoreCase("false")) {
                        rapid = false;
                    } else if (temp.equalsIgnoreCase("true")) {
                        rapid = true;
                    } else {
                        throw new IllegalArgumentException("TasselPipeline: parseArgs: LD Rapid Analysis parameter must be true or false.");
                    }

                    plugin.setRapidAnalysis(rapid);

                } else if (current.equalsIgnoreCase("-ldType")) {

                    LinkageDisequilibriumPlugin plugin = null;
                    try {
                        plugin = (LinkageDisequilibriumPlugin) myCurrentPipe.get(myCurrentPipe.size() - 1);
                    } catch (Exception e) {
                        throw new IllegalArgumentException("TasselPipeline: parseArgs: No LinkageDisequilibriumPlugin step defined: " + current);
                    }

                    String temp = args[index++].trim();
                    if (temp.equalsIgnoreCase("All")) {
                        plugin.setLDType(testDesign.All);
                    } else if (temp.equalsIgnoreCase("SlidingWindow")) {
                        plugin.setLDType(testDesign.SlidingWindow);
                    } else if (temp.equalsIgnoreCase("SiteByAll")) {
                        plugin.setLDType(testDesign.SiteByAll);
                    } else {
                        throw new IllegalArgumentException("TasselPipeline: parseArgs: LD Type parameter must be All, SlidingWindow, or SiteByAll.");
                    }

                } else if (current.equalsIgnoreCase("-ldHetTreatment")) {

                    LinkageDisequilibriumPlugin plugin = null;
                    try {
                        plugin = (LinkageDisequilibriumPlugin) myCurrentPipe.get(myCurrentPipe.size() - 1);
                    } catch (Exception e) {
                        throw new IllegalArgumentException("TasselPipeline: parseArgs: No LinkageDisequilibriumPlugin step defined: " + current);
                    }

                    String temp = args[index++].trim();
                    if (temp.equalsIgnoreCase("Haplotype")) {
                        plugin.setHetTreatment(HetTreatment.Haplotype);
                    } else if (temp.equalsIgnoreCase("Homozygous")) {
                        plugin.setHetTreatment(HetTreatment.Homozygous);
                    } else if (temp.equalsIgnoreCase("Genotype")) {
                        plugin.setHetTreatment(HetTreatment.Genotype);
                    } else {
                        throw new IllegalArgumentException("TasselPipeline: parseArgs: LD Het Treatment parameter must be Haplotype, Homozygous, or Genotype.");
                    }

                } else if (current.equalsIgnoreCase("-ldd")) {
                    String outputType = args[index++].trim();
                    getLinkageDiseqDisplayPlugin(outputType);
                } else if (current.equalsIgnoreCase("-ldplotsize")) {

                    LinkageDiseqDisplayPlugin plugin = null;
                    try {
                        plugin = (LinkageDiseqDisplayPlugin) myCurrentPipe.get(myCurrentPipe.size() - 1);
                    } catch (Exception e) {
                        throw new IllegalArgumentException("TasselPipeline: parseArgs: No LinkageDiseqDisplay step defined: " + current);
                    }

                    String str = args[index++].trim();
                    int plotSize = -1;
                    try {
                        plotSize = Integer.parseInt(str);
                    } catch (Exception e) {
                        throw new IllegalArgumentException("TasselPipeline: parseArgs: Problem with LD Plot size number: " + str);
                    }
                    if (plotSize < 1) {
                        throw new IllegalArgumentException("TasselPipeline: parseArgs: LD Plot size can't be less than 1.");
                    }

                    plugin.setImageSize(plotSize, plotSize);

                } else if (current.equalsIgnoreCase("-ldplotlabels")) {

                    LinkageDiseqDisplayPlugin plugin = null;
                    try {
                        plugin = (LinkageDiseqDisplayPlugin) myCurrentPipe.get(myCurrentPipe.size() - 1);
                    } catch (Exception e) {
                        throw new IllegalArgumentException("TasselPipeline: parseArgs: No LinkageDiseqDisplay step defined: " + current);
                    }

                    String temp = args[index++].trim();
                    boolean ldPlotLabels = true;
                    if (temp.equalsIgnoreCase("false")) {
                        ldPlotLabels = false;
                    } else if (temp.equalsIgnoreCase("true")) {
                        ldPlotLabels = true;
                    } else {
                        throw new IllegalArgumentException("TasselPipeline: parseArgs: LD Plot labels parameter must be true or false.");
                    }

                    plugin.setShowLabels(ldPlotLabels);

                } else if (current.equalsIgnoreCase("-o")) {

                    Plugin plugin = findLastPluginFromCurrentPipe(new Class[]{LinkageDiseqDisplayPlugin.class});

                    String temp = args[index++].trim();

                    if (plugin == null) {
                        throw new IllegalArgumentException("TasselPipeline: parseArgs: No LinkageDiseqDisplay step defined: " + current + " " + temp);
                    } else if (plugin instanceof LinkageDiseqDisplayPlugin) {
                        ((LinkageDiseqDisplayPlugin) plugin).setSaveFile(temp);
                    }

                } else if (current.equalsIgnoreCase("-ck")) {
                    KinshipPlugin plugin = new KinshipPlugin(myMainFrame, myIsInteractive);
                    integratePlugin(plugin, true);
                } else if (current.equalsIgnoreCase("-ckModelHets")) {
                    throw new IllegalArgumentException("TasselPipeline: parseArgs: -ckModelHets not needed in Tassel 5.0. It is designed to handle heterzygotes.");
                } else if (current.equalsIgnoreCase("-ckRescale")) {
                    throw new IllegalArgumentException("TasselPipeline: parseArgs: -ckRescale not needed in Tassel 5.0. It is designed to handle heterzygotes.");
                } else if (current.equalsIgnoreCase("-archaeopteryx")) {
                    ArchaeopteryxPlugin plugin = new ArchaeopteryxPlugin(myMainFrame, myIsInteractive);
                    integratePlugin(plugin, true);
                } else if (current.equalsIgnoreCase("-tree")) {

                    CreateTreePlugin plugin = new CreateTreePlugin(myMainFrame, myIsInteractive);
                    integratePlugin(plugin, true);

                    String temp = args[index++].trim();
                    if (temp.equalsIgnoreCase("Neighbor")) {
                        plugin.clusteringMethod(CreateTreePlugin.CLUSTERING_METHOD.Neighbor_Joining);
                    } else if (temp.equalsIgnoreCase("UPGMA")) {
                        plugin.clusteringMethod(CreateTreePlugin.CLUSTERING_METHOD.UPGMA);
                    } else {
                        throw new IllegalArgumentException("TasselPipeline: parseArgs: tree clustering method must be Neighbor or UPGMA: " + temp);
                    }

                } else if (current.equalsIgnoreCase("-treeSaveDistance")) {

                    CreateTreePlugin plugin = (CreateTreePlugin) findLastPluginFromCurrentPipe(new Class[]{CreateTreePlugin.class});
                    if (plugin == null) {
                        throw new IllegalArgumentException("TasselPipeline: parseArgs: No Create Tree step defined: " + current);
                    }

                    String temp = args[index++].trim();
                    boolean value = true;
                    if (temp.equalsIgnoreCase("false")) {
                        value = false;
                    } else if (temp.equalsIgnoreCase("true")) {
                        value = true;
                    } else {
                        throw new IllegalArgumentException("TasselPipeline: parseArgs: tree save distance matrix parameter must be true or false: " + value);
                    }

                    plugin.saveDistanceMatrix(value);

                } else if (current.equalsIgnoreCase("-gs")) {
                    RidgeRegressionEmmaPlugin plugin = new RidgeRegressionEmmaPlugin(myMainFrame, myIsInteractive);
                    integratePlugin(plugin, true);
                } else if (current.equalsIgnoreCase("-distanceMatrix")) {
                    DistanceMatrixPlugin plugin = new DistanceMatrixPlugin(myMainFrame, myIsInteractive);
                    integratePlugin(plugin, true);
                } else if (current.equalsIgnoreCase("-distMatrixRanges")) {
                    DistanceMatrixRangesPlugin plugin = new DistanceMatrixRangesPlugin(myMainFrame, myIsInteractive);
                    integratePlugin(plugin, true);
                } else if (current.equalsIgnoreCase("-distMatrixRangesLocus")) {
                    DistanceMatrixRangesPlugin plugin = null;
                    try {
                        plugin = (DistanceMatrixRangesPlugin) myCurrentPipe.get(myCurrentPipe.size() - 1);
                    } catch (Exception e) {
                        throw new IllegalArgumentException("TasselPipeline: parseArgs: No DistanceMatrixRangesPlugin step defined: " + current);
                    }

                    String str = args[index++].trim();
                    plugin.setLocus(str);
                } else if (current.equalsIgnoreCase("-distMatrixRangesTaxon")) {
                    DistanceMatrixRangesPlugin plugin = null;
                    try {
                        plugin = (DistanceMatrixRangesPlugin) myCurrentPipe.get(myCurrentPipe.size() - 1);
                    } catch (Exception e) {
                        throw new IllegalArgumentException("TasselPipeline: parseArgs: No DistanceMatrixRangesPlugin step defined: " + current);
                    }

                    String str = args[index++].trim();
                    plugin.setTaxon(str);
                } else if (current.equalsIgnoreCase("-distMatrixRangesPos")) {
                    DistanceMatrixRangesPlugin plugin = null;
                    try {
                        plugin = (DistanceMatrixRangesPlugin) myCurrentPipe.get(myCurrentPipe.size() - 1);
                    } catch (Exception e) {
                        throw new IllegalArgumentException("TasselPipeline: parseArgs: No DistanceMatrixRangesPlugin step defined: " + current);
                    }

                    String[] positions = args[index++].trim().split(",");
                    plugin.setPhysicalPositions(positions);
                } else if (current.equalsIgnoreCase("-distMatrixRangesPosFile")) {
                    DistanceMatrixRangesPlugin plugin = null;
                    try {
                        plugin = (DistanceMatrixRangesPlugin) myCurrentPipe.get(myCurrentPipe.size() - 1);
                    } catch (Exception e) {
                        throw new IllegalArgumentException("TasselPipeline: parseArgs: No DistanceMatrixRangesPlugin step defined: " + current);
                    }
                    String posFile = args[index++].trim();

                    List positions = new ArrayList();
                    BufferedReader br = null;
                    try {
                        br = Utils.getBufferedReader(posFile);
                        String inputline = br.readLine();
                        Pattern sep = Pattern.compile("\\s+");

                        while (inputline != null) {
                            inputline = inputline.trim();
                            String[] parsedline = sep.split(inputline);
                            for (int i = 0; i < parsedline.length; i++) {
                                if ((parsedline[i] != null) || (parsedline[i].length() != 0)) {
                                    positions.add(parsedline[i]);
                                }
                            }
                            inputline = br.readLine();
                        }
                    } finally {
                        br.close();
                    }

                    String[] positionArray = new String[positions.size()];
                    positionArray = (String[]) positions.toArray(positionArray);
                    plugin.setPhysicalPositions(positionArray);
                } else if (current.equalsIgnoreCase("-genotypeSummary")) {
                    GenotypeSummaryPlugin plugin = new GenotypeSummaryPlugin(myMainFrame, myIsInteractive);
                    String temp = args[index++].trim();
                    String[] types = temp.split(",");
                    plugin.overview(false);
                    plugin.siteSummary(false);
                    plugin.taxaSummary(false);
                    for (int i = 0; i < types.length; i++) {
                        if (types[i].equalsIgnoreCase("overall")) {
                            plugin.overview(true);
                        } else if (types[i].equalsIgnoreCase("site")) {
                            plugin.siteSummary(true);
                        } else if (types[i].equalsIgnoreCase("taxa")) {
                            plugin.taxaSummary(true);
                        } else if (types[i].equalsIgnoreCase("all")) {
                            plugin.overview(true);
                            plugin.siteSummary(true);
                            plugin.taxaSummary(true);
                        } else {
                            throw new IllegalArgumentException("TasselPipeline: parseArgs: -genotypeSummary illegal types: " + temp);
                        }
                    }
                    integratePlugin(plugin, true);
                } else if (current.equalsIgnoreCase("-export")) {
                    ExportMultiplePlugin plugin = new ExportMultiplePlugin(myMainFrame);
                    String temp = args[index].trim();
                    if (!temp.startsWith("-")) {
                        String[] filenames = temp.split(",");
                        plugin.setSaveFiles(filenames);
                        index++;
                    }
                    integratePlugin(plugin, false);
                } else if (current.equalsIgnoreCase("-exportType")) {

                    ExportMultiplePlugin plugin = (ExportMultiplePlugin) findLastPluginFromCurrentPipe(new Class[]{ExportMultiplePlugin.class});
                    if (plugin == null) {
                        throw new IllegalArgumentException("TasselPipeline: parseArgs: No Export step defined: " + current);
                    }

                    String type = args[index++].trim();
                    try {
                        plugin.setAlignmentFileType(FileLoadPlugin.TasselFileType.valueOf(type));
                    } catch (Exception e) {
                        try {
                            plugin.setSiteScoreType(SiteScore.SITE_SCORE_TYPE.valueOf(type));
                        } catch (Exception ex) {
                            throw new IllegalArgumentException("TasselPipeline: parseArgs: -exportType: Unknown type: " + type + "  Should be: " + Arrays.toString(FileLoadPlugin.TasselFileType.values()) + " or " + "ReferenceProbablity");
                        }
                    }

                } else if (current.equalsIgnoreCase("-exportIncludeAnno")) {

                    ExportMultiplePlugin plugin = (ExportMultiplePlugin) findLastPluginFromCurrentPipe(new Class[]{ExportMultiplePlugin.class});
                    if (plugin == null) {
                        throw new IllegalArgumentException("TasselPipeline: parseArgs: No Export step defined: " + current);
                    }

                    String temp = args[index++].trim();
                    boolean value = true;
                    if (temp.equalsIgnoreCase("false")) {
                        value = false;
                    } else if (temp.equalsIgnoreCase("true")) {
                        value = true;
                    } else {
                        throw new IllegalArgumentException("TasselPipeline: parseArgs: -exportIncludeAnno must be true or false: " + value);
                    }

                    plugin.setIncludeAnnotations(value);

                } else if (current.equalsIgnoreCase("-filterTaxaNames")) {
                    FilterTaxaAlignmentPlugin plugin = new FilterTaxaAlignmentPlugin(myMainFrame, myIsInteractive);
                    integratePlugin(plugin, true);
                } else if (current.equalsIgnoreCase("-filterAlign")) {
                    FilterAlignmentPlugin plugin = new FilterAlignmentPlugin(myMainFrame, myIsInteractive);
                    integratePlugin(plugin, true);
                } else if (current.equalsIgnoreCase("-filterAlignMinCount")) {
                    FilterAlignmentPlugin plugin = (FilterAlignmentPlugin) findLastPluginFromCurrentPipe(new Class[]{FilterAlignmentPlugin.class});
                    if (plugin == null) {
                        throw new IllegalArgumentException("TasselPipeline: parseArgs: No Filter Alignment step defined: " + current);
                    }
                    String temp = args[index++].trim();
                    int minCount = 0;
                    try {
                        minCount = Integer.parseInt(temp);
                    } catch (Exception e) {
                        throw new IllegalArgumentException("TasselPipeline: parseArgs: Problem parsing filter alignment min count: " + temp);
                    }
                    plugin.setMinCount(minCount);
                } else if (current.equalsIgnoreCase("-filterAlignMinFreq")) {
                    FilterAlignmentPlugin plugin = (FilterAlignmentPlugin) findLastPluginFromCurrentPipe(new Class[]{FilterAlignmentPlugin.class});
                    if (plugin == null) {
                        throw new IllegalArgumentException("TasselPipeline: parseArgs: No Filter Alignment step defined: " + current);
                    }
                    String temp = args[index++].trim();
                    double minFreq = 0;
                    try {
                        minFreq = Double.parseDouble(temp);
                    } catch (Exception e) {
                        throw new IllegalArgumentException("TasselPipeline: parseArgs: Problem parsing filter alignment min frequency: " + temp);
                    }
                    plugin.setMinFreq(minFreq);
                } else if (current.equalsIgnoreCase("-filterAlignMaxFreq")) {
                    FilterAlignmentPlugin plugin = (FilterAlignmentPlugin) findLastPluginFromCurrentPipe(new Class[]{FilterAlignmentPlugin.class});
                    if (plugin == null) {
                        throw new IllegalArgumentException("TasselPipeline: parseArgs: No Filter Alignment step defined: " + current);
                    }
                    String temp = args[index++].trim();
                    double maxFreq = 0;
                    try {
                        maxFreq = Double.parseDouble(temp);
                    } catch (Exception e) {
                        throw new IllegalArgumentException("TasselPipeline: parseArgs: Problem parsing filter alignment max frequency: " + temp);
                    }
                    plugin.setMaxFreq(maxFreq);
                } else if (current.equalsIgnoreCase("-filterAlignStart")) {
                    FilterAlignmentPlugin plugin = (FilterAlignmentPlugin) findLastPluginFromCurrentPipe(new Class[]{FilterAlignmentPlugin.class});
                    if (plugin == null) {
                        throw new IllegalArgumentException("TasselPipeline: parseArgs: No Filter Alignment step defined: " + current);
                    }
                    String temp = args[index++].trim();
                    int start = 0;
                    try {
                        start = Integer.parseInt(temp);
                    } catch (Exception e) {
                        throw new IllegalArgumentException("TasselPipeline: parseArgs: Problem parsing filter alignment start: " + temp);
                    }
                    plugin.setStart(start);
                } else if (current.equalsIgnoreCase("-filterAlignEnd")) {
                    FilterAlignmentPlugin plugin = (FilterAlignmentPlugin) findLastPluginFromCurrentPipe(new Class[]{FilterAlignmentPlugin.class});
                    if (plugin == null) {
                        throw new IllegalArgumentException("TasselPipeline: parseArgs: No Filter Alignment step defined: " + current);
                    }
                    String temp = args[index++].trim();
                    int end = 0;
                    try {
                        end = Integer.parseInt(temp);
                    } catch (Exception e) {
                        throw new IllegalArgumentException("TasselPipeline: parseArgs: Problem parsing filter alignment end: " + temp);
                    }
                    plugin.setEnd(end);
                } else if (current.equalsIgnoreCase("-filterAlignStartPos")) {
                    FilterAlignmentPlugin plugin = (FilterAlignmentPlugin) findLastPluginFromCurrentPipe(new Class[]{FilterAlignmentPlugin.class});
                    if (plugin == null) {
                        throw new IllegalArgumentException("TasselPipeline: parseArgs: No Filter Alignment step defined: " + current);
                    }
                    String temp = args[index++].trim();
                    int startPos = 0;
                    try {
                        startPos = Integer.parseInt(temp);
                    } catch (Exception e) {
                        throw new IllegalArgumentException("TasselPipeline: parseArgs: Problem parsing filter alignment start physical position: " + temp);
                    }
                    plugin.setStartPos(startPos);
                } else if (current.equalsIgnoreCase("-filterAlignEndPos")) {
                    FilterAlignmentPlugin plugin = (FilterAlignmentPlugin) findLastPluginFromCurrentPipe(new Class[]{FilterAlignmentPlugin.class});
                    if (plugin == null) {
                        throw new IllegalArgumentException("TasselPipeline: parseArgs: No Filter Alignment step defined: " + current);
                    }
                    String temp = args[index++].trim();
                    int endPos = 0;
                    try {
                        endPos = Integer.parseInt(temp);
                    } catch (Exception e) {
                        throw new IllegalArgumentException("TasselPipeline: parseArgs: Problem parsing filter alignment end physical position: " + temp);
                    }
                    plugin.setEndPos(endPos);
                } else if (current.equalsIgnoreCase("-filterAlignLocus")) {
                    FilterAlignmentPlugin plugin = (FilterAlignmentPlugin) findLastPluginFromCurrentPipe(new Class[]{FilterAlignmentPlugin.class});
                    if (plugin == null) {
                        throw new IllegalArgumentException("TasselPipeline: parseArgs: No Filter Alignment step defined: " + current);
                    }
                    String temp = args[index++].trim();
                    plugin.setLocusStr(temp);
                } else if (current.equalsIgnoreCase("-filterAlignExtInd")) {
                    FilterAlignmentPlugin plugin = (FilterAlignmentPlugin) findLastPluginFromCurrentPipe(new Class[]{FilterAlignmentPlugin.class});
                    if (plugin == null) {
                        throw new IllegalArgumentException("TasselPipeline: parseArgs: No Filter Alignment step defined: " + current);
                    }
                    plugin.setExtractIndels(true);
                } else if (current.equalsIgnoreCase("-filterAlignRemMinor")) {
                    FilterAlignmentPlugin plugin = (FilterAlignmentPlugin) findLastPluginFromCurrentPipe(new Class[]{FilterAlignmentPlugin.class});
                    if (plugin == null) {
                        throw new IllegalArgumentException("TasselPipeline: parseArgs: No Filter Alignment step defined: " + current);
                    }
                    plugin.setFilterMinorSNPs(true);
                } else if (current.equalsIgnoreCase("-filterAlignSliding")) {
                    FilterAlignmentPlugin plugin = (FilterAlignmentPlugin) findLastPluginFromCurrentPipe(new Class[]{FilterAlignmentPlugin.class});
                    if (plugin == null) {
                        throw new IllegalArgumentException("TasselPipeline: parseArgs: No Filter Alignment step defined: " + current);
                    }
                    plugin.setDoSlidingHaps(true);
                } else if (current.equalsIgnoreCase("-filterAlignHapLen")) {
                    FilterAlignmentPlugin plugin = (FilterAlignmentPlugin) findLastPluginFromCurrentPipe(new Class[]{FilterAlignmentPlugin.class});
                    if (plugin == null) {
                        throw new IllegalArgumentException("TasselPipeline: parseArgs: No Filter Alignment step defined: " + current);
                    }
                    String temp = args[index++].trim();
                    int hapLen = 0;
                    try {
                        hapLen = Integer.parseInt(temp);
                    } catch (Exception e) {
                        throw new IllegalArgumentException("TasselPipeline: parseArgs: Problem parsing filter alignment haplotype length: " + temp);
                    }
                    plugin.setWinSize(hapLen);
                } else if (current.equalsIgnoreCase("-filterAlignStepLen")) {
                    FilterAlignmentPlugin plugin = (FilterAlignmentPlugin) findLastPluginFromCurrentPipe(new Class[]{FilterAlignmentPlugin.class});
                    if (plugin == null) {
                        throw new IllegalArgumentException("TasselPipeline: parseArgs: No Filter Alignment step defined: " + current);
                    }
                    String temp = args[index++].trim();
                    int stepLen = 0;
                    try {
                        stepLen = Integer.parseInt(temp);
                    } catch (Exception e) {
                        throw new IllegalArgumentException("TasselPipeline: parseArgs: Problem parsing filter alignment step length: " + temp);
                    }
                    plugin.setStepSize(stepLen);
                } else if (current.equalsIgnoreCase("-numericalGenoTransform")) {
                    myLogger.warn("parseArgs: PLEASE USE NumericalGenotypePlugin.\n");
                    System.exit(1);
                } else if (current.equalsIgnoreCase("-includeTaxa")) {
                    FilterTaxaAlignmentPlugin plugin = new FilterTaxaAlignmentPlugin(myMainFrame, myIsInteractive);
                    String[] taxa = args[index++].trim().split(",");
                    Taxon[] ids = new Taxon[taxa.length];
                    for (int i = 0; i < taxa.length; i++) {
                        ids[i] = new Taxon(taxa[i]);
                    }
                    plugin.setIdsToKeep(new TaxaListBuilder().addAll(ids).build());
                    integratePlugin(plugin, true);
                } else if (current.equalsIgnoreCase("-includeTaxaInFile")) {
                    FilterTaxaAlignmentPlugin plugin = new FilterTaxaAlignmentPlugin(myMainFrame, myIsInteractive);
                    String taxaListFile = args[index++].trim();

                    List taxa = new ArrayList();
                    BufferedReader br = null;
                    try {
                        br = Utils.getBufferedReader(taxaListFile);
                        String inputline = br.readLine();
                        Pattern sep = Pattern.compile("\\s+");

                        while (inputline != null) {
                            inputline = inputline.trim();
                            String[] parsedline = sep.split(inputline);
                            for (int i = 0; i < parsedline.length; i++) {
                                if ((parsedline[i] != null) || (parsedline[i].length() != 0)) {
                                    taxa.add(parsedline[i]);
                                }
                            }
                            inputline = br.readLine();
                        }
                    } finally {
                        br.close();
                    }

                    Taxon[] ids = new Taxon[taxa.size()];
                    for (int i = 0; i < taxa.size(); i++) {
                        ids[i] = new Taxon((String) taxa.get(i));
                    }
                    plugin.setIdsToKeep(new TaxaListBuilder().addAll(ids).build());
                    integratePlugin(plugin, true);
                } else if (current.equalsIgnoreCase("-excludeTaxa")) {
                    FilterTaxaAlignmentPlugin plugin = new FilterTaxaAlignmentPlugin(myMainFrame, myIsInteractive);
                    String[] taxa = args[index++].trim().split(",");
                    Taxon[] ids = new Taxon[taxa.length];
                    for (int i = 0; i < taxa.length; i++) {
                        ids[i] = new Taxon(taxa[i]);
                    }
                    plugin.setIdsToRemove(new TaxaListBuilder().addAll(ids).build());
                    integratePlugin(plugin, true);
                } else if (current.equalsIgnoreCase("-excludeTaxaInFile")) {
                    FilterTaxaAlignmentPlugin plugin = new FilterTaxaAlignmentPlugin(myMainFrame, myIsInteractive);
                    String taxaListFile = args[index++].trim();

                    List taxa = new ArrayList();
                    BufferedReader br = null;
                    try {
                        br = Utils.getBufferedReader(taxaListFile);
                        String inputline = br.readLine();
                        Pattern sep = Pattern.compile("\\s+");

                        while (inputline != null) {
                            inputline = inputline.trim();
                            String[] parsedline = sep.split(inputline);
                            for (int i = 0; i < parsedline.length; i++) {
                                if ((parsedline[i] != null) || (parsedline[i].length() != 0)) {
                                    taxa.add(parsedline[i]);
                                }
                            }
                            inputline = br.readLine();
                        }
                    } finally {
                        br.close();
                    }

                    Taxon[] ids = new Taxon[taxa.size()];
                    for (int i = 0; i < taxa.size(); i++) {
                        ids[i] = new Taxon((String) taxa.get(i));
                    }
                    plugin.setIdsToRemove(new TaxaListBuilder().addAll(ids).build());
                    integratePlugin(plugin, true);
                } else if (current.equalsIgnoreCase("-includeSiteNames")) {
                    FilterSiteNamePlugin plugin = new FilterSiteNamePlugin(myMainFrame, myIsInteractive);
                    String[] names = args[index++].trim().split(",");
                    plugin.setSiteNamesToKeep(names);
                    integratePlugin(plugin, true);
                } else if (current.equalsIgnoreCase("-includeSiteNamesInFile")) {
                    FilterSiteNamePlugin plugin = new FilterSiteNamePlugin(myMainFrame, myIsInteractive);
                    String siteNameListFile = args[index++].trim();

                    List siteNames = new ArrayList();
                    BufferedReader br = null;
                    try {
                        br = Utils.getBufferedReader(siteNameListFile);
                        String inputline = br.readLine();
                        Pattern sep = Pattern.compile("\\s+");

                        while (inputline != null) {
                            inputline = inputline.trim();
                            String[] parsedline = sep.split(inputline);
                            for (int i = 0; i < parsedline.length; i++) {
                                if ((parsedline[i] != null) || (parsedline[i].length() != 0)) {
                                    siteNames.add(parsedline[i]);
                                }
                            }
                            inputline = br.readLine();
                        }
                    } finally {
                        br.close();
                    }

                    String[] siteNameArray = new String[siteNames.size()];
                    siteNameArray = (String[]) siteNames.toArray(siteNameArray);
                    plugin.setSiteNamesToKeep(siteNameArray);
                    integratePlugin(plugin, true);
                } else if (current.equalsIgnoreCase("-excludeSiteNames")) {
                    FilterSiteNamePlugin plugin = new FilterSiteNamePlugin(myMainFrame, myIsInteractive);
                    String[] sites = args[index++].trim().split(",");
                    plugin.setSiteNamesToRemove(sites);
                    integratePlugin(plugin, true);
                } else if (current.equalsIgnoreCase("-excludeSiteNamesInFile")) {
                    FilterSiteNamePlugin plugin = new FilterSiteNamePlugin(myMainFrame, myIsInteractive);
                    String siteNameListFile = args[index++].trim();

                    List siteNames = new ArrayList();
                    BufferedReader br = null;
                    try {
                        br = Utils.getBufferedReader(siteNameListFile);
                        String inputline = br.readLine();
                        Pattern sep = Pattern.compile("\\s+");

                        while (inputline != null) {
                            inputline = inputline.trim();
                            String[] parsedline = sep.split(inputline);
                            for (int i = 0; i < parsedline.length; i++) {
                                if ((parsedline[i] != null) || (parsedline[i].length() != 0)) {
                                    siteNames.add(parsedline[i]);
                                }
                            }
                            inputline = br.readLine();
                        }
                    } finally {
                        br.close();
                    }

                    String[] names = new String[siteNames.size()];
                    for (int i = 0; i < siteNames.size(); i++) {
                        names[i] = (String) siteNames.get(i);
                    }
                    plugin.setSiteNamesToRemove(names);
                    integratePlugin(plugin, true);
                } else if (current.equalsIgnoreCase("-subsetSites")) {
                    FilterSubsetPlugin plugin = new FilterSubsetPlugin(myMainFrame, myIsInteractive);
                    try {
                        double siteVal = Double.parseDouble(args[index++].trim());
                        plugin.setSiteSubset(siteVal);
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("TasselPipeline: parseArgs: Value following -subsetSites must be a number (decimal or integer)");
                    } catch (Exception e) {
                        // do nothing
                    }
                    if (args[index].equalsIgnoreCase("-step")) {
                        plugin.setIsRandom(false);
                        index++;
                    }
                    integratePlugin(plugin, true);
                } else if (current.equalsIgnoreCase("-subsetTaxa")) {
                    FilterSubsetPlugin plugin = new FilterSubsetPlugin(myMainFrame, myIsInteractive);
                    try {
                        double taxaVal = Double.parseDouble(args[index++].trim());
                        plugin.setTaxaSubset(taxaVal);
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("TasselPipeline: parseArgs: Value following -subsetTaxa must be a number (decimal or integer)");
                    } catch (Exception e) {
                        // do nothing
                    }
                    if (args[index].equalsIgnoreCase("-step")) {
                        plugin.setIsRandom(false);
                        index++;
                    }
                    integratePlugin(plugin, true);
                } else if (current.equalsIgnoreCase("-newCoordinates")) {
                    ConvertAlignmentCoordinatesPlugin plugin = new ConvertAlignmentCoordinatesPlugin(myMainFrame);
                    String mapFile = args[index++].trim();
                    plugin.setMapFilename(mapFile);
                    integratePlugin(plugin, true);
                } else {

                    try {
                        Plugin plugin = null;
                        String possibleClassName = current.substring(1);
                        List<String> matches = Utils.getFullyQualifiedClassNames(possibleClassName);
                        for (String match : matches) {
                            plugin = Plugin.getPluginInstance(match, myMainFrame, myIsInteractive);
                            if (plugin != null) {
                                break;
                            }
                        }

                        if (plugin == null) {
                            plugin = Plugin.getPluginInstance(possibleClassName, myMainFrame, myIsInteractive);
                        }

                        if (plugin != null) {
                            List<String> pluginArgs = new ArrayList<>();
                            if (index == args.length) {
                                integratePlugin(plugin, true);
                            }
                            String temp = args[index++].trim();
                            while (!temp.equalsIgnoreCase("-endPlugin")) {
                                if (temp.startsWith("-runfork")) {
                                    index--;
                                    break;
                                }
                                pluginArgs.add(temp);
                                if (index == args.length) {
                                    integratePlugin(plugin, true);
                                    myLogger.warn("TasselPipeline: parseArgs: No -endPlugin flag specified.");
                                }
                                temp = args[index++].trim();
                            }

                            String[] result = new String[pluginArgs.size()];
                            result = (String[]) pluginArgs.toArray(result);

                            try {
                                plugin.setParameters(result);
                            } catch (Exception e) {
                                e.printStackTrace();
                                // Self-describing Plugin Should already output Usage and any other error information.
                                ExceptionUtils.logExceptionCauses(e, myLogger, Level.ERROR);
                                System.exit(1);
                            }
                            integratePlugin(plugin, true);
                        } else {
                            throw new IllegalArgumentException("TasselPipeline: parseArgs: Unknown parameter: " + current);
                        }

                    } catch (UnsupportedOperationException usoe) {
                        throw new IllegalArgumentException("TasselPipeline: parseArgs: this plugin is not self-described: " + current);
                    } catch (Exception e) {
                        ExceptionUtils.logExceptionCauses(e, myLogger, Level.ERROR);
                        throw new IllegalArgumentException("TasselPipeline: parseArgs: Unknown parameter: " + current);
                    }

                }
            } catch (Exception e) {
                e.printStackTrace();
                System.exit(1);
            }

        }

        if (myFirstPlugin != null) {
            //((AbstractPlugin) myFirstPlugin).trace(0);
            tracePipeline();
        } else {
            myLogger.warn("parseArgs: no arguments specified.");
        }

    }

    public static String[] addForkFlagsIfNeeded(String[] args) {
        if ((args == null) || (args.length == 0)) {
            return args;
        }

        for (String a : args) {
            if (a.toLowerCase().startsWith("-fork") || a.toLowerCase().startsWith("-runfork")) {
                // If forks included, return arguments unchanged
                return args;
            }
        }

        // If no arguments have "-fork" or "-runfork", add them
        String[] newArgs = new String[args.length + 2];
        newArgs[0] = "-fork1";
        newArgs[newArgs.length - 1] = "-runfork1";
        System.arraycopy(args, 0, newArgs, 1, args.length);
        return newArgs;
    }

    private void tracePipeline() {

        for (ThreadedPluginListener thread : myThreads) {
            Plugin current = (Plugin) thread.getPluginListener();
            ((AbstractPlugin) current).trace(0);
        }

    }

    public FileLoadPlugin loadFile(String filename, FileLoadPlugin.TasselFileType fileType) {

        FileLoadPlugin plugin = new FileLoadPlugin(myMainFrame, myIsInteractive);
        if (fileType == null) {
            plugin.setTheFileType(FileLoadPlugin.TasselFileType.Unknown);
        } else {
            plugin.setTheFileType(fileType);
        }

        plugin.setOpenFiles(new String[]{filename});

        integratePlugin(plugin, true);

        return plugin;

    }

    public TableDisplayPlugin getTableDisplayPlugin(String filename, String flag) {

        TableDisplayPlugin plugin = null;

        if (flag.equalsIgnoreCase("-td_gui")) {
            plugin = new TableDisplayPlugin(myMainFrame, true);
            integratePlugin(plugin, false);
        } else if (flag.equalsIgnoreCase("-td_tab")) {
            filename = Utils.addSuffixIfNeeded(filename, ".txt");
            myLogger.info("getTableDisplayPlugin: " + filename);
            plugin = new TableDisplayPlugin(myMainFrame, myIsInteractive);
            plugin.setDelimiter("\t");
            plugin.setSaveFile(filename);
            integratePlugin(plugin, false);
        } else if (flag.equalsIgnoreCase("-td_csv")) {
            filename = Utils.addSuffixIfNeeded(filename, ".csv");
            myLogger.info("getTableDisplayPlugin: " + filename);
            plugin = new TableDisplayPlugin(myMainFrame, myIsInteractive);
            plugin.setDelimiter(",");
            plugin.setSaveFile(filename);
            integratePlugin(plugin, false);
        }

        return plugin;

    }

    public LinkageDiseqDisplayPlugin getLinkageDiseqDisplayPlugin(String type) {

        LinkageDiseqDisplayPlugin plugin = new LinkageDiseqDisplayPlugin(myMainFrame, true);

        if (type.equalsIgnoreCase("gui")) {
            plugin = new LinkageDiseqDisplayPlugin(null, true);
            plugin.setBlockSchematic(false);
            plugin.setLowerCorner(LinkageDisequilibriumComponent.P_VALUE);
            plugin.setUpperCorner(LinkageDisequilibriumComponent.RSQUARE);
        } else {
            plugin = new LinkageDiseqDisplayPlugin(null, false);
            plugin.setBlockSchematic(false);
            plugin.setLowerCorner(LinkageDisequilibriumComponent.P_VALUE);
            plugin.setUpperCorner(LinkageDisequilibriumComponent.RSQUARE);

            if (type.equalsIgnoreCase("png")) {
                plugin.setOutformat(AbstractDisplayPlugin.Outformat.png);
            } else if (type.equalsIgnoreCase("gif")) {
                plugin.setOutformat(AbstractDisplayPlugin.Outformat.gif);
            } else if (type.equalsIgnoreCase("bmp")) {
                plugin.setOutformat(AbstractDisplayPlugin.Outformat.bmp);
            } else if (type.equalsIgnoreCase("jpg")) {
                plugin.setOutformat(AbstractDisplayPlugin.Outformat.jpg);
            } else if (type.equalsIgnoreCase("svg")) {
                plugin.setOutformat(AbstractDisplayPlugin.Outformat.svg);
            } else {
                throw new IllegalArgumentException("TasselPipeline: getLinkageDiseqDisplayPlugin: unknown output type: " + type);
            }
        }

        integratePlugin(plugin, false);

        return plugin;

    }

    private void integratePlugin(Plugin plugin, boolean displayDataTree) {

        if (myFirstPlugin == null) {
            myFirstPlugin = plugin;
        }

        if (displayDataTree) {
            plugin.addListener(this);
        }

        if (myCurrentPipe == null) {
            myCurrentPipe = new ArrayList<>();

        }

        if (myCurrentPipe.isEmpty()) {
            myCurrentPipe.add(plugin);
        } else {
            plugin.receiveInput(myCurrentPipe.get(myCurrentPipe.size() - 1));
            myCurrentPipe.add(plugin);
        }

        if (myIsInteractive) {
            myStepsDialog.addPlugin(plugin, myDescriptions[myCurrentDescriptionIndex]);
        }

    }

    private Plugin findLastPluginFromAll(Class[] types) {

        if ((myCurrentPipe != null) && (myCurrentPipe.size() != 0)) {
            for (int i = myCurrentPipe.size() - 1; i >= 0; i--) {
                Plugin current = (Plugin) myCurrentPipe.get(i);
                if (matchType(types, current)) {
                    return current;
                }

            }
        }

        List keys = new ArrayList(myForks.keySet());
        for (int i = keys.size() - 1; i >= 0; i--) {
            List currentPipe = (List) myForks.get(keys.get(i));
            for (int j = currentPipe.size() - 1; j >= 0; j--) {
                Plugin current = (Plugin) currentPipe.get(j);
                if (matchType(types, current)) {
                    return current;
                }

            }
        }

        return null;

    }

    private Plugin findLastPluginFromCurrentPipe(Class[] types) {

        if ((myCurrentPipe != null) && (myCurrentPipe.size() != 0)) {
            for (int i = myCurrentPipe.size() - 1; i >= 0; i--) {
                Plugin current = (Plugin) myCurrentPipe.get(i);
                if (matchType(types, current)) {
                    return current;
                }

            }
        }

        return null;

    }

    private boolean matchType(Class[] types, Object test) {

        for (int i = 0; i < types.length; i++) {
            if (types[i].isInstance(test)) {
                return true;
            }

        }

        return false;

    }

    /**
     * Returns Tassel data set after complete.
     *
     * @param event event
     */
    @Override
    public void dataSetReturned(PluginEvent event) {
        DataSet tds = (DataSet) event.getSource();
        if ((tds != null) && (tds.getSize() != 0) && (myMainFrame != null)) {
            myMainFrame.getDataTreePanel().addDataSet(tds, DataTreePanel.NODE_TYPE_DEFAULT);
            myMainFrame.getDataTreePanel().setSelectionPath(tds.getData(0));
        }

    }

    /**
     * Returns progress of execution.
     *
     * @param event event
     */
    @Override
    public void progress(PluginEvent event) {

        if (myMainFrame == null) {

            DataSet ds = (DataSet) event.getSource();
            if (ds != null) {
                List percentage = ds.getDataOfType(Integer.class);
                Plugin plugin = ds.getCreator();
                Integer lastValue = myProgressValues.get(plugin);
                if (lastValue == null) {
                    lastValue = 0;
                }

                if (percentage.size() > 0) {
                    Datum datum = (Datum) percentage.get(0);
                    Integer percent = (Integer) datum.getData();
                    if (percent >= lastValue) {
                        LocalDateTime time = LocalDateTime.now();
                        String timeStr = time.format(DateTimeFormatter.ofPattern("MMM d, uuuu H:mm:s"));
                        myLogger.info(ds.getCreator().getClass().getName() + ": time: " + timeStr + ": progress: " + percent + "%");
                        lastValue = lastValue + 10;
                        myProgressValues.put(plugin, lastValue);
                    }
                }
            }

        }

    }
}
