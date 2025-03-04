package world.willfrog.alphafrogmicro.domestic.stock.repository;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;
import org.springframework.stereotype.Repository;
import world.willfrog.alphafrogmicro.domestic.stock.doc.StockInfoES;

@Repository
@ConditionalOnProperty(name = "advanced.es-enabled", havingValue = "true")
public interface StockInfoRepository extends ElasticsearchRepository<StockInfoES, Long> {
}
