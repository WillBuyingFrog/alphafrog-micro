package world.willfrog.alphafrogmicro.common.dao.domestic.index;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import world.willfrog.alphafrogmicro.common.pojo.domestic.index.IndexInfo;

import java.util.List;

@Mapper
public interface IndexInfoDao {

    @Insert("INSERT INTO alphafrog_index_info (ts_code, name, fullname, market, publisher, index_type, category, base_date, base_point, list_date, weight_rule,\"desc\", exp_date) " +
            "VALUES (#{tsCode}, #{name}, #{fullName}, #{market}, #{publisher}, #{indexType}, #{category}, #{baseDate}, #{basePoint}, #{listDate}, #{weightRule}, #{desc}, #{expDate})" +
            "ON CONFLICT (ts_code) DO NOTHING")
    int insertIndexInfo(IndexInfo indexInfo);

    @Select("SELECT * FROM alphafrog_index_info WHERE ts_code like CONCAT('%', #{tsCode}, '%') LIMIT #{limit} OFFSET #{offset}")
    List<IndexInfo> getIndexInfoByTsCode(@Param("tsCode") String tsCode, @Param("limit") int limit, @Param("offset") int offset);

    @Select("SELECT * FROM alphafrog_index_info WHERE fullname like CONCAT('%', #{fullName}, '%') LIMIT #{limit} OFFSET #{offset}")
    List<IndexInfo> getIndexInfoByFullName(@Param("fullName") String fullName, @Param("limit") int limit, @Param("offset") int offset);

    @Select("SELECT * FROM alphafrog_index_info WHERE name like CONCAT('%', #{name}, '%') LIMIT #{limit} OFFSET #{offset}")
    List<IndexInfo> getIndexInfoByName(@Param("name") String name, @Param("limit") int limit, @Param("offset") int offset);

    @Select("SELECT * FROM alphafrog_index_info " +
            "WHERE ts_code like CONCAT('%', #{query}, '%') " +
            "   OR fullname like CONCAT('%', #{query}, '%') " +
            "   OR name like CONCAT('%', #{query}, '%') " +
            "ORDER BY " +
            "   CASE " +
            "     WHEN ts_code = #{query} THEN 0 " +
            "     WHEN name = #{query} THEN 1 " +
            "     WHEN fullname = #{query} THEN 2 " +
            "     WHEN ts_code like CONCAT(#{query}, '%') THEN 3 " +
            "     WHEN name like CONCAT(#{query}, '%') THEN 4 " +
            "     WHEN fullname like CONCAT(#{query}, '%') THEN 5 " +
            "     ELSE 10 " +
            "   END, " +
            "   length(name) ASC, " +
            "   ts_code ASC " +
            "LIMIT #{limit} OFFSET #{offset}")
    List<IndexInfo> searchIndexInfo(@Param("query") String query, @Param("limit") int limit, @Param("offset") int offset);

    @Select("SELECT count(*) FROM alphafrog_index_info")
    int getIndexInfoCount();

    @Select("SELECT (ts_code) from alphafrog_index_info limit #{limit} offset #{offset}")
    List<String> getAllIndexInfoTsCodes(@Param("offset") int offset,@Param("limit") int limit);

    @Select("SELECT * FROM alphafrog_index_info ORDER BY ts_code LIMIT #{limit} OFFSET #{offset}")
    List<IndexInfo> getAllIndexInfo(@Param("offset") int offset, @Param("limit") int limit);

}