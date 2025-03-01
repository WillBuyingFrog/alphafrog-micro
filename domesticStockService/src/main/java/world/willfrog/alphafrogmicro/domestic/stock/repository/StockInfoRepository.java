package world.willfrog.alphafrogmicro.domestic.stock.repository;

import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;
import org.springframework.stereotype.Repository;
import world.willfrog.alphafrogmicro.domestic.stock.doc.StockInfoES;

@Repository
public interface StockInfoRepository extends ElasticsearchRepository<StockInfoES, Long> {
}
