package world.willfrog.externalinfo.search.profile;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import world.willfrog.externalinfo.search.profile.ProfileContext.PersonalUserProfile;

/**
 * 个性用户画像注入器。
 * 当前为预留接口，空实现；P3 后期从 agent memory 系统读取用户长期偏好后，在此处注入。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PersonalUserProfileInjector {

    /**
     * 将个性画像上下文注入到原始 prompt 中。
     * 当前直接返回 originalPrompt，不做任何修改。
     * TODO: P3 后期接入 agent memory 系统后实现具体注入逻辑
     */
    public String injectPersonalContext(String originalPrompt, PersonalUserProfile profile) {
        // 预留：后续根据 profile.frequentlyViewedAssets、preferredMarkets、topicsOfInterest 等字段进行注入
        return originalPrompt;
    }
}
