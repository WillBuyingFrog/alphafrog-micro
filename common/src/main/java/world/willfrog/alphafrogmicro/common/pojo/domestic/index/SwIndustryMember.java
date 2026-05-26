package world.willfrog.alphafrogmicro.common.pojo.domestic.index;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "alphafrog_index_sw_member",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"ts_code", "l3_code", "in_date"})
        })
public class SwIndustryMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @Column(name = "l1_code")
    String l1Code;

    @Column(name = "l1_name")
    String l1Name;

    @Column(name = "l2_code")
    String l2Code;

    @Column(name = "l2_name")
    String l2Name;

    @Column(name = "l3_code")
    String l3Code;

    @Column(name = "l3_name")
    String l3Name;

    @Column(name = "ts_code", nullable = false)
    String tsCode;

    @Column(name = "name")
    String name;

    @Column(name = "in_date")
    Long inDate;

    @Column(name = "out_date")
    Long outDate;

    @Column(name = "is_new")
    String isNew;

    @Column(name = "extended", columnDefinition = "JSONB")
    String extended;
}
