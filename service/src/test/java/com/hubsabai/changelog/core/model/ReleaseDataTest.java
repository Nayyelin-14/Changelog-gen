package com.hubsabai.changelog.core.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReleaseDataTest {

    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
    }

    @Test
    void shouldSerializeToJson() throws Exception {
        ReleaseData data = new ReleaseData();
        ReleaseData.ReleaseMeta meta = new ReleaseData.ReleaseMeta();
        meta.setOrg("datasabai");
        meta.setProject("MyProject");
        meta.setRepo("my-repo");
        meta.setMilestone("Sprint 24");
        meta.setReleaseDate("2026-06-26");
        data.setRelease(meta);

        ChangeItem item = new ChangeItem();
        item.setType(ChangeItem.ItemType.WORK_ITEM);
        item.setId("1234");
        item.setTitle("Fix login timeout");
        item.setCategory("bug");
        data.setItems(List.of(item));

        String json = mapper.writeValueAsString(data);

        assertTrue(json.contains("\"org\":\"datasabai\""), json);
        assertTrue(json.contains("\"project\":\"MyProject\""), json);
        assertTrue(json.contains("\"repo\":\"my-repo\""), json);
        assertTrue(json.contains("\"milestone\":\"Sprint 24\""), json);
        assertTrue(json.contains("\"releaseDate\":\"2026-06-26\""), json);
        assertTrue(json.contains("\"title\":\"Fix login timeout\""), json);
    }

    @Test
    void shouldDeserializeFromJson() throws Exception {
        String json = """
            {
              "release": {
                "org": "datasabai",
                "project": "MyProject",
                "repo": "my-repo",
                "milestone": "Sprint 24",
                "releaseDate": "2026-06-26"
              },
              "items": [
                {
                  "type": "WORK_ITEM",
                  "id": "1234",
                  "title": "Fix login timeout",
                  "category": "bug",
                  "description": "Session token not refreshed",
                  "author": "j.doe",
                  "links": [],
                  "filePaths": []
                }
              ]
            }
            """;

        ReleaseData data = mapper.readValue(json, ReleaseData.class);

        assertEquals("datasabai", data.getRelease().getOrg());
        assertEquals("MyProject", data.getRelease().getProject());
        assertEquals("my-repo", data.getRelease().getRepo());
        assertEquals("Sprint 24", data.getRelease().getMilestone());
        assertEquals("2026-06-26", data.getRelease().getReleaseDate());
        assertEquals(1, data.getItems().size());
        assertEquals("Fix login timeout", data.getItems().get(0).getTitle());
        assertEquals(ChangeItem.ItemType.WORK_ITEM, data.getItems().get(0).getType());
    }

    @Test
    void shouldHandleEmptyItems() throws Exception {
        ReleaseData data = new ReleaseData();
        ReleaseData.ReleaseMeta meta = new ReleaseData.ReleaseMeta();
        meta.setProject("EmptyProject");
        data.setRelease(meta);
        data.setItems(List.of());

        String json = mapper.writeValueAsString(data);
        ReleaseData parsed = mapper.readValue(json, ReleaseData.class);

        assertEquals("EmptyProject", parsed.getRelease().getProject());
        assertTrue(parsed.getItems().isEmpty());
    }
}
