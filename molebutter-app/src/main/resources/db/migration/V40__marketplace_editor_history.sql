CREATE TABLE marketplace_editor_history_link (
    draft_id BIGINT NOT NULL,
    previous_draft_id BIGINT NOT NULL,
    PRIMARY KEY (draft_id, previous_draft_id),
    UNIQUE KEY uq_editor_previous_draft (previous_draft_id),
    CONSTRAINT fk_editor_history_draft FOREIGN KEY (draft_id) REFERENCES marketplace_draft(id),
    CONSTRAINT fk_editor_history_previous FOREIGN KEY (previous_draft_id) REFERENCES marketplace_draft(id)
);
