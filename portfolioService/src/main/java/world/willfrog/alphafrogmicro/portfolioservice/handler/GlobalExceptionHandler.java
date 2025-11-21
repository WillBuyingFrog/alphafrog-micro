package world.willfrog.alphafrogmicro.portfolioservice.handler;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import world.willfrog.alphafrogmicro.common.dto.ResponseCode;
import world.willfrog.alphafrogmicro.common.dto.ResponseWrapper;
import world.willfrog.alphafrogmicro.portfolioservice.exception.BizException;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BizException.class)
    public ResponseWrapper<Void> handleBizException(BizException ex) {
        return ResponseWrapper.error(ex.getCode(), ex.getMessage());
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class, HttpMessageNotReadableException.class})
    public ResponseWrapper<Void> handleValidations(Exception ex) {
        return ResponseWrapper.error(ResponseCode.PARAM_ERROR, ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseWrapper<Void> handleOther(Exception ex) {
        log.error("Unexpected error", ex);
        return ResponseWrapper.error(ResponseCode.SYSTEM_ERROR, "系统异常，请稍后再试");
    }
}
