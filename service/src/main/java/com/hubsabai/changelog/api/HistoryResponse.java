package com.hubsabai.changelog.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public class HistoryResponse {

    private List<HistoryEntry> entries;
    private int total;

    public HistoryResponse() {}

    public HistoryResponse(List<HistoryEntry> entries, int total) {
        this.entries = entries;
        this.total = total;
    }

    @JsonProperty("entries")
    public List<HistoryEntry> getEntries() { return entries; }
    public void setEntries(List<HistoryEntry> entries) { this.entries = entries; }

    @JsonProperty("total")
    public int getTotal() { return total; }
    public void setTotal(int total) { this.total = total; }
}
