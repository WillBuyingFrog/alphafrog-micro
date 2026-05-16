package world.willfrog.agent.workflow;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WorkflowFailureClassifierTest {

    private final WorkflowFailureClassifier classifier = new WorkflowFailureClassifier();

    @Test
    void classify_shouldTreatHttp5xxAsInfraRetry() {
        WorkflowFailureClassifier.FailureClassification classification = classifier.classify(
                ReactTodoExecutor.TodoExecutionRecord.builder()
                        .summary("HTTP_ERROR_500 Internal server error")
                        .build());

        assertEquals(WorkflowFailureClassifier.FailureCategory.INFRA_RETRY, classification.category());
        assertEquals(WorkflowFailureClassifier.RecoveryAction.RETRY_CURRENT, classification.action());
    }

    @Test
    void classify_shouldTreatDatasetParameterErrorAsParamRetry() {
        WorkflowFailureClassifier.FailureClassification classification = classifier.classify(
                ReactTodoExecutor.TodoExecutionRecord.builder()
                        .output("{\"ok\":false,\"error\":{\"code\":\"MISSING_DATASET_IDS\",\"message\":\"dataset_ids required\"}}")
                        .build());

        assertEquals(WorkflowFailureClassifier.FailureCategory.PARAM_RETRY_WITH_HINT, classification.category());
        assertEquals(WorkflowFailureClassifier.RecoveryAction.RETRY_CURRENT, classification.action());
        assertEquals("MISSING_DATASET_IDS", classification.errorCode());
    }

    @Test
    void classify_shouldFailFastForCapabilityDisabled() {
        WorkflowFailureClassifier.FailureClassification classification = classifier.classify(
                ReactTodoExecutor.TodoExecutionRecord.builder()
                        .output("{\"ok\":false,\"error\":{\"code\":\"CAPABILITY_DISABLED\"}}")
                        .build());

        assertEquals(WorkflowFailureClassifier.FailureCategory.FATAL_FAIL, classification.category());
        assertEquals(WorkflowFailureClassifier.RecoveryAction.FAIL_FAST, classification.action());
    }
}
