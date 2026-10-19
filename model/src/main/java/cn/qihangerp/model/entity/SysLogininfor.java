package cn.qihangerp.model.entity;

import java.time.LocalDateTime;

/**
 * 系统访问记录 sys_logininfor
 *
 * P1-2 修复：登录审计基础设施此前整仓不存在（AsyncManager/SysLogininfor 均无），
 * SysLoginService 中的审计调用全部被注释，登录失败无痕迹、无法排查撞库。
 *
 * @author qihang
 */
public class SysLogininfor
{
    /** 访问ID */
    private Long infoId;

    /** 用户账号 */
    private String userName;

    /** 登录IP地址 */
    private String ipaddr;

    /** 登录地点 */
    private String loginLocation;

    /** 浏览器类型 */
    private String browser;

    /** 操作系统 */
    private String os;

    /** 状态（Success 成功 / Error 失败，同 Constants.LOGIN_*） */
    private String status;

    /** 提示消息（失败原因） */
    private String msg;

    /** 访问时间 */
    private LocalDateTime loginTime;

    public Long getInfoId()
    {
        return infoId;
    }

    public void setInfoId(Long infoId)
    {
        this.infoId = infoId;
    }

    public String getUserName()
    {
        return userName;
    }

    public void setUserName(String userName)
    {
        this.userName = userName;
    }

    public String getIpaddr()
    {
        return ipaddr;
    }

    public void setIpaddr(String ipaddr)
    {
        this.ipaddr = ipaddr;
    }

    public String getLoginLocation()
    {
        return loginLocation;
    }

    public void setLoginLocation(String loginLocation)
    {
        this.loginLocation = loginLocation;
    }

    public String getBrowser()
    {
        return browser;
    }

    public void setBrowser(String browser)
    {
        this.browser = browser;
    }

    public String getOs()
    {
        return os;
    }

    public void setOs(String os)
    {
        this.os = os;
    }

    public String getStatus()
    {
        return status;
    }

    public void setStatus(String status)
    {
        this.status = status;
    }

    public String getMsg()
    {
        return msg;
    }

    public void setMsg(String msg)
    {
        this.msg = msg;
    }

    public LocalDateTime getLoginTime()
    {
        return loginTime;
    }

    public void setLoginTime(LocalDateTime loginTime)
    {
        this.loginTime = loginTime;
    }
}
