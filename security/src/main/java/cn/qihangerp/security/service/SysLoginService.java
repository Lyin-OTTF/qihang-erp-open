package cn.qihangerp.security.service;

import cn.qihangerp.common.ServiceException;
import cn.qihangerp.common.config.RedisCache;
import cn.qihangerp.common.constant.CacheConstants;
import cn.qihangerp.common.constant.UserConstants;
import cn.qihangerp.common.exception.BlackListException;
import cn.qihangerp.common.exception.CaptchaException;
import cn.qihangerp.common.exception.CaptchaExpireException;
import cn.qihangerp.common.exception.UserNotExistsException;
import cn.qihangerp.common.utils.DateUtils;
import cn.qihangerp.common.utils.IpUtils;
import cn.qihangerp.common.utils.StringUtils;
import cn.qihangerp.model.entity.SysConfig;
import cn.qihangerp.model.entity.SysUser;
import cn.qihangerp.security.AsyncManager;
import cn.qihangerp.security.AuthenticationContextHolder;
import cn.qihangerp.security.LoginUser;
import cn.qihangerp.security.TokenService;
import cn.qihangerp.security.UserPasswordNotMatchException;
import cn.qihangerp.security.common.Constants;
import cn.qihangerp.service.ISysUserService;
import cn.qihangerp.service.SysConfigService;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;


/**
 * 登录校验方法
 *
 * P1-2 修复：
 * 1. 登录审计落库恢复（此前 AsyncManager/SysLogininfor 不存在，调用全被注释）；
 * 2. 新增密码错误防爆破（Redis 计数锁定，此前无任何防爆破机制）；
 * 3. IP 黑名单校验恢复（此前整段注释且 BlackListException 不存在）。
 *
 * @author qihang
 */
@Component
public class SysLoginService
{
    /** 密码错误达到该次数后锁定账号 */
    private static final int MAX_PWD_ERR_COUNT = 5;

    /** 锁定时长（分钟） */
    private static final long PWD_ERR_LOCK_MINUTES = 15;

    /** 密码错误计数 Key 前缀 */
    private static final String PWD_ERR_KEY_PREFIX = "login:pwd_err:";

    @Autowired
    private TokenService tokenService;

    @Resource
    private AuthenticationManager authenticationManager;

    @Autowired
    private RedisCache redisCache;

    @Autowired
    private ISysUserService userService;

    @Autowired
    private SysConfigService configService;

    @Autowired
    private AsyncManager asyncManager;

    /**
     * 登录验证
     *
     * @param username 用户名
     * @param password 密码
     * @param code 验证码
     * @param uuid 唯一标识
     * @return 结果
     */
    public String login(String username, String password, String code, String uuid)
    {
        // 防爆破前置：账号已被锁定时直接拒绝，不再校验验证码/密码
        checkPwdErrLock(username);
        // 验证码校验
        validateCaptcha(username, code, uuid);
        // 登录前置校验
        loginPreCheck(username, password);
        // 用户验证
        Authentication authentication = null;
        try
        {
            UsernamePasswordAuthenticationToken authenticationToken = new UsernamePasswordAuthenticationToken(username, password);
            AuthenticationContextHolder.setContext(authenticationToken);
            // 该方法会去调用UserDetailsServiceImpl.loadUserByUsername
            authentication = authenticationManager.authenticate(authenticationToken);
        }
        catch (Exception e)
        {
            if (e instanceof BadCredentialsException)
            {
                recordPwdErr(username);
                asyncManager.recordLogininfor(username, Constants.LOGIN_FAIL, "用户密码错误");
                throw new UserPasswordNotMatchException();
            }
            else
            {
                asyncManager.recordLogininfor(username, Constants.LOGIN_FAIL, e.getMessage());
                throw new ServiceException(e.getMessage());
            }
        }
        finally
        {
            AuthenticationContextHolder.clearContext();
        }
        // 登录成功：清除错误计数并记录审计日志
        clearPwdErr(username);
        asyncManager.recordLogininfor(username, Constants.LOGIN_SUCCESS, "登录成功");
        LoginUser loginUser = (LoginUser) authentication.getPrincipal();
        recordLoginInfo(loginUser.getUserId());
        // 生成token
        return tokenService.createToken(loginUser);
    }


    /**
     * 登录前置校验
     * @param username 用户名
     * @param password 用户密码
     */
    public void loginPreCheck(String username, String password)
    {
        // 用户名或密码为空 错误
        if (StringUtils.isEmpty(username) || StringUtils.isEmpty(password))
        {
            asyncManager.recordLogininfor(username, Constants.LOGIN_FAIL, "用户名/密码不能为空");
            throw new UserNotExistsException();
        }
        // 密码如果不在指定范围内 错误
        if (password.length() < UserConstants.PASSWORD_MIN_LENGTH
                || password.length() > UserConstants.PASSWORD_MAX_LENGTH)
        {
            recordPwdErr(username);
            asyncManager.recordLogininfor(username, Constants.LOGIN_FAIL, "密码长度不在允许范围");
            throw new UserPasswordNotMatchException();
        }
        // 用户名不在指定范围内 错误
        if (username.length() < UserConstants.USERNAME_MIN_LENGTH
                || username.length() > UserConstants.USERNAME_MAX_LENGTH)
        {
            asyncManager.recordLogininfor(username, Constants.LOGIN_FAIL, "用户名长度不在允许范围");
            throw new UserPasswordNotMatchException();
        }
        // IP黑名单校验（sys_config 中 sys.login.blackIPList，为空表示不启用）
        String blackStr = selectBlackIPList();
        if (StringUtils.isNotEmpty(blackStr) && IpUtils.isMatchedIp(blackStr, IpUtils.getIpAddr()))
        {
            asyncManager.recordLogininfor(username, Constants.LOGIN_FAIL, "IP被加入黑名单");
            throw new BlackListException();
        }
    }

    /**
     * 校验验证码
     *
     * @param username 用户名
     * @param code 验证码
     * @param uuid 唯一标识
     * @return 结果
     */
    public void validateCaptcha(String username, String code, String uuid)
    {
        boolean captchaEnabled = configService.selectCaptchaEnabled();
        if (captchaEnabled)
        {
            String verifyKey = CacheConstants.CAPTCHA_CODE_KEY + StringUtils.nvl(uuid, "");
            String captcha = redisCache.getCacheObject(verifyKey);

            redisCache.deleteObject(verifyKey);
            if (captcha == null)
            {
                asyncManager.recordLogininfor(username, Constants.LOGIN_FAIL, "验证码已过期");
                throw new CaptchaExpireException();
            }
            if (!code.equalsIgnoreCase(captcha))
            {
                asyncManager.recordLogininfor(username, Constants.LOGIN_FAIL, "验证码错误");
                throw new CaptchaException();
            }
        }
    }

    /**
     * 记录登录信息
     *
     * @param userId 用户ID
     */
    public void recordLoginInfo(Long userId)
    {
        SysUser sysUser = new SysUser();
        sysUser.setUserId(userId);
        sysUser.setLoginIp(IpUtils.getIpAddr());
        sysUser.setLoginDate(DateUtils.getNowDate());
        userService.updateUserProfile(sysUser);
    }

    // ==================== P1-2 新增：防爆破 ====================

    /**
     * 校验账号是否因密码错误次数过多被锁定。
     */
    private void checkPwdErrLock(String username)
    {
        Integer count = redisCache.getCacheObject(pwdErrKey(username));
        if (count != null && count >= MAX_PWD_ERR_COUNT)
        {
            asyncManager.recordLogininfor(username, Constants.LOGIN_FAIL, "账号已锁定（密码错误过多）");
            throw new ServiceException("密码错误次数过多，账号已锁定" + PWD_ERR_LOCK_MINUTES + "分钟，请稍后再试");
        }
    }

    /**
     * 记录一次密码错误；首次错误时设置锁定期，到期自动解除。
     */
    private void recordPwdErr(String username)
    {
        String key = pwdErrKey(username);
        Long count = redisCache.redisTemplate.opsForValue().increment(key, 1);
        if (count != null && count <= 1L)
        {
            redisCache.expire(key, PWD_ERR_LOCK_MINUTES, TimeUnit.MINUTES);
        }
    }

    /**
     * 登录成功，清除错误计数。
     */
    private void clearPwdErr(String username)
    {
        redisCache.deleteObject(pwdErrKey(username));
    }

    private String pwdErrKey(String username)
    {
        return PWD_ERR_KEY_PREFIX + username;
    }

    /**
     * 读取 IP 黑名单配置（sys.login.blackIPList，逗号/IP段分隔；未配置返回 null）。
     */
    private String selectBlackIPList()
    {
        SysConfig config = configService.lambdaQuery()
                .eq(SysConfig::getConfigKey, "sys.login.blackIPList")
                .one();
        return config == null ? null : config.getConfigValue();
    }
}
