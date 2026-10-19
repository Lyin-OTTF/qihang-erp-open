package cn.qihangerp.service.impl;

import cn.qihangerp.mapper.SysLogininforMapper;
import cn.qihangerp.model.entity.SysLogininfor;
import cn.qihangerp.service.ISysLogininforService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * 系统访问记录 业务层实现
 *
 * @author qihang
 */
@Service
public class SysLogininforServiceImpl extends ServiceImpl<SysLogininforMapper, SysLogininfor>
        implements ISysLogininforService
{
    @Override
    public void insertLogininfor(SysLogininfor logininfor)
    {
        this.save(logininfor);
    }
}
