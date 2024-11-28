package world.willfrog.alphafrogmicro.common.pojo.domestic.fund;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "alphafrog_fund_nav",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"ts_code", "nav_date"})
        })
public class FundNav {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long fundNavId;

    // 基金代码
    @Column(name = "ts_code", nullable = false)
    String tsCode;

    // 公告日期
    @Column(name = "ann_date")
    Long annDate;

    // 净值日期
    @Column(name = "nav_date", nullable = false)
    Long navDate;

    // 单位净值
    @Column(name = "unit_nav")
    Double unitNav;

    // 累计净值
    @Column(name = "accum_nav")
    Double accumNav;

    @Column(name = "accum_div")
    Double accumDiv;

    // 资产净值
    @Column(name = "net_asset")
    Double netAsset;

    @Column(name = "total_net_asset")
    Double totalNetAsset;

    @Column(name = "adj_nav")
    Double adjNav;

}
