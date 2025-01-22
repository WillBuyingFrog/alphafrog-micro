package world.willfrog.alphafrogmicro.domestic.index;

import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboService;
import org.springframework.stereotype.Service;
import world.willfrog.alphafrogmicro.common.dao.domestic.index.IndexInfoDao;
import world.willfrog.alphafrogmicro.common.dao.domestic.index.IndexQuoteDao;
import world.willfrog.alphafrogmicro.common.pojo.domestic.index.IndexDaily;
import world.willfrog.alphafrogmicro.common.pojo.domestic.index.IndexInfo;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticIndex.*;
import world.willfrog.alphafrogmicro.domestic.idl.DubboDomesticIndexServiceTriple.DomesticIndexServiceImplBase;

import java.util.List;

@DubboService
@Service
@Slf4j
public class DomesticIndexServiceImpl extends DomesticIndexServiceImplBase {

    private final IndexInfoDao indexInfoDao;
    private final IndexQuoteDao indexQuoteDao;


    public DomesticIndexServiceImpl(IndexInfoDao indexInfoDao,
                                    IndexQuoteDao indexQuoteDao) {
        this.indexInfoDao = indexInfoDao;
        this.indexQuoteDao = indexQuoteDao;
    }


    @Override
    public DomesticIndexInfoByTsCodeResponse getDomesticIndexInfoByTsCode(DomesticIndexInfoByTsCodeRequest request) {

        List<IndexInfo> indexInfoList = indexInfoDao.getIndexInfoByTsCode(request.getTsCode());

        if (indexInfoList.isEmpty()){
            return null;
        }

        DomesticIndexInfoByTsCodeResponse.Builder responseBuilder = DomesticIndexInfoByTsCodeResponse.newBuilder();

        IndexInfo indexInfo = indexInfoList.get(0);
        DomesticIndexInfoFullItem.Builder builder = DomesticIndexInfoFullItem.newBuilder();
        builder.setTsCode(indexInfo.getTsCode()).setName(indexInfo.getName())
                .setFullname(indexInfo.getFullName()).setMarket(indexInfo.getMarket());
        if (indexInfo.getPublisher() != null) {
            builder.setPublisher(indexInfo.getPublisher());
        }
        if (indexInfo.getIndexType() != null) {
            builder.setIndexType(indexInfo.getIndexType());
        }
        if (indexInfo.getCategory() != null) {
            builder.setCategory(indexInfo.getCategory());
        }
        if (indexInfo.getBaseDate() != null) {
            builder.setBaseDate(indexInfo.getBaseDate());
        }
        if (indexInfo.getBasePoint() != null) {
            builder.setBasePoint(indexInfo.getBasePoint());
        }
        if (indexInfo.getListDate() != null) {
            builder.setListDate(indexInfo.getListDate());
        }
        if (indexInfo.getWeightRule() != null) {
            builder.setWeightRule(indexInfo.getWeightRule());
        }
        if (indexInfo.getDesc() != null) {
            builder.setDesc(indexInfo.getDesc());
        }
        if (indexInfo.getExpDate() != null) {
            builder.setExpDate(indexInfo.getExpDate());
        }
        responseBuilder.setItem(builder.build());

        return responseBuilder.build();
    }

    @Override
    public DomesticIndexSearchResponse searchDomesticIndex(DomesticIndexSearchRequest request) {

        String query = request.getQuery();

        List<IndexInfo> indexInfoList = indexInfoDao.getIndexInfoByTsCode(query);

        indexInfoList.addAll(indexInfoDao.getIndexInfoByFullName(query));

        indexInfoList.addAll(indexInfoDao.getIndexInfoByName(query));

        indexInfoList = indexInfoList.stream()
                .distinct()
                .toList();

        DomesticIndexSearchResponse.Builder responseBuilder = DomesticIndexSearchResponse.newBuilder();

        for(IndexInfo indexInfo : indexInfoList) {
            DomesticIndexInfoSimpleItem.Builder itemBuilder = DomesticIndexInfoSimpleItem.newBuilder()
                    .setTsCode(indexInfo.getTsCode()).setName(indexInfo.getName())
                    .setFullname(indexInfo.getFullName()).setMarket(indexInfo.getMarket());

            responseBuilder.addItems(itemBuilder.build());
        }

        return responseBuilder.build();
    }

    @Override
    public DomesticIndexDailyByTsCodeAndDateRangeResponse getDomesticIndexDailyByTsCodeAndDateRange(
            DomesticIndexDailyByTsCodeAndDateRangeRequest request) {

        List<IndexDaily> indexDailyList = indexQuoteDao.getIndexDailiesByTsCodeAndDateRange(
                request.getTsCode(), request.getStartDate(), request.getEndDate()
        );

        if(indexDailyList.isEmpty()) {
            return null;
        }

        DomesticIndexDailyByTsCodeAndDateRangeResponse.Builder responseBuilder =
                DomesticIndexDailyByTsCodeAndDateRangeResponse.newBuilder();

        for (IndexDaily indexDaily : indexDailyList) {
            log.info("IndexDaily: {}", indexDaily);
            DomesticIndexDailyItem.Builder itemBuilder = DomesticIndexDailyItem.newBuilder()
                    .setTsCode(indexDaily.getTsCode()).setTradeDate(indexDaily.getTradeDate())
                    .setClose(indexDaily.getClose()).setOpen(indexDaily.getOpen())
                    .setHigh(indexDaily.getHigh()).setLow(indexDaily.getLow())
                    .setPreClose(indexDaily.getPreClose()).setChange(indexDaily.getChange())
                    .setPctChg(indexDaily.getPctChg()).setVol(indexDaily.getVol())
                    .setAmount(indexDaily.getAmount());

            responseBuilder.addItems(itemBuilder.build());
        }

        return responseBuilder.build();
    }
}
