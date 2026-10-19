package cn.qihangerp.security;

import cn.qihangerp.common.utils.IpUtils;
import cn.qihangerp.model.entity.SysLogininfor;
import cn.qihangerp.security.common.Constants;
import cn.qihangerp.service.ISysLogininforService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.TimerTask;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步任务管理器（登录审计落库）。
 *
 * P1-2 修复：登录审计基础设施此前不存在，SysLoginService 的审计调用全部被注释。
 * 现提供线程池 + recordLogininfor；IP/UA 在同步线程采集（异步线程已无 request 上下文），
 * 仅落库放异步，保证异常不阻断登录主流程。
 *
 * @author qihang
 */
@Component
public class AsyncManager
{
    private static final Logger log = LoggerFactory.getLogger(AsyncManager.class);

    /** 单例引用，兼容 RuoYi 风格 AsyncManager.me() 静态调用 */
    private static volatile AsyncManager me = null;

    private final ScheduledThreadPoolExecutor executor;

    private final ISysLogininforService logininforService;

    @Autowired
    public AsyncManager(ISysLogininforService logininforService)
    {
        this.logininforService = logininforService;
        // 单线程串行落库即可，登录日志量小；CallerRuns 防队列堆积丢审计
        this.executor = new ScheduledThreadPoolExecutor(1, new ThreadPoolExecutor.CallerRunsPolicy());
        me = this;
    }

    public static AsyncManager me()
    {
        return me;
    }

    /**
     * 执行异步任务（吞异常只记日志，绝不影响调用方主流程）。
     */
    public void execute(TimerTask task)
    {
        executor.execute(() ->
        {
            try
            {
                task.run();
            }
            catch (Exception e)
            {
                log.error("异步任务执行异常: {}", e.getMessage(), e);
            }
        });
    }

    /**
     * 记录登录日志。IP/UA 在同步线程采集，落库放异步。
     *
     * @param username 用户名
     * @param status   Constants.LOGIN_SUCCESS / LOGIN_FAIL
     * @param message  失败原因或成功提示
     */
    public void recordLogininfor(String username, String status, String message)
    {
        String ipaddr = IpUtils.getIpAddr();
        String userAgent = currentUserAgent();
        execute(new TimerTask()
        {
            @Override
            public void run()
            {
                try
                {
                    SysLogininfor bo = new SysLogininfor();
                    bo.setUserName(username);
                    bo.setIpaddr(ipaddr);
                    bo.setBrowser(userAgent);
                    bo.setOs(resolveOs(userAgent));
                    bo.setStatus(status);
                    bo.setMsg(message);
                    bo.setLoginTime(LocalDateTime.now());
                    logininforService.insertLogininfor(bo);
                }
                catch (Exception e)
                {
                    log.error("记录登录日志失败: {}", e.getMessage(), e);
                }
            }
        });
    }

    private String currentUserAgent()
    {
        try
        {
            HttpServletRequest request = cn.qihangerp.common.utils.ServletUtils.getRequest();
            if (request != null)
            {
                return request.getHeader("User-Agent");
            }
        }
        catch (Exception ignored)
        {
            // 非 Web 上下文（内部调用/异步线程）时无 UA，不阻断
        }
        return null;
    }

    /** 粗解析操作系统（审计展示用，不追求完备）。 */
    private String resolveOs(String userAgent)
    {
        if (userAgent == null || userAgent.isEmpty())
        {
            return "未知";
        }
        String ua = userAgent.toLowerCase();
        if (ua.contains("windows"))
        {
            return "Windows";
        }
        if (ua.contains("mac os") || ua.contains("macintosh"))
        {
            return "MacOS";
        }
        if (ua.contains("android"))
        {
            return "Android";
        }
        if (ua.contains("iphone") || ua.contains("ipad"))
        {
            return "iOS";
        }
        if (ua.contains("linux"))
        {
            return "Linux";
        }
        return "未知";
    }
}
