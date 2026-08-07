package com.hubsabai.changelog.ai;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public class AiRequest {
    private String model;
    private List<AiMessage> messages;
    private double temperature;

    public AiRequest() {}

    public AiRequest(String model, List<AiMessage> messages, double temperature) {
        this.model = model;
        this.messages = messages;
        this.temperature = temperature;
    }

    @JsonProperty("model")
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    @JsonProperty("messages")
    public List<AiMessage> getMessages() { return messages; }
    public void setMessages(List<AiMessage> messages) { this.messages = messages; }

    @JsonProperty("temperature")
    public double getTemperature() { return temperature; }
    public void setTemperature(double temperature) { this.temperature = temperature; }
}
