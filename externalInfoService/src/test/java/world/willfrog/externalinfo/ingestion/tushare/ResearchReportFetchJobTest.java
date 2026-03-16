package world.willfrog.externalinfo.ingestion.tushare;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ResearchReportFetchJobTest {

    @Mock
    private RagFetchLocalConfigLoader configLoader;

    @Mock
    private TuShareRagApiClient apiClient;

    @Mock
    private RagResearchReportDao researchReportDao;

    @Test
    void fetchRange_shouldSplitByMonthBoundaries() {
        when(apiClient.post(anyMap())).thenReturn(emptySuccessResponse());

        ResearchReportFetchJob job = new ResearchReportFetchJob(configLoader, apiClient, researchReportDao);
        job.fetchRange("20190115", "20190302", "电子");

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(apiClient, times(3)).post(captor.capture());

        List<Map<String, Object>> requests = captor.getAllValues();
        assertSegment(requests.get(0), "20190115", "20190131", "电子");
        assertSegment(requests.get(1), "20190201", "20190228", "电子");
        assertSegment(requests.get(2), "20190301", "20190302", "电子");
    }

    @SuppressWarnings("unchecked")
    private void assertSegment(Map<String, Object> request, String startDate, String endDate, String industry) {
        Map<String, Object> params = (Map<String, Object>) request.get("params");
        assertEquals("research_report", request.get("api_name"));
        assertEquals("trade_date,title,abstr,report_type,author,name,ts_code,inst_csname,ind_name,url",
                request.get("fields"));
        assertEquals(startDate, params.get("start_date"));
        assertEquals(endDate, params.get("end_date"));
        assertEquals(industry, params.get("ind_name"));
    }

    private JSONObject emptySuccessResponse() {
        JSONObject response = new JSONObject();
        response.put("code", 0);
        response.put("msg", "ok");
        JSONObject data = new JSONObject();
        data.put("items", new JSONArray());
        response.put("data", data);
        return response;
    }
}
