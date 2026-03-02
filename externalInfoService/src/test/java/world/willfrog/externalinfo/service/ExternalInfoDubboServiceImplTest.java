package world.willfrog.externalinfo.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import world.willfrog.alphafrogmicro.externalinfo.idl.GetTodayMarketNewsRequest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExternalInfoDubboServiceImplTest {

    @Mock
    private MarketNewsService marketNewsService;

    @Test
    void getTodayMarketNews_shouldDelegateAndMapResponse() {
        ExternalInfoDubboServiceImpl service = new ExternalInfoDubboServiceImpl(marketNewsService);

        when(marketNewsService.getTodayMarketNews(org.mockito.ArgumentMatchers.any())).thenReturn(
                new MarketNewsService.MarketNewsResult(
                        List.of(new MarketNewsService.MarketNewsItem(
                                "news-1",
                                "2026-02-26T09:00:00+08:00",
                                "沪深300高开",
                                "sina.com.cn",
                                "market",
                                "https://finance.sina.com.cn/example"
                        )),
                        "2026-02-26T09:01:00+08:00",
                        "exa"
                )
        );

        var response = service.getTodayMarketNews(GetTodayMarketNewsRequest.newBuilder()
                .setLimit(8)
                .setProvider("exa")
                .addLanguages("zh")
                .setStartPublishedDate("2026-02-26T00:00:00+08:00")
                .setEndPublishedDate("2026-02-26T23:59:59+08:00")
                .build());

        assertEquals("exa", response.getProvider());
        assertEquals("2026-02-26T09:01:00+08:00", response.getUpdatedAt());
        assertEquals(1, response.getDataCount());
        assertEquals("news-1", response.getData(0).getId());
        assertEquals("沪深300高开", response.getData(0).getTitle());

        verify(marketNewsService).getTodayMarketNews(argThat(query ->
                "exa".equals(query.provider())
                        && query.limit() == 8
                        && List.of("zh").equals(query.languages())
                        && "2026-02-26T00:00:00+08:00".equals(query.startPublishedDate())
                        && "2026-02-26T23:59:59+08:00".equals(query.endPublishedDate())
        ));
    }
}
