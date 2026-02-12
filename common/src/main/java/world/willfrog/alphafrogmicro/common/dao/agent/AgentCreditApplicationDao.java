package world.willfrog.alphafrogmicro.common.dao.agent;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import world.willfrog.alphafrogmicro.common.pojo.agent.AgentCreditApplication;

@Mapper
public interface AgentCreditApplicationDao {

    @Insert("INSERT INTO alphafrog_agent_credit_application (" +
            "application_id, user_id, amount, reason, contact, status, ext, processed_at" +
            ") VALUES (" +
            "#{applicationId}, #{userId}, #{amount}, #{reason}, #{contact}, #{status}, CAST(#{ext} AS jsonb), #{processedAt}" +
            ")")
    int insert(AgentCreditApplication application);
}
