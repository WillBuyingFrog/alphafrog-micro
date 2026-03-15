package world.willfrog.externalinfo.ingestion.oss;

import lombok.Data;

/**
 * OSS 文档上传请求体。
 */
@Data
public class RagUploadDocRequest {

    /** 文档类型："ann"（公告）或 "research"（研报）*/
    private String docType;

    /** 股票代码，例如 "000001.SZ" */
    private String tsCode;

    /** 日期，格式 YYYYMMDD */
    private String date;

    /** 文档标题 */
    private String title;

    /** Markdown 或纯文本内容 */
    private String content;

    /** 文件扩展名，默认 ".md" */
    private String fileExtension;
}
