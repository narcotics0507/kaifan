-- Append-only reconciliation history; never change receipts or table state.
CREATE TABLE IF NOT EXISTS daily_reconciliation (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 business_date DATE NOT NULL,
 revision INT NOT NULL,
 snapshot_json MEDIUMTEXT NOT NULL,
 snapshot_token CHAR(64) NOT NULL,
 actual_json TEXT NOT NULL,
 reason VARCHAR(500) NOT NULL DEFAULT '',
 operator_id BIGINT NOT NULL,
 operator_name VARCHAR(100) NOT NULL,
 created_at DATETIME NOT NULL,
 UNIQUE KEY uk_day_revision (business_date, revision)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
