package world.willfrog.alphafrogmicro.domestic.fetch.utils;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;

/**
 * TuShare API 响应处理工具类
 * 统一处理 TuShare 返回的 JSON 数据结构，避免空指针异常
 */
@Slf4j
public class TuShareResponseUtils {

    /**
     * 从 TuShare 响应中提取数据数组
     * 
     * @param response TuShare API 返回的 JSON 对象
     * @param apiName  API 名称（用于日志）
     * @return 数据数组，如果响应无效或没有数据则返回 null
     */
    public static JSONArray extractItems(JSONObject response, String apiName) {
        if (response == null) {
            log.warn("TuShare API [{}] returned null response", apiName);
            return null;
        }
        
        // 检查 API 返回的错误码
        Integer code = response.getInteger("code");
        if (code != null && code != 0) {
            log.warn("TuShare API [{}] returned error code {}: {}", 
                    apiName, code, response.getString("msg"));
            return null;
        }
        
        JSONObject data = response.getJSONObject("data");
        if (data == null) {
            log.debug("TuShare API [{}] returned null data", apiName);
            return null;
        }
        
        JSONArray items = data.getJSONArray("items");
        if (items == null) {
            log.debug("TuShare API [{}] returned null items", apiName);
        }
        
        return items;
    }

    /**
     * 从 TuShare 响应中提取字段数组
     * 
     * @param response TuShare API 返回的 JSON 对象
     * @param apiName  API 名称（用于日志）
     * @return 字段数组，如果响应无效或没有字段则返回 null
     */
    public static JSONArray extractFields(JSONObject response, String apiName) {
        if (response == null) {
            return null;
        }
        
        Integer code = response.getInteger("code");
        if (code != null && code != 0) {
            return null;
        }
        
        JSONObject data = response.getJSONObject("data");
        if (data == null) {
            return null;
        }
        
        return data.getJSONArray("fields");
    }

    /**
     * 从 TuShare 响应中同时提取 items 和 fields
     * 
     * @param response TuShare API 返回的 JSON 对象
     * @param apiName  API 名称（用于日志）
     * @return DataWrapper 包含 items 和 fields，如果无效则返回 null
     */
    public static DataWrapper extractData(JSONObject response, String apiName) {
        JSONArray items = extractItems(response, apiName);
        JSONArray fields = extractFields(response, apiName);
        
        // items 可以为空（表示没有数据），但 fields 必须有
        if (fields == null) {
            log.warn("TuShare API [{}] returned null fields", apiName);
            return null;
        }
        
        return new DataWrapper(items, fields);
    }

    /**
     * 数据包装类
     */
    public static class DataWrapper {
        private final JSONArray items;
        private final JSONArray fields;

        public DataWrapper(JSONArray items, JSONArray fields) {
            this.items = items;
            this.fields = fields;
        }

        public JSONArray getItems() {
            return items;
        }

        public JSONArray getFields() {
            return fields;
        }

        /**
         * 是否有数据（items 不为 null 且不为空）
         */
        public boolean hasData() {
            return items != null && !items.isEmpty();
        }
    }
}
