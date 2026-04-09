package world.willfrog.alphafrogmicro.adminservice;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import world.willfrog.alphafrogmicro.common.dao.admin.AdminDataOverviewCacheDao;
import world.willfrog.alphafrogmicro.common.dao.domestic.common.DataOverviewDao;
import world.willfrog.alphafrogmicro.common.pojo.admin.AdminDataOverviewCache;

import java.time.OffsetDateTime;

/**
 * 定时更新 admin 数据概览缓存
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdminDataOverviewCacheService {

    private final DataOverviewDao dataOverviewDao;
    private final AdminDataOverviewCacheDao cacheDao;

    /**
     * 每10分钟执行一次数据概览缓存更新
     */
    @Scheduled(fixedRate = 600_000, initialDelay = 10_000)
    public void refreshCache() {
        log.info("开始刷新 admin 数据概览缓存");
        try {
            long fundCount = dataOverviewDao.countFundInfo();
            long indexCount = dataOverviewDao.countIndexInfo();
            long stockCount = dataOverviewDao.countStockInfo();
            long fundNavCount = dataOverviewDao.countFundNav();
            long indexDailyCount = dataOverviewDao.countIndexDaily();
            long stockDailyCount = dataOverviewDao.countStockDaily();

            AdminDataOverviewCache latest = cacheDao.getLatest();
            OffsetDateTime now = OffsetDateTime.now();

            if (latest == null) {
                AdminDataOverviewCache newCache = new AdminDataOverviewCache();
                newCache.setFundCount(fundCount);
                newCache.setIndexCount(indexCount);
                newCache.setStockCount(stockCount);
                newCache.setFundNavCount(fundNavCount);
                newCache.setIndexDailyCount(indexDailyCount);
                newCache.setStockDailyCount(stockDailyCount);
                newCache.setCachedAt(now);
                newCache.setCreatedAt(now);
                newCache.setUpdatedAt(now);
                cacheDao.insert(newCache);
                log.info("数据概览缓存初始化完成: fundCount={}, stockCount={}, indexCount={}",
                        fundCount, stockCount, indexCount);
            } else {
                latest.setFundCount(fundCount);
                latest.setIndexCount(indexCount);
                latest.setStockCount(stockCount);
                latest.setFundNavCount(fundNavCount);
                latest.setIndexDailyCount(indexDailyCount);
                latest.setStockDailyCount(stockDailyCount);
                latest.setCachedAt(now);
                latest.setUpdatedAt(now);
                cacheDao.updateById(latest);
                log.info("数据概览缓存更新完成: fundCount={}, stockCount={}, indexCount={}",
                        fundCount, stockCount, indexCount);
            }

            // 清理旧记录，只保留最新的一条
            cacheDao.deleteOldRecords();

        } catch (Exception e) {
            log.error("刷新 admin 数据概览缓存失败", e);
        }
    }
}
