package world.willfrog.alphafrogmicro.domestic.stock.doc;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

@Document(indexName = "alphafrog_stock_info_index")
@Getter
@Setter
@ToString
public class StockInfoES {

    @Id
    private Long stock_info_id;

    @Field(type = FieldType.Text, fielddata = true)
    private String symbol;

    @Field(type = FieldType.Text, fielddata = true, name = "ts_code")
    private String tsCode;

    @Field(type = FieldType.Text, fielddata = true)
    private String name;

    @Field(type = FieldType.Text, fielddata = true)
    private String enname;

    @Field(type = FieldType.Text, fielddata = true)
    private String fullname;

    @Field(type = FieldType.Text, fielddata = true)
    private String cnspell;

    @Field(type = FieldType.Text, fielddata = true)
    private String area;

    @Field(type = FieldType.Text, fielddata = true)
    private String industry;
}
