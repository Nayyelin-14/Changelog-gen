package com.hubsabai.changelog.connector.azuredevops;

import com.hubsabai.changelog.connector.azuredevops.dto.WorkItemResponse;

import java.util.Map;

/** Field extraction helpers shared by every place that turns a raw {@link WorkItemResponse} into a {@code ChangeItem}. */
public final class WorkItemFields {

    private WorkItemFields() {}

    public static String string(WorkItemResponse wi, String fieldName) {
        if (wi.getFields() == null) return null;
        Object val = wi.getFields().get(fieldName);
        if (val == null) return null;
        if (val instanceof String s) return s;
        if (val instanceof Map<?, ?> map) {
            Object name = map.get("displayName");
            return name != null ? name.toString() : val.toString();
        }
        return val.toString();
    }

    public static String htmlUrl(WorkItemResponse wi) {
        return wi.getLinks() != null && wi.getLinks().getHtml() != null
                ? wi.getLinks().getHtml().getHref()
                : null;
    }
}
