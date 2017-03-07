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
import moa.streams.filters.privacy.PrivacyEvaluation;
import moa.streams.filters.privacy.PrivacyFilter;
import moa.streams.filters.privacy.PrivacyFilter.EvaluationNotEnabledException;
import weka.core.Instance;

public class Anonymize extends MainTask {
	
	/** Serializable ID */
	private static final long serialVersionUID = 5157111181251282973L;
	
	private static final String PURPOSE_STRING = "Task to anonymize a stream of data, writing it to a file once" +
			" it is anonymized. An evaluation is also performed, based on the estimated disclosure risk of the " +
			"output dataset, along with an estimation of the information loss due to the anonymization process.";
	
	private static final String MONITOR_INITIAL_STATE = "Anonymizing stream...";
	
	private static final String MONITOR_EVALUATION_STATE = "i: %d, dr: %.3f, iil: %.2f, il: %.2f";

	private static final String MONITOR_UPDATE_STATE = "i: %d";
	
	/* **** **** **** TASK OPTIONS **** **** **** */
	
	/* **** Filter options **** */
	public ClassOption filterOption = new ClassOption("filter", 'f',
            "Privacy filter to be applied.", PrivacyFilter.class, 
            "noiseaddition.NoiseAdditionFilter -c 0.0 -a 0.25");
	private PrivacyFilter filter;
	
	/* **** Stream options **** */
	public ClassOption streamOption = new ClassOption("stream", 's',
            "Stream to be filtered.", InstanceStream.class,
            "generators.RandomRBFGenerator");
	private InstanceStream stream;
	
	public IntOption maxInstancesOption = new IntOption("maxInstances", 'm',
            "Maximum number of instances to process. If set to -1, keep processing until the" +
            " input stream runs out of instance (no maximum is set).", -1, -1, Integer.MAX_VALUE);
	
	/* **** Evaluation options **** */
	public FlagOption silenceEvaluationOption = new FlagOption("silenceEvaluation", 'q', 
			"Disable dumping evaluation output to a file.");
	
	public IntOption evaluationUpdateRateOption = new IntOption("evaluationUpdateRate", 'u',
			"Number of instances to skip between anonymization evaluation updates.", 100, 1, Integer.MAX_VALUE);

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
    		"File to save the final report of the anonymization process.", null, null, true);
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
		if (!silenceEvaluationOption.isSet()
				&& filter.isEvaluationEnabled()) {
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
	
	private void writeArffHeader(Writer arffWriter, InstanceStream stream) throws IOException {
		if (!silenceAnonymizationOption.isSet() && !suppressHeaderOption.isSet()) {
			arffWriter.write(stream.getHeader().toString());
            arffWriter.write("\n");
		}
	}
	
	private void writeEvaluationHeader(Writer evaluationWriter, PrivacyFilter filter)
											throws IOException, EvaluationNotEnabledException {
		if (!silenceEvaluationOption.isSet()
				&& filter.isEvaluationEnabled()) {
			evaluationWriter.write(filter.getEvaluation().getEvaluationCSVHeader());
			evaluationWriter.write("\n");
		}
	}
	
	private void writeAnonymizedInstance(Writer arffWriter, Instance instance) throws IOException {
		if (!silenceAnonymizationOption.isSet()) {
			arffWriter.write(instance.toString());
			arffWriter.write("\n");
		}
	}
	
	private void writeEvaluation(Writer evaluationWriter, PrivacyEvaluation evaluation) throws IOException {
		if (!silenceEvaluationOption.isSet()
				&& filter.isEvaluationEnabled()) {
			evaluationWriter.write(evaluation.getEvaluationCSVRecord());
			evaluationWriter.write("\n");
		}
	}
	
	private void closeWriter(Writer writer) throws IOException {
		if (writer != null) {
			writer.flush();
			writer.close();
		}
	}
	
	private boolean keepProcessing(long processedInstances, PrivacyFilter filter) {
		return filter.hasMoreInstances() &&                          // as long as the stream has instances,
				(maxInstancesOption.getValue() < 0 ||                //  process ALL instance in the stream
				processedInstances < maxInstancesOption.getValue()); //  OR process up to a maximum
	}
	
	@Override
	protected Object doMainTask(TaskMonitor monitor, ObjectRepository repository) {
		//prepare the stream and the filter
		this.stream = (InstanceStream) getPreparedClassOption(streamOption);
		this.filter = (PrivacyFilter) getPreparedClassOption(filterOption);
		filter.setInputStream(stream);
		
		//prepare the potential necessary files and variables
		Writer arffWriter = getArffOutputFile();
		Writer evaluationWriter = getEvaluationOutputFile();
		
		try {
			//write headers for both output files
			writeArffHeader(arffWriter, stream);
			writeEvaluationHeader(evaluationWriter, filter);
			
			//begin filtering
			monitor.setCurrentActivityDescription(MONITOR_INITIAL_STATE);
			long anonymizedInstances = 0;
			while (keepProcessing(anonymizedInstances, filter)) {
				Instance instance = filter.nextInstance();
				if (instance != null) {
					anonymizedInstances++;
					writeAnonymizedInstance(arffWriter, instance);
					
					//update evaluation if needed (check the evaluation update rate)
					if (anonymizedInstances % evaluationUpdateRateOption.getValue() == 0) {
						//check whether the evaluation is enabled to avoid exceptions
						if (filter.isEvaluationEnabled()) {
							PrivacyEvaluation evaluation = filter.getEvaluation();
							writeEvaluation(evaluationWriter, evaluation);
							monitor.setCurrentActivityDescription(
								String.format(MONITOR_EVALUATION_STATE, anonymizedInstances,
										evaluation.getDisclosureRisk(),
										evaluation.getIncrementalInformationLoss(),
										evaluation.getInformationLoss())
							);
						}
						else {
							monitor.setCurrentActivityDescription(
								String.format(MONITOR_UPDATE_STATE, anonymizedInstances));
						}
					}
				}
			}
			
			//flush and close the writer streams
			closeWriter(arffWriter);
			closeWriter(evaluationWriter);
			
			//get the necessary data for the report
			double disclosureRisk = 0.0;
			double informationLoss = 0.0;
			if (filter.isEvaluationEnabled()) {
				PrivacyEvaluation finalEvaluation = filter.getEvaluation();
				disclosureRisk = finalEvaluation.getDisclosureRisk();
				informationLoss = finalEvaluation.getInformationLoss();
			}
			//return report
			return getAnonymizationReport(stream, filter, 
					anonymizedInstances,
					disclosureRisk,	informationLoss);
		} catch (IOException e) {
			throw new RuntimeException("Failed to complete the task.", e);
		} catch (EvaluationNotEnabledException e) {
			throw new RuntimeException("An evaluation was requested, but the estimators were disabled", e);
		}
	}
	
	private String getAnonymizationReport(InstanceStream stream, PrivacyFilter filter,
			long anonymizedInstances,
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
		if (!silenceEvaluationOption.isSet()
				&& filter.isEvaluationEnabled()) {
			builder.append("Total disclosure risk:  " + String.format("%.12f", disclosureRisk) + "\n");
			builder.append("Total information loss: " + String.format("%.12f", informationLoss) + "\n");
		}
		builder.append("**** **** **** **** **** **** **** **** **** **** **** **** **** **** **** ****\n");
		return builder.toString();
	}

}
