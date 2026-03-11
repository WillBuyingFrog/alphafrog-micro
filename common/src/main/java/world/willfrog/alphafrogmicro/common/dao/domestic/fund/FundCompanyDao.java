package world.willfrog.alphafrogmicro.common.dao.domestic.fund;

import org.apache.ibatis.annotations.*;
import world.willfrog.alphafrogmicro.common.pojo.domestic.fund.FundCompany;

import java.util.List;

@Mapper
public interface FundCompanyDao {

    @Insert("INSERT INTO alphafrog_fund_company (name, shortname, short_enname, province, city, address, " +
            "phone, office, website, chairman, manager, reg_capital, setup_date, end_date, employees, " +
            "main_business, org_code, credit_code, extended) " +
            "VALUES (#{name}, #{shortname}, #{shortEnname}, #{province}, #{city}, #{address}, " +
            "#{phone}, #{office}, #{website}, #{chairman}, #{manager}, #{regCapital}, #{setupDate}, #{endDate}, " +
            "#{employees}, #{mainBusiness}, #{orgCode}, #{creditCode}, #{extended}) " +
            "ON CONFLICT (credit_code) DO NOTHING")
    int insertFundCompany(FundCompany fundCompany);

    @Select("SELECT * FROM alphafrog_fund_company")
    @Results({
            @Result(property = "id", column = "id"),
            @Result(property = "name", column = "name"),
            @Result(property = "shortname", column = "shortname"),
            @Result(property = "shortEnname", column = "short_enname"),
            @Result(property = "province", column = "province"),
            @Result(property = "city", column = "city"),
            @Result(property = "address", column = "address"),
            @Result(property = "phone", column = "phone"),
            @Result(property = "office", column = "office"),
            @Result(property = "website", column = "website"),
            @Result(property = "chairman", column = "chairman"),
            @Result(property = "manager", column = "manager"),
            @Result(property = "regCapital", column = "reg_capital"),
            @Result(property = "setupDate", column = "setup_date"),
            @Result(property = "endDate", column = "end_date"),
            @Result(property = "employees", column = "employees"),
            @Result(property = "mainBusiness", column = "main_business"),
            @Result(property = "orgCode", column = "org_code"),
            @Result(property = "creditCode", column = "credit_code"),
            @Result(property = "extended", column = "extended")
    })
    List<FundCompany> getAll();

    @Delete("DELETE FROM alphafrog_fund_company")
    int deleteAll();

    @Delete("DELETE FROM alphafrog_fund_company WHERE credit_code = #{creditCode}")
    int deleteByCreditCode(@Param("creditCode") String creditCode);
}
