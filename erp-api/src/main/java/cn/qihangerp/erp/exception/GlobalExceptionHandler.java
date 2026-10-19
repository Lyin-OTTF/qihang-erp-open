package cn.qihangerp.erp.exception;

import cn.qihangerp.common.AjaxResult;
import cn.qihangerp.common.ServiceException;
import cn.qihangerp.common.exception.BaseException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器。
 *
 * P1-3/P1-4 修复：此前整仓无 @RestControllerAdvice，各 Controller 自行
 * catch(Exception) 后把 e.getMessage()（可能含 SQL 片段、堆栈、第三方服务响应体）
 * 原样返回客户端，既泄露内部细节又暴露攻击面。现统一收敛：
 * - 业务异常（ServiceException/BaseException）返回设计给用户的提示；
 * - 其余异常对外只回通用文案，明细仅进服务端日志。
 *
 * @author qihang
 */
@RestControllerAdvice
public class GlobalExceptionHandler
{
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 业务异常：消息本身是面向用户的提示，可返回；携带 code 时沿用 */
    @ExceptionHandler(ServiceException.class)
    public AjaxResult handleServiceException(ServiceException e, HttpServletRequest request)
    {
        log.warn("业务异常 [{}]: {}", request.getRequestURI(), e.getMessage());
        Integer code = e.getCode();
        return AjaxResult.error(code == null ? 500 : code, e.getMessage());
    }

    /** 用户态异常（UserException 家族，经 BaseException 收敛） */
    @ExceptionHandler(BaseException.class)
    public AjaxResult handleBaseException(BaseException e, HttpServletRequest request)
    {
        log.warn("用户态异常 [{}]: {}", request.getRequestURI(), e.getMessage());
        String msg = e.getDefaultMessage();
        return AjaxResult.error(500, msg == null || msg.isEmpty() ? "操作失败，请检查输入" : msg);
    }

    /** 无权限（方法级安全 @PreAuthorize 拒绝） */
    @ExceptionHandler(AccessDeniedException.class)
    public AjaxResult handleAccessDeniedException(AccessDeniedException e, HttpServletRequest request)
    {
        log.warn("访问被拒绝 [{}]: {}", request.getRequestURI(), e.getMessage());
        return AjaxResult.error(403, "没有权限，请联系管理员授权");
    }

    /** @Valid 参数校验失败：取第一个字段错误（属用户可修正的提示） */
    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
    public AjaxResult handleValidationException(BindException e, HttpServletRequest request)
    {
        String msg = e.getBindingResult().getFieldErrors().isEmpty()
                ? "参数校验失败"
                : e.getBindingResult().getFieldErrors().get(0).getDefaultMessage();
        log.warn("参数校验失败 [{}]: {}", request.getRequestURI(), msg);
        return AjaxResult.error(400, msg);
    }

    /** 兜底：不向客户端泄露任何内部细节，全栈进日志 */
    @ExceptionHandler(Exception.class)
    public AjaxResult handleException(Exception e, HttpServletRequest request)
    {
        log.error("系统异常 [{}]", request.getRequestURI(), e);
        return AjaxResult.error(500, "系统繁忙，请稍后再试");
    }
}
