package world.willfrog.alphafrogmicro.common.pojo.domestic.stock;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "alphafrog_stock_balancesheet",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"ts_code", "end_date", "report_type"})
        })
public class StockBalancesheet {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @Column(name = "ts_code", nullable = false)
    String tsCode;

    @Column(name = "ann_date")
    Long annDate;

    @Column(name = "f_ann_date")
    Long fAnnDate;

    @Column(name = "end_date", nullable = false)
    Long endDate;

    @Column(name = "report_type")
    String reportType;

    @Column(name = "comp_type")
    String compType;

    @Column(name = "end_type")
    String endType;

    // 流动资产
    @Column(name = "money_cap")
    Double moneyCap;

    @Column(name = "accounts_receiv")
    Double accountsReceiv;

    @Column(name = "inventories")
    Double inventories;

    @Column(name = "total_cur_assets")
    Double totalCurAssets;

    // 非流动资产
    @Column(name = "fix_assets")
    Double fixAssets;

    @Column(name = "goodwill")
    Double goodwill;

    @Column(name = "intan_assets")
    Double intanAssets;

    @Column(name = "r_and_d")
    Double rAndD;

    @Column(name = "total_nca")
    Double totalNca;

    @Column(name = "total_assets")
    Double totalAssets;

    // 流动负债
    @Column(name = "st_borr")
    Double stBorr;

    @Column(name = "acct_payable")
    Double acctPayable;

    @Column(name = "total_cur_liab")
    Double totalCurLiab;

    // 非流动负债
    @Column(name = "lt_borr")
    Double ltBorr;

    @Column(name = "bond_payable")
    Double bondPayable;

    @Column(name = "total_ncl")
    Double totalNcl;

    @Column(name = "total_liab")
    Double totalLiab;

    // 股东权益
    @Column(name = "total_hldr_eqy_exc_min_int")
    Double totalHldrEqyExcMinInt;

    @Column(name = "total_hldr_eqy_inc_min_int")
    Double totalHldrEqyIncMinInt;

    @Column(name = "minority_int")
    Double minorityInt;

    @Column(name = "update_flag")
    String updateFlag;

    @Column(name = "extended", columnDefinition = "JSONB")
    String extended;
}
