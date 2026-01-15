package world.willfrog.alphafrogmicro.common.dao.domestic.common;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface DataCompletenessDao {

    /**
     * 通用查询：获取指定表在指定时间范围内的存在日期列表
     * 注意：表名和列名使用 ${} 拼接，存在 SQL 注入风险，调用方必须确保传入值为安全的常量（如表名）
     *
     * @param tableName 表名，例如 alphafrog_index_daily
     * @param codeCol   代码列名，例如 ts_code
     * @param dateCol   日期列名，例如 trade_date
     * @param code      具体的代码值，例如 000300.SH
     * @param startDate 开始时间戳
     * @param endDate   结束时间戳
     * @return 存在的日期时间戳列表
     */
    @Select("SELECT ${dateCol} FROM ${tableName} " +
            "WHERE ${codeCol} = #{code} AND ${dateCol} BETWEEN #{startDate} AND #{endDate} " +
            "ORDER BY ${dateCol}")
    List<Long> getExistingDates(@Param("tableName") String tableName,
                                @Param("codeCol") String codeCol,
                                @Param("dateCol") String dateCol,
                                @Param("code") String code,
                                @Param("startDate") long startDate,
                                @Param("endDate") long endDate);
}

