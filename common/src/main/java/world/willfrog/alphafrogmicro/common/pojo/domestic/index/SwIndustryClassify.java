package world.willfrog.alphafrogmicro.common.pojo.domestic.index;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "alphafrog_index_sw_classify",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"index_code", "src"})
        })
public class SwIndustryClassify {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @Column(name = "index_code", nullable = false)
    String indexCode;

    @Column(name = "industry_name", nullable = false)
    String industryName;

    @Column(name = "parent_code")
    String parentCode;

    @Column(name = "level")
    String level;

    @Column(name = "industry_code")
    String industryCode;

    @Column(name = "is_pub")
    String isPub;

    @Column(name = "src")
    String src;
}
