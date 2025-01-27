package world.willfrog.alphafrogmicro.common.dao.domestic.stock;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import world.willfrog.alphafrogmicro.common.pojo.domestic.stock.StockInfo;

import java.util.List;

@Mapper
public interface StockInfoDao {

    @Select("INSERT INTO alphafrog_stock_info (ts_code, symbol, name, area, industry, fullname, enname, cnspell, market," +
            " exchange, curr_type, list_status, list_date, delist_date, is_hs, act_name, act_ent_type) " +
            "VALUES (#{tsCode}, #{symbol}, #{name}, #{area}, #{industry}, #{fullName}, #{enName}, #{cnspell}, #{market}," +
            " #{exchange}, #{currType}, #{listStatus}, #{listDate}, #{delistDate}, #{isHs}, #{actName}, #{actEntType})" +
            "ON CONFLICT (ts_code, symbol) DO NOTHING")
    Integer insertStockInfo(StockInfo stockInfo);

    @Select("SELECT * FROM alphafrog_stock_info WHERE ts_code like '%${tsCode}%'")
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
    List<StockInfo> getStockInfoByTsCode(String tsCode);

    @Select("SELECT * FROM alphafrog_stock_info WHERE fullname like '%${fullName}%'")
    List<StockInfo> getStockInfoByFullName(String fullName);

    @Select("SELECT * FROM alphafrog_stock_info WHERE name like '%${name}%'")
    List<StockInfo> getStockInfoByName(String Name);


}
