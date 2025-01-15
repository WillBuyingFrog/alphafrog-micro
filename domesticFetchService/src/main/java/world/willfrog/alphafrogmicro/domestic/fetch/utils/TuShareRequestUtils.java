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

    public JSONObject createTusharePostRequest(Map<String, Object> params) {
        try ( CloseableHttpClient httpClient = HttpClients.createDefault() ) {
            HttpPost request = new HttpPost("http://api.tushare.pro");
            request.setHeader("Content-Type", "application/json");

            JSONObject jsonParams = new JSONObject();
            jsonParams.put("token", tushareToken);
            jsonParams.putAll(params);
            String jsonParamsString = jsonParams.toString();
            log.info("[DEBUG] jsonParamsString: " + jsonParamsString);
            StringEntity entity = new StringEntity(jsonParamsString, ContentType.APPLICATION_JSON);
            request.setEntity(entity);

            try ( ClassicHttpResponse response = httpClient.execute(request) ) {
                HttpEntity responseEntity = response.getEntity();
                if (responseEntity != null) {
                    String responseBody = EntityUtils.toString(responseEntity);
                    JSONObject responseJson = JSONObject.parseObject(responseBody);
                    JSONArray fetchedFields = responseJson.getJSONObject("data").getJSONArray("fields");
                    JSONArray fetchedData = responseJson.getJSONObject("data").getJSONArray("items");

                    // System.out.println("Fields: " + fetchedFields);
                    // System.out.println("Data[0]: " + fetchedData.get(0));

                    return responseJson;
                } else {
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
}
