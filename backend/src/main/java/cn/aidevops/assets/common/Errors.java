package cn.aidevops.assets.common;

import jakarta.validation.ConstraintViolationException;
import java.util.*;
import org.slf4j.*;
import org.springframework.dao.*;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class Errors {
  private static final Logger log = LoggerFactory.getLogger(Errors.class);

  @ExceptionHandler(ApiException.class)
  ResponseEntity<?> api(ApiException e) {
    return response(e.status, e.code, e.getMessage());
  }

  @ExceptionHandler({org.springframework.orm.ObjectOptimisticLockingFailureException.class})
  ResponseEntity<?> conflict(Exception e) {
    return response(409, "VERSION_CONFLICT", "记录已变化，请刷新后重试");
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  ResponseEntity<?> integrity(Exception e) {
    return response(409, "DATA_CONFLICT", "记录重复或仍被其他对象引用");
  }

  @ExceptionHandler({
    MethodArgumentNotValidException.class,
    ConstraintViolationException.class,
    IllegalArgumentException.class,
    org.springframework.http.converter.HttpMessageNotReadableException.class,
    org.springframework.web.bind.MissingServletRequestParameterException.class,
    org.springframework.web.multipart.support.MissingServletRequestPartException.class,
    org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class
  })
  ResponseEntity<?> invalid(Exception e) {
    return response(
        400,
        "VALIDATION_ERROR",
        e instanceof IllegalArgumentException ? e.getMessage() : "请求字段或格式不正确");
  }

  @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
  ResponseEntity<?> tooLarge(Exception e) {
    return response(413, "IMPORT_TOO_LARGE", "上传文件超过允许大小");
  }

  @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
  ResponseEntity<?> method(Exception e) {
    return response(405, "METHOD_NOT_ALLOWED", "请求方法不支持");
  }

  @ExceptionHandler(org.springframework.web.HttpMediaTypeNotSupportedException.class)
  ResponseEntity<?> media(Exception e) {
    return response(415, "UNSUPPORTED_MEDIA_TYPE", "请求内容类型不支持");
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<?> unknown(Exception e) {
    log.error("request failed traceId={}", MDC.get("traceId"), e);
    return response(500, "INTERNAL_ERROR", "处理失败，请通过请求编号查询日志");
  }

  private ResponseEntity<?> response(int status, String code, String message) {
    return ResponseEntity.status(status)
        .body(
            Map.of(
                "code",
                code,
                "message",
                Objects.toString(message, "请求失败"),
                "traceId",
                Objects.toString(MDC.get("traceId"), "")));
  }
}
