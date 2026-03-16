package world.willfrog.externalinfo.ingestion.tushare;

import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@Slf4j
public class TuShareRagApiClient {

    private final RagFetchLocalConfigLoader configLoader;
    private static final String TUSHARE_URL = "http://api.tushare.pro";
    private static final int MAX_LOG_BODY_LENGTH = 8000;

    public TuShareRagApiClient(RagFetchLocalConfigLoader configLoader) {
        this.configLoader = configLoader;
    }

    /**
     * 向 TuShare API 发送 POST 请求并返回 JSON 响应。
     *
     * @param params 请求参数（api_name、params、fields 等），token 会自动注入
     * @return 响应 JSONObject，失败返回 null
     */
    public JSONObject post(Map<String, Object> params) {
        String token = configLoader.current()
                .map(RagFetchProperties::getTushareToken)
                .filter(t -> !t.isBlank())
                .orElseThrow(() -> new IllegalStateException("tushareToken not configured"));

        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            HttpPost request = new HttpPost(TUSHARE_URL);
            request.setHeader("Content-Type", "application/json");

            JSONObject jsonParams = new JSONObject();
            jsonParams.put("token", token);
            jsonParams.putAll(params);
            String jsonParamsString = jsonParams.toString();

            StringEntity entity = new StringEntity(jsonParamsString, ContentType.APPLICATION_JSON);
            request.setEntity(entity);

            long startMs = System.currentTimeMillis();
            try (ClassicHttpResponse response = httpClient.execute(request)) {
                HttpEntity responseEntity = response.getEntity();
                if (responseEntity != null) {
                    String responseBody = EntityUtils.toString(responseEntity);
                    long costMs = System.currentTimeMillis() - startMs;
                    if (log.isDebugEnabled()) {
                        log.debug("TuShare RAG response status={} cost_ms={} body_len={}",
                                response.getCode(), costMs, responseBody.length());
                    }
                    JSONObject responseJson = JSONObject.parseObject(responseBody);
                    if (responseJson == null) {
                        log.warn("TuShare RAG response is not valid JSON");
                        return null;
                    }
                    return responseJson;
                } else {
                    log.warn("TuShare RAG response entity is empty, status={}", response.getCode());
                    return null;
                }
            }
        } catch (Exception e) {
            log.error("Error calling TuShare RAG API: {}", e.getMessage(), e);
            return null;
        }
    }
}
