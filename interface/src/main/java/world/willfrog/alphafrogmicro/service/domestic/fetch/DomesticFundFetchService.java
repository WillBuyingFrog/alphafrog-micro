package world.willfrog.alphafrogmicro.service.domestic.fetch;

public interface DomesticFundFetchService {
    int directFetchFundNavByNavDateAndMarket(String navDate, String market, int offset, int limit);

    int batchFetchFundNavByTradeDate(long tradeDateTimestamp);
}
