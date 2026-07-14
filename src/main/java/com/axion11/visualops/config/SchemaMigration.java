package com.axion11.visualops.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * One-time schema migrations that Hibernate ddl-auto=update cannot handle
 * (e.g. changing NOT NULL to NULL on existing columns).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SchemaMigration implements ApplicationRunner {

    private final JdbcTemplate jdbc;

    @Override
    public void run(ApplicationArguments args) {
        alterColumnNullable("comments", "asset_id");
        rewriteOldBucketUrls();
    }

    private void alterColumnNullable(String table, String column) {
        try {
            jdbc.execute("ALTER TABLE " + table + " MODIFY " + column + " BIGINT NULL");
            log.info("Schema migration: {}.{} set to nullable", table, column);
        } catch (Exception e) {
            // Column may already be nullable or table doesn't exist yet
            log.debug("Schema migration skipped for {}.{}: {}", table, column, e.getMessage());
        }
    }

    /**
     * Rewrite any GCS URLs that still point to the legacy {@code axion11-assets}
     * bucket so they reference the migrated {@code axion11-prod-assets} bucket.
     * Idempotent — touches only rows whose values still contain the old bucket name.
     */
    private void rewriteOldBucketUrls() {
        rewriteUrlColumn("image_uploads", "public_url");
        rewriteUrlColumn("image_uploads", "preview_url");
        rewriteUrlColumn("comments", "annotation_image_url");
        rewriteUrlColumn("face_groups", "face_thumbnail_url");
    }

    private void rewriteUrlColumn(String table, String column) {
        try {
            int updated = jdbc.update(
                    "UPDATE " + table + " SET " + column +
                            " = REPLACE(" + column + ", 'axion11-assets/', 'axion11-prod-assets/')" +
                            " WHERE " + column + " LIKE '%axion11-assets/%'"
            );
            if (updated > 0) log.info("URL migration: rewrote {} rows in {}.{}", updated, table, column);
        } catch (Exception e) {
            log.debug("URL migration skipped for {}.{}: {}", table, column, e.getMessage());
        }
    }
}
