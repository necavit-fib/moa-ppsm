package moa.streams.filters.privacy.differentialprivacy;

import moa.options.FloatOption;
import moa.options.IntOption;
import moa.streams.filters.privacy.InstancePair;
import moa.streams.filters.privacy.PrivacyFilter;
import moa.streams.filters.privacy.differentialprivacy.algorithms.laplace.LaplaceMechanism;
import moa.streams.filters.privacy.differentialprivacy.microaggregation.TotalOrderKNNMicroAggregator;
import weka.core.Instance;


public class DifferentialPrivacyFilter extends PrivacyFilter {

	/** Serializable */
	private static final long serialVersionUID = 7849083467422191222L;

	/** The <em>K</em> value for the <em>k</em>-anonymity property to be satisfied. */
	public IntOption kAnonymityValueOption = new IntOption("kAnonimty", 'k', 
			"The size of the clusters that will be used to perform the aggregation", 3, 2, Integer.MAX_VALUE);

	/** The size of the historical buffer considered before starting to perform the filtering. */
    public IntOption bufferSizeOption = new IntOption("bufferLength", 'b', 
    		"Size of the historical buffer considered for the microaggregation process", 100, 10, Integer.MAX_VALUE);
    
    /** The differential privacy parameter */
    public FloatOption epsilonOption = new FloatOption("epsilon", 'e', 
    		"The differential privacy parameter", 0.1f, Double.MIN_VALUE, Double.MAX_VALUE);
    
    /** Random generator seed */
	public IntOption randomSeedOption = new IntOption("randomSeed", 'r', 
			"The pseudo-random generator seed.", 3141592, Integer.MIN_VALUE, Integer.MAX_VALUE);
    
    private TotalOrderKNNMicroAggregator microAggregator;
	private LaplaceMechanism laplaceMechanism;
	
	@Override
	public void getDescription(StringBuilder sb, int indent) {
		// TODO Auto-generated method stub
	}

	@Override
	public void prepareAnonymizationFilterForUse() {
		this.microAggregator = new TotalOrderKNNMicroAggregator(kAnonymityValueOption.getValue(), 
																bufferSizeOption.getValue());
		this.laplaceMechanism = new LaplaceMechanism(randomSeedOption.getValue(),
													 epsilonOption.getValue());
	}

	@Override
	public void restartAnonymizationFilter() {
		prepareAnonymizationFilterForUse();
	}
	
	@Override
	public InstancePair nextAnonymizedInstancePair() {
		if (inputStream.hasMoreInstances()) {
			// Check if the stream has more instances, in order to avoid asking for instances
			//  that do not exist. Even when the filter user has called hasMoreInstances()
			//  on this filter, there is no guarantee that the one who has more instances
			//  is the filter!! See the hasMoreInstances() implementation to understand this.
			Instance originalInstance = (Instance) inputStream.nextInstance().copy();
			microAggregator.addInstance(originalInstance);
		}
		
		InstancePair microaggregatedPair = microAggregator.nextAnonymizedInstancePair();
		if (microaggregatedPair != null) {
			Instance anonymizedInstance = laplaceMechanism.addLaplaceNoise(microaggregatedPair.anonymizedInstance);
			return new InstancePair(microaggregatedPair.originalInstance, 
									anonymizedInstance);
		}
		else {
			return null;
		}
	}
	
	@Override
	public boolean hasMoreInstances() {
		return microAggregator.hasMoreInstances() || inputStream.hasMoreInstances();
	}
	
}