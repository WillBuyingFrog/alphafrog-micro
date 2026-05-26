package world.willfrog.alphafrogmicro.common.utils;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PlaceholderResolverTest {

    @Test
    void resolveString_shouldReplaceEmbeddedPlaceholder() {
        String path = System.getenv("PATH");
        assumeTrue(path != null && !path.isBlank());

        assertEquals("prefix-" + path + "-suffix", PlaceholderResolver.resolveString("prefix-${PATH}-suffix"));
    }

    @Test
    void resolveJsonObject_shouldResolvePlaceholdersInsideArrays() {
        String path = System.getenv("PATH");
        assumeTrue(path != null && !path.isBlank());

        JSONObject root = new JSONObject();
        JSONArray items = new JSONArray();
        items.add("${PATH}");

        JSONObject nested = new JSONObject();
        nested.put("url", "file:${PATH}");
        items.add(nested);

        JSONArray nestedArray = new JSONArray();
        nestedArray.add("inner-${PATH}");
        items.add(nestedArray);
        root.put("items", items);

        PlaceholderResolver.resolveJsonObject(root);

        JSONArray resolvedItems = root.getJSONArray("items");
        assertEquals(path, resolvedItems.getString(0));
        assertEquals("file:" + path, resolvedItems.getJSONObject(1).getString("url"));
        assertEquals("inner-" + path, resolvedItems.getJSONArray(2).getString(0));
    }
}
