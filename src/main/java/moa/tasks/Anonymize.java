package moa.tasks;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;

import moa.core.ObjectRepository;
import moa.options.ClassOption;
import moa.options.FileOption;
import moa.options.FlagOption;
import moa.options.IntOption;
import moa.streams.InstanceStream;
import moa.streams.filters.privacy.Evaluation;
import moa.streams.filters.privacy.PrivacyFilter;
import weka.core.Instance;

public class Anonymize extends MainTask {
	
	/** Serializable ID */
	private static final long serialVersionUID = 5157111181251282973L;
	
	private static final String PURPOSE_STRING = "Task to anonymize a stream of data, writing it to a file once" +
			" it is anonymized. An evaluation is also performed, based on the estimated disclosure risk of the " +
			"output dataset, along with an estimation of the information loss due to the anonymization process.";
	
	private static final String MONITOR_INITIAL_STATE = "Writing anonymized stream to ARFF file.";
	
	private static final String MONITOR_EVALUATION_STATE = "Writing anonymized stream to ARFF file. " +
			"%d already anonymized instances.";
	
	/* **** **** **** TASK OPTIONS **** **** **** */
	
	/* **** Filter options **** */
	public ClassOption filterOption = new ClassOption("filter", 'f',
            "Privacy filter to be applied.", PrivacyFilter.class, 
            "noiseaddition.NoiseAdditionFilter"); //TODO when this is refactored, check the package of the default filter!!
	
	/* **** Stream options **** */
	public ClassOption streamOption = new ClassOption("stream", 's',
            "Stream to be filtered.", InstanceStream.class,
            "generators.RandomRBFGenerator");
	
	public IntOption maxInstancesOption = new IntOption("maxInstances", 'm',
            "Maximum number of instances to write to file.", 10000000, 0,
            Integer.MAX_VALUE);
	
	/* **** Evaluation options **** */
	public FlagOption silenceEvaluationOption = new FlagOption("silenceEvaluation", 'q', 
			"Disable dumping evaluation output to a file.");
	
	public IntOption evaluationUpdateRateOption = new IntOption("evaluationUpdateRate", 'u',
			"Number of instances to skip between anonymization evaluation updates.", 10, 1, Integer.MAX_VALUE);

    public FileOption evaluationFileOption = new FileOption("evaluationFile", 'e', 
    		"Destination CSV file for the anonymization process evaluation.", "evaluation", "csv", true);
    
    /* **** Anonymized output options **** */
    public FlagOption silenceAnonymizationOption = new FlagOption("silenceAnonymization", 'Q', 
			"Disable dumping anonymization output to a file.");	
    
    public FileOption arffFileOption = new FileOption("arffFile", 'a',
            "Destination ARFF file for the anonymized dataset.", "anonymized", "arff", true);
    
    public FlagOption suppressHeaderOption = new FlagOption("suppressHeader",
            'h', "Suppress header from output.");
    
    /* **** **** **** **** **** **** **** **** **** */
    
    public Anonymize() {
    	outputFileOption = new FileOption("anonymizationResultFile", 'O',
    		"File to save the final report of the anonymization process.", "anonymizationReport", "moa", true);
	}
    
	@Override
	public String getPurposeString() {
		return PURPOSE_STRING;
	}
	
	@Override
	public Class<?> getTaskResultType() {
		return String.class;
	}
	
	private Writer getArffOutputFile() {
		File arffFile = null;
		//check whether the anonymization output is silenced
		if (!silenceAnonymizationOption.isSet()) {
			//get a writable file reference
			arffFile = getFile(arffFileOption);
			try {
				return new BufferedWriter(new FileWriter(arffFile));
			} catch (IOException e) {
				throw new RuntimeException("Failed to open the anonymization output file.", e);
			}
		}
		return null;
	}
	
	private Writer getEvaluationOutputFile() {
		File evaluationFile = null;
		//check whether the evaluation output is silenced
		if (!silenceEvaluationOption.isSet()) {
			//get a writable file reference
			evaluationFile = getFile(evaluationFileOption);
			try {
				return new BufferedWriter(new FileWriter(evaluationFile));
			} catch (IOException e) {
				throw new RuntimeException("Failed to open the evaluation output file.", e);
			}
		}
		return null;
	}
	
	private File getFile(FileOption fileOption) {
		File file = fileOption.getFile();
		String filePath = file.getPath();
		if (!filePath.contains(fileOption.getDefaultFileExtension())) {
			filePath = filePath + "." + fileOption.getDefaultFileExtension();
			file = new File(filePath);
		}
		return file;
	}

	@Override
	protected Object doMainTask(TaskMonitor monitor, ObjectRepository repository) {
		//prepare the stream and the filter
		InstanceStream stream = (InstanceStream) getPreparedClassOption(streamOption);
		PrivacyFilter filter = (PrivacyFilter) getPreparedClassOption(filterOption);
		filter.setInputStream(stream);
		
		//prepare the potential necessary files and variables
		Writer arffWriter = getArffOutputFile();
		Writer evaluationWriter = getEvaluationOutputFile();
		
		try {
			//write headers for both output files
			if (arffWriter != null && !suppressHeaderOption.isSet()) {
				arffWriter.write(stream.getHeader().toString());
	            arffWriter.write("\n");
			}
			if (evaluationWriter != null) {
				evaluationWriter.write(filter.getEvaluation().getEvaluationCSVHeader());
				evaluationWriter.write("\n");
			}
			
			//begin filtering
			monitor.setCurrentActivityDescription(MONITOR_INITIAL_STATE);
			int anonymizedInstances = 0;
			while (anonymizedInstances < maxInstancesOption.getValue() && filter.hasMoreInstances()) {
				//request next anonymized instance
				Instance instance = filter.nextInstance();
				
				//instance could be null if the filter is buffered (as long as the filter buffer is not yet full)
				if (instance != null) {
					anonymizedInstances++;
					
					//write instance
					if (arffWriter != null) {
						arffWriter.write(instance.toString());
						arffWriter.write("\n");
					}
					
					//update evaluation
					if (anonymizedInstances % evaluationUpdateRateOption.getValue() == 0) {
						Evaluation evaluation = filter.getEvaluation();
						monitor.setCurrentActivityDescription(
								String.format(MONITOR_EVALUATION_STATE, anonymizedInstances));
						
						//write evauation
						if (evaluationWriter != null) {
							evaluationWriter.write(evaluation.getEvaluationCSVRecord());
							evaluationWriter.write("\n");
						}
					}
				}
			}
			
			//flush and close the writer streams
			if (arffWriter != null) {
				arffWriter.flush();
				arffWriter.close();
			}
			if (evaluationWriter != null) {
				evaluationWriter.flush();
				evaluationWriter.close();
			}
			
			//return report
			return getAnonymizationReport(stream, filter, anonymizedInstances,
											filter.getCurrentDisclosureRisk(), filter.getCurrentInformationLoss());
		} catch (Exception e) {
			throw new RuntimeException("Failed to complete the task.", e);
		}
	}
	
	private String getAnonymizationReport(InstanceStream stream, PrivacyFilter filter, int anonymizedInstances,
			double disclosureRisk, double informationLoss) {
		StringBuilder builder = new StringBuilder(1024);
		builder.append("**** **** **** **** **** ANONYMIZATION TASK COMPLETED **** **** **** **** ****\n");
		builder.append(anonymizedInstances);
		builder.append(" instances have been anonymized from the stream with header:\n");
		builder.append(stream.getHeader() == null ? "error: no stream header available" : stream.getHeader().toString());
		builder.append("\n");
		if (!silenceAnonymizationOption.isSet()) {
			builder.append("and have been stored in the file: " + arffFileOption.getFile().getPath() + "\n");
		}
		builder.append("Total disclosure risk:  " + String.format("%.12f", disclosureRisk) + "\n");
		builder.append("Total information loss: " + String.format("%.12f", informationLoss) + "\n");
		builder.append("**** **** **** **** **** **** **** **** **** **** **** **** **** **** **** ****\n");
		return builder.toString();
	}

}