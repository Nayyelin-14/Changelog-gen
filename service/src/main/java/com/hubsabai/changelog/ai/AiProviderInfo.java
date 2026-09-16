package com.hubsabai.changelog.ai;

import com.fasterxml.jackson.annotation.JsonProperty;

/** One selectable entry in the provider dropdown. {@code enabled} is true only when the admin has
 * configured an API key for it (a provider with no key is hidden from users entirely). */
public record AiProviderInfo(
        @JsonProperty("id") String id,
        @JsonProperty("label") String label,
        @JsonProperty("enabled") boolean enabled) {
}