package world.willfrog.externalinfo.ingestion.oss;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * 通过 VPC 内网 endpoint 将文档上传到阿里云 OSS，返回 object key。
 *
 * <p>AK/SK 仅保存在服务端环境变量，本地脚本无需持有凭证。
 */
@Service
@Slf4j
public class OssUploadService {

    private static final Pattern UNSAFE_CHARS = Pattern.compile("[^\\w\\u4e00-\\u9fff-]");

    @Value("${alphafrog.rag.oss.endpoint}")
    private String endpoint;

    @Value("${alphafrog.rag.oss.bucket}")
    private String bucket;

    @Value("${alphafrog.rag.oss.access-key-id}")
    private String accessKeyId;

    @Value("${alphafrog.rag.oss.access-key-secret}")
    private String accessKeySecret;

    @Value("${alphafrog.rag.oss.path-prefix:alphafrog-rag}")
    private String pathPrefix;

    /**
     * 上传文档内容到 OSS，返回 object key。
     *
     * @param req 上传请求，包含文档类型、股票代码、日期、标题、内容、文件扩展名
     * @return object key，例如 "alphafrog-rag/ann/000001.SZ/20260315_标题.md"
     */
    public String uploadDoc(RagUploadDocRequest req) {
        String ext = (req.getFileExtension() != null && !req.getFileExtension().isBlank())
                ? req.getFileExtension() : ".md";
        String safeTitle = UNSAFE_CHARS.matcher(req.getTitle()).replaceAll("_");
        if (safeTitle.length() > 60) {
            safeTitle = safeTitle.substring(0, 60);
        }
        String tsCode = (req.getTsCode() != null && !req.getTsCode().isBlank())
                ? req.getTsCode() : "no_code";

        String ossKey = String.format("%s/%s/%s/%s_%s%s",
                pathPrefix, req.getDocType(), tsCode, req.getDate(), safeTitle, ext);

        OSS ossClient = new OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret);
        try {
            byte[] contentBytes = req.getContent().getBytes(StandardCharsets.UTF_8);
            ossClient.putObject(bucket, ossKey, new ByteArrayInputStream(contentBytes));
            log.info("[OssUploadService] Uploaded docType={} tsCode={} date={} ossKey={}",
                    req.getDocType(), tsCode, req.getDate(), ossKey);
        } finally {
            ossClient.shutdown();
        }

        return ossKey;
    }
}
