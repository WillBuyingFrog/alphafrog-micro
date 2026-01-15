package world.willfrog.alphafrogmicro.frontend.advice;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

@ControllerAdvice
public class FrogInfoResponseAdvice implements ResponseBodyAdvice<Object> {

    private final BuildProperties buildProperties;

    public FrogInfoResponseAdvice(ObjectProvider<BuildProperties> buildPropertiesProvider) {
        this.buildProperties = buildPropertiesProvider.getIfAvailable();
    }

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return StringHttpMessageConverter.class.isAssignableFrom(converterType);
    }

    @Override
    public Object beforeBodyWrite(
            Object body,
            MethodParameter returnType,
            MediaType selectedContentType,
            Class<? extends HttpMessageConverter<?>> selectedConverterType,
            ServerHttpRequest request,
            ServerHttpResponse response
    ) {
        if (!(body instanceof String)) {
            return body;
        }

        String payload = ((String) body).trim();
        if (payload.isEmpty()) {
            return body;
        }

        JSONObject jsonObject;
        try {
            Object parsed = JSON.parse(payload);
            if (!(parsed instanceof JSONObject)) {
                return body;
            }
            jsonObject = (JSONObject) parsed;
        } catch (Exception e) {
            return body;
        }

        JSONObject frogInfo = jsonObject.getJSONObject("frog-info");
        if (frogInfo == null) {
            frogInfo = new JSONObject();
        }
        if (!frogInfo.containsKey("version")) {
            frogInfo.put("version", resolveVersion());
        }
        jsonObject.put("frog-info", frogInfo);
        return jsonObject.toJSONString();
    }

    private String resolveVersion() {
        String envVersion = System.getenv("ALPHAFROG_VERSION");
        if (envVersion != null && !envVersion.isBlank()) {
            return envVersion.trim();
        }
        if (buildProperties != null) {
            String version = buildProperties.get("alphafrog.version");
            if (version != null && !version.isBlank()) {
                return version.trim();
            }
            return buildProperties.getVersion();
        }
        return "unknown";
    }
}
