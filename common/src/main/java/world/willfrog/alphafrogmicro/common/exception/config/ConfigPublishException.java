package world.willfrog.alphafrogmicro.common.exception.config;

public class ConfigPublishException extends RuntimeException {

    public ConfigPublishException(String message, Throwable cause) {
        super(message, cause);
    }

    public ConfigPublishException(String message) {
        super(message);
    }
}
