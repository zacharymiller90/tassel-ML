package net.maizegenetics.phenotype;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Pattern;

import org.apache.log4j.Logger;

import net.maizegenetics.phenotype.Phenotype.ATTRIBUTE_TYPE;
import net.maizegenetics.taxa.Taxon;
import net.maizegenetics.util.BitSet;
import net.maizegenetics.util.OpenBitSet;

/**
 * @author Peter Bradbury
 *
 */
public class PhenotypeBuilder {
	private Logger myLogger = Logger.getLogger(PhenotypeBuilder.class);
	private enum SOURCE_TYPE{file, phenotype, list, join};
	private SOURCE_TYPE source;
	private String filename;
	private Phenotype basePhenotype;
	private List<Phenotype> phenotypesToJoin;
	private List<Taxon> taxaToKeep = null;
	private List<Taxon> taxaToRemove = null;
	private List<PhenotypeAttribute> attributeList = null;
	private List<ATTRIBUTE_TYPE> attributeTypeList = null;
  	private int[] indexOfAttributesToKeep = null;
	private HashMap<ATTRIBUTE_TYPE, Integer> attributeChangeMap = new HashMap<ATTRIBUTE_TYPE, Integer>();
	private boolean isUnionJoin;
	private boolean isFilterable = false;
	private String phenotypeName = "Phenotype";
	
	public PhenotypeBuilder() {
		
	}
	
	/**
	 * @param basePhenotype	the base Phenotype that will be filtered or copied
	 * @return	a filterable PhenotypeBuilder
	 * This is the only method that returns a filterable PhenotypeBuilder
	 */
	public PhenotypeBuilder filterablePhenotypeBuilder(Phenotype basePhenotype) {
		this.basePhenotype = basePhenotype;
		source = SOURCE_TYPE.phenotype;
		isFilterable = true;
		return this;
	}
	
	/**
	 * @param filename	the name of a file containing phenotype data to be imported
	 * @return	a PhenotypeBuilder that will import a file
	 */
	public PhenotypeBuilder fromFile(String filename) {
		this.filename = filename;
		source = SOURCE_TYPE.file;
		return this;
	}
	
	/**
	 * @param phenotypes	a List of Phenotypes to be joined
	 * @return	a Phenotype builder that will build a join of the list of Phenotypes
	 * A union join returns a Phenotype containing any taxon present in at least one of the Phenotypes to be joined.
	 * An intersect join returns a Phenotype containing only taxa present in all of the Phenotypes to be joined.
	 * The type of join method should be specified using the intersect() or union() method. If no join method is specified, an intersect join will be performed.
	 */
	public PhenotypeBuilder joinPhenotypes(List<Phenotype> phenotypes) {
		phenotypesToJoin = phenotypes;
		isUnionJoin = false;
		source = SOURCE_TYPE.join;
		return this;
	}
	
	/**
	 * @return	a builder that will perform an intersect join if given a list of Phenotypes to join
	 */
	public PhenotypeBuilder intersect() {
		isUnionJoin = false;
		return this;
	}
	
	/**
	 * @return	a builder that will perform a union join if given a list of Phenotypes to join
	 */
	public PhenotypeBuilder union() {
		isUnionJoin = true;
		return this;
	}
	
	/**
	 * @param attributes	a list of attributes
	 * @param types	a list of types matching the attribute list
	 * @return	a PhenotypeBuilder that will build using these lists
	 * The attribute and type lists must be the same size and the types must be compatible with the attributes
	 */
	public PhenotypeBuilder fromList(List<PhenotypeAttribute> attributes, List<ATTRIBUTE_TYPE> types) {
		attributeList = attributes;
		attributeTypeList = types;
		source = SOURCE_TYPE.list;
		return this;
	}
	
	public PhenotypeBuilder assignName(String name) {
		phenotypeName = name;
		return this;
	}
	
	/**
	 * @param taxaToKeep	a list of taxa to be kept from the base Phenotype
	 * @return	a PhenotypeBuilder that will return a FilterPhenotype with taxa in the taxaToKeep list
	 * Only taxa that are in both taxaToKeep and the base Phenotype will be included in the Phenotype that is built.
	 * This function can only be applied to a filterable instance.
	 */
	public PhenotypeBuilder keepTaxa(List<Taxon> taxaToKeep) {
		if (!isFilterable) notFilterable();
		this.taxaToKeep = taxaToKeep; 
		return this;
	}
	
	/**
	 * @param taxaToRemove	a list of taxa to removed from the base Phenotype
	 * @return	a PhenotypeBuilder that will return a Phenotype with taxa from the supplied list excluded
	 * Any taxa in taxaToRemove but not in the base Phenotype will be ignored.
	 * This function can only be applied to a filterable instance.
	 */
	public PhenotypeBuilder removeTaxa(List<Taxon> taxaToRemove)  {
		if (!isFilterable) notFilterable();
		this.taxaToRemove = taxaToRemove;
		return this;
	}
	
	/**
	 * @param attributesToKeep	a list of the attributes to be kept
	 * @return	a PhenotypeBuilder that will return a new Phenotype with only the attributes in the supplied list
	 * Only attributes in both attributesToKeep and the base Phenotype will be included in the Phenotype that is built.
	 * The order of the attributes in the new Phenotype will match that in attributesToKeep.
	 * This function can only be applied to a filterable instance.
	 */
	public PhenotypeBuilder keepAttributes(List<PhenotypeAttribute> attributesToKeep) {
		if (!isFilterable) notFilterable();
		attributeList = attributesToKeep;
		return this;
	}
	
	/**
	 * @param indexOfAttributes	the column numbers of the attributes in the base Phenotype to be included in the newly built Phenotype
	 * @return	a PhenotypeBuilder that will build a Phenotype with the specified attributes
	 * This function can only be applied to a filterable instance.
	 */
	public PhenotypeBuilder keepAttributes(int[] indexOfAttributes) {
		if (!isFilterable) notFilterable();
		this.indexOfAttributesToKeep = indexOfAttributes;
		return this;
	}
	
	/**
	 * @param attributeIndex	the numeric index (column number) of an attribute in the base Phenotype
	 * @param type	the new type for that attribute
	 * @return	a PhenotypeBuilder that will build a phenotype with the changed attribute type
	 * This function can only be applied to a filterable instance.
	 */
	public PhenotypeBuilder changeAttributeType(int attributeIndex, ATTRIBUTE_TYPE type) {
		if (!isFilterable) notFilterable();
		attributeChangeMap.put(type, attributeIndex );
		return this;
	}
	
	/**
	 * @param attributeTypes	a list of attribute types for the attributes to be built
	 * @return	a PhenotypeBuilder that will build a Phenotype that will have this list of types
	 * The order of types must be the same as the order of attributes as supplied by the keepAttributes methods if used or in the base Phenotype if the attribute list is not changed.
	 * This function can only be applied to a filterable instance.
	 */
	public PhenotypeBuilder typesOfRetainedAttributes(List<ATTRIBUTE_TYPE> attributeTypes) {
		if (!isFilterable) notFilterable();
		attributeTypeList = attributeTypes;
		return this;
	}
	
	/**
	 * @return a new Phenotype built with the supplied parameters
	 */
	public Phenotype build() {
		if (source == SOURCE_TYPE.file) {
			try {
				File phenotypeFile = new File(filename);
				BufferedReader br = new BufferedReader(new FileReader(phenotypeFile));
				String topline = br.readLine();
				if (phenotypeName.equals("Phenotype")) {
					phenotypeName = new File(filename).getName();
					if (phenotypeName.endsWith(".txt")) phenotypeName = phenotypeName.substring(0, phenotypeName.length() - 4);
				}
				Phenotype myPhenotype;
				if (topline.toLowerCase().startsWith("<phenotype")) {
					myPhenotype = importPhenotypeFile(phenotypeFile);
				} else {
					myPhenotype = importTraits(phenotypeFile);
				}
				br.close();
				return myPhenotype;
				
			} catch (IOException e) {
				e.printStackTrace();
				return null;
			}
		} else if (source == SOURCE_TYPE.phenotype) {
			return filterBasePhenotype();
		} else if (source == SOURCE_TYPE.list) {
			return createPhenotypeFromLists();
		} else if (source == SOURCE_TYPE.join) {
			return joinPhenotypes();
		}
		return null;
	}
	
	//private methods  ------------------------------------------------------
	private void notFilterable() {
		throw new java.lang.IllegalStateException("Phenotype Builder error: applied a filter method to a non-filterable builder.");
	}
	
	private Phenotype importPhenotypeFile(File phenotypeFile) throws IOException {
		Pattern whiteSpace = Pattern.compile("\\s+");
		ArrayList<PhenotypeAttribute> attributes = new ArrayList<>();
		ArrayList<ATTRIBUTE_TYPE> types = new ArrayList<>();

		BufferedReader phenotypeReader = new BufferedReader(new FileReader(phenotypeFile));
		phenotypeReader.readLine();  //assumes the first line has been read to determine that this is indeed a Phenotype file
		String[] typeString = whiteSpace.split(phenotypeReader.readLine());
		String[] phenoNames = whiteSpace.split(phenotypeReader.readLine());
		int nPheno = typeString.length;
		ArrayList<String[]> stringData = new ArrayList<String[]>();
		String inputStr;
		while ((inputStr = phenotypeReader.readLine()) != null) {
			stringData.add(whiteSpace.split(inputStr));
		}

		int nObs = stringData.size();
		for (int pheno = 0; pheno < nPheno; pheno++) {
			if (typeString[pheno].toLowerCase().startsWith("cov") || typeString[pheno].toLowerCase().startsWith("dat")) {
				float[] dataArray = new float[nObs];
				OpenBitSet missing = new OpenBitSet(nObs);
				int obsCount = 0;
				for (String[] inputLine : stringData) {
					try {
						dataArray[obsCount] = Float.parseFloat(inputLine[pheno]);
					} catch (NumberFormatException nfe) {
						dataArray[obsCount] = Float.NaN;
						missing.fastSet(obsCount);
					}
					obsCount++;
				}
				attributes.add(new NumericAttribute(phenoNames[pheno], dataArray, missing));
				if (typeString[pheno].toLowerCase().startsWith("cov")) types.add(ATTRIBUTE_TYPE.covariate);
				else types.add(ATTRIBUTE_TYPE.data);
			} else if (typeString[pheno].toLowerCase().startsWith("tax")) {
				ArrayList<Taxon> taxa = new ArrayList<>();
				for (String[] inputLine : stringData) {
					taxa.add(new Taxon(inputLine[pheno]));
				}
				attributes.add(new TaxaAttribute(taxa, phenoNames[pheno]));
				types.add(ATTRIBUTE_TYPE.taxa);
			} else if (typeString[pheno].toLowerCase().startsWith("fac")) {
				String[] labelArray = new String[nObs];
				int obsCount = 0;
				for (String[] inputLine : stringData) {
					labelArray[obsCount++] = inputLine[pheno];
				}
				attributes.add(new CategoricalAttribute(phenoNames[pheno], labelArray));
				types.add(ATTRIBUTE_TYPE.factor);
			}
		}
		phenotypeReader.close();

		return new CorePhenotype(attributes, types, phenotypeName);
	}
	
	private Phenotype importTraits(File phenotypeFile) throws IOException {
		BufferedReader phenotypeReader = new BufferedReader(new FileReader(phenotypeFile));

		int numberOfDataLines = 0;
		String inputline = phenotypeReader.readLine();
		ArrayList<String> headerLines = new ArrayList<>();
		boolean isFactor = false;
		boolean isCovariate = false;
		boolean hasHeaders = false;
		boolean isTrait = false;
		String[] traitnames = new String[0];
		while (inputline != null) {
			inputline = inputline.trim();
			if (inputline.length() > 1 && !inputline.startsWith("<") && !inputline.startsWith("#")) {
				numberOfDataLines++;
			} else if (inputline.toLowerCase().startsWith("<trai")) {
				isTrait = true;
				String[] splitLine = inputline.split("[<>\\s]+");
				traitnames = Arrays.copyOfRange(splitLine, 2, splitLine.length);
			} else if (inputline.toLowerCase().startsWith("<cov")) {
				isCovariate = true;
			} else if (inputline.toLowerCase().startsWith("<fac")) {
				isFactor = true;
			} else if (inputline.toLowerCase().startsWith("<head")) {
				hasHeaders = true;
				headerLines.add(inputline);
			}
			
			inputline = phenotypeReader.readLine();
		}
		phenotypeReader.close();
		
		if (hasHeaders) {
			processTraitsAndFactors(phenotypeFile, traitnames, numberOfDataLines, isCovariate, headerLines);
		} else if (isFactor) {
			return processFactors(phenotypeFile, traitnames, numberOfDataLines);
		} else if (isTrait) {
			return processTraits(phenotypeFile, traitnames, numberOfDataLines, isCovariate);
		}
		throw new IllegalArgumentException("Unrecognized format for a phenotype.");
	}
	
	private Phenotype processTraits(File phenotypeFile, String[] traitnames, int numberOfDataLines, boolean isCovariate) throws IOException {
		int ntraits = traitnames.length;
		int nattributes = ntraits + 1;
		ArrayList<PhenotypeAttribute> attributes = new ArrayList<>(nattributes);
		ArrayList<ATTRIBUTE_TYPE> types = new ArrayList<>(nattributes);
		ArrayList<float[]> traitValues = new ArrayList<>(ntraits);
		ArrayList<BitSet> missingList = new ArrayList<>(ntraits);
		ArrayList<Taxon> taxaList = new ArrayList<>();
		
		types.add(ATTRIBUTE_TYPE.taxa);
		ATTRIBUTE_TYPE myAttributeType;
		if (isCovariate) myAttributeType = ATTRIBUTE_TYPE.covariate;
		else myAttributeType = ATTRIBUTE_TYPE.data;
		for (int i = 0; i < ntraits; i++) {
			traitValues.add(new float[numberOfDataLines]);
			missingList.add(new OpenBitSet(numberOfDataLines));
			types.add(myAttributeType);
		}
		
		BufferedReader phenotypeReader = new BufferedReader(new FileReader(phenotypeFile));
		int dataCount = 0;
		int lineCount = 1;
		String inputline;
		while ((inputline = phenotypeReader.readLine()) != null) {
			inputline = inputline.trim();
			if (inputline.length() > 1 && !inputline.startsWith("<") && !inputline.startsWith("#")) {
				String[] values = inputline.split("\\s+");
				if (values.length != ntraits + 1) {
					String msg = String.format("Incorrect number of values in line %d of %s", lineCount, phenotypeFile.getName());
					phenotypeReader.close();
					throw new IllegalArgumentException(msg);
				}
				taxaList.add(new Taxon(values[0]));
				for (int i = 0; i < ntraits; i++) {
					float val;
					try {
						val = Float.parseFloat(values[i + 1]);
					} catch (NumberFormatException e) {
						val = Float.NaN;
						missingList.get(i).fastSet(dataCount);
					}
					traitValues.get(i)[dataCount] = val;
				}
				dataCount++;
			}
			
			lineCount++;
		}
		
		phenotypeReader.close();
		
		attributes.add(new TaxaAttribute(taxaList));
		for (int i = 0; i < ntraits; i++) attributes.add(new NumericAttribute(traitnames[i], traitValues.get(i), missingList.get(i)));
		return new CorePhenotype(attributes, types, phenotypeName);
	}
	
	private Phenotype processFactors(File phenotypeFile, String[] traitnames, int numberOfDataLines) throws IOException {
		int ntraits = traitnames.length;
		int nattributes = ntraits + 1;
		ArrayList<PhenotypeAttribute> attributes = new ArrayList<>(nattributes);
		ArrayList<ATTRIBUTE_TYPE> types = new ArrayList<>(nattributes);
		ArrayList<String[]> traitValues = new ArrayList<>(ntraits);
		ArrayList<Taxon> taxaList = new ArrayList<>();
		
		types.add(ATTRIBUTE_TYPE.taxa);
		ATTRIBUTE_TYPE myAttributeType = ATTRIBUTE_TYPE.factor;
		for (int i = 0; i < ntraits; i++) {
			traitValues.add(new String[numberOfDataLines]);
			types.add(myAttributeType);
		}
		
		BufferedReader phenotypeReader = new BufferedReader(new FileReader(phenotypeFile));
		int dataCount = 0;
		int lineCount = 1;
		String inputline;
		while ((inputline = phenotypeReader.readLine()) != null) {
			inputline = inputline.trim();
			if (inputline.length() > 1 && !inputline.startsWith("<") && !inputline.startsWith("#")) {
				String[] values = inputline.split("\\s+");
				if (values.length != ntraits + 1) {
					String msg = String.format("Incorrect number of values in line %d of %s", lineCount, phenotypeFile.getName());
					phenotypeReader.close();
					throw new IllegalArgumentException(msg);
				}
				taxaList.add(new Taxon(values[0]));
				for (int i = 0; i < ntraits; i++) {
					traitValues.get(i)[dataCount] = values[i + 1];
				}
				dataCount++;
			}
			
			lineCount++;
		}
		
		phenotypeReader.close();
		
		attributes.add(new TaxaAttribute(taxaList));
		for (int i = 0; i < ntraits; i++) attributes.add(new CategoricalAttribute(traitnames[i], traitValues.get(i)));
		return new CorePhenotype(attributes, types, phenotypeName);
	}
	
	private void processHeader(String headerLine, Map<String, ArrayList<String>> headerMap) {
		String[] header = headerLine.split("[<>=\\s]+");
		if ( header[1].toLowerCase().equals("header") && header[2].toLowerCase().equals("name")) {
			String name = header[3];
			ArrayList<String> values = new ArrayList<>();
			for (int i = 4; i < header.length; i++) {
				values.add(header[i]);
			}
			headerMap.put(name, values);
		} else throw new IllegalArgumentException("Improperly formatted Header: " + headerLine);
	}
	
	private Phenotype processTraitsAndFactors(File phenotypeFile, String[] traitnames, int numberOfDataLines, boolean isCovariate, ArrayList<String> headerList)
	throws IOException {
		TreeSet<String> traitSet = new TreeSet<>();
		for (String trait : traitnames) traitSet.add(trait);
		HashMap<String, Integer> traitMap = new HashMap<>();
		int traitCount = 0;
		for (String trait : traitSet) traitMap.put(trait, traitCount++);
		
		int ntraitnames = traitnames.length;
		int ntraits = traitSet.size();
		int nfactors = headerList.size();
		String[] factorNames = new String[nfactors];
		
		//create set of composite headers and get factor names
		String[] splitHeader = headerList.get(0).split("[<>=\\s]+");
		factorNames[0] = splitHeader[3];
		String[] factorValues = Arrays.copyOfRange(splitHeader, 4, splitHeader.length);
		
		for (int i = 1; i < nfactors; i++) {
			splitHeader = headerList.get(i).split("[<>=\\s]+");
			factorNames[i] = splitHeader[3].replace("|", ":");
			for (int j = 0; j < factorValues.length; j++) {
				factorValues[j] += "|" + splitHeader[j + 4].replace("|", ":");
			}
		}
		
		TreeSet<String> factorSet = new TreeSet<>();
		for (String val:factorValues) factorSet.add(val);
		int nCompositeFactors = factorSet.size();
		HashMap<String,Integer> factorMap = new HashMap<>();
		int factorCount = 0;
		for (String factor : factorSet) factorMap.put(factor, factorCount++);
		
		//the length of each attribute array will be numberOfDataLines * number of composite headers
		int ndata = numberOfDataLines * nCompositeFactors;

		//create the factor arrays
		ArrayList<String[]> factorAttributeArrays = new ArrayList<>(nfactors);
		for (int i = 0; i < nfactors; i++) factorAttributeArrays.add(new String[ndata]);
		int fromIndex = 0;
		int toIndex = numberOfDataLines;
		for (String factor : factorSet) {
			String[] subFactor = factor.split("|");
			for (int i = 0; i < nfactors; i++) {
				Arrays.fill(factorAttributeArrays.get(i), fromIndex, toIndex, subFactor[i]);
			}
			fromIndex += numberOfDataLines;
			toIndex += numberOfDataLines;
		}
		
		//create the trait arrays and temporary taxa list
		ArrayList<float[]> traitAttributeArrays = new ArrayList<>();
		ArrayList<BitSet> missingList = new ArrayList<>();
		ArrayList<Taxon> tempTaxa = new ArrayList<>();

		for (int i = 0; i < ntraits; i++) {  //initialize the trait arrays to NaN
			float[] traitArray = new float[ndata];
			Arrays.fill(traitArray, Float.NaN);
			traitAttributeArrays.add(traitArray);
			missingList.add(new OpenBitSet(ndata));
		}
		
		BufferedReader br = new BufferedReader(new FileReader(phenotypeFile));
		int dataCount = 0;
		int lineCount = 1;
		String inputline;
		while ((inputline = br.readLine()) != null) {
			inputline = inputline.trim();
			if (inputline.length() > 1 && !inputline.startsWith("<") && !inputline.startsWith("#")) {
				String[] values = inputline.split("\\s+");
				if (values.length != ntraitnames + 1) {
					String msg = String.format("Incorrect number of values in line %d of %s", lineCount, phenotypeFile.getName());
					br.close();
					throw new IllegalArgumentException(msg);
				}
				tempTaxa.add(new Taxon(values[0]));
				for (int i = 0; i < ntraitnames; i++) {
					float val;
					int traitnum = traitMap.get(traitnames[i]);
					int factornum = factorMap.get(factorValues[i]);
					int dataIndex = factornum * numberOfDataLines + dataCount;
					try {
						val = Float.parseFloat(values[i + 1]);
					} catch (NumberFormatException e) {
						val = Float.NaN;
						missingList.get(traitnum).fastSet(dataIndex);
					}
					traitAttributeArrays.get(traitnum)[dataIndex] = val;
				}
				dataCount++;
			}
			
			lineCount++;
		}

		br.close();
		
		//create the taxa list
		ArrayList<Taxon> taxaList = new ArrayList<>(ndata);
		for (int i = 0; i < nCompositeFactors; i++) taxaList.addAll(tempTaxa);

		//create the taxa, factor, and trait attributes and types in that order
		ATTRIBUTE_TYPE myAttributeType;
		if (isCovariate) myAttributeType = ATTRIBUTE_TYPE.covariate;
		else myAttributeType = ATTRIBUTE_TYPE.data;

		ArrayList<PhenotypeAttribute> attributes = new ArrayList<>(nfactors + ntraits);
		ArrayList<ATTRIBUTE_TYPE> types = new ArrayList<>(nfactors + ntraits);
		
		attributes.add(new TaxaAttribute(taxaList));
		types.add(ATTRIBUTE_TYPE.taxa);
		
		for (int i = 0; i < nfactors; i++) {
			attributes.add(new CategoricalAttribute(factorNames[i], factorAttributeArrays.get(i)));
			types.add(ATTRIBUTE_TYPE.factor);
		}
		
		traitCount = 0;
		for (String trait : traitSet) {
			attributes.add(new NumericAttribute(trait, traitAttributeArrays.get(traitCount), missingList.get(traitCount)));
			types.add(myAttributeType);
			traitCount++;
		}
		
		return new CorePhenotype(attributes, types, phenotypeName);
	}
	
	private Phenotype filterBasePhenotype() {
		//TODO implement
		return null;
	}
	
	private Phenotype createPhenotypeFromLists() {
		if (attributeList.size() != attributeTypeList.size()) throw new IllegalArgumentException("Error building Phenotype: attribute list size not equal to type list size.");
		Iterator<ATTRIBUTE_TYPE> typeIter = attributeTypeList.iterator();
		for (PhenotypeAttribute attr : attributeList) {
			ATTRIBUTE_TYPE type = typeIter.next();
			if (!attr.isTypeCompatible(type)) {
				throw new IllegalArgumentException("Error building Phenotype: types not compatible with attributes.");
			}
		}
		return new CorePhenotype(attributeList, attributeTypeList, phenotypeName);
	}
	
	private Phenotype joinPhenotypes() {
		//TODO implement
		return null;
	}
}
