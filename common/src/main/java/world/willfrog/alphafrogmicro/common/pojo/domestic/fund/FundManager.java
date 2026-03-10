package world.willfrog.alphafrogmicro.common.pojo.domestic.fund;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "alphafrog_fund_manager",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"ts_code", "name", "begin_date"})
        })
public class FundManager {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @Column(name = "ts_code", nullable = false)
    String tsCode;

    @Column(name = "ann_date")
    Long annDate;

    @Column(name = "name", nullable = false)
    String name;

    @Column(name = "gender")
    String gender;

    @Column(name = "birth_year")
    String birthYear;

    @Column(name = "edu")
    String edu;

    @Column(name = "nationality")
    String nationality;

    @Column(name = "begin_date", nullable = false)
    Long beginDate;

    @Column(name = "end_date")
    Long endDate;

    @Column(name = "resume", columnDefinition = "TEXT")
    String resume;
}
