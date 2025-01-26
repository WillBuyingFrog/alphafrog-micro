package world.willfrog.alphafrogmicro.domestic.index;

import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboService;
import org.springframework.stereotype.Service;
import world.willfrog.alphafrogmicro.common.dao.domestic.index.IndexInfoDao;
import world.willfrog.alphafrogmicro.common.dao.domestic.index.IndexQuoteDao;
import world.willfrog.alphafrogmicro.common.dao.domestic.index.IndexWeightDao;
import world.willfrog.alphafrogmicro.common.pojo.domestic.index.IndexDaily;
import world.willfrog.alphafrogmicro.common.pojo.domestic.index.IndexInfo;
import world.willfrog.alphafrogmicro.common.pojo.domestic.index.IndexWeight;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticIndex.*;
import world.willfrog.alphafrogmicro.domestic.idl.DubboDomesticIndexServiceTriple.DomesticIndexServiceImplBase;

import java.util.List;

@DubboService
@Service
@Slf4j
public class DomesticIndexServiceImpl extends DomesticIndexServiceImplBase {

    private final IndexInfoDao indexInfoDao;
    private final IndexQuoteDao indexQuoteDao;
    private final IndexWeightDao indexWeightDao;


    public DomesticIndexServiceImpl(IndexInfoDao indexInfoDao,
                                    IndexQuoteDao indexQuoteDao, IndexWeightDao indexWeightDao) {
        this.indexInfoDao = indexInfoDao;
        this.indexQuoteDao = indexQuoteDao;
        this.indexWeightDao = indexWeightDao;
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
            return DomesticIndexDailyByTsCodeAndDateRangeResponse.newBuilder().build();
        }

        DomesticIndexDailyByTsCodeAndDateRangeResponse.Builder responseBuilder =
                DomesticIndexDailyByTsCodeAndDateRangeResponse.newBuilder();

        for (IndexDaily indexDaily : indexDailyList) {
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


    @Override
    public DomesticIndexWeightByTsCodeAndDateRangeResponse getDomesticIndexWeightByTsCodeAndDateRange(
            DomesticIndexWeightByTsCodeAndDateRangeRequest request) {

        List<IndexWeight> indexWeightList = indexWeightDao.getIndexWeightsByTsCodeAndDateRange(
                request.getTsCode(), request.getStartDate(), request.getEndDate()
        );

        if (indexWeightList.isEmpty()) {
            return DomesticIndexWeightByTsCodeAndDateRangeResponse.newBuilder().build();
        }

        DomesticIndexWeightByTsCodeAndDateRangeResponse.Builder builder =
                DomesticIndexWeightByTsCodeAndDateRangeResponse.newBuilder();

        for (IndexWeight indexWeight : indexWeightList) {
            DomesticIndexWeightItem.Builder itemBuilder = DomesticIndexWeightItem.newBuilder()
                    .setIndexCode(indexWeight.getIndexCode()).setTradeDate(indexWeight.getTradeDate())
                    .setWeight(indexWeight.getWeight());

            builder.addItems(itemBuilder.build());
        }

        return builder.build();
    }


    @Override
    public DomesticIndexWeightByConCodeAndDateRangeResponse getDomesticIndexWeightByConCodeAndDateRange(
            DomesticIndexWeightByConCodeAndDateRangeRequest request) {
        
        List<IndexWeight> indexWeightList = indexWeightDao.getIndexWeightsByConCodeAndDateRange(
                request.getConCode(), request.getStartDate(), request.getEndDate()
        );

        if (indexWeightList.isEmpty()) {
            return DomesticIndexWeightByConCodeAndDateRangeResponse.newBuilder().build();
        }

        DomesticIndexWeightByConCodeAndDateRangeResponse.Builder builder =
                DomesticIndexWeightByConCodeAndDateRangeResponse.newBuilder();

        for (IndexWeight indexWeight : indexWeightList) {
            DomesticIndexWeightItem.Builder itemBuilder = DomesticIndexWeightItem.newBuilder()
                    .setIndexCode(indexWeight.getIndexCode()).setTradeDate(indexWeight.getTradeDate())
                    .setWeight(indexWeight.getWeight());

            builder.addItems(itemBuilder.build());
        }
        
        return builder.build();

    }


}
