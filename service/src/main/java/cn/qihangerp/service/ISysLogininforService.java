package cn.qihangerp.service;

import cn.qihangerp.model.entity.SysLogininfor;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * 系统访问记录 业务层
 *
 * @author qihang
 */
public interface ISysLogininforService extends IService<SysLogininfor>
{
    /**
     * 记录一条登录日志（异步调用方保证，方法本身同步落库）。
     */
    void insertLogininfor(SysLogininfor logininfor);
}
