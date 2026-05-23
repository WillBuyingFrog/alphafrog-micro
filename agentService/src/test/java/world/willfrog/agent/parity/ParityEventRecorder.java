package world.willfrog.agent.parity;

import org.mockito.ArgumentCaptor;
import world.willfrog.agent.platform.service.AgentEventService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;

/**
 * 记录并汇总一次 parity 运行中 legacy agentService 产生的事件。
 *
 * <p>通过捕获 {@link AgentEventService#append} 调用来收集事件，
 * 最终生成 {@link ParityRunResult} 中的事件列表。</p>
 */
public class ParityEventRecorder {

    private final List<Map<String, Object>> events = new ArrayList<>();

    /**
     * 从已执行的 mock eventService 中捕获所有已发送事件。
     *
     * <p>注意：应在被测代码执行完成后调用。</p>
     */
    public void captureFrom(AgentEventService eventService) {
        try {
            ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
            verify(eventService, org.mockito.Mockito.atLeast(0)).append(
                    anyString(), anyString(), anyString(), captor.capture()
            );
            for (Map<String, Object> payload : captor.getAllValues()) {
                events.add(Map.of(
                        "event_type", payload.getOrDefault("event_type", "UNKNOWN"),
                        "payload", payload
                ));
            }
        } catch (Exception e) {
            // 无事件时也允许继续
        }
    }

    /**
     * 手动追加一个事件（用于 fixture 构造已知事件）。
     */
    public void record(String eventType, Map<String, Object> payload) {
        events.add(Map.of(
                "event_type", eventType,
                "payload", payload
        ));
    }

    public List<Map<String, Object>> getEvents() {
        return new ArrayList<>(events);
    }

    public boolean hasEvent(String eventType) {
        return events.stream().anyMatch(e -> eventType.equals(e.get("event_type")));
    }

    public long count(String eventType) {
        return events.stream().filter(e -> eventType.equals(e.get("event_type"))).count();
    }

    public void clear() {
        events.clear();
    }
}
