package world.willfrog.alphafrogmicro.common.pojo.domestic.index;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Getter
@Setter
@NoArgsConstructor
@Table( name = "alphafrog_index_weight",
        uniqueConstraints = @UniqueConstraint(columnNames = {"index_code", "con_code", "trade_date"}))
public class IndexWeight {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long indexWeightId;

    @Column(name = "index_code")
    String indexCode;

    @Column(name = "con_code")
    String conCode;

    @Column(name = "trade_date")
    Long tradeDate;

    @Column(name = "weight")
    Double weight;

}
