package world.willfrog.agentlangchain.planning;

import com.fasterxml.jackson.databind.JsonNode;
import world.willfrog.agent.workflow.PlanExecutionMode;
import world.willfrog.agent.workflow.TodoItem;
import world.willfrog.agent.workflow.TodoStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

final class LangchainTodoPlanParser {

    private LangchainTodoPlanParser() {
    }

    static LangchainTodoPlan fromJsonRoot(JsonNode root, PlanExecutionMode mode, int maxTodos) {
        if (root == null || !root.isObject()) {
            throw new IllegalStateException("todo_plan_empty");
        }
        JsonNode itemsNode = root.path("items");
        if (!itemsNode.isArray()) {
            itemsNode = root.path("todos");
        }
        List<TodoItem> items = new ArrayList<>();
        if (itemsNode.isArray()) {
            int seq = 1;
            for (JsonNode node : itemsNode) {
                if (items.size() >= maxTodos) {
                    break;
                }
                List<String> dependsOn = new ArrayList<>();
                JsonNode dependsOnNode = node.path("dependsOn");
                if (dependsOnNode.isArray()) {
                    for (JsonNode depNode : dependsOnNode) {
                        dependsOn.add(depNode.asText());
                    }
                }
                String description = nvl(node.path("description").asText(""));
                if (description.isBlank()) {
                    continue;
                }
                int sequence = node.path("sequence").asInt(seq);
                String itemId = nvl(node.path("id").asText("")).trim();
                if (itemId.isBlank()) {
                    itemId = "todo_" + sequence;
                }
                items.add(TodoItem.builder()
                        .id(itemId)
                        .sequence(sequence)
                        .description(description)
                        .dependsOn(dependsOn)
                        .groupKey(nvl(node.path("groupKey").asText("")))
                        .parallelizable(node.path("parallelizable").asBoolean(false))
                        .status(TodoStatus.PENDING)
                        .createdAt(Instant.now())
                        .build());
                seq++;
            }
        }
        if (items.isEmpty()) {
            throw new IllegalStateException("todo_plan_empty");
        }
        List<String> extractedEntities = new ArrayList<>();
        JsonNode entitiesNode = root.path("extractedEntities");
        if (!entitiesNode.isArray()) {
            entitiesNode = root.path("extracted_entities");
        }
        if (entitiesNode.isArray()) {
            for (JsonNode entityNode : entitiesNode) {
                String entity = nvl(entityNode.asText("")).trim();
                if (!entity.isBlank() && !extractedEntities.contains(entity)) {
                    extractedEntities.add(entity);
                }
            }
        }
        return LangchainTodoPlan.builder()
                .analysis(nvl(root.path("analysis").asText("")))
                .items(items)
                .extractedEntities(extractedEntities)
                .executionMode(mode)
                .build();
    }

    private static String nvl(String value) {
        return value == null ? "" : value;
    }
}
