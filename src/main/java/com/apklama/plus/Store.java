package com.apklama.plus;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * One row per Bazaar purchase: until when it's paid, the hash of its current access token, and
 * today's AI use. Raw access tokens are never stored, only their SHA-256.
 */
final class Store implements AutoCloseable {
    /** What an access token belongs to. */
    record Session(String purchaseToken, long validUntil, long checkedAt) {
    }

    private final Connection db;

    Store(Path file) throws SQLException {
        db = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
        try (Statement s = db.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("""
                    CREATE TABLE IF NOT EXISTS purchases (
                        purchase_token TEXT PRIMARY KEY,
                        package        TEXT NOT NULL,
                        product        TEXT NOT NULL,
                        valid_until    INTEGER NOT NULL,
                        checked_at     INTEGER NOT NULL,
                        token_hash     TEXT UNIQUE,
                        usage_day      INTEGER NOT NULL DEFAULT 0,
                        usage_count    INTEGER NOT NULL DEFAULT 0
                    )""");
        }
    }

    /** Records a paid check and gives the purchase a new access token; the old one stops working. */
    synchronized void grant(String purchaseToken, String pkg, String product, long validUntil, long now,
                            String tokenHash) throws SQLException {
        try (PreparedStatement p = db.prepareStatement("""
                INSERT INTO purchases (purchase_token, package, product, valid_until, checked_at, token_hash)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT(purchase_token) DO UPDATE SET package = excluded.package,
                    product = excluded.product, valid_until = excluded.valid_until,
                    checked_at = excluded.checked_at, token_hash = excluded.token_hash""")) {
            p.setString(1, purchaseToken);
            p.setString(2, pkg);
            p.setString(3, product);
            p.setLong(4, validUntil);
            p.setLong(5, now);
            p.setString(6, tokenHash);
            p.executeUpdate();
        }
    }

    /** The store says it's not paid (any more): its access token stops working. */
    synchronized void end(String purchaseToken) throws SQLException {
        try (PreparedStatement p = db.prepareStatement(
                "UPDATE purchases SET token_hash = NULL, valid_until = 0 WHERE purchase_token = ?")) {
            p.setString(1, purchaseToken);
            p.executeUpdate();
        }
    }

    synchronized Session find(String tokenHash) throws SQLException {
        try (PreparedStatement p = db.prepareStatement(
                "SELECT purchase_token, valid_until, checked_at FROM purchases WHERE token_hash = ?")) {
            p.setString(1, tokenHash);
            try (ResultSet r = p.executeQuery()) {
                return r.next() ? new Session(r.getString(1), r.getLong(2), r.getLong(3)) : null;
            }
        }
    }

    /** Counts one AI request for {@code day}; false when the purchase already used {@code limit} today. */
    synchronized boolean use(String purchaseToken, long day, int limit) throws SQLException {
        try (PreparedStatement p = db.prepareStatement("""
                UPDATE purchases
                SET usage_count = CASE WHEN usage_day = ? THEN usage_count + 1 ELSE 1 END, usage_day = ?
                WHERE purchase_token = ? AND (usage_day <> ? OR usage_count < ?)""")) {
            p.setLong(1, day);
            p.setLong(2, day);
            p.setString(3, purchaseToken);
            p.setLong(4, day);
            p.setInt(5, limit);
            return p.executeUpdate() == 1;
        }
    }

    @Override
    public synchronized void close() throws SQLException {
        db.close();
    }
}
