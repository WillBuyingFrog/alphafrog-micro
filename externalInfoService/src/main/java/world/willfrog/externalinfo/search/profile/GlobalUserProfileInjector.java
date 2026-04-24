package world.willfrog.externalinfo.search.profile;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import world.willfrog.externalinfo.search.profile.ProfileContext.GlobalUserProfile;

/**
 * 将全局画像注入到各 backend 的 search prompt / system prompt 中。
 * 注入逻辑保持非侵入：如果画像字段为空，则不注入相关内容。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class GlobalUserProfileInjector {

    /**
     * 为 Perplexity / Exa 的 system prompt 追加画像信息。
     * 格式：用户画像：时区 {timezone}，地区 {region}，使用 {language} 回答。
     */
    public String injectIntoSystemPrompt(String originalPrompt, GlobalUserProfile profile) {
        if (originalPrompt == null) {
            originalPrompt = "";
        }
        if (profile == null) {
            return originalPrompt;
        }

        StringBuilder sb = new StringBuilder(originalPrompt);
        if (!sb.isEmpty()) {
            sb.append("\n\n");
        }
        sb.append("用户画像：");

        boolean hasAny = false;
        if (hasText(profile.timezone())) {
            sb.append("时区 ").append(profile.timezone());
            hasAny = true;
        }
        if (hasText(profile.region())) {
            if (hasAny) {
                sb.append("，");
            }
            sb.append("地区 ").append(profile.region());
            hasAny = true;
        }
        if (hasText(profile.language())) {
            if (hasAny) {
                sb.append("，");
            }
            // 将语言代码映射为自然语言描述
            String langDesc = mapLanguageToDescription(profile.language());
            sb.append("使用").append(langDesc).append("回答");
            hasAny = true;
        }
        if (hasText(profile.answerStyle())) {
            if (hasAny) {
                sb.append("，");
            }
            sb.append("回答风格 ").append(profile.answerStyle());
        }
        sb.append("。");

        return sb.toString();
    }

    /**
     * 为 Tavily 的 query 追加画像前缀。
     * 格式：[用户位于 {region}，使用 {language}，时区 {timezone}] {原始query}
     */
    public String injectIntoQuery(String originalQuery, GlobalUserProfile profile) {
        if (!hasText(originalQuery)) {
            return originalQuery;
        }
        if (profile == null) {
            return originalQuery;
        }

        StringBuilder prefix = new StringBuilder();
        prefix.append("[");

        boolean hasAny = false;
        if (hasText(profile.region())) {
            String regionDesc = mapRegionToDescription(profile.region());
            prefix.append("用户位于").append(regionDesc);
            hasAny = true;
        }
        if (hasText(profile.language())) {
            if (hasAny) {
                prefix.append("，");
            }
            String langDesc = mapLanguageToDescription(profile.language());
            prefix.append("使用").append(langDesc);
            hasAny = true;
        }
        if (hasText(profile.timezone())) {
            if (hasAny) {
                prefix.append("，");
            }
            String tzDesc = mapTimezoneToDescription(profile.timezone());
            prefix.append("时区 ").append(tzDesc);
        }
        prefix.append("] ");

        return prefix + originalQuery;
    }

    /**
     * 为 RagPrefetcher 的相关性判定 prompt 注入语言信息。
     */
    public String injectIntoRelevancePrompt(String originalPrompt, GlobalUserProfile profile) {
        if (originalPrompt == null) {
            originalPrompt = "";
        }
        if (profile == null) {
            return originalPrompt;
        }

        if (!hasText(profile.language())) {
            return originalPrompt;
        }

        StringBuilder sb = new StringBuilder(originalPrompt);
        if (!sb.isEmpty()) {
            sb.append("\n\n");
        }
        String langDesc = mapLanguageToDescription(profile.language());
        sb.append("请使用").append(langDesc).append("进行相关性判定。");

        return sb.toString();
    }

    // ==================== 辅助方法 ====================

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private String mapLanguageToDescription(String language) {
        return switch (language.toLowerCase()) {
            case "zh", "zh-cn", "zh-hans" -> "中文";
            case "en" -> "英文";
            case "ja" -> "日文";
            case "ko" -> "韩文";
            default -> language;
        };
    }

    private String mapRegionToDescription(String region) {
        return switch (region.toUpperCase()) {
            case "CN" -> "中国大陆";
            case "US" -> "美国";
            case "HK" -> "中国香港";
            case "TW" -> "中国台湾";
            case "JP" -> "日本";
            case "KR" -> "韩国";
            case "SG" -> "新加坡";
            case "GB" -> "英国";
            default -> region;
        };
    }

    private String mapTimezoneToDescription(String timezone) {
        return switch (timezone) {
            case "Asia/Shanghai" -> "UTC+8";
            case "Asia/Tokyo" -> "UTC+9";
            case "Asia/Seoul" -> "UTC+9";
            case "America/New_York" -> "UTC-5/UTC-4";
            case "America/Los_Angeles" -> "UTC-8/UTC-7";
            case "Europe/London" -> "UTC+0/UTC+1";
            case "Europe/Paris" -> "UTC+1/UTC+2";
            case "Australia/Sydney" -> "UTC+10/UTC+11";
            case "UTC" -> "UTC+0";
            default -> timezone;
        };
    }
}
