package world.willfrog.alphafrogmicro.common.pojo.user;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

@Entity
@NoArgsConstructor
@Getter
@Setter
@Table(name = "alphafrog_user", uniqueConstraints = {
        @UniqueConstraint(columnNames = "username"),
        @UniqueConstraint(columnNames = "email")
})
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long userId;

    @Column(nullable = false)
    private String username;

    @Column(nullable = false)
    private String password;

    @Column(nullable = false)
    private String email;

    @Column(nullable = false)
    private Long registerTime;

    private Integer userType;

    private Integer userLevel;

    private Integer credit;

    private String status;

    private OffsetDateTime disabledAt;

    private String disabledReason;

    private OffsetDateTime statusUpdatedAt;
}
