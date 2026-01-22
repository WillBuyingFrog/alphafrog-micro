package world.willfrog.agent.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import world.willfrog.agent.entity.AgentRunEvent;

import java.util.List;

@Mapper
public interface AgentRunEventMapper {

    int insert(AgentRunEvent event);

    List<AgentRunEvent> listByRunIdAfterSeq(@Param("runId") String runId,
                                           @Param("afterSeq") int afterSeq,
                                           @Param("limit") int limit);

    Integer findMaxSeq(@Param("runId") String runId);
}

