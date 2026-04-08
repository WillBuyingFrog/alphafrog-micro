package world.willfrog.externalinfo.ingestion.oss;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * OSS 文档上传内部端点：接收 frontend 转发的文档内容，经 VPC 内网上传到 OSS。
 *
 * <p>此端点仅在 Docker 内网（alphafrog-network）可访问，18096 端口未对外暴露。
 * 鉴权由 frontend 层统一处理，此处直接信任来自内网的请求。
 */
@RestController
@RequestMapping("/rag")
@Slf4j
public class RagUploadDocController {

    private final OssUploadService ossUploadService;

    public RagUploadDocController(OssUploadService ossUploadService) {
        this.ossUploadService = ossUploadService;
    }

    @PostMapping("/upload-doc")
    public ResponseEntity<?> uploadDoc(@RequestBody RagUploadDocRequest request) {
        // 校验必填字段
        if (isBlank(request.getContent())) {
            return ResponseEntity.badRequest().body(Map.of("error", "content is required"));
        }
        if (isBlank(request.getDocType())) {
            return ResponseEntity.badRequest().body(Map.of("error", "docType is required"));
        }
        if (isBlank(request.getDate())) {
            return ResponseEntity.badRequest().body(Map.of("error", "date is required"));
        }
        if (isBlank(request.getTitle())) {
            return ResponseEntity.badRequest().body(Map.of("error", "title is required"));
        }

        String ossKey = ossUploadService.uploadDoc(request);
        return ResponseEntity.ok(Map.of("ossKey", ossKey));
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
