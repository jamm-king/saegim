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

CREATE TABLE IF NOT EXISTS review_days (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    target_date DATE NOT NULL,
    status VARCHAR(24) NOT NULL,
    error VARCHAR(500) NULL,
    created_at DATETIME(6) NOT NULL,
    current_question_id BIGINT NULL,
    UNIQUE KEY uq_review_day (target_date)
);

CREATE TABLE IF NOT EXISTS review_questions (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    review_day_id BIGINT NOT NULL,
    position INT NOT NULL,
    question TEXT NOT NULL,
    expected_answer TEXT NOT NULL,
    source_message_ids TEXT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'WAITING',
    UNIQUE KEY uq_review_position (review_day_id, position)
);
