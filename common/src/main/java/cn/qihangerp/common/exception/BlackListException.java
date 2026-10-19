package cn.qihangerp.common.exception;

/**
 * IP黑名单异常。
 *
 * P1-2 修复：SysLoginService.loginPreCheck 的 IP 黑名单校验整段被注释，
 * 且其依赖的 BlackListException 此前不存在，无法直接恢复。
 *
 * @author qihang
 */
public class BlackListException extends UserException
{
    private static final long serialVersionUID = 1L;

    public BlackListException()
    {
        super("blocked", null, "IP已被加入系统黑名单，禁止登录");
    }
}
