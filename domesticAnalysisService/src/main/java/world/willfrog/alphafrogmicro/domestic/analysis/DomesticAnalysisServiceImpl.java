package world.willfrog.alphafrogmicro.domestic.analysis;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
public class DomesticAnalysisServiceImpl {

    private final KafkaTemplate<String, String> template;

    public DomesticAnalysisServiceImpl(KafkaTemplate<String, String> template) {
        this.template = template;
    }



}
