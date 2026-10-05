-- NULL identifies pre-recommendation runs; existing history is not reclassified.
ALTER TABLE product_refresh_entry ADD selection_snapshot JSON NULL;
