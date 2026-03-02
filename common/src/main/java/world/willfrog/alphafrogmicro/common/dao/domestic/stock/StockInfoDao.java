package world.willfrog.alphafrogmicro.common.dao.domestic.stock;

import org.apache.ibatis.annotations.*;
import org.springframework.cache.annotation.Cacheable;
import world.willfrog.alphafrogmicro.common.pojo.domestic.stock.StockInfo;

import java.util.List;

@Mapper
public interface StockInfoDao {

    @Insert("INSERT INTO alphafrog_stock_info (ts_code, symbol, name, area, industry, fullname, enname, cnspell, market," +
            " exchange, curr_type, list_status, list_date, delist_date, is_hs, act_name, act_ent_type) " +
            "VALUES (#{tsCode}, #{symbol}, #{name}, #{area}, #{industry}, #{fullName}, #{enName}, #{cnspell}, #{market}," +
            " #{exchange}, #{currType}, #{listStatus}, #{listDate}, #{delistDate}, #{isHs}, #{actName}, #{actEntType})" +
            "ON CONFLICT (ts_code, symbol) DO NOTHING")
    Integer insertStockInfo(StockInfo stockInfo);

    @Select("SELECT * FROM alphafrog_stock_info WHERE ts_code like CONCAT('%', #{tsCode}, '%') LIMIT #{limit} OFFSET #{offset}")
    @Results({
            @Result(column = "id", property = "stockInfoId"),
            @Result(column = "ts_code", property = "tsCode"),
            @Result(column = "symbol", property = "symbol"),
            @Result(column = "name", property = "name"),
            @Result(column = "area", property = "area"),
            @Result(column = "industry", property = "industry"),
            @Result(column = "fullname", property = "fullName"),
            @Result(column = "enname", property = "enName"),
            @Result(column = "cnspell", property = "cnspell"),
            @Result(column = "market", property = "market"),
            @Result(column = "exchange", property = "exchange"),
            @Result(column = "curr_type", property = "currType"),
            @Result(column = "list_status", property = "listStatus"),
            @Result(column = "list_date", property = "listDate"),
            @Result(column = "delist_date", property = "delistDate"),
            @Result(column = "is_hs", property = "isHs"),
            @Result(column = "act_name", property = "actName"),
            @Result(column = "act_ent_type", property = "actEntType")
    })
    @Cacheable(value = "stockInfoCache", key = "'stock:info:' + #tsCode + ':' + #limit + ':' + #offset", cacheManager = "stockInfoCacheManager")
    List<StockInfo> getStockInfoByTsCode(@Param("tsCode") String tsCode, @Param("limit") int limit, @Param("offset") int offset);

    @Select("SELECT * FROM alphafrog_stock_info WHERE fullname like CONCAT('%', #{fullName}, '%') LIMIT #{limit} OFFSET #{offset}")
    List<StockInfo> getStockInfoByFullName(@Param("fullName") String fullName, @Param("limit") int limit, @Param("offset") int offset);

    @Select("SELECT * FROM alphafrog_stock_info WHERE name like CONCAT('%', #{name}, '%') LIMIT #{limit} OFFSET #{offset}")
    List<StockInfo> getStockInfoByName(@Param("name") String name, @Param("limit") int limit, @Param("offset") int offset);


    @Select("SELECT ts_code FROM alphafrog_stock_info OFFSET #{offset} LIMIT #{limit}")
    List<String> getStockTsCode(@Param("offset") int offset, @Param("limit") int limit);

    @Select("SELECT COUNT(*) FROM alphafrog_stock_info")
    int getStockInfoCount();

    @Select("SELECT * FROM alphafrog_stock_info ORDER BY ts_code LIMIT #{limit} OFFSET #{offset}")
    @Results({
            @Result(column = "id", property = "stockInfoId"),
            @Result(column = "ts_code", property = "tsCode"),
            @Result(column = "symbol", property = "symbol"),
            @Result(column = "name", property = "name"),
            @Result(column = "area", property = "area"),
            @Result(column = "industry", property = "industry"),
            @Result(column = "fullname", property = "fullName"),
            @Result(column = "enname", property = "enName"),
            @Result(column = "cnspell", property = "cnspell"),
            @Result(column = "market", property = "market"),
            @Result(column = "exchange", property = "exchange"),
            @Result(column = "curr_type", property = "currType"),
            @Result(column = "list_status", property = "listStatus"),
            @Result(column = "list_date", property = "listDate"),
            @Result(column = "delist_date", property = "delistDate"),
            @Result(column = "is_hs", property = "isHs"),
            @Result(column = "act_name", property = "actName"),
            @Result(column = "act_ent_type", property = "actEntType")
    })
    List<StockInfo> getAllStockInfo(@Param("offset") int offset, @Param("limit") int limit);

}
