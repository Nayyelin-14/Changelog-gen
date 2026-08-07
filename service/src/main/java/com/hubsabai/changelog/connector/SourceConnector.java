package com.hubsabai.changelog.connector;

import com.hubsabai.changelog.core.model.ReleaseData;

public interface SourceConnector {
    ReleaseData fetch(ConnectionConfig config);
}
