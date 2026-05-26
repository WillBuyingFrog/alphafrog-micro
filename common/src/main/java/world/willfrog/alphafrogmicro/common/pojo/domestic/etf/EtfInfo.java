package world.willfrog.alphafrogmicro.common.pojo.domestic.etf;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "alphafrog_domestic_etf")
public class EtfInfo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ts_code", nullable = false)
    private String tsCode;

    @Column(name = "name")
    private String name;

    @Column(name = "full_name")
    private String fullName;

    @Column(name = "exchange")
    private String exchange;

    @Column(name = "mgr_name")
    private String mgrName;

    @Column(name = "list_status")
    private String listStatus;

    @Column(name = "etf_type")
    private String etfType;

    @Column(name = "index_code")
    private String indexCode;

    @Column(name = "index_name")
    private String indexName;

    @Column(name = "list_date")
    private Long listDate;

    @Column(name = "setup_date")
    private Long setupDate;

    @Column(name = "extended", columnDefinition = "JSONB")
    private String extended;
}
