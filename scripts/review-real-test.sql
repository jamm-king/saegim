CREATE DATABASE IF NOT EXISTS saegim_review_real_test;
GRANT ALL PRIVILEGES ON saegim_review_real_test.* TO 'saegim'@'%';
USE saegim_review_real_test;
CREATE TABLE IF NOT EXISTS messages (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(36) NULL,
    role VARCHAR(16) NOT NULL,
    content MEDIUMTEXT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    kind VARCHAR(16) NOT NULL DEFAULT 'CHAT',
    status VARCHAR(16) NOT NULL DEFAULT 'COMPLETE',
    in_reply_to BIGINT NULL,
    review_question_id BIGINT NULL,
    review_action VARCHAR(16) NULL,
    UNIQUE KEY uq_messages_request (request_id),
    UNIQUE KEY uq_messages_reply (in_reply_to),
    KEY idx_messages_day (kind, created_at)
);
INSERT INTO messages (request_id, role, content, created_at) SELECT 'fixture-review-user', role, content, '2026-10-01 02:00:00' FROM saegim.messages WHERE id = 1 AND kind = 'CHAT' AND status = 'COMPLETE' AND NOT EXISTS (SELECT 1 FROM messages WHERE request_id = 'fixture-review-user');
INSERT INTO messages (request_id, role, content, created_at) SELECT 'fixture-review-assistant', role, content, '2026-10-01 02:00:01' FROM saegim.messages WHERE id = 2 AND kind = 'CHAT' AND status = 'COMPLETE' AND NOT EXISTS (SELECT 1 FROM messages WHERE request_id = 'fixture-review-assistant');
