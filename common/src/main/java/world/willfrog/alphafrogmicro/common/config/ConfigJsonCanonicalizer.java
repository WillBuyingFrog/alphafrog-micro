package world.willfrog.alphafrogmicro.common.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Iterator;
import java.util.TreeSet;

/**
 * 配置 JSON 规范化工具。
 *
 * <p>PostgreSQL jsonb 会重排对象字段。配置同步状态只关心语义内容，不应受字段顺序影响。</p>
 */
public final class ConfigJsonCanonicalizer {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private ConfigJsonCanonicalizer() {
    }

    public static String canonicalJson(JsonNode node) {
        try {
            return OBJECT_MAPPER.writeValueAsString(sort(node));
        } catch (Exception e) {
            throw new IllegalArgumentException("配置 JSON 规范化失败", e);
        }
    }

    public static String canonicalJson(byte[] contentBytes) {
        try {
            return canonicalJson(OBJECT_MAPPER.readTree(contentBytes));
        } catch (Exception e) {
            return new String(contentBytes, StandardCharsets.UTF_8);
        }
    }

    public static String md5Hex(JsonNode node) {
        return md5Hex(canonicalJson(node));
    }

    public static String md5Hex(byte[] contentBytes) {
        return md5Hex(canonicalJson(contentBytes));
    }

    public static String md5Hex(String content) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalArgumentException("配置 MD5 计算失败", e);
        }
    }

    private static JsonNode sort(JsonNode node) {
        if (node == null || node.isNull() || node.isValueNode()) {
            return node;
        }
        if (node.isArray()) {
            ArrayNode sortedArray = JsonNodeFactory.instance.arrayNode();
            for (JsonNode item : node) {
                sortedArray.add(sort(item));
            }
            return sortedArray;
        }
        if (node.isObject()) {
            ObjectNode sortedObject = JsonNodeFactory.instance.objectNode();
            TreeSet<String> fieldNames = new TreeSet<>();
            Iterator<String> iterator = node.fieldNames();
            while (iterator.hasNext()) {
                fieldNames.add(iterator.next());
            }
            for (String fieldName : fieldNames) {
                sortedObject.set(fieldName, sort(node.get(fieldName)));
            }
            return sortedObject;
        }
        return node;
    }
}
