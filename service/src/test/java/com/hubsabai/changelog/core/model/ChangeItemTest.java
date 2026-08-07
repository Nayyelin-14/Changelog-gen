package com.hubsabai.changelog.core.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ChangeItemTest {

    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
    }

    @Test
    void shouldSerializeToJson() throws Exception {
        ChangeItem item = new ChangeItem();
        item.setType(ChangeItem.ItemType.WORK_ITEM);
        item.setId("1234");
        item.setTitle("Fix login timeout");
        item.setCategory("bug");
        item.setDescription("Session token not refreshed on slow networks");
        item.setAuthor("j.doe");
        item.setProject("MyProject");
        item.setRepo("my-repo");
        item.setLinks(List.of("https://dev.azure.com/org/proj/_workitems/edit/1234"));
        item.setFilePaths(List.of("src/auth/login.ts"));

        String json = mapper.writeValueAsString(item);

        assertTrue(json.contains("\"type\":\"WORK_ITEM\""), json);
        assertTrue(json.contains("\"id\":\"1234\""), json);
        assertTrue(json.contains("\"title\":\"Fix login timeout\""), json);
        assertTrue(json.contains("\"category\":\"bug\""), json);
        assertTrue(json.contains("\"project\":\"MyProject\""), json);
        assertTrue(json.contains("\"repo\":\"my-repo\""), json);
    }

    @Test
    void shouldSupportPullRequestType() throws Exception {
        ChangeItem item = new ChangeItem();
        item.setType(ChangeItem.ItemType.PULL_REQUEST);
        item.setId("42");
        item.setTitle("Add avatar upload endpoint");
        item.setCategory("feat");
        item.setProject("MyProject");
        item.setRepo("my-repo");

        String json = mapper.writeValueAsString(item);
        ChangeItem parsed = mapper.readValue(json, ChangeItem.class);

        assertEquals(ChangeItem.ItemType.PULL_REQUEST, parsed.getType());
        assertEquals("my-repo", parsed.getRepo());
    }

    @Test
    void shouldDeserializeFromJson() throws Exception {
        String json = """
            {
              "type": "COMMIT",
              "id": null,
              "title": "Add avatar upload endpoint",
              "category": "feature",
              "description": "New POST /api/users/avatar endpoint",
              "author": null,
              "links": ["https://dev.azure.com/org/proj/_git/repo/pullrequest/89"],
              "filePaths": ["src/routes/avatar.ts"]
            }
            """;

        ChangeItem item = mapper.readValue(json, ChangeItem.class);

        assertEquals(ChangeItem.ItemType.COMMIT, item.getType());
        assertNull(item.getId());
        assertEquals("Add avatar upload endpoint", item.getTitle());
        assertEquals("feature", item.getCategory());
        assertEquals(1, item.getLinks().size());
        assertEquals(1, item.getFilePaths().size());
    }

    @Test
    void shouldHandleMinimalFields() throws Exception {
        ChangeItem item = new ChangeItem();
        item.setType(ChangeItem.ItemType.WORK_ITEM);
        item.setTitle("Minimal item");
        item.setCategory("task");

        String json = mapper.writeValueAsString(item);

        assertTrue(json.contains("\"title\":\"Minimal item\""));
        assertTrue(json.contains("\"category\":\"task\""));
        assertTrue(json.contains("\"type\":\"WORK_ITEM\""));
    }
}
