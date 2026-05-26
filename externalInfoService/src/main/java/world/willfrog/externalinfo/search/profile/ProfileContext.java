package world.willfrog.externalinfo.search.profile;

import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 提供当前用户的画像上下文。
 * 包含全局固定画像与个性画像两部分。
 */
@Component
public class ProfileContext {

    private static final GlobalUserProfile GLOBAL_PROFILE = new GlobalUserProfile(
            "Asia/Shanghai",
            "CN",
            "zh",
            "concise"
    );

    /**
     * 返回全局固定画像
     */
    public GlobalUserProfile getGlobalProfile() {
        return GLOBAL_PROFILE;
    }

    /**
     * 返回个性画像。
     * 当前为空实现，所有字段为 null 或空列表。
     */
    public PersonalUserProfile getPersonalProfile(String userId) {
        return new PersonalUserProfile(
                userId,
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyMap()
        );
    }

    /**
     * 全局固定画像
     */
    public record GlobalUserProfile(
            String timezone,        // 时区，如 "Asia/Shanghai"
            String region,          // 地区代码，如 "CN"
            String language,        // 语言代码，如 "zh"
            String answerStyle      // 回答风格，如 "concise" | "detailed"
    ) {
    }

    /**
     * 个性用户画像
     */
    public record PersonalUserProfile(
            String userId,
            List<String> frequentlyViewedAssets,   // 常看资产
            List<String> preferredMarkets,         // 偏好市场
            List<String> topicsOfInterest,         // 关注主题
            Map<String, Object> agentMemory        // 扩展字段
    ) {
    }
}
