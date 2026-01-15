package world.willfrog.alphafrogmicro.portfolioservice.exception;

import lombok.Getter;
import world.willfrog.alphafrogmicro.common.dto.ResponseCode;

@Getter
public class BizException extends RuntimeException {
    private final ResponseCode code;

    public BizException(ResponseCode code, String message) {
        super(message);
        this.code = code;
    }
}
