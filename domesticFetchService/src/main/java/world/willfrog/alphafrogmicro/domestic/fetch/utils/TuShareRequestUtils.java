package world.willfrog.alphafrogmicro.domestic.fetch.utils;

import java.util.Map;

import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.HttpEntity;


import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.JSONArray;


import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
@Slf4j
//@PropertySource("classpath:application.yml")
public class TuShareRequestUtils {

    @Value("${tushare.token}")
    private String tushareToken;

    private static final int MAX_LOG_BODY_LENGTH = 8000;

    public JSONObject createTusharePostRequest(Map<String, Object> params) {
        try ( CloseableHttpClient httpClient = HttpClients.createDefault() ) {
            HttpPost request = new HttpPost("http://api.tushare.pro");
            request.setHeader("Content-Type", "application/json");

            JSONObject jsonParams = new JSONObject();
            jsonParams.put("token", tushareToken);
            jsonParams.putAll(params);
            String jsonParamsString = jsonParams.toString();
//            if (log.isDebugEnabled()) {
//                JSONObject safeParams = new JSONObject(jsonParams);
//                safeParams.put("token", maskToken(tushareToken));
//                log.debug("TuShare raw request payload: {}", safeParams.toString());
//                log.debug("TuShare request payload length: {}", jsonParamsString.length());
//                if (tushareToken == null || tushareToken.isBlank()) {
//                    log.debug("TuShare token is empty");
//                }
//            }
            StringEntity entity = new StringEntity(jsonParamsString, ContentType.APPLICATION_JSON);
            request.setEntity(entity);

            long startMs = System.currentTimeMillis();
            try ( ClassicHttpResponse response = httpClient.execute(request) ) {
                HttpEntity responseEntity = response.getEntity();
                if (responseEntity != null) {
                    String responseBody = EntityUtils.toString(responseEntity);
                    long costMs = System.currentTimeMillis() - startMs;
//                    if (log.isDebugEnabled()) {
//                        log.debug("TuShare response status={} cost_ms={} body_len={}",
//                                response.getCode(), costMs, responseBody.length());
//                        log.debug("TuShare raw response body: {}", trimBody(responseBody));
//                    }
//                    if (response.getCode() != 200) {
//                        log.warn("TuShare HTTP status not OK: {}", response.getCode());
//                    }
                    JSONObject responseJson = JSONObject.parseObject(responseBody);
                    if (responseJson == null) {
                        log.warn("TuShare response is not JSON");
                        return null;
                    }

                    Integer code = responseJson.getInteger("code");
                    String msg = responseJson.getString("msg");
                    JSONObject dataObject = responseJson.getJSONObject("data");
                    JSONArray fetchedFields = dataObject == null ? null : dataObject.getJSONArray("fields");
                    JSONArray fetchedData = dataObject == null ? null : dataObject.getJSONArray("items");
                    int fieldsSize = fetchedFields == null ? 0 : fetchedFields.size();
                    int dataSize = fetchedData == null ? 0 : fetchedData.size();
//                    if (log.isDebugEnabled()) {
//                        log.debug("TuShare response parsed code={} msg={} fields_size={} items_size={}",
//                                code, msg, fieldsSize, dataSize);
//                    }
//                    if (code != null && code != 0) {
//                        log.warn("TuShare response code not zero: code={} msg={}", code, msg);
//                    }

                    return responseJson;
                } else {
                    log.warn("TuShare response entity is empty, status={}", response.getCode());
                    return null;
                }
            } catch (Exception e) {
                log.error("Error occurred while fetching data from TuShare!");
                log.error("jsonParamString: " + jsonParamsString);
                log.error("Error stack trace", e);
//                e.printStackTrace();
                return null;
            }


        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private String maskToken(String token) {
        if (token == null || token.isBlank()) {
            return "";
        }
        int length = token.length();
        if (length <= 6) {
            return "***";
        }
        return token.substring(0, 3) + "***" + token.substring(length - 3);
    }

    private String trimBody(String body) {
        if (body == null) {
            return "";
        }
        if (body.length() <= MAX_LOG_BODY_LENGTH) {
            return body;
        }
        return body.substring(0, MAX_LOG_BODY_LENGTH) +
                "...(truncated, total_len=" + body.length() + ")";
    }
}
