package net.maizegenetics.analysis.association;

import java.util.ArrayList;

import net.maizegenetics.dna.snp.score.AlleleProbability;
import net.maizegenetics.dna.snp.score.SiteScore.SITE_SCORE_TYPE;
import net.maizegenetics.plugindef.Datum;
import net.maizegenetics.stats.linearmodels.CovariateModelEffect;
import net.maizegenetics.stats.linearmodels.LinearModelUtils;
import net.maizegenetics.stats.linearmodels.ModelEffect;
import net.maizegenetics.stats.linearmodels.SweepFastLinearModel;
import net.maizegenetics.util.TableReportBuilder;

public class AlleleProbabilityFELM extends AbstractFixedEffectLM {
	int numberOfSiteReportColumns;
	int numberOfAlleleReportColumns;

	public AlleleProbabilityFELM(Datum data) {
		super(data);
	}
	
	@Override
	public void initializeReportBuilders() {
		String tableName = "GLM Site Tests - " + myDatum.getName();
		String[] columnNames;
		if (permute) columnNames = new String[]{"Trait","Marker","Chr","Position","marker_F","marker_p","perm_p","marker_Rsq","marker_df","marker_MS","error_df","error_MS","model_df","model_MS" };
		else columnNames = new String[]{"Trait","Marker","Chr","Position","marker_F","marker_p","marker_Rsq","marker_df","marker_MS","error_df","error_MS","model_df","model_MS" };
		numberOfSiteReportColumns = columnNames.length;
		siteReportBuilder = TableReportBuilder.getInstance(tableName, columnNames);
		
		tableName = "GLM Allele Estimates - " + myDatum.getName();
		columnNames = new String[]{"Trait","Marker","Chr","Position","Allele","Estimate"};
		numberOfAlleleReportColumns = columnNames.length;
		alleleReportBuilder = TableReportBuilder.getInstance(tableName, columnNames);
		permpvalueColumn = 6;
		markerpvalueColumn = 5;
	}

	@Override
	protected void analyzeSite(int siteNumber, ArrayList<ModelEffect> baseEffects) {
		String siteName = myGenoPheno.genotypeTable().siteName(siteNumber);
		SweepFastLinearModel markerModel = null;
		ArrayList<ModelEffect> modelPlusMarkers = new ArrayList<>(baseEffects);

		//retrieve values at this site, get A,C,G, and T, gap, insertion values
		//delete any that are monomorphic
		//if remaining sum to a vector of 1's drop one

		//retrieve values for A,C,G,T,gap,insertion and test for monomorphic
		int ntaxa = myGenoPheno.genotypeTable().numberOfTaxa();
		float[] sumOfValues = new float[ntaxa];
		ArrayList<float[]> probList = new ArrayList<>();
		ArrayList<SITE_SCORE_TYPE> typeList = new ArrayList<>();
		for (SITE_SCORE_TYPE type : AlleleProbability.ALLELE_PROBABILITY_TYPES) {
			float[] values = alleleProbsOfType(type, siteNumber);
			if (!isMonomorphic(values)) {
				probList.add(values);
				typeList.add(type);
				for (int t = 0; t < ntaxa; t++) sumOfValues[t] += values[t];
			}
		}

		//do the arrays sum to an array of ones?
		//since the byte conversion is approximate use 0.95 to 1.05 to test
		boolean sumsToOne = true;
		for (int t = 0; t < ntaxa; t++) {
			if (sumOfValues[t] < 0.95 || sumOfValues[t] > 1.05) {
				sumsToOne = false;
				break;
			}
		}

		//add the alleles as ModelEffects
		int numberOfAlleles = probList.size();
		int numberOfAllelesInModel = numberOfAlleles;
		if (sumsToOne) numberOfAllelesInModel--;
		for (int a = 0; a < numberOfAllelesInModel; a++) {
			double[] covar = getNonMissingDoubles(probList.get(a), missingObsForSite);
			modelPlusMarkers.add(new CovariateModelEffect(covar));
		}

		if (areTaxaReplicated) myModel.add(taxaEffect());

		//solve the model
		markerModel = new SweepFastLinearModel(modelPlusMarkers, siteData);

        //calculate model
        double[] modelSSdf = markerModel.getModelcfmSSdf();
        if (areTaxaReplicated) errorSSdf = markerModel.getIncrementalSSdf(taxaEffectNumber);
        else errorSSdf = markerModel.getResidualSSdf();
        markerSSdf = new double[]{0,0};
        for (int a = 0; a < numberOfAllelesInModel; a++) {
        	double[] SSdf = markerModel.getIncrementalSSdf(a + numberOfBaseEffects);
        	markerSSdf[0] += SSdf[0];
        	markerSSdf[1] += SSdf[1];
        }
        double rsq = markerSSdf[0] / (modelSSdf[0] + errorSSdf[0]);
        
        double F = markerSSdf[0] / markerSSdf[1] / errorSSdf[0] * errorSSdf[1];
        double p;
        try {
            p = LinearModelUtils.Ftest(F, markerSSdf[1], errorSSdf[1]);
        } catch (Exception e) {
            p = Double.NaN;
        }
        double[] beta = markerModel.getBeta();
		
        //add results to site report
        //{"Trait","Marker","Chr","Position","marker_F","marker_p","marker_Rsq","marker_df","marker_MS","error_df","error_MS","model_df","model_MS" }
        Object[] rowData = new Object[numberOfSiteReportColumns];
        int columnCount = 0;
        rowData[columnCount++] = currentTraitName;
        rowData[columnCount++] = siteName;	
        rowData[columnCount++] = myGenoPheno.genotypeTable().chromosomeName(siteNumber);
        rowData[columnCount++] = myGenoPheno.genotypeTable().chromosomalPosition(siteNumber);
        rowData[columnCount++] = new Double(F);
        rowData[columnCount++] = new Double(p);
        rowData[columnCount++] = new Double(rsq);
        rowData[columnCount++] = new Double(markerSSdf[1]);
        rowData[columnCount++] = new Double(markerSSdf[0]/markerSSdf[1]);
        rowData[columnCount++] = new Double(errorSSdf[1]);
        rowData[columnCount++] = new Double(errorSSdf[0]/errorSSdf[1]);
        rowData[columnCount++] = new Double(modelSSdf[1]);
        rowData[columnCount++] = new Double(modelSSdf[0]/modelSSdf[1]);
        siteReportBuilder.add(rowData);
        
        //add results to allele report
        //{"Trait","Marker","Chr","Position","Allele","Estimate"}
        int firstEstimateIndex = beta.length - numberOfAllelesInModel;
        for (int a = 0; a < numberOfAlleles; a++) {
            rowData = new Object[numberOfAlleleReportColumns];
            columnCount = 0;
            rowData[columnCount++] = currentTraitName;
            rowData[columnCount++] = siteName;
            rowData[columnCount++] = myGenoPheno.genotypeTable().chromosomeName(siteNumber);
            rowData[columnCount++] = myGenoPheno.genotypeTable().chromosomalPosition(siteNumber);
            rowData[columnCount++] = typeNameMap.get(typeList.get(a));
            if (a < numberOfAllelesInModel) rowData[columnCount++] = new Double(beta[firstEstimateIndex + a]);
            else rowData[columnCount++] = new Double(0.0);
            alleleReportBuilder.add(rowData);
        }
     
	}

}