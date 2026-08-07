package com.hubsabai.changelog.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public class ModelBenchResult {

    private String model;
    private int trials;
    private int successes;
    private int failures;
    private double successRatePercent;
    private Long p50Ms;
    private Long p95Ms;
    private List<String> errors;
    private String sampleOutput;
    private Integer avgPromptTokens;
    private Integer avgCompletionTokens;
    private Integer avgTotalTokens;

    public ModelBenchResult() {}

    public ModelBenchResult(String model, int trials, int successes, int failures,
                             double successRatePercent, Long p50Ms, Long p95Ms, List<String> errors, String sampleOutput,
                             Integer avgPromptTokens, Integer avgCompletionTokens, Integer avgTotalTokens) {
        this.model = model;
        this.trials = trials;
        this.successes = successes;
        this.failures = failures;
        this.successRatePercent = successRatePercent;
        this.p50Ms = p50Ms;
        this.p95Ms = p95Ms;
        this.errors = errors;
        this.sampleOutput = sampleOutput;
        this.avgPromptTokens = avgPromptTokens;
        this.avgCompletionTokens = avgCompletionTokens;
        this.avgTotalTokens = avgTotalTokens;
    }

    @JsonProperty("model")
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    @JsonProperty("trials")
    public int getTrials() { return trials; }
    public void setTrials(int trials) { this.trials = trials; }

    @JsonProperty("successes")
    public int getSuccesses() { return successes; }
    public void setSuccesses(int successes) { this.successes = successes; }

    @JsonProperty("failures")
    public int getFailures() { return failures; }
    public void setFailures(int failures) { this.failures = failures; }

    @JsonProperty("successRatePercent")
    public double getSuccessRatePercent() { return successRatePercent; }
    public void setSuccessRatePercent(double successRatePercent) { this.successRatePercent = successRatePercent; }

    /** Null when every trial failed — there's no successful latency to report. */
    @JsonProperty("p50Ms")
    public Long getP50Ms() { return p50Ms; }
    public void setP50Ms(Long p50Ms) { this.p50Ms = p50Ms; }

    @JsonProperty("p95Ms")
    public Long getP95Ms() { return p95Ms; }
    public void setP95Ms(Long p95Ms) { this.p95Ms = p95Ms; }

    /** Distinct failure messages seen, truncated — empty when every trial succeeded. */
    @JsonProperty("errors")
    public List<String> getErrors() { return errors; }
    public void setErrors(List<String> errors) { this.errors = errors; }

    /** The last successful trial's generated text — lets a reader judge output quality, not just timing. Null if every trial failed. */
    @JsonProperty("sampleOutput")
    public String getSampleOutput() { return sampleOutput; }
    public void setSampleOutput(String sampleOutput) { this.sampleOutput = sampleOutput; }

    @JsonProperty("avgPromptTokens")
    public Integer getAvgPromptTokens() { return avgPromptTokens; }
    public void setAvgPromptTokens(Integer avgPromptTokens) { this.avgPromptTokens = avgPromptTokens; }

    @JsonProperty("avgCompletionTokens")
    public Integer getAvgCompletionTokens() { return avgCompletionTokens; }
    public void setAvgCompletionTokens(Integer avgCompletionTokens) { this.avgCompletionTokens = avgCompletionTokens; }

    @JsonProperty("avgTotalTokens")
    public Integer getAvgTotalTokens() { return avgTotalTokens; }
    public void setAvgTotalTokens(Integer avgTotalTokens) { this.avgTotalTokens = avgTotalTokens; }
}
