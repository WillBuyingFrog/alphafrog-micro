package world.willfrog.alphafrogmicro.common.pojo.domestic.index;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import world.willfrog.alphafrogmicro.common.pojo.domestic.quote.Quote;

@Entity
@Getter
@Setter
@NoArgsConstructor
@Table( name = "alphafrog_index_weekly",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"ts_code", "trade_date"})
        } )
public class IndexWeekly extends Quote {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long indexWeeklyId;
}
