package cn.qihangerp.mapper;

import cn.qihangerp.model.entity.SysLogininfor;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 系统访问记录 数据层
 *
 * @author qihang
 */
public interface SysLogininforMapper extends BaseMapper<SysLogininfor>
{
    /**
     * 按条件查询登录日志（用户名/IP 模糊，状态精确）。
     */
    List<SysLogininfor> selectLogininforList(@Param("bo") SysLogininfor bo);
}
