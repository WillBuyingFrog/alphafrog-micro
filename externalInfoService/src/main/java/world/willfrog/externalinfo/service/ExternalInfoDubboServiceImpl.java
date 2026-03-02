package world.willfrog.externalinfo.service;

import lombok.RequiredArgsConstructor;
import org.apache.dubbo.config.annotation.DubboService;
import world.willfrog.alphafrogmicro.externalinfo.idl.DubboExternalInfoDubboServiceTriple;
import world.willfrog.alphafrogmicro.externalinfo.idl.GetTodayMarketNewsRequest;
import world.willfrog.alphafrogmicro.externalinfo.idl.GetTodayMarketNewsResponse;
import world.willfrog.alphafrogmicro.externalinfo.idl.MarketNewsItemMessage;

@DubboService
@RequiredArgsConstructor
public class ExternalInfoDubboServiceImpl extends DubboExternalInfoDubboServiceTriple.ExternalInfoDubboServiceImplBase {

    private final MarketNewsService marketNewsService;

    @Override
    public GetTodayMarketNewsResponse getTodayMarketNews(GetTodayMarketNewsRequest request) {
        MarketNewsService.MarketNewsResult result = marketNewsService.getTodayMarketNews(new MarketNewsService.MarketNewsQuery(
                nvl(request.getProvider()),
                request.getLanguagesList(),
                request.getLimit(),
                nvl(request.getStartPublishedDate()),
                nvl(request.getEndPublishedDate())
        ));

        GetTodayMarketNewsResponse.Builder builder = GetTodayMarketNewsResponse.newBuilder()
                .setUpdatedAt(nvl(result.updatedAt()))
                .setProvider(nvl(result.provider()));
        if (result.items() != null) {
            for (MarketNewsService.MarketNewsItem item : result.items()) {
                if (item == null) {
                    continue;
                }
                builder.addData(MarketNewsItemMessage.newBuilder()
                        .setId(nvl(item.id()))
                        .setTimestamp(nvl(item.timestamp()))
                        .setTitle(nvl(item.title()))
                        .setSource(nvl(item.source()))
                        .setCategory(nvl(item.category()))
                        .setUrl(nvl(item.url()))
                        .build());
            }
        }
        return builder.build();
    }

    private String nvl(String value) {
        return value == null ? "" : value;
    }
}
