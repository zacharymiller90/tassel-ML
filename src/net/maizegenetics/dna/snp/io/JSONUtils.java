/*
 *  JSONUtils
 * 
 *  Created on Mar 6, 2015
 */
package net.maizegenetics.dna.snp.io;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonNumber;
import javax.json.JsonObject;
import javax.json.JsonReader;
import javax.json.JsonString;
import javax.json.stream.JsonGenerator;
import javax.json.stream.JsonGeneratorFactory;
import net.maizegenetics.dna.map.Chromosome;
import net.maizegenetics.dna.map.GeneralPosition;
import net.maizegenetics.dna.map.Position;
import net.maizegenetics.dna.map.PositionList;
import net.maizegenetics.dna.map.PositionListBuilder;
import net.maizegenetics.taxa.TaxaList;
import net.maizegenetics.taxa.TaxaListBuilder;
import net.maizegenetics.taxa.Taxon;
import net.maizegenetics.util.GeneralAnnotation;
import net.maizegenetics.util.GeneralAnnotationStorage;
import net.maizegenetics.util.Utils;
import org.apache.log4j.Logger;

/**
 *
 * @author Terry Casstevens
 */
public class JSONUtils {

    private static final Logger myLogger = Logger.getLogger(JSONUtils.class);

    private JSONUtils() {
        // utility
    }

    private static void taxaListToJSON(TaxaList taxa, JsonGenerator generator) {
        generator.writeStartArray("TaxaList");
        taxa.stream().forEach((current) -> {
            taxaToJSON(current, generator);
        });
        generator.writeEnd();
    }

    private static void taxaToJSON(Taxon taxon, JsonGenerator generator) {
        generator.writeStartObject();
        generator.write("name", taxon.getName());
        generalAnnotationToJSON(taxon.getAnnotation(), generator);
        generator.writeEnd();
    }

    private static void generalAnnotationToJSON(GeneralAnnotation annotation, JsonGenerator generator) {
        if (annotation == null) {
            return;
        }
        Set<String> keys = annotation.getAnnotationKeys();
        if (keys.isEmpty()) {
            return;
        }
        generator.writeStartObject("anno");
        keys.stream().forEach((key) -> {
            String[] values = annotation.getTextAnnotation(key);
            if (values.length == 1) {
                generator.write(key, values[0]);
            } else {
                generator.writeStartArray(key);
                for (String current : values) {
                    generator.write(current);
                }
                generator.writeEnd();
            }
        });
        generator.writeEnd();
    }

    /**
     * Exports given taxa list to JSON file.
     *
     * @param taxa taxa list
     * @param filename file name (adds .json if needed)
     *
     * @return final filename
     */
    public static String exportTaxaListToJSON(TaxaList taxa, String filename) {
        filename = Utils.addGzSuffixIfNeeded(filename, ".json");
        try (BufferedWriter writer = Utils.getBufferedWriter(filename)) {
            Map<String, Object> properties = new HashMap<>(1);
            properties.put(JsonGenerator.PRETTY_PRINTING, true);
            JsonGeneratorFactory factory = Json.createGeneratorFactory(properties);
            try (JsonGenerator generator = factory.createGenerator(writer)) {
                generator.writeStartObject();
                taxaListToJSON(taxa, generator);
                generator.writeEnd();
            }
            return filename;
        } catch (Exception e) {
            myLogger.debug(e.getMessage(), e);
            throw new IllegalStateException("JSONUtils: exportTaxaListToJSON: problem saving file: " + filename + "\n" + e.getMessage());
        }
    }

    /**
     * Imports taxa list from JSON file.
     *
     * @param filename filename
     *
     * @return taxa list
     */
    public static TaxaList importTaxaListFromJSON(String filename) {
        try (BufferedReader reader = Utils.getBufferedReader(filename)) {
            try (JsonReader jsonReader = Json.createReader(reader)) {
                JsonObject obj = jsonReader.readObject();
                JsonArray taxaList = obj.getJsonArray("TaxaList");
                if (taxaList == null) {
                    throw new IllegalArgumentException("JSONUtils: importTaxaListFromJSON: There is no TaxaList in this file: " + filename);
                }
                return taxaListFromJSON(taxaList);
            }
        } catch (Exception e) {
            myLogger.debug(e.getMessage(), e);
            throw new IllegalStateException("JSONUtils: importTaxaListFromJSON: problem reading file: " + filename + "\n" + e.getMessage());
        }
    }

    private static TaxaList taxaListFromJSON(JsonArray taxaList) {
        if (taxaList.isEmpty()) {
            return null;
        }
        TaxaListBuilder builder = new TaxaListBuilder();
        taxaList.forEach((current) -> {
            builder.add(taxonFromJSON((JsonObject) current));
        });
        return builder.build();
    }

    private static Taxon taxonFromJSON(JsonObject json) {
        String name = json.getString("name");
        if (name == null) {
            throw new IllegalStateException("JSONUtils: taxonFromJSON: All Taxa must have a name.");
        }

        JsonObject anno = json.getJsonObject("anno");
        if (anno != null) {
            return new Taxon(name, generalAnnotationFromJSON(anno));
        } else {
            return new Taxon(name);
        }
    }

    private static GeneralAnnotationStorage.Builder generalAnnotationBuilderFromJSON(JsonObject json) {
        GeneralAnnotationStorage.Builder builder = GeneralAnnotationStorage.getBuilder();
        if (json != null) {
            json.forEach((key, value) -> {
                if (value instanceof JsonArray) {
                    JsonArray jsonArray = (JsonArray) value;
                    jsonArray.forEach((str) -> {
                        builder.addAnnotation(key, ((JsonString) str).getString());
                    });
                } else if (value instanceof JsonString) {
                    builder.addAnnotation(key, ((JsonString) value).getString());
                } else {
                    throw new IllegalArgumentException("JSONUtils: generalAnnotationBuilderFromJSON: unknown value type: " + value.getClass().getName());
                }
            });
        }
        return builder;
    }

    private static GeneralAnnotation generalAnnotationFromJSON(JsonObject json) {
        if (json == null) {
            return GeneralAnnotationStorage.EMPTY_ANNOTATION_STORAGE;
        }
        GeneralAnnotationStorage.Builder builder = generalAnnotationBuilderFromJSON(json);
        return builder.build();
    }

    public static String exportPositionListToJSON(PositionList positions, String filename) {
        filename = Utils.addGzSuffixIfNeeded(filename, ".json");
        try (BufferedWriter writer = Utils.getBufferedWriter(filename)) {
            Map<String, Object> properties = new HashMap<>(1);
            properties.put(JsonGenerator.PRETTY_PRINTING, true);
            JsonGeneratorFactory factory = Json.createGeneratorFactory(properties);
            try (JsonGenerator generator = factory.createGenerator(writer)) {
                generator.writeStartObject();
                positionListToJSON(positions, generator);
                generator.writeEnd();
            }
            return filename;
        } catch (Exception e) {
            myLogger.debug(e.getMessage(), e);
            throw new IllegalStateException("JSONUtils: exportTaxaListToJSON: problem saving file: " + filename + "\n" + e.getMessage());
        }
    }

    private static void positionListToJSON(PositionList positions, JsonGenerator generator) {
        generator.writeStartArray("PositionList");
        positions.stream().forEach((current) -> {
            positionToJSON(current, generator);
        });
        generator.writeEnd();
    }

    private static void positionToJSON(Position position, JsonGenerator generator) {
        generator.writeStartObject();

        String snpID = position.getSNPID();
        if (snpID != null) {
            generator.write("SNPID", snpID);
        }

        chromosomeToJSON(position.getChromosome(), generator);
        generator.write("position", position.getPosition());

        float cm = position.getCM();
        if (!Float.isNaN(cm)) {
            generator.write("CM", cm);
        }

        float globalMAF = position.getGlobalMAF();
        if (!Float.isNaN(globalMAF)) {
            generator.write("globalMAF", globalMAF);
        }

        float globalSiteCoverage = position.getGlobalSiteCoverage();
        if (!Float.isNaN(globalSiteCoverage)) {
            generator.write("globalSiteCoverage", globalSiteCoverage);
        }

        generator.write("strand", Position.getStrand(position.getStrand()));

        generalAnnotationToJSON(position.getAnnotation(), generator);

        generator.writeEnd();
    }

    private static void chromosomeToJSON(Chromosome chromosome, JsonGenerator generator) {
        if (chromosome == null) {
            return;
        }
        generator.writeStartObject("chr");
        generator.write("name", chromosome.getName());
        int length = chromosome.getLength();
        if (length != -1) {
            generator.write("length", length);
        }
        generalAnnotationToJSON(chromosome.getAnnotation(), generator);
        generator.writeEnd();
    }

    public static PositionList importPositionListFromJSON(String filename) {
        try (BufferedReader reader = Utils.getBufferedReader(filename)) {
            try (JsonReader jsonReader = Json.createReader(reader)) {
                JsonObject obj = jsonReader.readObject();
                JsonArray positionList = obj.getJsonArray("PositionList");
                if (positionList == null) {
                    throw new IllegalArgumentException("JSONUtils: importPositionListFromJSON: There is no TaxaList in this file: " + filename);
                }
                return positionListFromJSON(positionList);
            }
        } catch (Exception e) {
            myLogger.debug(e.getMessage(), e);
            throw new IllegalStateException("JSONUtils: importPositionListFromJSON: problem reading file: " + filename + "\n" + e.getMessage());
        }
    }

    private static PositionList positionListFromJSON(JsonArray positionList) {
        if (positionList.isEmpty()) {
            return null;
        }
        PositionListBuilder builder = new PositionListBuilder();
        positionList.forEach((current) -> {
            builder.add(positionFromJSON((JsonObject) current));
        });
        return builder.build();
    }

    private static Position positionFromJSON(JsonObject json) {
        JsonObject chrObj = json.getJsonObject("chr");
        Chromosome chr = chromosomeFromJSON(chrObj);

        int position = json.getInt("position");

        GeneralPosition.Builder builder;
        JsonObject anno = json.getJsonObject("anno");
        if (anno != null) {
            builder = new GeneralPosition.Builder(chr, position);
        } else {
            builder = new GeneralPosition.Builder(chr, position, generalAnnotationBuilderFromJSON(anno));
        }

        JsonString snpID = json.getJsonString("SNPID");
        if (snpID != null) {
            builder.snpName(snpID.getString());
        }

        JsonNumber cm = json.getJsonNumber("CM");
        if (cm != null) {
            try {
                builder.cM(Float.parseFloat(cm.toString()));
            } catch (NumberFormatException nex) {
                throw new IllegalStateException("JSONUtils: positionFromJSON: illegal CM value: " + cm);
            }
        }

        JsonNumber globalMAF = json.getJsonNumber("globalMAF");
        if (globalMAF != null) {
            try {
                builder.maf(Float.parseFloat(globalMAF.toString()));
            } catch (NumberFormatException nex) {
                throw new IllegalStateException("JSONUtils: positionFromJSON: illegal globalMAF value: " + globalMAF);
            }
        }

        JsonNumber globalSiteCoverage = json.getJsonNumber("globalSiteCoverage");
        if (globalSiteCoverage != null) {
            try {
                builder.siteCoverage(Float.parseFloat(globalSiteCoverage.toString()));
            } catch (NumberFormatException nex) {
                throw new IllegalStateException("JSONUtils: positionFromJSON: illegal globalSiteCoverage value: " + globalSiteCoverage);
            }
        }

        String strand = json.getString("strand");
        if (strand != null) {
            try {
                builder.strand(strand);
            } catch (Exception e) {
                throw new IllegalStateException("JSONUtils: positionFromJSON: illegal strand value: " + strand);
            }
        }

        return builder.build();
    }

    private static Chromosome chromosomeFromJSON(JsonObject json) {
        if (json == null) {
            return Chromosome.UNKNOWN;
        }

        String name = json.getString("name");
        if (name == null) {
            throw new IllegalStateException("JSONUtils: chromosomeFromJSON: chromosomes must has a name.");
        }

        JsonNumber lengthObj = json.getJsonNumber("length");

        JsonObject anno = json.getJsonObject("anno");

        if ((lengthObj == null) && (anno == null)) {
            return new Chromosome(name);
        } else {
            int length = -1;
            if (lengthObj != null) {
                try {
                    length = Integer.valueOf(lengthObj.toString());
                } catch (NumberFormatException nex) {
                    throw new IllegalArgumentException("JSONUtils: chromosomeFromJSON: illegal chromosome length: " + lengthObj);
                }
            }
            GeneralAnnotation annotations = null;
            if (anno != null) {
                annotations = generalAnnotationFromJSON(anno);
            }
            return new Chromosome(name, length, annotations);
        }
    }

}