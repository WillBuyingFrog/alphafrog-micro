package world.willfrog.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agent.flow.code-refine")
public class CodeRefineProperties {

    /**
     * 外部本地配置文件路径（可选）。
     */
    private String configFile;

    /**
     * Python 代码执行与纠错的最大尝试次数。
     */
    private int maxAttempts = 3;

    public String getConfigFile() {
        return configFile;
    }

    public void setConfigFile(String configFile) {
        this.configFile = configFile;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }
}
