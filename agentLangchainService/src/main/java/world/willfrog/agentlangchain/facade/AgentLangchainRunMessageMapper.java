package world.willfrog.agentlangchain.facade;

import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.alphafrogmicro.agent.idl.AgentRunMessage;

final class AgentLangchainRunMessageMapper {

    private AgentLangchainRunMessageMapper() {
    }

    static AgentRunMessage toRunMessage(AgentRun run) {
        return AgentRunMessage.newBuilder()
                .setId(nvl(run.getId()))
                .setUserId(nvl(run.getUserId()))
                .setStatus(run.getStatus() == null ? "" : run.getStatus().name())
                .setCurrentStep(run.getCurrentStep() == null ? 0 : run.getCurrentStep())
                .setMaxSteps(run.getMaxSteps() == null ? 0 : run.getMaxSteps())
                .setPlanJson(nvl(run.getPlanJson()))
                .setSnapshotJson(nvl(run.getSnapshotJson()))
                .setLastError(nvl(run.getLastError()))
                .setTtlExpiresAt(run.getTtlExpiresAt() == null ? "" : run.getTtlExpiresAt().toString())
                .setStartedAt(run.getStartedAt() == null ? "" : run.getStartedAt().toString())
                .setUpdatedAt(run.getUpdatedAt() == null ? "" : run.getUpdatedAt().toString())
                .setCompletedAt(run.getCompletedAt() == null ? "" : run.getCompletedAt().toString())
                .setExt(nvl(run.getExt()))
                .build();
    }

    private static String nvl(String value) {
        return value == null ? "" : value;
    }
}
