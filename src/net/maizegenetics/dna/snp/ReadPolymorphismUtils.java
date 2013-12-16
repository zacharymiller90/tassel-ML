package net.maizegenetics.dna.snp;

import net.maizegenetics.dna.snp.genotypecall.GenotypeCallTable;
import net.maizegenetics.dna.snp.genotypecall.GenotypeCallTableBuilder;
import net.maizegenetics.taxa.TaxaList;
import net.maizegenetics.taxa.TaxaListBuilder;
import net.maizegenetics.util.Utils;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.regex.Pattern;

/**
 * Methods for reading non-standard file format.
 * @deprecated
 */
@Deprecated
public class ReadPolymorphismUtils {

    private static Pattern WHITESPACE = Pattern.compile("\\s+");

    //prevents instantiation
    private ReadPolymorphismUtils() {
    }

    public static GenotypeTable readPolymorphismFile(String inFile) throws IOException {
        BufferedReader br = Utils.getBufferedReader(inFile);
        String inline = br.readLine();
        String[] markerNames = null;
        ArrayList<String[]> dataList = new ArrayList<String[]>();
        int nTaxa = 0;
        int nMarkers = 0;

        if (!inline.startsWith("#") && !inline.startsWith("<")) {
            String[] data = inline.split("[:\\s]+");
            nTaxa = Integer.parseInt(data[0]);
            nMarkers = Integer.parseInt(data[1]);
            markerNames = WHITESPACE.split(br.readLine());
            if (markerNames.length != nMarkers) {
                throw new IllegalStateException("ReadPolymorphismUtils: readPolymorphismFile: Number markers: " + markerNames.length + " doesn't match declaration: " + nMarkers);
            }
            for (int i = 0; i < nTaxa; i++) {
                inline = br.readLine();
                data = WHITESPACE.split(inline);
                dataList.add(data);
            }
        } else {
            while (inline != null) {
                if (!inline.startsWith("#")) {
                    if (inline.startsWith("<")) {
                        String[] data = inline.split("[<>\\s]+");
                        if (data[1].toLowerCase().startsWith("marker")) {
                            nMarkers = data.length - 2;
                            markerNames = new String[nMarkers];
                            System.arraycopy(data, 2, markerNames, 0, nMarkers);
                        }
                    } else {
                        dataList.add(WHITESPACE.split(inline));
                    }
                }
                inline = br.readLine();
            }
            nTaxa = dataList.size();
        }

        String[][] finalData = new String[nTaxa][nMarkers];
        String[] taxa = new String[nTaxa];
        for (int t = 0; t < nTaxa; t++) {
            String[] taxonData = dataList.get(t);
            taxa[t] = taxonData[0];
            for (int s = 0; s < nMarkers; s++) {
                if (taxonData[s + 1].equals("?")) {
                    finalData[t][s] = GenotypeTable.UNKNOWN_ALLELE_STR;
                } else if (taxonData[s + 1].equals("?:?")) {
                    finalData[t][s] = GenotypeTable.UNKNOWN_DIPLOID_ALLELE_STR;
                } else {
                    finalData[t][s] = taxonData[s + 1];
                }
            }
        }

        GenotypeCallTable genotype = GenotypeCallTableBuilder.getUnphasedNucleotideGenotypeBuilder(nTaxa, nMarkers)
                .setBases(finalData)
                .build();

        TaxaList taxaList = new TaxaListBuilder().addAll(taxa).build();

        return GenotypeTableBuilder.getInstance(genotype, null, taxaList);

    }

    public static GeneticMap readGeneticMapFile(String filename) throws IOException {
        BufferedReader br = new BufferedReader(new FileReader(filename));
        String inline = br.readLine();
        while (inline != null && inline.startsWith("#")) {
            inline = br.readLine();
        }
        if (!inline.trim().toLowerCase().equals("<map>")) {
            br.close();
            throw new IOException("Expected <Map> as first line of file.");
        }

        GeneticMap theMap = new GeneticMap("Genetic Map: " + filename);

        while (inline != null) {
            if (!inline.startsWith("#")) {
                theMap.addMarker(WHITESPACE.split(inline));
            }
            inline = br.readLine();
        }
        br.close();
        return theMap;
    }
}