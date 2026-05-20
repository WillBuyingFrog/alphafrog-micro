package world.willfrog.alphafrogmicro.common.dao.domestic.etf;

import org.apache.ibatis.annotations.*;
import world.willfrog.alphafrogmicro.common.pojo.domestic.etf.EtfInfo;

import java.util.List;

@Mapper
public interface EtfInfoDao {

    @Insert("INSERT INTO alphafrog_domestic_etf (ts_code, name, full_name, exchange, mgr_name, list_status, " +
            "etf_type, index_code, index_name, list_date, setup_date, extended) " +
            "VALUES (#{tsCode}, #{name}, #{fullName}, #{exchange}, #{mgrName}, #{listStatus}, " +
            "#{etfType}, #{indexCode}, #{indexName}, #{listDate}, #{setupDate}, #{extended}) " +
            "ON CONFLICT (ts_code) DO NOTHING")
    int insertEtfInfo(EtfInfo etfInfo);

    @Select("SELECT * FROM alphafrog_domestic_etf WHERE ts_code = #{tsCode}")
    @Results(id = "etfInfoMap", value = {
            @Result(property = "id", column = "id"),
            @Result(property = "tsCode", column = "ts_code"),
            @Result(property = "name", column = "name"),
            @Result(property = "fullName", column = "full_name"),
            @Result(property = "exchange", column = "exchange"),
            @Result(property = "mgrName", column = "mgr_name"),
            @Result(property = "listStatus", column = "list_status"),
            @Result(property = "etfType", column = "etf_type"),
            @Result(property = "indexCode", column = "index_code"),
            @Result(property = "indexName", column = "index_name"),
            @Result(property = "listDate", column = "list_date"),
            @Result(property = "setupDate", column = "setup_date"),
            @Result(property = "extended", column = "extended")
    })
    EtfInfo getByTsCode(@Param("tsCode") String tsCode);

    @Select("SELECT * FROM alphafrog_domestic_etf WHERE name ILIKE CONCAT('%', #{keyword}, '%') " +
            "OR ts_code ILIKE CONCAT('%', #{keyword}, '%') LIMIT #{limit}")
    @ResultMap("etfInfoMap")
    List<EtfInfo> searchByKeyword(@Param("keyword") String keyword, @Param("limit") int limit);

    @Select("SELECT * FROM alphafrog_domestic_etf WHERE index_code = #{indexCode} LIMIT #{limit}")
    @ResultMap("etfInfoMap")
    List<EtfInfo> getByIndexCode(@Param("indexCode") String indexCode, @Param("limit") int limit);
}
