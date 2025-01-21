package world.willfrog.alphafrogmicro.common.pojo.domestic.stock;

import jakarta.persistence.*;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "alphafrog_stock_info",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"ts_code", "symbol"})
        })
public class StockInfo {

    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Id
    Long stockInfoId;

    // TS代码
    @Column(name = "ts_code", nullable = false)
    String tsCode;

    // 股票代码
    @Column(name = "symbol", nullable = false)
    String symbol;

    // 股票名称
    @Column(name = "name", nullable = false)
    String name;

    // 地域
    @Column(name = "area")
    String area;

    // 所属行业
    @Column(name = "industry", nullable = false)
    String industry;

    // 股票全称
    @Column(name = "fullname")
    String fullName;

    // 英文全称
    @Column(name = "enname")
    String enName;

    // 拼音缩写
    @Column(name = "cnspell")
    String cnspell;

    // 市场类型（主板/创业板/科创板/CDR）
    @Column(name = "market", nullable = false)
    String market;

    // 交易所代码
    @Column(name = "exchange")
    String exchange;

    // 交易货币
    @Column(name = "curr_type")
    String currType;

    // 上市状态 L上市 D退市 P暂停上市
    @Column(name = "list_status")
    String listStatus;

    // 上市日期
    @Column(name = "list_date", nullable = false)
    Long listDate;

    // 退市日期
    @Column(name = "delist_date")
    Long delistDate;

    // 是否沪深港通标的，N否 H沪股通 S深股通
    @Column(name = "is_hs")
    String isHs;

    // 实控人名称
    @Column(name = "act_name")
    String actName;

    // 实控人企业性质
    @Column(name = "act_ent_type")
    String actEntType;
}