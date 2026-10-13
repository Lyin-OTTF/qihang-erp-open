package cn.qihangerp.agent.trace;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * Agent Trace Mapper。
 */
@Mapper
public interface TraceMapper extends BaseMapper<TraceEntry> {

    /**
     * 按traceId查询完整调用链（按创建时间排序）。
     */
    @Select("SELECT * FROM agent_trace WHERE trace_id = #{traceId} ORDER BY created_at ASC")
    List<TraceEntry> findByTraceId(@Param("traceId") String traceId);

    /**
     * 按商户ID查询最近的trace（分页）。
     */
    @Select("SELECT * FROM agent_trace WHERE merchant_id = #{merchantId} ORDER BY created_at DESC LIMIT #{limit}")
    List<TraceEntry> findRecentByMerchant(@Param("merchantId") Long merchantId, @Param("limit") int limit);

    /**
     * 统计指定traceId的调用次数。
     */
    @Select("SELECT COUNT(*) FROM agent_trace WHERE trace_id = #{traceId}")
    int countByTraceId(@Param("traceId") String traceId);
}
