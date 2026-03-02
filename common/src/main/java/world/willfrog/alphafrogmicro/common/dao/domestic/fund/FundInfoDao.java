package world.willfrog.alphafrogmicro.common.dao.domestic.fund;

import org.apache.ibatis.annotations.*;
import world.willfrog.alphafrogmicro.common.pojo.domestic.fund.FundInfo;

import java.util.List;

@Mapper
public interface FundInfoDao {

    @Insert("INSERT INTO alphafrog_fund_info (ts_code, name, management, custodian, fund_type, found_date, due_date, " +
            "list_date, issue_date, delist_date, issue_amount, m_fee, c_fee, duration_year, p_value, min_amount, " +
            "exp_return, benchmark, status, invest_type, type, trustee, purc_startdate, redm_startdate, market) " +
            "VALUES (#{tsCode}, #{name}, #{management}, #{custodian}, #{fundType}, #{foundDate}, #{dueDate}, " +
            "#{listDate}, #{issueDate}, #{delistDate}, #{issueAmount}, #{mFee}, #{cFee}, #{durationYear}, #{pValue}, " +
            "#{minAmount}, #{expReturn}, #{benchmark}, #{status}, #{investType}, #{type}, #{trustee}, " +
            "#{purcStartDate}, #{redmStartDate}, #{market}) " +
            "ON CONFLICT (ts_code) DO NOTHING")
    int insertFundInfo(FundInfo fundInfo);

    @Select("SELECT * FROM alphafrog_fund_info WHERE ts_code like CONCAT('%', #{tsCode}, '%') LIMIT #{limit} OFFSET #{offset}")
    @Results({
            @Result(column = "ts_code", property = "tsCode"),
            @Result(column = "fund_type", property = "fundType"),
            @Result(column = "found_date", property = "foundDate"),
            @Result(column = "due_date", property = "dueDate"),
            @Result(column = "list_date", property = "listDate"),
            @Result(column = "issue_date", property = "issueDate"),
            @Result(column = "delist_date", property = "delistDate"),
            @Result(column = "issue_amount", property = "issueAmount"),
            @Result(column = "m_fee", property = "mFee"),
            @Result(column = "c_fee", property = "cFee"),
            @Result(column = "duration_year", property = "durationYear"),
            @Result(column = "p_value", property = "pValue"),
            @Result(column = "min_amount", property = "minAmount"),
            @Result(column = "exp_return", property = "expReturn"),
            @Result(column = "purc_startdate", property = "purcStartDate"),
            @Result(column = "redm_startdate", property = "redmStartDate")
    })
    List<FundInfo> getFundInfoByTsCode(@Param("tsCode") String tsCode, @Param("limit") int limit, @Param("offset") int offset);

    @Select("SELECT * FROM alphafrog_fund_info WHERE name like CONCAT('%', #{name}, '%') LIMIT #{limit} OFFSET #{offset}")
    List<FundInfo> getFundInfoByName(@Param("name") String name, @Param("limit") int limit, @Param("offset") int offset);

    @Select("SELECT ts_code FROM alphafrog_fund_info LIMIT #{limit} OFFSET #{offset}")
    List<String> getFundTsCode(@Param("offset") int offset, @Param("limit") int limit);

    @Select("SELECT COUNT(*) FROM alphafrog_fund_info")
    int getFundInfoCount();

    @Select("SELECT * FROM alphafrog_fund_info ORDER BY ts_code LIMIT #{limit} OFFSET #{offset}")
    @Results({
            @Result(column = "ts_code", property = "tsCode"),
            @Result(column = "fund_type", property = "fundType"),
            @Result(column = "found_date", property = "foundDate"),
            @Result(column = "due_date", property = "dueDate"),
            @Result(column = "list_date", property = "listDate"),
            @Result(column = "issue_date", property = "issueDate"),
            @Result(column = "delist_date", property = "delistDate"),
            @Result(column = "issue_amount", property = "issueAmount"),
            @Result(column = "m_fee", property = "mFee"),
            @Result(column = "c_fee", property = "cFee"),
            @Result(column = "duration_year", property = "durationYear"),
            @Result(column = "p_value", property = "pValue"),
            @Result(column = "min_amount", property = "minAmount"),
            @Result(column = "exp_return", property = "expReturn"),
            @Result(column = "purc_startdate", property = "purcStartDate"),
            @Result(column = "redm_startdate", property = "redmStartDate")
    })
    List<FundInfo> getAllFundInfo(@Param("offset") int offset, @Param("limit") int limit);

}
