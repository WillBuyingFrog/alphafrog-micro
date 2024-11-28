package world.willfrog.alphafrogmicro.service.domestic;

import world.willfrog.alphafrogmicro.common.pojo.domestic.fund.FundInfo;
import world.willfrog.alphafrogmicro.common.pojo.domestic.fund.FundNav;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticFund.*;

import java.util.List;

public interface DomesticFundService {
    DomesticFundNavsByTsCodeAndDateRangeResponse getDomesticFundNavsByTsCodeAndDateRange(
            DomesticFundNavsByTsCodeAndDateRangeRequest request);

    DomesticFundInfoByTsCodeResponse getDomesticFundInfoByTsCode(DomesticFundInfoByTsCodeRequest request);

    DomesticFundSearchResponse searchFundInfo(DomesticFundSearchRequest request);
}
