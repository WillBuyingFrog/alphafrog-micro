package world.willfrog.alphafrogmicro.domestic.fund;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.apache.dubbo.config.annotation.DubboService;
import world.willfrog.alphafrogmicro.common.dao.domestic.fund.FundInfoDao;
import world.willfrog.alphafrogmicro.common.dao.domestic.fund.FundNavDao;
import world.willfrog.alphafrogmicro.common.dao.domestic.fund.FundPortfolioDao;
import world.willfrog.alphafrogmicro.common.pojo.domestic.fund.FundInfo;
import world.willfrog.alphafrogmicro.common.pojo.domestic.fund.FundNav;
import world.willfrog.alphafrogmicro.common.pojo.domestic.fund.FundPortfolio;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticFund.*;
import world.willfrog.alphafrogmicro.domestic.idl.DubboDomesticFundServiceTriple.DomesticFundServiceImplBase;

import java.util.List;
import java.util.ArrayList;

@DubboService
@Service
@Slf4j
public class DomesticFundServiceImpl extends DomesticFundServiceImplBase {

    private final FundNavDao fundNavDao;
    private final FundInfoDao fundInfoDao;
    private final FundPortfolioDao fundPortfolioDao;

    public DomesticFundServiceImpl(FundNavDao fundNavDao, FundInfoDao fundInfoDao, FundPortfolioDao fundPortfolioDao) {
        this.fundNavDao = fundNavDao;
        this.fundInfoDao = fundInfoDao;
        this.fundPortfolioDao = fundPortfolioDao;
    }


    @Override
    public DomesticFundNavsByTsCodeAndDateRangeResponse getDomesticFundNavsByTsCodeAndDateRange(
            DomesticFundNavsByTsCodeAndDateRangeRequest request
    ) {
        String tsCode = request.getTsCode();
        long startDateTimestamp = request.getStartDateTimestamp();
        long endDateTimestamp = request.getEndDateTimestamp();

        List<FundNav> fundNavList = null;

        try{
            fundNavList = fundNavDao.getFundNavsByTsCodeAndDateRange(tsCode,
                                            startDateTimestamp, endDateTimestamp);
        } catch (Exception e) {
            log.error("Error occurred while getting fund navs", e);
        }

        // 创建响应对象
        DomesticFundNavsByTsCodeAndDateRangeResponse.Builder responseBuilder =
                DomesticFundNavsByTsCodeAndDateRangeResponse.newBuilder();

        if(fundNavList != null) {
            try {
                for (FundNav fundNav : fundNavList) {
                    DomesticFundNavItem.Builder itemBuilder = DomesticFundNavItem.newBuilder()
                            .setTsCode(fundNav.getTsCode()).setAnnDate(fundNav.getAnnDate())
                            .setNavDate(fundNav.getNavDate()).setUnitNav(fundNav.getUnitNav())
                            .setAdjNav(fundNav.getAdjNav());

                    if(fundNav.getAccumNav() != null){
                        itemBuilder.setAccumNav(fundNav.getAccumNav());
                    }
                    if(fundNav.getNetAsset() != null){
                        itemBuilder.setNetAsset(fundNav.getNetAsset());
                    }
                    if(fundNav.getTotalNetAsset() != null) {
                        itemBuilder.setTotalNetAsset(fundNav.getTotalNetAsset());
                    }
                    if(fundNav.getAccumDiv() != null) {
                        itemBuilder.setAccumDiv(fundNav.getAccumDiv());
                    }
                    responseBuilder.addItems(itemBuilder.build());
                }
                return responseBuilder.build();
            } catch (Exception e) {
                log.error("Error occurred while converting DAO response object to protobuf object", e);
                return null;
            }
        } else {
            return null;
        }
    }

    @Override
    public DomesticFundInfoByTsCodeResponse getDomesticFundInfoByTsCode(DomesticFundInfoByTsCodeRequest request) {

        String tsCode = request.getTsCode();
        List<FundInfo> fundInfoList;
        List<DomesticFundInfoFullItem> items = new ArrayList<>();

        try {
            fundInfoList = fundInfoDao.getFundInfoByTsCode(tsCode);

            for(FundInfo fundInfo : fundInfoList) {
                log.info("FundInfo: {}", fundInfo);
                DomesticFundInfoFullItem.Builder itemBuilder = DomesticFundInfoFullItem.newBuilder()
                        .setTsCode(fundInfo.getTsCode()).setName(fundInfo.getName());

                // 设置optional字段，并检查是否为null
                if (fundInfo.getManagement() != null) {
                    itemBuilder.setManagement(fundInfo.getManagement());
                }
                if (fundInfo.getCustodian() != null) {
                    itemBuilder.setCustodian(fundInfo.getCustodian());
                }
                if (fundInfo.getFundType() != null) {
                    itemBuilder.setFundType(fundInfo.getFundType());
                }
                if (fundInfo.getFoundDate() != null) {
                    itemBuilder.setFoundDate(fundInfo.getFoundDate());
                }
                if (fundInfo.getDueDate() != null) {
                    itemBuilder.setDueDate(fundInfo.getDueDate());
                }
                if (fundInfo.getListDate() != null) {
                    itemBuilder.setListDate(fundInfo.getListDate());
                }
                if (fundInfo.getIssueDate() != null) {
                    itemBuilder.setIssueDate(fundInfo.getIssueDate());
                }
                if (fundInfo.getDelistDate() != null) {
                    itemBuilder.setDelistDate(fundInfo.getDelistDate());
                }
                if (fundInfo.getIssueAmount() != null) {
                    itemBuilder.setIssueAmount(fundInfo.getIssueAmount());
                }
                if (fundInfo.getMFee() != null) {
                    itemBuilder.setMFee(fundInfo.getMFee());
                }
                if (fundInfo.getCFee() != null) {
                    itemBuilder.setCFee(fundInfo.getCFee());
                }
                if (fundInfo.getDurationYear() != null) {
                    itemBuilder.setDurationYear(fundInfo.getDurationYear());
                }
                if (fundInfo.getPValue() != null) {
                    itemBuilder.setPValue(fundInfo.getPValue());
                }
                if (fundInfo.getMinAmount() != null) {
                    itemBuilder.setMinAmount(fundInfo.getMinAmount());
                }
                if (fundInfo.getExpReturn() != null) {
                    itemBuilder.setExpReturn(fundInfo.getExpReturn());
                }
                if (fundInfo.getBenchmark() != null) {
                    itemBuilder.setBenchmark(fundInfo.getBenchmark());
                }
                if (fundInfo.getStatus() != null) {
                    itemBuilder.setStatus(fundInfo.getStatus());
                }
                if (fundInfo.getInvestType() != null) {
                    itemBuilder.setInvestType(fundInfo.getInvestType());
                }
                if (fundInfo.getType() != null) {
                    itemBuilder.setType(fundInfo.getType());
                }
                if (fundInfo.getTrustee() != null) {
                    itemBuilder.setTrustee(fundInfo.getTrustee());
                }
                if (fundInfo.getPurcStartDate() != null) {
                    itemBuilder.setPurcStartDate(fundInfo.getPurcStartDate());
                }
                if (fundInfo.getRedmStartDate() != null) {
                    itemBuilder.setRedmStartDate(fundInfo.getRedmStartDate());
                }
                if (fundInfo.getMarket() != null) {
                    itemBuilder.setMarket(fundInfo.getMarket());
                }

                items.add(itemBuilder.build());
            }

            return DomesticFundInfoByTsCodeResponse.newBuilder()
                    .addAllItems(items).build();

        } catch (Exception e) {
            log.error("Error occurred while getting fund info", e);
        }
        return null;
    }

    @Override
    public DomesticFundSearchResponse searchDomesticFundInfo(DomesticFundSearchRequest request) {
        
        String query = request.getQuery();

        List<FundInfo> fundInfoList = new ArrayList<>();
        List<DomesticFundInfoSimpleItem> items = new ArrayList<>();

        try{
            fundInfoList = fundInfoDao.getFundInfoByTsCode(query);
            fundInfoList.addAll(fundInfoDao.getFundInfoByName(query));
            // 去重
            fundInfoList = fundInfoList.stream()
                    .distinct()
                    .toList();
        } catch (Exception e) {
            log.error("Error occurred while searching fund info", e);
            return null;
        }
        

        try {
            for (FundInfo fundInfo : fundInfoList) {
                DomesticFundInfoSimpleItem.Builder itemBuilder = DomesticFundInfoSimpleItem.newBuilder()
                        .setTsCode(fundInfo.getTsCode())
                        .setName(fundInfo.getName());
                
                if (fundInfo.getManagement() != null) {
                    itemBuilder.setManagement(fundInfo.getManagement());
                }

                if (fundInfo.getFundType() != null) {
                    itemBuilder.setFundType(fundInfo.getFundType());
                }

                if (fundInfo.getFoundDate() != null) {
                    itemBuilder.setFoundDate(fundInfo.getFoundDate());
                }

                if (fundInfo.getBenchmark() != null) {
                    itemBuilder.setBenchmark(fundInfo.getBenchmark());
                }

                items.add(itemBuilder.build());
            }

            return DomesticFundSearchResponse.newBuilder()
                    .addAllItems(items)
                    .build();
        } catch (Exception e) {
            log.error("Error occurred while converting DAO response object to protobuf object", e);
            return null;
        }
    }

    public DomesticFundPortfolioByTsCodeAndDateRangeResponse getDomesticFundPortfolioByTsCodeAndDateRange(
            DomesticFundPortfolioByTsCodeAndDateRangeRequest request
    ) {
        String tsCode = request.getTsCode();
        long startDateTimestamp = request.getStartDateTimestamp();
        long endDateTimestamp = request.getEndDateTimestamp();

        List<FundPortfolio> fundPortfolioList = null;

        try{
            fundPortfolioList = fundPortfolioDao.getFundPortfolioByTsCodeAndDateRange(tsCode,
                    startDateTimestamp, endDateTimestamp);
        } catch (Exception e) {
            log.error("Error occurred while getting fund portfolio", e);
        }

        // 创建响应对象
        DomesticFundPortfolioByTsCodeAndDateRangeResponse.Builder responseBuilder =
                DomesticFundPortfolioByTsCodeAndDateRangeResponse.newBuilder();

        if(fundPortfolioList != null) {
            try {
                for (FundPortfolio fundPortfolio : fundPortfolioList) {
                    DomesticFundPortfolioItem.Builder itemBuilder = DomesticFundPortfolioItem.newBuilder()
                            .setTsCode(fundPortfolio.getTsCode()).setAnnDate(fundPortfolio.getAnnDate())
                            .setEndDate(fundPortfolio.getEndDate()).setSymbol(fundPortfolio.getSymbol())
                            .setMkv(fundPortfolio.getMkv()).setAmount(fundPortfolio.getAmount());

                    if (fundPortfolio.getStkMkvRatio() != null) {
                        itemBuilder.setSktMkvRatio(fundPortfolio.getStkMkvRatio());
                    }

                    if (fundPortfolio.getStkFloatRatio() != null) {
                        itemBuilder.setSktFloatRatio(fundPortfolio.getStkFloatRatio());
                    }


                    responseBuilder.addItems(itemBuilder.build());
                }
                return responseBuilder.build();
            } catch (Exception e) {
                log.error("Error occurred while converting DAO response object to protobuf object", e);
                return null;
            }
        } else {
            return null;
        }
    }

    @Override
    public DomesticFundPortfolioBySymbolAndDateRangeResponse getDomesticFundPortfolioBySymbolAndDateRange(
            DomesticFundPortfolioBySymbolAndDateRangeRequest request
    ) {
        String symbol = request.getSymbol();
        long startDateTimestamp = request.getStartDateTimestamp();
        long endDateTimestamp = request.getEndDateTimestamp();

        List<FundPortfolio> fundPortfolioList = null;

        try{
            fundPortfolioList = fundPortfolioDao.getFundPortfolioBySymbolAndDateRange(symbol,
                    startDateTimestamp, endDateTimestamp);
        } catch (Exception e) {
            log.error("Error occurred while getting fund portfolio", e);
        }

        // 创建响应对象
        DomesticFundPortfolioBySymbolAndDateRangeResponse.Builder responseBuilder =
                DomesticFundPortfolioBySymbolAndDateRangeResponse.newBuilder();

        if(fundPortfolioList != null) {
            try {
                for (FundPortfolio fundPortfolio : fundPortfolioList) {
                    DomesticFundPortfolioItem.Builder itemBuilder = DomesticFundPortfolioItem.newBuilder()
                            .setTsCode(fundPortfolio.getTsCode()).setAnnDate(fundPortfolio.getAnnDate())
                            .setEndDate(fundPortfolio.getEndDate()).setSymbol(fundPortfolio.getSymbol())
                            .setMkv(fundPortfolio.getMkv()).setAmount(fundPortfolio.getAmount());

                    if (fundPortfolio.getStkMkvRatio() != null) {
                        itemBuilder.setSktMkvRatio(fundPortfolio.getStkMkvRatio());
                    }

                    if (fundPortfolio.getStkFloatRatio() != null) {
                        itemBuilder.setSktFloatRatio(fundPortfolio.getStkFloatRatio());
                    }

                    responseBuilder.addItems(itemBuilder.build());
                }
                return responseBuilder.build();
            } catch (Exception e) {
                log.error("Error occurred while converting DAO response object to protobuf object", e);
                return null;
            }
        } else {
            return null;
        }
    }

}
