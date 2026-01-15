package world.willfrog.alphafrogmicro.frontend.model;

public record AuthProfileResponse(
        Long userId,
        String username,
        String email,
        Integer userType,
        Integer userLevel,
        Integer credit,
        Long registerTime
) {
}
