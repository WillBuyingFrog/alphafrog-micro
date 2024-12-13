package world.willfrog.alphafrogmicro.common.pojo.domestic.index;

import jakarta.persistence.*;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "alphafrog_index_info",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"ts_code"})
        })
public class IndexInfo {

    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Id
    Long indexInfoId;


    // 指数代码
    @Column(name = "ts_code", nullable = false)
    String tsCode;

    // 指数名称
    @Column(name = "name", nullable = false)
    String name;

    // 指数全名
    @Column(name = "fullname")
    String fullName;

    // 指数所属市场
    @Column(name = "market", nullable = false)
    String market;

    // 指数发布者
    @Column(name = "publisher")
    String publisher;

    // 指数风格
    @Column(name = "index_type")
    String indexType;

    // 指数类别
    @Column(name = "category")
    String category;

    // 基期
    @Column(name = "base_date")
    Long baseDate;

    // 基点
    @Column(name = "base_point")
    Double basePoint;

    // 发布日期
    @Column(name = "list_date")
    Long listDate;

    // 加权方式
    @Column(name = "weight_rule")
    String weightRule;

    // 描述
    @Column(name = "desc")
    String desc;

    // 终止日期
    @Column(name = "exp_date")
    Long expDate;


}
