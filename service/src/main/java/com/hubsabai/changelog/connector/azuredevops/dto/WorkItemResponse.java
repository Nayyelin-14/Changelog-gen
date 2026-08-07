package com.hubsabai.changelog.connector.azuredevops.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

public class WorkItemResponse {

    private int id;
    private Map<String, Object> fields;
    private Links links;

    public WorkItemResponse() {}

    @JsonProperty("id")
    public int getId() { return id; }
    public void setId(int id) { this.id = id; }

    @JsonProperty("fields")
    public Map<String, Object> getFields() { return fields; }
    public void setFields(Map<String, Object> fields) { this.fields = fields; }

    @JsonProperty("_links")
    public Links getLinks() { return links; }
    public void setLinks(Links links) { this.links = links; }

    public static class Links {
        private Link html;

        @JsonProperty("html")
        public Link getHtml() { return html; }
        public void setHtml(Link html) { this.html = html; }
    }

    public static class Link {
        private String href;

        @JsonProperty("href")
        public String getHref() { return href; }
        public void setHref(String href) { this.href = href; }
    }
}
