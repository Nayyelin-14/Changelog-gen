package com.hubsabai.changelog.connector.azuredevops;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChangeCategoryClassifierTest {

    @Test
    void shouldClassifyTextByKeyword() {
        assertEquals("fix", ChangeCategoryClassifier.fromText("fix: null pointer on login"));
        assertEquals("feat", ChangeCategoryClassifier.fromText("feat: add avatar upload"));
        assertEquals("refactor", ChangeCategoryClassifier.fromText("refactor: clean up service layer"));
        assertEquals("test", ChangeCategoryClassifier.fromText("test: increase coverage for login flow"));
        assertEquals("docs", ChangeCategoryClassifier.fromText("doc: update README"));
        assertEquals("build", ChangeCategoryClassifier.fromText("build: bump quarkus version"));
        assertEquals("chore", ChangeCategoryClassifier.fromText("bump dependency versions"));
    }

    @Test
    void shouldClassifyRevertAsFixEvenWhenTheOriginalMessageEmbedsAnotherKeyword() {
        // A revert's title is usually the original commit/PR's own message quoted verbatim, which
        // would otherwise match that original message's keyword (feat, refactor, ...) and mislabel
        // the undo as if it were the change itself.
        assertEquals("fix", ChangeCategoryClassifier.fromText("Revert \"feat(testing): add testing webview components and styles\""));
        assertEquals("fix", ChangeCategoryClassifier.fromText("revert: refactor auth module"));
    }

    @Test
    void shouldDefaultToChoreForNullText() {
        assertEquals("chore", ChangeCategoryClassifier.fromText(null));
    }

    @Test
    void shouldClassifyByWorkItemType() {
        assertEquals("fix", ChangeCategoryClassifier.fromWorkItemType("Bug"));
        assertEquals("feat", ChangeCategoryClassifier.fromWorkItemType("User Story"));
        assertEquals("feat", ChangeCategoryClassifier.fromWorkItemType("Product Backlog Item"));
        assertEquals("feat", ChangeCategoryClassifier.fromWorkItemType("Epic"));
        assertEquals("chore", ChangeCategoryClassifier.fromWorkItemType("Task"));
        assertEquals("chore", ChangeCategoryClassifier.fromWorkItemType("Unknown Type"));
        assertEquals("chore", ChangeCategoryClassifier.fromWorkItemType(null));
    }
}
