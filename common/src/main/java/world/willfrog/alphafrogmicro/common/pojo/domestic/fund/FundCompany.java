package world.willfrog.alphafrogmicro.common.pojo.domestic.fund;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "alphafrog_fund_company",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"credit_code"})
        })
public class FundCompany {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @Column(name = "name", nullable = false)
    String name;

    @Column(name = "shortname")
    String shortname;

    @Column(name = "short_enname")
    String shortEnname;

    @Column(name = "province")
    String province;

    @Column(name = "city")
    String city;

    @Column(name = "address")
    String address;

    @Column(name = "phone")
    String phone;

    @Column(name = "office")
    String office;

    @Column(name = "website")
    String website;

    @Column(name = "chairman")
    String chairman;

    @Column(name = "manager")
    String manager;

    @Column(name = "reg_capital")
    Double regCapital;

    @Column(name = "setup_date")
    Long setupDate;

    @Column(name = "end_date")
    Long endDate;

    @Column(name = "employees")
    Double employees;

    @Column(name = "main_business", columnDefinition = "TEXT")
    String mainBusiness;

    @Column(name = "org_code")
    String orgCode;

    @Column(name = "credit_code")
    String creditCode;
}
