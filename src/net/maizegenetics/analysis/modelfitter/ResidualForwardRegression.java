package net.maizegenetics.analysis.modelfitter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.StreamSupport;

import org.apache.commons.math3.distribution.FDistribution;

import net.maizegenetics.dna.map.Position;
import net.maizegenetics.phenotype.GenotypePhenotype;
import net.maizegenetics.stats.linearmodels.CovariateModelEffect;
import net.maizegenetics.stats.linearmodels.ModelEffect;
import net.maizegenetics.stats.linearmodels.PartitionedLinearModel;
import net.maizegenetics.stats.linearmodels.SweepFastLinearModel;
import net.maizegenetics.util.Tuple;

public class ResidualForwardRegression extends AbstractForwardRegression {
    List<ModelEffect> meanOnlyModel;
    
    public ResidualForwardRegression(GenotypePhenotype data, int phenotypeIndex, double enterLimit, int maxVariants) {
        super(data, phenotypeIndex, enterLimit, maxVariants);
        
    }

    @Override
    public void fitModel() {
        meanOnlyModel = new ArrayList<ModelEffect>();
        double[] meanarray = new double[numberOfObservations];
        Arrays.fill(meanarray,1);
        meanOnlyModel.add(new CovariateModelEffect(meanarray, "mean"));
        
        SweepFastLinearModel sflm = new SweepFastLinearModel(myModel,y);
        int maxModelSize = myModel.size() + maxVariants;
        while ((sflm = forwardStepParallel(sflm)) != null && myModel.size() < maxModelSize);
    }
    
    
    @Override
    public void fitModelForSubsample(int[] subSample) {
        int numberOfSamples = subSample.length;
        
        meanOnlyModel = new ArrayList<ModelEffect>();
        double[] meanarray = new double[numberOfSamples];
        Arrays.fill(meanarray,1);
        meanOnlyModel.add(new CovariateModelEffect(meanarray, "mean"));
        
        //create myModel from myBaseModel for this subsample
        myModel = myBaseModel.stream().map(me -> me.getSubSample(subSample)).collect(Collectors.toList());
        double[] original = y;
        y = Arrays.stream(subSample).mapToDouble(i -> original[subSample[i]]).toArray();
        
        SweepFastLinearModel sflm = new SweepFastLinearModel(myModel,y);
        int maxModelSize = myModel.size() + maxVariants;
        while ((sflm = forwardStepParallel(sflm, subSample)) != null && myModel.size() < maxModelSize);
        
        y = original;
    }

    private SweepFastLinearModel forwardStep(SweepFastLinearModel sflm) {
        double[] residuals = sflm.getResiduals().to1DArray();
        int n = residuals.length;
        
        //Find the site with the best fit
        SweepFastLinearModel residualSflm = new SweepFastLinearModel(meanOnlyModel, residuals);
        PartitionedLinearModel plm = new PartitionedLinearModel(meanOnlyModel, residualSflm);
        
        AdditiveSite bas = siteList.stream().map(s -> { s.SS(plm.testNewModelEffect(s.getCovariate())); return s;})
                .reduce(new AdditiveSite(), (a,b) -> b.SS() > a.SS() ? b : a);
        SiteInformation bestSite = new SiteInformation(bas.siteNumber(), bas.getCovariate(), bas.SS());

        ModelEffect siteEffect = new CovariateModelEffect(bestSite.covariate);
        myModel.add(siteEffect);
        sflm = new SweepFastLinearModel(myModel, y);
        double[] errorSSdf = sflm.getResidualSSdf();
        double[] siteSSdf = sflm.getIncrementalSSdf(myModel.size() - 1);
        double F,p;
        if (siteSSdf[1] < FDistribution.DEFAULT_INVERSE_ABSOLUTE_ACCURACY || errorSSdf[0] < FDistribution.DEFAULT_INVERSE_ABSOLUTE_ACCURACY) {
            F = Double.NaN;
            p = Double.NaN;
        } else {
            F = siteSSdf[0] / siteSSdf[1] / errorSSdf[0] * errorSSdf[1];
            p = 1 - (new FDistribution(siteSSdf[1], errorSSdf[1]).cumulativeProbability(F));
        }
        
        if (!Double.isNaN(p) && p <= enterLimit) {
            addVariant(bestSite.siteIndex, p);
            return sflm;
        }
        else return null;
    }
    
    private Function<AdditiveSite, AdditiveSite> siteSubsetTester(int[] subset, PartitionedLinearModel plm) {
        return site -> { site.SS(plm.testNewModelEffect(site.getCovariate(subset))); return site;}; 
    }
    
    private Function<AdditiveSite, AdditiveSite> siteTester(PartitionedLinearModel plm) {
        return site -> { site.SS(plm.testNewModelEffect(site.getCovariate())); return site;}; 
    }
    
    private SweepFastLinearModel forwardStep(SweepFastLinearModel sflm, int[] subset) {
        double[] residuals = sflm.getResiduals().to1DArray();
        int n = residuals.length;
        
        //Find the site with the best fit
        SweepFastLinearModel residualSflm = new SweepFastLinearModel(meanOnlyModel, residuals);
        PartitionedLinearModel plm = new PartitionedLinearModel(meanOnlyModel, residualSflm);
        
        AdditiveSite bas = siteList.stream().map(siteSubsetTester(subset, plm))
                .reduce(new AdditiveSite(), (a,b) -> b.SS() > a.SS() ? b : a);
        SiteInformation bestSite = new SiteInformation(bas.siteNumber(), bas.getCovariate(), bas.SS());

        ModelEffect siteEffect = new CovariateModelEffect(bestSite.covariate);
        myModel.add(siteEffect);
        sflm = new SweepFastLinearModel(myModel, y);
        double[] errorSSdf = sflm.getResidualSSdf();
        double[] siteSSdf = sflm.getIncrementalSSdf(myModel.size() - 1);
        double F,p;
        if (siteSSdf[1] < FDistribution.DEFAULT_INVERSE_ABSOLUTE_ACCURACY || errorSSdf[0] < FDistribution.DEFAULT_INVERSE_ABSOLUTE_ACCURACY) {
            F = Double.NaN;
            p = Double.NaN;
        } else {
            F = siteSSdf[0] / siteSSdf[1] / errorSSdf[0] * errorSSdf[1];
            p = 1 - (new FDistribution(siteSSdf[1], errorSSdf[1]).cumulativeProbability(F));
        }
        
        if (!Double.isNaN(p) && p <= enterLimit) {
            //{"trait","SnpID","Chr","Pos", "p-value", "-log10p"}
            addVariant(bestSite.siteIndex, p);
            return sflm;
        }
        else return null;
    }
    
    private SweepFastLinearModel forwardStepParallel(SweepFastLinearModel sflm) {
        double[] residuals = sflm.getResiduals().to1DArray();
        
        AdditiveSite bestSite = StreamSupport.stream(new AbstractForwardRegression.ForwardStepAdditiveSpliterator(siteList, meanOnlyModel, residuals), true)
                .reduce(new AdditiveSite(), (a,b) -> b.SS() > a.SS() ? b : a);
        
        ModelEffect siteEffect = new CovariateModelEffect(bestSite.getCovariate());
        myModel.add(siteEffect);
        sflm = new SweepFastLinearModel(myModel, y);
        double[] errorSSdf = sflm.getResidualSSdf();
        double[] siteSSdf = sflm.getIncrementalSSdf(myModel.size() - 1);
        double F,p;
        if (siteSSdf[1] < FDistribution.DEFAULT_INVERSE_ABSOLUTE_ACCURACY || errorSSdf[0] < FDistribution.DEFAULT_INVERSE_ABSOLUTE_ACCURACY) {
            F = Double.NaN;
            p = Double.NaN;
        } else {
            F = siteSSdf[0] / siteSSdf[1] / errorSSdf[0] * errorSSdf[1];
            p = 1 - (new FDistribution(siteSSdf[1], errorSSdf[1]).cumulativeProbability(F));
        }
        
        if (!Double.isNaN(p) && p <= enterLimit) {
            addVariant(bestSite.siteNumber(), p);
            return sflm;
        }
        else return null;
    }
    
    private SweepFastLinearModel forwardStepParallel(SweepFastLinearModel sflm, int[] subset) {
        double[] residuals = sflm.getResiduals().to1DArray();
        
        AdditiveSite bestSite = StreamSupport.stream(new AbstractForwardRegression.ForwardStepAdditiveSubsettingSpliterator(siteList, meanOnlyModel, residuals, subset), true)
                .reduce(new AdditiveSite(), (a,b) -> b.SS() > a.SS() ? b : a);
        
        ModelEffect siteEffect = new CovariateModelEffect(bestSite.getCovariate());
        myModel.add(siteEffect);
        sflm = new SweepFastLinearModel(myModel, y);
        double[] errorSSdf = sflm.getResidualSSdf();
        double[] siteSSdf = sflm.getIncrementalSSdf(myModel.size() - 1);
        double F,p;
        if (siteSSdf[1] < FDistribution.DEFAULT_INVERSE_ABSOLUTE_ACCURACY || errorSSdf[0] < FDistribution.DEFAULT_INVERSE_ABSOLUTE_ACCURACY) {
            F = Double.NaN;
            p = Double.NaN;
        } else {
            F = siteSSdf[0] / siteSSdf[1] / errorSSdf[0] * errorSSdf[1];
            p = 1 - (new FDistribution(siteSSdf[1], errorSSdf[1]).cumulativeProbability(F));
        }
        
        if (!Double.isNaN(p) && p <= enterLimit) {
            addVariant(bestSite.siteNumber(), p);
            return sflm;
        }
        else return null;
    }
    
}